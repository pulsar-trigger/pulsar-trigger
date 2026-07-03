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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

        /** BR-E1 status characteristic (00050004): the camera **indicates** the
         *  shutter state here after each button event — `0x01` = shutter
         *  open/pressed, `0x03` = closed/released. Sniffer-confirmed 2026-07-01. */
        val STATUS_CHAR_UUID: UUID = UUID.fromString("00050004-0000-1000-0000-d8492fffa821")

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
    @Volatile private var statusChar: BluetoothGattCharacteristic? = null
    /** True iff the most recent control write was a shutter PRESS (0x8C). The
     *  status char emits 0x01 for an AF half-press (0x4C) and zoom too, so we
     *  only trust a 0x01/0x03 indication as shutter state when this is set. */
    @Volatile private var lastControlWasShutterPress = false

    /** BR-E1 shutter state from the 00050004 status indication: null = unknown,
     *  true = open, false = closed. Lets the bulb toggle be idempotent +
     *  self-confirming (see `CanonBleTransport.ensureShutter`). */
    private val _shutterOpen = MutableStateFlow<Boolean?>(null)
    val shutterOpen: StateFlow<Boolean?> = _shutterOpen

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
    /** Completed by onDescriptorWrite — lets [enableStatusIndication] wait for
     *  the CCCD write to finish before releasing [opMutex], so the next GATT op
     *  (a shutter write) can't land on top of the in-flight descriptor write. */
    private val descriptorSignal = AtomicReference<CompletableDeferred<Boolean>?>(null)
    /** Completed by onCharacteristicRead — lets [enableStatusIndication] seed the
     *  initial shutter state from a one-off read of the status char. */
    private val readSignal = AtomicReference<CompletableDeferred<ByteArray?>?>(null)
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
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Deliberately NO requestConnectionPriority(HIGH) here. The
                        // v0.600 fast-interval request was a guess at the bulb-hold
                        // problem (bonding turned out to be the real fix, v0.602) and
                        // became the prime suspect for the camera's periodic
                        // status=19 disconnects every ~2-3 min: the PC (default
                        // interval) never gets dropped mid-run, the phone (HIGH)
                        // reliably does — one drop killed an endurance run mid-frame
                        // (v0.613 diag). Default/balanced matches the real BR-E1.
                        g.discoverServices()
                    } else connectSignal.getAndSet(null)?.complete(false)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectSignal.getAndSet(null)?.complete(false)
                    servicesSignal.getAndSet(null)?.complete(false)
                    writeSignal.getAndSet(null)?.complete(false)
                    descriptorSignal.getAndSet(null)?.complete(false)
                    readSignal.getAndSet(null)?.complete(null)
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
                    // Locate the status char (00050004) but DON'T subscribe here:
                    // enabling it queues a CCCD descriptor write, and Android allows
                    // only one GATT op in flight — during service discovery that
                    // write races the pairing write (writePairName) and breaks
                    // pairing (regression seen v0.596). The transport calls
                    // enableStatusIndication() AFTER pairing, when the link is idle.
                    statusChar = bre1Service.getCharacteristic(STATUS_CHAR_UUID)
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
            if (characteristic.uuid == STATUS_CHAR_UUID && value.isNotEmpty()) {
                val code = value[0].toInt() and 0xFF
                // 0x01 ("active") is ALSO emitted by an AF half-press (0x4C) and
                // by zoom — not just the shutter. Only trust it as a shutter
                // open/close when the last control write was a shutter press
                // (0x8C); otherwise AF's 0x01 flips shutterOpen=true and the next
                // ensureShutter skips the real open (manual "Hold" no-op, verified
                // in the v0.600 diagnostics: 0x4C → 0x01 → shutterOpen=true).
                if (lastControlWasShutterPress) {
                    when (code) {
                        0x01 -> _shutterOpen.value = true
                        0x03 -> _shutterOpen.value = false
                    }
                }
                CanonBleLog.d(TAG, "BR-E1 status: 0x%02X (shutterPress=%s) → shutterOpen=%s"
                    .format(code, lastControlWasShutterPress, _shutterOpen.value))
            }
            // DIAGNOSTIC: log EVERY smart-mode notification (bytes) so we can
            // spot which characteristic reflects shutter open/close — correlate
            // these lines with the "smart bulb toggle" writes. See
            // subscribeSmartNotifyForDiagnostics().
            if (protocol == CanonProtocol.SMART) {
                CanonBleLog.d(TAG, "smart notify[${characteristic.uuid.toString().take(8)}]: " +
                    value.joinToString("") { "%02x".format(it) })
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

        @Deprecated("Old API still used for minSdk 26 compatibility")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // Only [enableStatusIndication] arms descriptorSignal; the SMART
            // path's fire-and-forget CCCD write leaves it null (no-op complete).
            descriptorSignal.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Old API still used for minSdk 26 compatibility")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
            if (characteristic.uuid == STATUS_CHAR_UUID && value != null && value.isNotEmpty()) {
                // Seed at connect: the shutter is at rest, so anything but 0x01
                // (open) means closed — including 0x00 (idle), which the read
                // returns before any shutter action. Seeding false (not null)
                // stops the session-start defensive close from blind-toggling OPEN.
                _shutterOpen.value = (value[0].toInt() and 0xFF) == 0x01
            }
            readSignal.getAndSet(null)?.complete(value)
        }
    }

    /** Open a GATT session and negotiate services. Returns true on success.
     *  On first-time pairing the OS dialog appears during this call (when
     *  Android first encrypts the link); the caller's coroutine suspends
     *  until the bond completes or times out. */
    @SuppressLint("MissingPermission")
    suspend fun connect(timeoutMs: Long = 30_000, autoConnect: Boolean = false): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        connectSignal.set(deferred)
        servicesSignal.set(CompletableDeferred())
        // Reset lifecycle flags so a fresh connect on a reused instance
        // (today this class is single-use, but the contract should be
        // robust to future refactors) doesn't inherit prior teardown state.
        releasedByUser = false
        fullyConnected = false
        protocol = CanonProtocol.NONE
        // autoConnect=true is the OS-managed reconnect: the stack completes the
        // connection whenever the (bonded) body becomes available — even via a
        // directed advertisement a service-UUID scan never sees. Used for
        // reconnect; first connect uses autoConnect=false (immediate attempt).
        CanonBleLog.i(TAG, "connectGatt(autoConnect=$autoConnect) to ${device.address}")
        gatt = device.connectGatt(ctx, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            connectSignal.set(null)
            return false
        }
        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } catch (t: Throwable) {
            // Cancellation (e.g. user navigated away / started another connect)
            // must close the pending autoConnect GATT so it doesn't leak.
            close()
            throw t
        }
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

    /** Send the BR-E1 control word to the control characteristic
     *  (WRITE_NO_RESPONSE). The real BR-E1 remote writes **two** bytes — the
     *  command byte plus a trailing `0x00` — confirmed by an nRF Sniffer capture
     *  of the hardware remote (2026-06-30). A bare 1-byte write does fire the
     *  RP/R, but the 2-byte form is exactly what Canon's remote sends and is the
     *  more compatible shape. Used for every shutter press, release, focus,
     *  video toggle. */
    @SuppressLint("MissingPermission")
    suspend fun writeControl(byte: Byte): Boolean = opMutex.withLock {
        val ch = controlChar ?: run {
            CanonBleLog.w(TAG, "writeControl: no control characteristic (not connected?)")
            return@withLock false
        }
        val g = gatt ?: return@withLock false
        // Mark whether this is a shutter PRESS so the status-indication handler
        // knows a following 0x01/0x03 reflects the shutter — not an AF half-press
        // (0x4C) or zoom, which also emit 0x01 on the status char.
        lastControlWasShutterPress = (byte == SHUTTER_PRESS)
        @Suppress("DEPRECATION")
        run {
            // Two-byte control word [cmd, 0x00] — matches the hardware BR-E1
            // remote (nRF Sniffer, 2026-06-30). The trailing byte is always 0x00.
            ch.value = byteArrayOf(byte, 0x00)
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

    /** Subscribe to the BR-E1 status characteristic (00050004) **after** pairing.
     *  Doing this during service discovery raced the pairing write and broke
     *  pairing (one GATT op in flight); here the link is idle. Serialized through
     *  [opMutex] and waits for onDescriptorWrite so the first shutter write can't
     *  collide with the in-flight CCCD write. Best-effort: a body without the
     *  status char (or one that never confirms) just leaves [shutterOpen] null and
     *  the bulb falls back to a plain toggle. Returns true iff the CCCD write
     *  confirmed. */
    @SuppressLint("MissingPermission")
    suspend fun enableStatusIndication(): Boolean = opMutex.withLock {
        val ch = statusChar ?: return@withLock false
        val g = gatt ?: return@withLock false
        val cccd = try {
            g.setCharacteristicNotification(ch, true)
            ch.getDescriptor(CCCD_UUID)
        } catch (e: Exception) {
            CanonBleLog.w(TAG, "enableStatusIndication: notify setup failed: ${e.message}")
            null
        } ?: run {
            CanonBleLog.w(TAG, "enableStatusIndication: no CCCD on status char — bulb stays unconfirmed")
            return@withLock false
        }
        val deferred = CompletableDeferred<Boolean>()
        descriptorSignal.set(deferred)
        @Suppress("DEPRECATION")
        run { cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE }
        @Suppress("DEPRECATION")
        if (g.writeDescriptor(cccd) != true) {
            descriptorSignal.set(null)
            CanonBleLog.w(TAG, "enableStatusIndication: writeDescriptor() couldn't queue")
            return@withLock false
        }
        val ok = withTimeoutOrNull(3_000) { deferred.await() } ?: false
        CanonBleLog.d(TAG, "enableStatusIndication: CCCD write confirmed=$ok")
        // Seed the current shutter state with a one-off read so the first bulb
        // op starts from a KNOWN position. Without this the session-start
        // defensive close runs with state=null and blind-toggles — which OPENS a
        // closed shutter instead of ensuring it closed (the "derail" bug).
        val rd = CompletableDeferred<ByteArray?>()
        readSignal.set(rd)
        @Suppress("DEPRECATION")
        if (g.readCharacteristic(ch)) {
            withTimeoutOrNull(2_000) { rd.await() }   // onCharacteristicRead seeds _shutterOpen
            CanonBleLog.d(TAG, "enableStatusIndication: seeded shutterOpen=${_shutterOpen.value}")
        } else {
            readSignal.set(null)
            CanonBleLog.w(TAG, "enableStatusIndication: status read couldn't queue — state stays unknown")
        }
        ok
    }

    /** Reset the cached shutter state to CLOSED. Called by the transport after a
     *  single-shot TAP (Timelapse / Cable release / Compat test fire): on the
     *  non-bulb dial those modes require, the 0x01 ack means "shot fired", NOT
     *  "shutter held open" — leaving the cache 'open' made the main-menu safety
     *  close toggle the camera and fire ~2 stray frames when leaving those modes
     *  (Eduardo, 2026-07-01). Any late tap-ack is already ignored because the
     *  trailing 0x0C write clears [lastControlWasShutterPress]. */
    fun markShutterClosed() { _shutterOpen.value = false }

    /** DIAGNOSTIC (smartphone mode): subscribe to EVERY notify/indicate-capable
     *  characteristic on the body and log its notifications, to hunt for the
     *  RP's shutter-STATE signal. Smart mode currently has no known state char,
     *  so a toggle-parity desync (a single-shot tap in Bulb, or an eaten toggle)
     *  can leave the sensor OPEN with no way to detect it (the Camera Test
     *  left-sensor-open bug, 2026-07-02). Read-only: subscribing + logging
     *  changes NO shutter behaviour. Serialized (one CCCD write in flight at a
     *  time, waiting for onDescriptorWrite) to respect Android's single-GATT-op
     *  limit. Usage: connect the RP, do a manual **open → wait → close** in Bulb,
     *  and read the `smart notify[…]` lines — whichever characteristic's bytes
     *  flip with the shutter is the state signal we wire in next. */
    @SuppressLint("MissingPermission")
    suspend fun subscribeSmartNotifyForDiagnostics() = opMutex.withLock {
        val g = gatt ?: return@withLock
        val targets = g.services.flatMap { it.characteristics }.filter {
            (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
        }
        CanonBleLog.d(TAG, "diag: subscribing to ${targets.size} notify/indicate chars " +
            "for shutter-state discovery")
        for (ch in targets) {
            val tag = ch.uuid.toString().take(8)
            try {
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    CanonBleLog.d(TAG, "diag: $tag has no CCCD — skipping")
                } else {
                    val notify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    @Suppress("DEPRECATION")
                    cccd.value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                 else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    val deferred = CompletableDeferred<Boolean>()
                    descriptorSignal.set(deferred)
                    @Suppress("DEPRECATION")
                    if (g.writeDescriptor(cccd)) {
                        val ok = withTimeoutOrNull(3_000) { deferred.await() } ?: false
                        CanonBleLog.d(TAG, "diag: subscribed $tag (notify=$notify) cccd=$ok")
                    } else {
                        descriptorSignal.set(null)
                        CanonBleLog.d(TAG, "diag: $tag CCCD write couldn't queue")
                    }
                }
            } catch (e: Exception) {
                CanonBleLog.w(TAG, "diag: subscribe $tag failed: ${e.message}")
            }
        }
    }

    // NOTE deliberately NO readStatusRaw()-style verification helper: a raw read
    // of 00050004 reports the same PHANTOM state as the 0x001b ack when the
    // camera eats a press during its ~3–4 s post-frame cooldown (card-proven
    // 2026-07-02 — nine "verified open" reads with 0.4–0.5 s frames on the card).
    // The wire has no truth signal; the defense is the viewmodel's 4 s minimum
    // interval (canonBleSafeInterval).

    /** Write a payload to an arbitrary characteristic (WRITE_NO_RESPONSE),
     *  serialized through [opMutex]. Used by the smartphone-mode handshake +
     *  shutter. Returns true once `onCharacteristicWrite` confirms.
     *
     *  Retries on `writeCharacteristic() == false`: the first write right
     *  after a fresh connect routinely fails to even queue because the
     *  encrypted-link upgrade is still mid-flight ("busy"), even though
     *  `bondState == BONDED`. Each retry waits a short backoff so the L2CAP
     *  setup can complete. Without this, the smartphone handshake's first
     *  `[01,name]` write would fail, the whole connect would abort, and the
     *  viewmodel's `onCanonBleLinkDropped` reconnect would have to cycle the
     *  GATT 3–4 times before one happened to land in a writable window
     *  (RP diagnostics log 2026-05-29). */
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
        var queued = false
        val backoffs = longArrayOf(150, 300, 500, 800)
        for ((attempt, wait) in backoffs.withIndex()) {
            val deferred = CompletableDeferred<Boolean>()
            writeSignal.set(deferred)
            @Suppress("DEPRECATION")
            if (g.writeCharacteristic(c) == true) {
                // First write may trigger Android bonding (encrypted-link setup);
                // 30 s covers the OS / on-camera confirmation.
                val ok = withTimeoutOrNull(30_000) { deferred.await() } ?: false
                CanonBleLog.d(TAG, "$label: ${payload.joinToString("") { "%02x".format(it) }} confirmed=$ok" +
                    if (attempt > 0) " (after $attempt retries)" else "")
                queued = true
                return@withLock ok
            }
            writeSignal.set(null)
            CanonBleLog.w(TAG, "$label: writeCharacteristic() returned false " +
                "(couldn't queue, attempt ${attempt + 1}/${backoffs.size}) — backing off ${wait}ms")
            delay(wait)
        }
        if (!queued) CanonBleLog.w(TAG, "$label: gave up after ${backoffs.size} queue attempts")
        false
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
        /** First-time pairing: wait the full window for the user to confirm
         *  on the camera, and FAIL if rejected/timed-out. On a reconnect of an
         *  already-registered body the camera won't re-prompt (and may not
         *  re-send 0x02), so we wait only briefly and proceed regardless —
         *  otherwise reconnect stalls for the full timeout. */
        freshPair: Boolean = true,
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

        if (freshPair) onAwaitConfirm()
        val waitMs = if (freshPair) 70_000L else 6_000L
        val result = withTimeoutOrNull(waitMs) { pr.await() }
        pairResultSignal.set(null)
        when (result) {
            SMART_PAIR_ACCEPT -> CanonBleLog.i(TAG, "smart pairing ACCEPTED (0x02)")
            SMART_PAIR_REJECT -> { CanonBleLog.w(TAG, "smart pairing REJECTED (0x03)"); return false }
            null -> if (freshPair) {
                CanonBleLog.w(TAG, "smart pairing: no accept within ${waitMs}ms — aborting first pair")
                return false
            } else {
                CanonBleLog.i(TAG, "reconnect: no re-accept (already registered) — proceeding")
            }
            else -> CanonBleLog.w(TAG, "smart pairing: unexpected 0x%02X — proceeding".format(result.toInt() and 0xFF))
        }

        return writeNoResponse(smartIdenChar, byteArrayOf(0x01), "smart ID5 finalize") &&
            writeNoResponse(smartModeChar, byteArrayOf(SMART_MODE_SHOOT), "smart MODE_SHOOT")
    }

    /** Smartphone-mode shutter — **bulb path**: explicit press/release
     *  events. Press = `[0x00,0x01]`, release = `[0x00,0x02]`. Same byte
     *  pattern as M-mode `smartShutterTap` because the empirical
     *  "toggle on [00,01]" recipe for Bulb (claimed in v0.290 docs)
     *  caused continuous shooting on the EOS RP in v0.357 testing —
     *  the camera treats repeated `[00,01]` as repeated *presses* in
     *  Bulb too, never registers the release. `[00,02]` is the release
     *  on both dial settings.
     *  Used by `startBulb` / `stopBulb`. See docs/canon-ble-research.md §7. */
    suspend fun smartShutter(press: Boolean): Boolean = writeNoResponse(
        smartShutterChar,
        if (press) byteArrayOf(0x00, 0x01) else byteArrayOf(0x00, 0x02),
        if (press) "smart shutter DOWN [00,01]" else "smart shutter UP [00,02]",
    )

    /** Smartphone-mode shutter — **single-shot path**: distinct press / release
     *  events. Press = `[0x00,0x01]`, release = `[0x00,0x02]`.
     *
     *  In M (non-bulb) mode the camera treats the two bytes as distinct shutter
     *  events, NOT as a positional toggle. With the bulb-style
     *  `[00,01]/[00,01]` pair the "release" re-presses, leaving the button
     *  DOWN and the body shooting continuously (verified on EOS RP). `[00,02]`
     *  was previously read as "inert" but that test was run in Bulb, where the
     *  camera tracks shutter-open state on `[00,01]` only and doesn't process
     *  release events — the byte does fire in M.
     *
     *  Used by `fireShutter` only. `startBulb` / `stopBulb` keep the
     *  `[00,01]` toggle path above (verified to work for the bulb state
     *  machine). Confirmed firing one frame in M on the RP, v0.290. */
    suspend fun smartShutterTap(press: Boolean): Boolean = writeNoResponse(
        smartShutterChar,
        if (press) byteArrayOf(0x00, 0x01) else byteArrayOf(0x00, 0x02),
        if (press) "smart shutter TAP press [00,01]" else "smart shutter TAP release [00,02]",
    )

    /** Smartphone-mode **BULB toggle** — the dial-on-BULB path. With the RP on
     *  BULB the shutter is a TOGGLE on `[00,01]` (like BR-E1): one press OPENS,
     *  the next CLOSES. `[00,02]` is **inert in Bulb** — it fires the release in
     *  M, but Bulb tracks state on `[00,01]` only and ignores it (card/shutter-
     *  proven on the RP: press opens, `[00,02]` never stops → the manual-hold-
     *  won't-close + every-other-shot bug). So Bulb open AND close both send
     *  `[00,01]`; the transport gates every call on its `bulbOpen` flag so a
     *  redundant/defensive close can't send a stray `[00,01]` and re-open the
     *  shutter (that un-gated re-toggle was the old "continuous shooting"
     *  misdiagnosis). Restores the fc760d3 model. Distinct from
     *  [smartShutterTap] (`[00,01]`/`[00,02]`, the M-mode single-shot path). */
    suspend fun smartBulbToggle(): Boolean = writeNoResponse(
        smartShutterChar,
        byteArrayOf(0x00, 0x01),
        "smart bulb toggle [00,01]",
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
        statusChar = null
        _shutterOpen.value = null
        smartNameChar = null
        smartIdenChar = null
        smartModeChar = null
        smartShutterChar = null
        protocol = CanonProtocol.NONE
    }
}
