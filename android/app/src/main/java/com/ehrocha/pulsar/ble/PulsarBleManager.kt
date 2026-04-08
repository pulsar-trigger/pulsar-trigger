/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import com.ehrocha.pulsar.AppConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PulsarBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "PulsarBLE"
        const val OTA_MTU = AppConfig.BLE_OTA_MTU  // request max MTU for fast OTA transfer
    }

    @Volatile
    private var cmdChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var statusChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var otaCtrlChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var otaDataChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var pitchChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var shouldRefreshCache = false

    private val _status = MutableStateFlow<StatusFrame?>(null)
    val status: StateFlow<StatusFrame?> = _status

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo

    private val _trackerPitch = MutableStateFlow<Float?>(null)
    val trackerPitch: StateFlow<Float?> = _trackerPitch

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _otaStatus = MutableStateFlow<OtaStatus?>(null)
    val otaStatus: StateFlow<OtaStatus?> = _otaStatus

    /** Chip model byte from OTA_READY response (1=ESP32, 3=ESP32-S3, etc.) */
    private val _otaChipModel = MutableStateFlow<Int?>(null)
    val otaChipModel: StateFlow<Int?> = _otaChipModel

    private val _mtu = MutableStateFlow(AppConfig.BLE_DEFAULT_MTU)  // default BLE MTU
    val mtu: StateFlow<Int> = _mtu

    private val _rssi = MutableStateFlow<Int?>(null)
    val rssi: StateFlow<Int?> = _rssi

    private val rssiScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var rssiJob: Job? = null

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val svc = gatt.getService(PulsarUuids.SERVICE) ?: return false
        cmdChar = svc.getCharacteristic(PulsarUuids.CHAR_COMMAND)
        statusChar = svc.getCharacteristic(PulsarUuids.CHAR_STATUS)
        pitchChar = svc.getCharacteristic(PulsarUuids.CHAR_PITCH)  // optional — new firmware only

        // OTA service is optional (old firmware may not have it)
        gatt.getService(PulsarUuids.OTA_SERVICE)?.let { otaSvc ->
            otaCtrlChar = otaSvc.getCharacteristic(PulsarUuids.OTA_CONTROL)
            otaDataChar = otaSvc.getCharacteristic(PulsarUuids.OTA_DATA)
        }

        return cmdChar != null && statusChar != null
    }

    override fun initialize() {
        super.initialize()
        _connectionState.value = true

        setNotificationCallback(statusChar).with { _, data ->
            data.value?.let { bytes ->
                // DeviceInfoFrame has marker 0xFF in byte 0
                if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == 0xFF) {
                    DeviceInfo.parse(bytes)?.let { _deviceInfo.value = it }
                } else {
                    StatusFrame.parse(bytes)?.let { _status.value = it }
                }
            }
        }
        enableNotifications(statusChar).enqueue()

        // Subscribe to pitch notifications (tracker alignment mode — optional)
        pitchChar?.let { pitch ->
            setNotificationCallback(pitch).with { _, data ->
                data.value?.takeIf { it.size >= 4 }?.let { bytes ->
                    _trackerPitch.value = ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .float
                }
            }
            enableNotifications(pitch).enqueue()
        }

        // Subscribe to OTA control notifications (status feedback)
        otaCtrlChar?.let { ctrl ->
            setNotificationCallback(ctrl).with { _, data ->
                data.value?.takeIf { it.isNotEmpty() }?.let { bytes ->
                    _otaStatus.value = OtaStatus.fromByte(bytes[0])
                    // OTA_READY carries chip model in byte[1]
                    if (bytes[0] == OtaStatus.READY.id && bytes.size >= 2) {
                        _otaChipModel.value = bytes[1].toInt() and 0xFF
                    }
                }
            }
            enableNotifications(ctrl).enqueue()
        }

        // Request max MTU for OTA
        requestMtu(OTA_MTU).with { _, newMtu ->
            _mtu.value = newMtu
            Log.i(TAG, "MTU negotiated: $newMtu")
        }.enqueue()

        sendCommand(CommandBuilder.statusRequest())
        Log.i(TAG, "Initialized — notifications enabled, status requested")

        // Start periodic RSSI polling
        rssiJob?.cancel()
        rssiJob = rssiScope.launch {
            while (isActive) {
                readRssi().with { _, rssiValue ->
                    _rssi.value = rssiValue
                }.enqueue()
                delay(AppConfig.BLE_RSSI_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onServicesInvalidated() {
        cmdChar = null
        statusChar = null
        pitchChar = null
        otaCtrlChar = null
        otaDataChar = null
        _connectionState.value = false
        rssiJob?.cancel()
        _rssi.value = null
    }

    fun sendCommand(packet: ByteArray) {
        cmdChar?.let { char ->
            writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        } ?: Log.w(TAG, "Command characteristic not available")
    }

    /** Write to OTA control characteristic (write-with-response). */
    fun writeOtaControl(packet: ByteArray) {
        otaCtrlChar?.let { char ->
            writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        } ?: Log.w(TAG, "OTA control characteristic not available")
    }

    /** Write a firmware chunk to OTA data characteristic (write-no-response for speed). */
    fun writeOtaData(chunk: ByteArray): Boolean {
        val char = otaDataChar ?: return false
        writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .enqueue()
        return true
    }

    fun hasOtaSupport(): Boolean = otaCtrlChar != null && otaDataChar != null

    /** Returns usable payload per BLE write (MTU - 3 for ATT header). */
    fun otaChunkSize(): Int = (_mtu.value - 3).coerceAtLeast(AppConfig.BLE_MIN_OTA_CHUNK)

    /** Request GATT cache clear on next disconnect+reconnect. */
    fun requestCacheRefresh() {
        shouldRefreshCache = true
    }

    override fun shouldClearCacheWhenDisconnected(): Boolean {
        if (shouldRefreshCache) {
            shouldRefreshCache = false
            Log.i(TAG, "Clearing GATT cache on disconnect")
            return true
        }
        return super.shouldClearCacheWhenDisconnected()
    }

    fun connectDevice(device: BluetoothDevice) {
        connect(device)
            .retry(AppConfig.BLE_CONNECT_RETRIES, AppConfig.BLE_RETRY_DELAY_MS)
            .useAutoConnect(false)
            .enqueue()
    }

    fun disconnectDevice() {
        disconnect().enqueue()
    }
}
