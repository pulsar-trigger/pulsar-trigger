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
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Which Canon BLE protocol a connected body speaks, decided after service
 *  discovery (not from the advertisement — see [CanonBleDiscovery]). */
enum class CanonProtocol {
    /** No recognised Canon service — connect should fail. */
    NONE,
    /** BR-E1 remote service (00050000). Fires older DSLR / M-series bodies. */
    BRE1,
    /** Smartphone-mode with the 00030000 control service present — BLE-only
     *  shutter works (RP / R5 / R6 / newer). */
    SMART,
    /** Smartphone-mode but NO 00030000 control service (the 2018 EOS R). The
     *  body has no BLE shutter at all — even Camera Connect needs Wi-Fi.
     *  Connect should report this and steer the user to USB/Wi-Fi. */
    SMART_NO_SHUTTER,
}

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

        // ── Smartphone-mode protocol (service 00010000) ────────────────
        // A second, richer Canon BLE protocol — the only one that fires the
        // EOS R-series (the BR-E1 service above pairs but won't shoot them).
        // Derived from gkoh/furble's CanonEOSSmart + confirmed firing an
        // EOS RP. See docs/canon-ble-research.md §7.
        val SMART_SERVICE_UUID: UUID = UUID.fromString("00010000-0000-1000-0000-d8492fffa821")
        /** Identity service name char — `[0x01, name]` write + the
         *  pairing-result indication (`0x02` accept / `0x03` reject). */
        val SMART_NAME_UUID: UUID = UUID.fromString("00010006-0000-1000-0000-d8492fffa821")
        /** Identity/registration char — the `[0x03,uuid]`/`[0x04,name]`/
         *  `[0x05,0x02]`/`[0x01]` handshake writes. */
        val SMART_IDEN_UUID: UUID = UUID.fromString("0001000a-0000-1000-0000-d8492fffa821")
        /** Remote-control service. **Present only on bodies that support
         *  BLE-only shutter (RP/R5/R6/newer); ABSENT on the 2018 EOS R,
         *  which has no BLE shutter and needs Wi-Fi/USB.** */
        val SMART_CTRL_SERVICE_UUID: UUID = UUID.fromString("00030000-0000-1000-0000-d8492fffa821")
        /** Mode-select char — write [MODE_SHOOT] to enter shooting mode. */
        val SMART_MODE_UUID: UUID = UUID.fromString("00030010-0000-1000-0000-d8492fffa821")
        /** Shutter char — `[0x00,0x01]` press / `[0x00,0x02]` release. */
        val SMART_SHUTTER_UUID: UUID = UUID.fromString("00030030-0000-1000-0000-d8492fffa821")
        /** Client Characteristic Configuration descriptor (enable notify/indicate). */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val SMART_PAIR_ACCEPT: Byte = 0x02
        const val SMART_PAIR_REJECT: Byte = 0x03
        const val SMART_MODE_SHOOT: Byte = 0x02

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

    // ── Smartphone-mode chars (set in onServicesDiscovered when SMART) ──
    @Volatile private var smartNameChar: BluetoothGattCharacteristic? = null
    @Volatile private var smartIdenChar: BluetoothGattCharacteristic? = null
    @Volatile private var smartModeChar: BluetoothGattCharacteristic? = null
    @Volatile private var smartShutterChar: BluetoothGattCharacteristic? = null

    /** Which protocol the connected body speaks. Set in onServicesDiscovered. */
    @Volatile var protocol: CanonProtocol = CanonProtocol.NONE
        private set

    private val connectSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val servicesSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val writeSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    /** Completed by the [SMART_NAME_UUID] indication carrying the camera's
     *  pairing-result byte (0x02 accept / 0x03 reject). */
    private val pairResultSignal = AtomicReference<CompletableDeferred<Byte>?>(null)
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
            CanonBleLog.d(TAG, "onConnectionStateChange status=$status newState=$newState")
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
                        CanonBleLog.i(TAG, "spontaneous disconnect from ${device.address}")
                        runCatching { onSpontaneousDisconnect() }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            // Auto-detect protocol from the discovered services (the
            // advertisement is unreliable — an RP in smartphone mode still
            // advertises the BR-E1 UUID). Smartphone-mode takes precedence:
            // it's the only path that fires the R-series.
            val smartService = g.getService(SMART_SERVICE_UUID)
            val bre1Service = g.getService(SERVICE_UUID)
            val ok: Boolean
            when {
                smartService != null -> {
                    smartNameChar = smartService.getCharacteristic(SMART_NAME_UUID)
                    smartIdenChar = smartService.getCharacteristic(SMART_IDEN_UUID)
                    val ctrl = g.getService(SMART_CTRL_SERVICE_UUID)
                    smartModeChar = ctrl?.getCharacteristic(SMART_MODE_UUID)
                    smartShutterChar = ctrl?.getCharacteristic(SMART_SHUTTER_UUID)
                    val haveIdentity = smartNameChar != null && smartIdenChar != null
                    protocol = when {
                        !haveIdentity -> CanonProtocol.NONE
                        smartModeChar != null && smartShutterChar != null -> CanonProtocol.SMART
                        // Identity present but no 00030000 control service =
                        // the 2018 EOS R: registers over BLE but has no BLE
                        // shutter (Camera Connect needs Wi-Fi). Connect still
                        // "succeeds" so the transport can report it cleanly.
                        else -> CanonProtocol.SMART_NO_SHUTTER
                    }
                    ok = protocol != CanonProtocol.NONE
                    // Arm the pairing-result indication channel BEFORE the
                    // handshake writes so the camera's accept byte can't be missed.
                    if (ok) smartNameChar?.let { enableIndication(g, it) }
                    CanonBleLog.i(TAG, "smartphone-mode: protocol=$protocol (name=${smartNameChar != null} " +
                        "iden=${smartIdenChar != null} mode=${smartModeChar != null} " +
                        "shutter=${smartShutterChar != null}) bondState=${device.bondState}")
                }
                bre1Service != null -> {
                    controlChar = bre1Service.getCharacteristic(CONTROL_CHAR_UUID)
                    pairChar = bre1Service.getCharacteristic(PAIR_CHAR_UUID)
                    protocol = if (controlChar != null && pairChar != null)
                        CanonProtocol.BRE1 else CanonProtocol.NONE
                    ok = protocol == CanonProtocol.BRE1
                    if (!ok) CanonBleLog.w(TAG, "BR-E1 service present but missing char: " +
                        "control=${controlChar != null} pair=${pairChar != null}")
                    // Diagnostic snapshot for the docs/canon-ble.md troubleshooting
                    // flow. bondState 12=BONDED 11=BONDING 10=NONE; char props
                    // bitmask 0x04=WRITE_NO_RESPONSE 0x08=WRITE 0x10=NOTIFY 0x20=INDICATE.
                    controlChar?.let { c ->
                        CanonBleLog.d(TAG, "services: BR-E1 control char props=0x%02X, bondState=%d"
                            .format(c.properties, device.bondState))
                    }
                }
                else -> {
                    CanonBleLog.w(TAG, "no Canon service (BR-E1 00050000 or smartphone 00010000) found")
                    protocol = CanonProtocol.NONE
                    ok = false
                }
            }
            if (ok) fullyConnected = true
            servicesSignal.getAndSet(null)?.complete(ok)
            connectSignal.getAndSet(null)?.complete(ok)
        }

        @Deprecated("Old API still used for minSdk 26 compatibility")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            // Smartphone-mode pairing result: the camera indicates 0x02
            // (accept) or 0x03 (reject) on the name char once the user
            // confirms the registration on the body.
            if (characteristic.uuid == SMART_NAME_UUID && value.isNotEmpty()) {
                CanonBleLog.d(TAG, "smart pairing indication: 0x%02X".format(value[0].toInt() and 0xFF))
                pairResultSignal.getAndSet(null)?.complete(value[0])
            }
        }

        @Deprecated("Old API still used for minSdk 26 compatibility")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            // Logged for the docs/canon-ble.md troubleshooting flow: a
            // GATT_SUCCESS here on a control write that didn't actually
            // fire the shutter means the camera accepted-then-ignored the
            // byte (arm/state issue), vs a non-zero status meaning the
            // link rejected the write (encryption / CCCD / permission).
            val charName = when (characteristic.uuid) {
                CONTROL_CHAR_UUID -> "control"
                PAIR_CHAR_UUID -> "pair"
                else -> characteristic.uuid.toString()
            }
            CanonBleLog.d(TAG, "onCharacteristicWrite[$charName] status=$status " +
                "(${if (status == BluetoothGatt.GATT_SUCCESS) "GATT_SUCCESS" else "FAILURE"})")
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
        protocol = CanonProtocol.NONE
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            connectSignal.set(null)
            return false
        }
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        if (!result) close()
        return result
    }

    /** Arm-write: `[0x03, <ASCII device name>]` to the pairing
     *  characteristic, sent on every connect to register Pulsar as the
     *  active remote for the session.
     *
     *  Uses **WRITE_NO_RESPONSE** to match `pklaus/canoremote` (the only
     *  reference that claims EOS R-series support — it writes the pair
     *  byte with `response=False`). `iebyt/cbremote` uses write-with-
     *  response and does NOT shoot on Eduardo's EOS R/RP, so the
     *  with-response path is the suspected R-series divergence. */
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
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val deferred = CompletableDeferred<Boolean>()
        writeSignal.set(deferred)
        CanonBleLog.d(TAG, "writePairName: sending [0x03, \"$name\"] no-response (${payload.size} bytes)")
        @Suppress("DEPRECATION")
        if (g.writeCharacteristic(ch) != true) {
            CanonBleLog.w(TAG, "writePairName: writeCharacteristic() returned false (couldn't queue)")
            writeSignal.set(null)
            return@withLock false
        }
        // No-response write still raises onCharacteristicWrite (confirms it
        // left the phone). First connect may also pop the OS pair dialog —
        // 30 s timeout covers the user tapping through it.
        val ok = withTimeoutOrNull(30_000) { deferred.await() } ?: false
        CanonBleLog.d(TAG, "writePairName: confirmed=$ok")
        ok
    }

    /** Send one byte to the control characteristic (WRITE_NO_RESPONSE).
     *  Used for every shutter press, release, focus, video toggle. */
    @SuppressLint("MissingPermission")
    suspend fun writeControl(byte: Byte): Boolean = opMutex.withLock {
        val ch = controlChar ?: run {
            CanonBleLog.w(TAG, "writeControl: no control characteristic (not connected?)")
            return@withLock false
        }
        val g = gatt ?: return@withLock false
        @Suppress("DEPRECATION")
        run {
            ch.value = byteArrayOf(byte)
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val deferred = CompletableDeferred<Boolean>()
        writeSignal.set(deferred)
        CanonBleLog.d(TAG, "writeControl: sending 0x%02X".format(byte.toInt() and 0xFF))
        @Suppress("DEPRECATION")
        if (g.writeCharacteristic(ch) != true) {
            // writeCharacteristic returning false = the op couldn't even be
            // queued (busy, or the link isn't writable). Distinct from a
            // queued write that later fails in onCharacteristicWrite.
            CanonBleLog.w(TAG, "writeControl: writeCharacteristic() returned false (couldn't queue 0x%02X)"
                .format(byte.toInt() and 0xFF))
            writeSignal.set(null)
            return@withLock false
        }
        // NO_RESPONSE writes still raise onCharacteristicWrite; should
        // return within a few ms. Generous timeout for slow stacks.
        val ok = withTimeoutOrNull(2_000) { deferred.await() } ?: false
        if (!ok) CanonBleLog.w(TAG, "writeControl: 0x%02X did not confirm (timeout or GATT failure)"
            .format(byte.toInt() and 0xFF))
        ok
    }

    /** Enable indications on a characteristic (set notify + write the CCCD).
     *  Best-effort and fire-and-forget — the descriptor write completes well
     *  before the camera's accept indication (which waits on user confirm). */
    @SuppressLint("MissingPermission")
    private fun enableIndication(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID) ?: return
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(cccd)
            }
        } catch (e: Exception) {
            CanonBleLog.w(TAG, "enableIndication failed: ${e.message}")
        }
    }

    /** Write a payload to an arbitrary characteristic (WRITE_NO_RESPONSE),
     *  serialized through [opMutex]. Used by the smartphone-mode handshake +
     *  shutter. Returns true once `onCharacteristicWrite` confirms. */
    @SuppressLint("MissingPermission")
    private suspend fun writeNoResponse(
        ch: BluetoothGattCharacteristic?,
        payload: ByteArray,
        label: String,
    ): Boolean = opMutex.withLock {
        val c = ch ?: run { CanonBleLog.w(TAG, "$label: characteristic missing"); return@withLock false }
        val g = gatt ?: return@withLock false
        @Suppress("DEPRECATION")
        run {
            c.value = payload
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val deferred = CompletableDeferred<Boolean>()
        writeSignal.set(deferred)
        @Suppress("DEPRECATION")
        if (g.writeCharacteristic(c) != true) {
            CanonBleLog.w(TAG, "$label: writeCharacteristic() returned false (couldn't queue)")
            writeSignal.set(null)
            return@withLock false
        }
        // First write may trigger Android bonding (encrypted-link setup);
        // 30 s covers the OS / on-camera confirmation.
        val ok = withTimeoutOrNull(30_000) { deferred.await() } ?: false
        CanonBleLog.d(TAG, "$label: ${payload.joinToString("") { "%02x".format(it) }} confirmed=$ok")
        ok
    }

    /** Ensure the link is OS-bonded before the smartphone handshake. On the
     *  RP this is required — the registration writes are ignored on an
     *  unbonded link, and `createBond()` is what makes the camera show its
     *  pairing-confirm prompt (the equivalent of `bluetoothctl pair`). Calls
     *  [onAwaitConfirm] so the UI tells the user to confirm on the camera,
     *  then polls [BluetoothDevice.getBondState] until BONDED or timeout. */
    @SuppressLint("MissingPermission")
    suspend fun ensureBonded(onAwaitConfirm: () -> Unit, timeoutMs: Long = 60_000): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            CanonBleLog.i(TAG, "ensureBonded: already BONDED")
            return true
        }
        CanonBleLog.i(TAG, "ensureBonded: bondState=${device.bondState} → createBond() " +
            "(confirm the pairing on the camera)")
        onAwaitConfirm()
        val started = runCatching { device.createBond() }.getOrDefault(false)
        CanonBleLog.d(TAG, "ensureBonded: createBond() returned $started")
        val ok = withTimeoutOrNull(timeoutMs) {
            while (device.bondState != BluetoothDevice.BOND_BONDED) delay(300)
            true
        } ?: false
        CanonBleLog.i(TAG, "ensureBonded: result=${if (ok) "BONDED" else "NOT bonded (state=${device.bondState})"}")
        return ok
    }

    /** Smartphone-mode registration handshake (per furble CanonEOSSmart,
     *  confirmed firing an EOS RP — see docs/canon-ble-research.md §7):
     *    [01,name]→name; [03,uuid]→iden; [04,name]→iden; [05,02]→iden;
     *    wait for the camera's 0x02 accept (user confirms on the body);
     *    [01]→iden finalize; [MODE_SHOOT]→mode.
     *  [onAwaitConfirm] fires when we start waiting, so the UI can prompt the
     *  user to confirm on the camera. Returns true once shoot-mode is set. */
    suspend fun armSmart(
        name: String,
        deviceUuid: ByteArray,
        onAwaitConfirm: () -> Unit,
        confirmTimeoutMs: Long = 70_000,
    ): Boolean {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val pr = CompletableDeferred<Byte>()
        pairResultSignal.set(pr)              // arm before writes so accept can't race past us
        // Let the CCCD-enable write (queued in onServicesDiscovered) settle —
        // Android allows one GATT op in flight, so the first identify write
        // could fail to queue if it lands on top of the descriptor write.
        delay(300)
        val wrote =
            writeNoResponse(smartNameChar, byteArrayOf(0x01) + nameBytes, "smart ID1 [01,name]") &&
            writeNoResponse(smartIdenChar, byteArrayOf(0x03) + deviceUuid, "smart ID2 [03,uuid]") &&
            writeNoResponse(smartIdenChar, byteArrayOf(0x04) + nameBytes, "smart ID3 [04,name]") &&
            writeNoResponse(smartIdenChar, byteArrayOf(0x05, 0x02), "smart ID4 [05,02]")
        if (!wrote) { pairResultSignal.set(null); return false }

        onAwaitConfirm()
        val result = withTimeoutOrNull(confirmTimeoutMs) { pr.await() }
        pairResultSignal.set(null)
        when (result) {
            SMART_PAIR_ACCEPT -> CanonBleLog.i(TAG, "smart pairing ACCEPTED (0x02)")
            SMART_PAIR_REJECT -> { CanonBleLog.w(TAG, "smart pairing REJECTED (0x03)"); return false }
            null -> { CanonBleLog.w(TAG, "smart pairing: no accept within ${confirmTimeoutMs}ms"); return false }
            else -> CanonBleLog.w(TAG, "smart pairing: unexpected 0x%02X — proceeding".format(result.toInt() and 0xFF))
        }

        return writeNoResponse(smartIdenChar, byteArrayOf(0x01), "smart ID5 finalize") &&
            writeNoResponse(smartModeChar, byteArrayOf(SMART_MODE_SHOOT), "smart MODE_SHOOT")
    }

    /** Smartphone-mode shutter — a **toggle** on `[0x00,0x01]` (button
     *  down ↔ up), verified on the EOS RP. furble's `[0x00,0x02]` "release"
     *  is inert on the RP, so BOTH press and release send `[0x00,0x01]`:
     *  press = button-down (opens / fires), release = button-up (closes).
     *  A complete shot or bulb is two toggles, returning the button to "up".
     *  See docs/canon-ble-research.md §7. */
    suspend fun smartShutter(press: Boolean): Boolean = writeNoResponse(
        smartShutterChar,
        byteArrayOf(0x00, 0x01),
        if (press) "smart shutter DOWN [00,01]" else "smart shutter UP [00,01]",
    )

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
        smartNameChar = null
        smartIdenChar = null
        smartModeChar = null
        smartShutterChar = null
        protocol = CanonProtocol.NONE
    }
}
