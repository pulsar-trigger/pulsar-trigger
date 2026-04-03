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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data

class PulsarBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "PulsarBLE"
        const val OTA_MTU = 517  // request max MTU for fast OTA transfer
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
    private var shouldRefreshCache = false

    private val _status = MutableStateFlow<StatusFrame?>(null)
    val status: StateFlow<StatusFrame?> = _status

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    private val _otaStatus = MutableStateFlow<OtaStatus?>(null)
    val otaStatus: StateFlow<OtaStatus?> = _otaStatus

    private val _mtu = MutableStateFlow(23)  // default BLE MTU
    val mtu: StateFlow<Int> = _mtu

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val svc = gatt.getService(PulsarUuids.SERVICE) ?: return false
        cmdChar = svc.getCharacteristic(PulsarUuids.CHAR_COMMAND)
        statusChar = svc.getCharacteristic(PulsarUuids.CHAR_STATUS)

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
                StatusFrame.parse(bytes)?.let { _status.value = it }
            }
        }
        enableNotifications(statusChar).enqueue()

        // Subscribe to OTA control notifications (status feedback)
        otaCtrlChar?.let { ctrl ->
            setNotificationCallback(ctrl).with { _, data ->
                data.value?.takeIf { it.isNotEmpty() }?.let { bytes ->
                    _otaStatus.value = OtaStatus.fromByte(bytes[0])
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
    }

    override fun onServicesInvalidated() {
        cmdChar = null
        statusChar = null
        otaCtrlChar = null
        otaDataChar = null
        _connectionState.value = false
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
    fun otaChunkSize(): Int = (_mtu.value - 3).coerceAtLeast(20)

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
            .retry(3, 200)
            .useAutoConnect(false)
            .enqueue()
    }

    fun disconnectDevice() {
        disconnect().enqueue()
    }
}
