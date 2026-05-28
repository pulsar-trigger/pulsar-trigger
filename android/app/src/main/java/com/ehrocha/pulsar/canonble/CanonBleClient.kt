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
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * GATT wire-level helper for Canon's BR-E1-compatible bodies. Owns one
 * [BluetoothGatt] session at a time. Operations are serialized through
 * [opMutex] — Android's GATT stack allows only one in-flight op per
 * connection, and back-to-back writes that don't wait for
 * `onCharacteristicWrite` get silently dropped.
 *
 * The protocol is one-way (phone → camera) so we never subscribe to
 * notifications; reads aren't needed either. Bonding is OS-mediated — the
 * first write to [PAIR_CHAR_UUID] triggers Android's pairing dialog when
 * the camera demands MITM encryption.
 *
 * Wire format (verified across five independent open-source references —
 * see `docs/canon-ble.md`):
 *
 *  - Pairing characteristic (one-shot, on first connect):
 *      `[0x03, ...ASCII device name...]`
 *  - Control characteristic (every shutter / focus / video event):
 *      single byte = `mode | button` bits.
 */
class CanonBleClient(
    private val ctx: Context,
    private val device: BluetoothDevice,
    /** Invoked when the GATT link drops spontaneously (camera powered off,
     *  bond cleared, body went out of range, etc.). NOT invoked for the
     *  ordinary [close] path. The viewmodel uses this to flip the
     *  reconnecting banner and re-arm the BLE scan. */
    private val onSpontaneousDisconnect: () -> Unit = {},
) {

    companion object {
        private const val TAG = "CanonBleClient"

        /** Top-level Canon BLE remote service. */
        val SERVICE_UUID: UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821")

        /** Pair-time characteristic — accepts `[0x03, name…]` to register
         *  the phone as a remote. */
        val PAIR_CHAR_UUID: UUID = UUID.fromString("00050002-0000-1000-0000-d8492fffa821")

        /** Shutter / focus / video control characteristic. Single-byte
         *  writes with the mode + button bit-mask. */
        val CONTROL_CHAR_UUID: UUID = UUID.fromString("00050003-0000-1000-0000-d8492fffa821")

        // ── Control byte: mode bits (low nibble) ───────────────────────
        const val MODE_DELAY: Byte = 0x04         // 2-second self-timer
        const val MODE_MOVIE: Byte = 0x08         // video record mode
        const val MODE_IMMEDIATE: Byte = 0x0C     // direct release (most uses)

        // ── Control byte: button bits (high nibble) ────────────────────
        const val BUTTON_WIDE: Byte = 0x10        // PowerShot zoom out
        const val BUTTON_TELE: Byte = 0x20        // PowerShot zoom in
        const val BUTTON_FOCUS: Byte = 0x40       // AF half-press
        const val BUTTON_RELEASE: Byte = -0x80    // full press (0x80)

        /** "Shutter button pressed, no AF, immediate-release mode" — the
         *  go-to single-shot byte (== 0x8C). Same value as cbremote's
         *  `SIGNAL_ONE_SHUTTER`. */
        const val SHUTTER_PRESS: Byte = (MODE_IMMEDIATE.toInt() or BUTTON_RELEASE.toInt()).toByte()

        /** "Shutter button released" — clears the button bits but keeps
         *  the mode (== 0x0C). cbremote calls this `SIGNAL_WAKE_IMMEDIATE`. */
        const val SHUTTER_RELEASE: Byte = MODE_IMMEDIATE
    }

    private val opMutex = Mutex()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var controlChar: BluetoothGattCharacteristic? = null
    @Volatile private var pairChar: BluetoothGattCharacteristic? = null

    private val connectSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val servicesSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val writeSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    /** True iff the caller explicitly invoked [close]. Disconnect events
     *  that arrive after this is set are expected; don't fire the
     *  spontaneous-disconnect callback in that case. */
    @Volatile private var releasedByUser = false
    /** True once we've reached the post-services-discovered state, so we
     *  know any later STATE_DISCONNECTED is a real link drop (not just a
     *  failed initial connect — those are handled by the connect deferred). */
    @Volatile private var fullyConnected = false

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) g.discoverServices()
                    else connectSignal.getAndSet(null)?.complete(false)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectSignal.getAndSet(null)?.complete(false)
                    servicesSignal.getAndSet(null)?.complete(false)
                    writeSignal.getAndSet(null)?.complete(false)
                    // Distinguish "link drop in flight" (auto-reconnect
                    // candidate) from "caller asked to close" (terminal).
                    if (fullyConnected && !releasedByUser) {
                        Log.i(TAG, "spontaneous disconnect from ${device.address}")
                        runCatching { onSpontaneousDisconnect() }
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "Canon BLE service not found on device")
                servicesSignal.getAndSet(null)?.complete(false)
                return
            }
            controlChar = service.getCharacteristic(CONTROL_CHAR_UUID)
            pairChar = service.getCharacteristic(PAIR_CHAR_UUID)
            val ok = controlChar != null && pairChar != null
            if (!ok) Log.w(TAG, "missing char: control=${controlChar != null} pair=${pairChar != null}")
            if (ok) fullyConnected = true
            servicesSignal.getAndSet(null)?.complete(ok)
            connectSignal.getAndSet(null)?.complete(ok)
        }

        @Deprecated("Old API still used for minSdk 26 compatibility")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeSignal.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** Open a GATT session and negotiate services. Returns true on success.
     *  On first-time pairing the OS dialog appears during this call (when
     *  Android first encrypts the link); the caller's coroutine suspends
     *  until the bond completes or times out. */
    @SuppressLint("MissingPermission")
    suspend fun connect(timeoutMs: Long = 30_000): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        connectSignal.set(deferred)
        servicesSignal.set(CompletableDeferred())
        // Reset lifecycle flags so a fresh connect on a reused instance
        // (today this class is single-use, but the contract should be
        // robust to future refactors) doesn't inherit prior teardown state.
        releasedByUser = false
        fullyConnected = false
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            connectSignal.set(null)
            return false
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        if (!result) close()
        return result
    }

    /** First-time pair-write: `[0x03, <ASCII device name>]` to the pairing
     *  characteristic. Skip on subsequent connects if the device is
     *  already bonded — the camera doesn't need to be re-introduced. */
    @SuppressLint("MissingPermission")
    suspend fun writePairName(name: String): Boolean = opMutex.withLock {
        val ch = pairChar ?: return@withLock false
        val g = gatt ?: return@withLock false
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(1 + nameBytes.size).apply {
            this[0] = 0x03
            System.arraycopy(nameBytes, 0, this, 1, nameBytes.size)
        }
        @Suppress("DEPRECATION")
        run {
            ch.value = payload
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val deferred = CompletableDeferred<Boolean>()
        writeSignal.set(deferred)
        @Suppress("DEPRECATION")
        if (g.writeCharacteristic(ch) != true) {
            writeSignal.set(null)
            return@withLock false
        }
        // Pair-write can take a long time — the user has to confirm the
        // Android system pair dialog. 30 s is generous; below that the
        // user sometimes hasn't tapped Pair yet.
        withTimeoutOrNull(30_000) { deferred.await() } ?: false
    }

    /** Send one byte to the control characteristic (WRITE_NO_RESPONSE).
     *  Used for every shutter press, release, focus, video toggle. */
    @SuppressLint("MissingPermission")
    suspend fun writeControl(byte: Byte): Boolean = opMutex.withLock {
        val ch = controlChar ?: return@withLock false
        val g = gatt ?: return@withLock false
        @Suppress("DEPRECATION")
        run {
            ch.value = byteArrayOf(byte)
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val deferred = CompletableDeferred<Boolean>()
        writeSignal.set(deferred)
        @Suppress("DEPRECATION")
        if (g.writeCharacteristic(ch) != true) {
            writeSignal.set(null)
            return@withLock false
        }
        // NO_RESPONSE writes still raise onCharacteristicWrite; should
        // return within a few ms. Generous timeout for slow stacks.
        withTimeoutOrNull(2_000) { deferred.await() } ?: false
    }

    val address: String get() = device.address
    val name: String? @SuppressLint("MissingPermission") get() = try { device.name } catch (_: SecurityException) { null }

    @SuppressLint("MissingPermission")
    fun close() {
        releasedByUser = true
        fullyConnected = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        gatt = null
        controlChar = null
        pairChar = null
    }
}
