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
    }

    @Volatile
    private var cmdChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var statusChar: BluetoothGattCharacteristic? = null

    private val _status = MutableStateFlow<StatusFrame?>(null)
    val status: StateFlow<StatusFrame?> = _status

    private val _connectionState = MutableStateFlow(false)
    val connectionState: StateFlow<Boolean> = _connectionState

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val svc = gatt.getService(PulsarUuids.SERVICE) ?: return false
        cmdChar = svc.getCharacteristic(PulsarUuids.CHAR_COMMAND)
        statusChar = svc.getCharacteristic(PulsarUuids.CHAR_STATUS)
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
        sendCommand(CommandBuilder.statusRequest())
        Log.i(TAG, "Initialized — notifications enabled, status requested")
    }

    override fun onServicesInvalidated() {
        cmdChar = null
        statusChar = null
        _connectionState.value = false
    }

    fun sendCommand(packet: ByteArray) {
        cmdChar?.let { char ->
            writeCharacteristic(char, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                .enqueue()
        } ?: Log.w(TAG, "Command characteristic not available")
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
