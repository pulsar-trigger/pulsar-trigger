/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin `BluetoothGatt` wrapper for the GATT Explorer wizard. Deliberately
 * separate from [CanonBleClient] (which is locked to Pulsar's two known
 * Canon protocols) so the explorer can talk to anything — including
 * bodies Pulsar doesn't yet support.
 *
 * The contract is intentionally narrow: connect / discover / read /
 * write / subscribe / disconnect, plus reactive [services] and a
 * [bondAddress] hint. Every operation appends a timestamped line to
 * [GattExplorerLog] so the artifact a community tester shares back to
 * the project captures the exact sequence of probes.
 *
 * **Status: scaffold.** All five operations are implemented end-to-end
 * via the standard Android `BluetoothGattCallback`. Concurrency is
 * single-op-at-a-time (the platform queues writes; we don't pipeline
 * multiple writes/reads in flight). See [docs/gatt-explorer-draft.md].
 */
@SuppressLint("MissingPermission")
class GattExplorerClient(private val context: Context) {

    private companion object { const val TAG = "GattExplorer" }

    private var gatt: BluetoothGatt? = null

    private val _services = MutableStateFlow<List<BluetoothGattService>>(emptyList())
    val services: StateFlow<List<BluetoothGattService>> = _services

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Address of the bonded peer once [connect] succeeds — read by the
     *  share-report header so the captured RE artifact knows which body
     *  it came from. Empty until connected. */
    @Volatile var bondAddress: String = ""
        private set

    /** Most recent notification payload per characteristic UUID. Updated
     *  on every notify/indicate; the explorer screen observes this to
     *  render a live notification log per subscribed char. */
    private val _notifications = MutableStateFlow<Map<UUID, ByteArray>>(emptyMap())
    val notifications: StateFlow<Map<UUID, ByteArray>> = _notifications

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                GattExplorerLog.i(TAG, "connected to ${g.device.address}")
                bondAddress = g.device.address ?: ""
                _connected.value = true
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                GattExplorerLog.i(TAG, "disconnected from ${g.device.address} (status=$status)")
                _connected.value = false
                _services.value = emptyList()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val services = g.services.orEmpty()
                GattExplorerLog.i(TAG, "discovered ${services.size} service(s):")
                for (svc in services) {
                    val hint = KnownGattUuids.lookup(svc.uuid)?.nickname ?: "unknown"
                    GattExplorerLog.i(TAG, "  service ${svc.uuid} [$hint]")
                    for (c in svc.characteristics) {
                        val props = propsToString(c.properties)
                        val chint = KnownGattUuids.lookup(c.uuid)?.nickname ?: "unknown"
                        GattExplorerLog.i(TAG, "    char ${c.uuid} props=$props [$chint]")
                    }
                }
                _services.value = services
            } else {
                GattExplorerLog.e(TAG, "service discovery failed (status=$status)")
            }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                GattExplorerLog.i(TAG, "READ ${characteristic.uuid} → ${value.toHex()}")
            } else {
                GattExplorerLog.e(TAG, "READ ${characteristic.uuid} failed status=$status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Pre-Android 13 callback; the new ABI passes value explicitly.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: ByteArray(0)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    GattExplorerLog.i(TAG, "READ ${characteristic.uuid} → ${value.toHex()}")
                } else {
                    GattExplorerLog.e(TAG, "READ ${characteristic.uuid} failed status=$status")
                }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                GattExplorerLog.i(TAG, "WRITE ${characteristic.uuid} OK")
            } else {
                GattExplorerLog.e(TAG, "WRITE ${characteristic.uuid} failed status=$status")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val hex = value.toHex()
            GattExplorerLog.i(TAG, "NOTIFY ${characteristic.uuid} → $hex")
            _notifications.value = _notifications.value + (characteristic.uuid to value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // Pre-Android 13 callback; new ABI passes value explicitly.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: ByteArray(0)
                val hex = value.toHex()
                GattExplorerLog.i(TAG, "NOTIFY ${characteristic.uuid} → $hex")
                _notifications.value = _notifications.value + (characteristic.uuid to value)
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        GattExplorerLog.i(TAG, "connect requested to ${device.address}")
        gatt?.close()
        gatt = device.connectGatt(context, false, callback)
    }

    fun disconnect() {
        gatt?.let {
            GattExplorerLog.i(TAG, "disconnect requested")
            it.disconnect()
            it.close()
        }
        gatt = null
        _connected.value = false
        _services.value = emptyList()
    }

    fun readChar(svcUuid: UUID, charUuid: UUID) {
        val char = findChar(svcUuid, charUuid) ?: return
        val ok = gatt?.readCharacteristic(char) == true
        if (!ok) GattExplorerLog.e(TAG, "READ ${charUuid} could not be queued")
    }

    /** Write bytes to a characteristic. Passes [withResponse] = true →
     *  WRITE; false → WRITE_NO_RESPONSE (default for Canon's protocols). */
    fun writeChar(svcUuid: UUID, charUuid: UUID, bytes: ByteArray, withResponse: Boolean = false) {
        val char = findChar(svcUuid, charUuid) ?: return
        val g = gatt ?: return
        val writeType = if (withResponse)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val rc = g.writeCharacteristic(char, bytes, writeType)
            GattExplorerLog.i(TAG, "WRITE ${charUuid} ${bytes.toHex()} (response=$withResponse, rc=$rc)")
        } else {
            @Suppress("DEPRECATION")
            char.writeType = writeType
            @Suppress("DEPRECATION")
            char.value = bytes
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(char)
            GattExplorerLog.i(TAG, "WRITE ${charUuid} ${bytes.toHex()} (response=$withResponse, queued=$ok)")
        }
    }

    /** Toggle notification subscription on a characteristic. CCCD is
     *  written automatically; both subscribe + unsubscribe go through
     *  the same path. */
    fun setNotify(svcUuid: UUID, charUuid: UUID, enabled: Boolean) {
        val char = findChar(svcUuid, charUuid) ?: return
        val g = gatt ?: return
        val ok = g.setCharacteristicNotification(char, enabled)
        // CCCD descriptor 2902 — required for the camera to actually start
        // sending notify/indicate packets.
        val cccd = char.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            val value = if (!enabled) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                else if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
        GattExplorerLog.i(TAG, "${if (enabled) "SUBSCRIBE" else "UNSUBSCRIBE"} ${charUuid} ok=$ok")
    }

    private fun findChar(svcUuid: UUID, charUuid: UUID): BluetoothGattCharacteristic? {
        val g = gatt ?: return null
        val svc = g.getService(svcUuid) ?: run {
            GattExplorerLog.e(TAG, "service $svcUuid not found")
            return null
        }
        return svc.getCharacteristic(charUuid) ?: run {
            GattExplorerLog.e(TAG, "char $charUuid not in service $svcUuid")
            null
        }
    }

    private fun propsToString(props: Int): String = buildList {
        if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
        if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WNR")
        if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
        if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
    }.joinToString(",")
}

private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

internal fun ByteArray.toHex(): String =
    joinToString(" ") { "%02x".format(it) }

/** Parse a free-form hex string (whitespace + 0x prefixes tolerated).
 *  Returns null when input doesn't decode cleanly so the UI can surface
 *  a parse error instead of writing garbage. */
internal fun String.parseHexOrNull(): ByteArray? {
    val cleaned = replace("0x", "", ignoreCase = true)
        .filter { !it.isWhitespace() }
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(cleaned[2 * i], 16)
        val lo = Character.digit(cleaned[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
