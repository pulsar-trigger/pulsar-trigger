/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.BUTTON_FOCUS
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.BUTTON_WIDE
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.MODE_IMMEDIATE
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.SHUTTER_PRESS
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.SHUTTER_RELEASE
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.transport.TransportKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom

/** Outcome of [CanonBleTransport.connect]. Distinguishes a plain failure from
 *  the EOS-R case (registers in smartphone mode but has no BLE shutter) so the
 *  UI can steer the user to USB/Wi-Fi instead of just "connect failed". */
sealed interface CanonBleConnectResult {
    data class Ok(val transport: CanonBleTransport) : CanonBleConnectResult
    object Failed : CanonBleConnectResult
    /** Smartphone-mode body with no 00030000 control service (2018 EOS R). */
    object NoBleShutter : CanonBleConnectResult
}

/**
 * `CameraTransport` over Canon's BR-E1 BLE service. The protocol is
 * one-way (phone → camera) single-byte writes; see `docs/canon-ble.md`
 * for the full wire format. Capabilities are inherently limited vs
 * CCAPI/PTP:
 *
 *  - **Bulb works** via the same press/hold pattern as a real BR-E1
 *    hardware remote (write 0x8C, wait, write 0x0C). The body must be
 *    in Bulb on its mode dial — the protocol has no way to set shutter
 *    speed remotely.
 *  - **No live view / lens info / battery readout / settings.** Those
 *    aren't in the BR-E1 protocol, period. Star Focus tiles, lens
 *    auto-fill, the battery chip, and the camera-params panel all
 *    fall back to "transport doesn't support it" gates.
 *
 * Phase 1 covers connect, pairing, fireShutter, startBulb / stopBulb.
 * Auto-reconnect on BLE re-advertise and the BLE-reconnecting banner
 * come in Phase 3.
 */
class CanonBleTransport private constructor(
    val device: BluetoothDevice,
    private val client: CanonBleClient,
) : CameraTransport {

    companion object {
        private const val TAG = "CanonBleTransport"

        /** Brief press-release pause for single shots (ms). Matches the
         *  200 ms delay used in maxmacstn/ESP32-Canon-BLE-Remote — long
         *  enough for the body to register the press before we let go,
         *  short enough that single-shot Timelapse keeps its rhythm. */
        private const val SHUTTER_TAP_MS = 200L

        /** How long to hold the AF half-press before the full release. The
         *  body needs a beat to lock focus before the shutter fires.
         *  Same value used across ESP32-Canon-BLE-Remote + cbremote. */
        private const val AF_HOLD_MS = 200L

        /** Settle after the wake nudge, giving a sleeping body time to power its
         *  shooting engine back up before the run's first real press. */
        private const val WAKE_SETTLE_MS = 1_300L

        /** The EOS R eats a shutter press that lands within ~3–4 s of finishing a
         *  frame (post-frame cooldown; card-proven). The viewmodel clamps intra-run
         *  intervals to 4 s, but this transport-level floor also covers what that
         *  misses: STEP BOUNDARIES in a multi-step flow and the first frame of each
         *  step (their gap is the step's start-delay, often < 4 s). startBulb waits
         *  out any shortfall since the last real close. Matches CANON_BLE_MIN_INTERVAL_MS. */
        private const val POST_FRAME_COOLDOWN_MS = 4_000L

        /** Bulb verify-retry: max toggles [ensureShutter] sends to reach the
         *  wanted state. The EOS R has a period-3 quirk — after it opens, the very
         *  next 0x8C is a DEAD no-op (fires no frame, confirmed by card count) and
         *  the one after closes — so a close routinely needs 2 clicks. Headroom
         *  covers a rare double-dead. */
        private const val MAX_SHUTTER_ATTEMPTS = 4

        /** How long to wait for the 0x001b indication to confirm a bulb toggle
         *  flipped the shutter. A real toggle indicates in ~70 ms (the wait returns
         *  early); a dead click leaves the state unchanged, so this times out and
         *  we retry. */
        private const val SHUTTER_CONFIRM_MS = 400L


        /** Phone-side device name that shows up in the camera's
         *  paired-devices list. Short, brand-aligned, fits Canon's
         *  display width without ellipsis. */
        const val PAIR_NAME = "Pulsar"

        private const val SMART_UUID_PREF = "pulsar_canon_ble"
        private const val SMART_UUID_KEY = "smart_device_uuid"

        /** Open a GATT session and arm the camera with whichever protocol it
         *  exposes (auto-detected after service discovery):
         *   - **BR-E1** (00050000): `[0x03, name]` arm-write, as before.
         *   - **Smartphone** (00010000 + 00030000): the furble identify
         *     handshake, then [onAwaitConfirm] fires while we wait for the
         *     user to confirm the pairing on the camera body.
         *   - **Smartphone but no 00030000** (2018 EOS R): no BLE shutter →
         *     [CanonBleConnectResult.NoBleShutter].
         *
         *  [onSpontaneousDisconnect] fires when the link drops *after* a
         *  successful connect; not invoked for an explicit [release]. */
        @SuppressLint("MissingPermission")
        suspend fun connect(
            ctx: Context,
            device: BluetoothDevice,
            onSpontaneousDisconnect: () -> Unit = {},
            onAwaitConfirm: () -> Unit = {},
            /** Reconnect to a bonded body: OS-managed [autoConnect] + a longer
             *  window, since the camera may take a while to become available. */
            autoConnect: Boolean = false,
            connectTimeoutMs: Long = 30_000,
        ): CanonBleConnectResult {
            var transportRef: CanonBleTransport? = null
            val client = CanonBleClient(ctx, device, onSpontaneousDisconnect = {
                transportRef?.markDisconnected()
                onSpontaneousDisconnect()
            })
            if (!client.connect(timeoutMs = connectTimeoutMs, autoConnect = autoConnect)) {
                CanonBleLog.w(TAG, "GATT connect failed for ${device.address}")
                client.close()
                return CanonBleConnectResult.Failed
            }
            // Arm the camera per detected protocol, before any shutter write.
            when (client.protocol) {
                CanonProtocol.BRE1 -> {
                    // Bond the link like a real BR-E1 remote does. The link was
                    // BOND_NONE (writePairName is app-level only, no OS bond),
                    // which (a) blocks auto-reconnect — Android's autoConnect only
                    // silently re-attaches to a BONDED device on the camera's
                    // directed advert, so an unbonded body needs re-pairing to come
                    // back — and (b) is the suspected cause of the camera
                    // self-closing bulb on long exposures (safety timeout for an
                    // unbonded remote). First connect pops the OS pair dialog;
                    // already-bonded reconnects return immediately. NON-FATAL: if
                    // the bond doesn't take, basic single-shot/manual firing still
                    // works unbonded (just no reconnect / long bulb-hold).
                    if (!client.ensureBonded(onAwaitConfirm)) {
                        CanonBleLog.w(TAG, "BR-E1: bond not established for ${device.address} — " +
                            "continuing UNBONDED (fires, but no auto-reconnect / long bulb-hold)")
                    }
                    // BR-E1: `[0x03, name]` on every connect — the "arm as the
                    // active remote" step the body expects each session (matches
                    // iebyt/cbremote's onServicesDiscovered). See docs/canon-ble.md.
                    if (!client.writePairName(PAIR_NAME)) {
                        CanonBleLog.w(TAG, "BR-E1 pair/arm write failed for ${device.address}; aborting")
                        client.close()
                        return CanonBleConnectResult.Failed
                    }
                    // Now that pairing is done and the link is idle, subscribe to
                    // the status char for bulb self-confirmation. Best-effort: a
                    // failure here just means the bulb toggle runs unconfirmed —
                    // it must NOT abort the (working) connection.
                    client.enableStatusIndication()
                }
                CanonProtocol.SMART -> {
                    // Already bonded == a reconnect (or a re-pair of a known
                    // body): the camera won't re-prompt, so armSmart skips the
                    // long accept-wait. A fresh (unbonded) connect is a first
                    // pairing → full confirm-on-camera flow.
                    val freshPair = device.bondState != BluetoothDevice.BOND_BONDED
                    // The RP ignores the registration writes on an unbonded
                    // link — bond first (this is what makes the camera show its
                    // pairing-confirm prompt), then run the handshake.
                    if (!client.ensureBonded(onAwaitConfirm)) {
                        CanonBleLog.w(TAG, "bond not established for ${device.address}; aborting")
                        client.close()
                        return CanonBleConnectResult.Failed
                    }
                    // Smartphone-mode registration handshake (fires the RP /
                    // R5 / R6 / newer). Needs a persisted identity UUID so
                    // re-connects reuse the same registration.
                    if (!client.armSmart(PAIR_NAME, deviceUuid(ctx), onAwaitConfirm, freshPair = freshPair)) {
                        CanonBleLog.w(TAG, "smartphone-mode registration failed for ${device.address}")
                        client.close()
                        return CanonBleConnectResult.Failed
                    }
                    // DIAGNOSTIC (read-only): subscribe to all notify chars to hunt
                    // the RP's shutter-state signal so we can guarantee-close smart
                    // bulb (the Camera Test left-sensor-open parity bug). Best-effort
                    // — a failure here must not block a working connection.
                    runCatching { client.subscribeSmartNotifyForDiagnostics() }
                }
                CanonProtocol.SMART_NO_SHUTTER -> {
                    CanonBleLog.w(TAG, "${device.address} registered in smartphone mode but exposes " +
                        "no 00030000 control service (e.g. 2018 EOS R) — no BLE shutter")
                    client.close()
                    return CanonBleConnectResult.NoBleShutter
                }
                CanonProtocol.NONE -> {
                    client.close()
                    return CanonBleConnectResult.Failed
                }
            }
            val label = client.name?.takeIf { it.isNotBlank() } ?: "Canon BLE camera"
            CanonBleLog.i(TAG, "connected + armed $label (${device.address}) via ${client.protocol}")
            val transport = CanonBleTransport(device, client).also {
                it._label.value = label
                it._connected.value = true
                transportRef = it
            }
            return CanonBleConnectResult.Ok(transport)
        }

        /** Generate-once / persist a 16-byte smartphone-mode identity UUID, so
         *  re-connects reuse the same registration instead of re-prompting. */
        private fun deviceUuid(ctx: Context): ByteArray {
            val prefs = ctx.getSharedPreferences(SMART_UUID_PREF, Context.MODE_PRIVATE)
            prefs.getString(SMART_UUID_KEY, null)?.let { hex ->
                runCatching {
                    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                }.getOrNull()?.takeIf { it.size == 16 }?.let { return it }
            }
            val u = ByteArray(16).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(SMART_UUID_KEY, u.joinToString("") { "%02x".format(it) }).apply()
            return u
        }
    }

    override val kind = TransportKind.CANON_BLE

    // No wire-serialization mutex here — unlike the PTP transports, Canon
    // BLE goes through Android's GATT stack which only allows one
    // outstanding op per connection. The Nordic BLE library Pulsar uses
    // queues operations internally, so concurrent callers from coroutines
    // are serialised on the wire side without an explicit Mutex.
    private val _label = MutableStateFlow(device.address)
    override val label: StateFlow<String> = _label

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    // ── Capability flags ──────────────────────────────────────────────
    // The protocol is one-byte phone-to-camera. There's no GetDeviceInfo
    // equivalent, so we can't condition these on body advertisements —
    // they're constants. Every body on Canon's BR-E1 compatibility list
    // (EOS R/RP/R5/R6, M50, M200, 6D II, 77D, 200D, 800D, Ra, 850D,
    // G7X III, G5X II) supports bulb-via-press-hold; none expose live
    // view / lens info / battery via BLE.

    /** Press-and-hold pattern drives bulb on any BR-E1-compatible body.
     *  User must put the mode dial on Bulb on the camera itself. */
    override val supportsBulb: Boolean = true

    override val supportsLiveView: Boolean = false
    override val supportsLensInfo: Boolean = false
    override val supportsBatteryReadout: Boolean = false

    // BR-E1 mode does a half-press AF tap before the release when af=true.
    // Smartphone mode has no AF wire action — the toggle would be cosmetic.
    override val supportsAfToggle: Boolean
        get() = !isSmart

    /** Tracks whether a bulb exposure is in flight. `stopBulb` is a no-op
     *  if false — guards against double-release after a cable-pull mid-run. */
    @Volatile private var bulbOpen = false

    /** [SystemClock.elapsedRealtime] when the shutter last closed a REAL frame
     *  (not a defensive no-op) — the anchor for the post-frame cooldown wait in
     *  [startBulb]. 0 = no frame yet this session (first open is free). */
    @Volatile private var lastRealCloseElapsed = 0L

    /** Serializes the normal-flow shutter ops. The camera is a TOGGLE, so two
     *  callers interleaving presses (v0.613 diag: two stopBulbs racing, pressed
     *  25–100 ms apart) produce stray toggles = phantom frames. ensureShutter's
     *  check-press-confirm isn't atomic; this makes each whole op atomic. Held by
     *  the run-loop ops only (fireShutter/setShutterMode/startBulb/stopBulb/
     *  ensureShutterClosedSafely). The abort paths [stop]/[release] deliberately
     *  do NOT lock — they must stay prompt, and their close-vs-close interleave
     *  with a running op is benign. Private helpers never lock → no nesting. */
    private val opLock = Mutex()

    /** True for smartphone-mode bodies (RP / R5 / R6 / newer, service 00030000):
     *  positional press [00,01] / release [00,02] shutter, NOT the BR-E1 toggle.
     *  Read by the viewmodel to decide whether the BR-E1-derived 4 s interval
     *  clamp applies (under test whether smart mode shares the cooldown). */
    val isSmart get() = client.protocol == CanonProtocol.SMART

    /** Press the shutter using whichever protocol is active. Bulb path: the
     *  smartphone toggle `[00,01]`; BR-E1 path: `0x8C` full press. */
    private suspend fun pressShutter() {
        if (isSmart) client.smartShutter(press = true) else client.writeControl(SHUTTER_PRESS)
    }

    /** Release the shutter for a bulb exposure. Smartphone: `[00,01]` toggle
     *  back to "up" (Bulb tracks shutter state on this byte). BR-E1: `0x0C`. */
    private suspend fun releaseShutter() {
        if (isSmart) client.smartShutter(press = false) else client.writeControl(SHUTTER_RELEASE)
    }

    /** BR-E1 bulb is a **toggle** (Canon BR-E1 manual groups bulb with movie as a
     *  start/stop toggle; confirmed by nRF Sniffer `~/bulb-toggle.pcapng` AND by
     *  every-other-shot returning when v0.603 tried press-and-hold). One full
     *  *click* — `0x8C` press → brief hold → `0x0C` release — toggles the shutter:
     *  first click OPENS (camera holds it open between clicks, ~5 s in the sniff),
     *  the next click CLOSES. The camera acts on the `0x8C`; the `0x0C` is inert
     *  button-up (a lone `0x0C` was the every-other-shot bug). So `startBulb` and
     *  `stopBulb` each send exactly ONE click — strict open/close alternation
     *  keeps them in phase, no state tracking needed. The camera echoes state on
     *  the 0x001b indication (0x01 open / 0x03 closed) — logged for diagnostics,
     *  not used for control (the v0.596–601 state machine that did was fragile:
     *  AF's 0x01 corrupted it, see CanonBleClient). BR-E1 only. */
    private suspend fun bre1BulbToggle() {
        client.writeControl(SHUTTER_PRESS)   // 0x8C — toggles the shutter
        delay(SHUTTER_TAP_MS)
        client.writeControl(SHUTTER_RELEASE) // 0x0C — button-up (inert)
    }

    /** Drive the BR-E1 bulb shutter to [wantOpen] with **verify-retry** against the
     *  **AF-gated cached indication** ([client.shutterOpen]).
     *
     *  The camera is a toggle, but the EOS R has a **period-3 quirk** (mapped by
     *  driving the body directly, 2026-07-01): after it opens, the very next 0x8C
     *  is a **DEAD no-op** — it fires no frame (confirmed by card count = exactly 3
     *  for 3 exposures) and doesn't flip the state — and the click after that
     *  closes. So a close routinely needs 2 clicks. A single toggle can't recover;
     *  we toggle, wait for the indication, and retry the dead click.
     *
     *  We verify against `shutterOpen` (updated from the 0x001b indication, and
     *  **gated on the last write being a shutter press** — see CanonBleClient) NOT
     *  a raw char read: an AF half-press (0x4C) pollutes the raw char to 0x01, so a
     *  raw read makes manual "Hold" think it's already open and skip the open (the
     *  v0.606 regression). The cached copy ignores AF's 0x01, so both bulb and
     *  manual work. Idempotent (no click if already there — the runner's defensive
     *  close is a safe no-op). BR-E1 only; the smartphone path presses/holds. */
    private suspend fun ensureShutter(wantOpen: Boolean) {
        for (att in 0 until MAX_SHUTTER_ATTEMPTS) {
            if ((client.shutterOpen.value ?: false) == wantOpen) {
                if (att > 0) CanonBleLog.d(TAG, "bulb: shutter ${if (wantOpen) "open" else "closed"} " +
                    "reached after $att toggle(s)")
                // Retrospective drop fingerprint: a close needing ≥2 toggles means
                // the first close-press acked without flipping — which, card-proven,
                // is what a silently-EATEN open looks like (the "close" presses were
                // actually open+close → a ~0.5 s frame). With the 4 s minimum
                // interval this shouldn't happen; if it does, the gap was too tight.
                if (!wantOpen && att >= 2) CanonBleLog.w(TAG,
                    "bulb: close needed $att presses — the previous OPEN may have been " +
                    "eaten by the camera's post-frame cooldown (frame likely ~0.5s; interval too tight?)")
                return
            }
            bre1BulbToggle()
            // A real toggle flips shutterOpen → the next check returns immediately;
            // a period-3 DEAD click leaves it unchanged → this times out → retry.
            withTimeoutOrNull(SHUTTER_CONFIRM_MS) { client.shutterOpen.first { it == wantOpen } }
        }
        if ((client.shutterOpen.value ?: false) != wantOpen) CanonBleLog.w(TAG,
            "bulb: shutter did not reach ${if (wantOpen) "open" else "closed"} " +
            "after $MAX_SHUTTER_ATTEMPTS toggles (state=${client.shutterOpen.value})")
    }

    /** Single shot. BR-E1 with AF: half-press → wait → full-press → release.
     *  Smartphone mode folds AF into the release, so there's no separate AF
     *  step — just press → brief tap → release.
     *
     *  Smartphone-mode release uses `[00,02]` (distinct release event), NOT
     *  the bulb-path `[00,01]` toggle. In M (non-bulb) mode the camera reads
     *  two `[00,01]`s as "press, press" and leaves the button DOWN
     *  (confirmed on EOS RP via diagnostics log 2026-05-29). Bulb is unaffected
     *  — `startBulb` / `stopBulb` still use the `[00,01]` toggle path. */
    override suspend fun fireShutter(af: Boolean) = opLock.withLock {
        if (!_connected.value) return@withLock
        if (af && !isSmart) {
            // BR-E1 only: half-press → camera does AF → release the half-press
            client.writeControl((MODE_IMMEDIATE.toInt() or BUTTON_FOCUS.toInt()).toByte())
            delay(AF_HOLD_MS)
            client.writeControl(SHUTTER_RELEASE)
        }
        if (isSmart) {
            client.smartShutterTap(press = true)
            delay(SHUTTER_TAP_MS)
            client.smartShutterTap(press = false)
        } else {
            pressShutter()
            delay(SHUTTER_TAP_MS)
            releaseShutter()
            // Single-shot tap on the (required) non-bulb dial: the 0x01 ack means
            // "shot fired", not "shutter held open" — reset the cache so the
            // main-menu safety close doesn't toggle the camera into firing ~2
            // stray frames after Timelapse / Cable release.
            client.markShutterClosed()
            // A shot just fired — anchor the cooldown so a following bulb open waits.
            lastRealCloseElapsed = SystemClock.elapsedRealtime()
        }
    }

    /** BR-E1 has no body-settings access (the user sets Bulb on the dial), but
     *  this hook runs once at the start of every bulb run — the right moment for
     *  the **wake nudge**. The camera EATS the first button press after sleep /
     *  long idle as its wake (card-proven: 0.5 s first frame after ~30 min idle,
     *  clean frames after; arm-writes, reads and subscribes do NOT wake it — only
     *  a button does, and the wake consumes that button). So press the one button
     *  that's harmless when awake: **zoom-wide** — R-series bodies don't support
     *  the power-zoom adapter, so awake it's a no-op; asleep it absorbs the wake
     *  and the run's first real open fires. The nudge write isn't a shutter press,
     *  so any ack it draws is ignored by the state gate. */
    override suspend fun setShutterMode(bulb: Boolean) = opLock.withLock {
        if (isSmart) return@withLock
        CanonBleLog.d(TAG, "setShutterMode($bulb): no-op on Canon BLE (dial) — sending wake nudge (zoom-W)")
        client.writeControl((MODE_IMMEDIATE.toInt() or BUTTON_WIDE.toInt()).toByte())
        delay(SHUTTER_TAP_MS)
        client.writeControl(SHUTTER_RELEASE)
        delay(WAKE_SETTLE_MS)
    }

    /** Open the shutter for a bulb exposure. With AF: do a quick AF
     *  half-press first so the body has focus locked before we hold the
     *  release. Caller must pair with [stopBulb] before the next op.
     *
     *  We flip [bulbOpen] BEFORE the BLE write. The press write goes through
     *  `writeNoResponse` which has a `deferred.await()` suspension point;
     *  if the coroutine is cancelled there, the write may already have
     *  been queued and reached the camera (camera enters DOWN state) but
     *  the post-write `bulbOpen = true` would never run — and the finally
     *  block's `stopBulb()` would early-return on the false flag, leaving
     *  the body holding the shutter forever. Verified on R6 v0.372
     *  ("every-other-shot" diagnostic). Setting the flag conservatively
     *  means the finally always tries the release, which is harmless on
     *  an already-released camera. */
    override suspend fun startBulb(af: Boolean) = opLock.withLock {
        if (!_connected.value) return@withLock
        // Post-frame cooldown: don't press until ≥4 s after the last real close, or
        // the EOS R eats the open (card-proven ~0.5 s frame). Backstops the
        // viewmodel's per-interval clamp for step boundaries + each step's first
        // frame. BR-E1 only; the first frame of the session (lastRealClose=0) is free.
        if (!isSmart && lastRealCloseElapsed != 0L) {
            val since = SystemClock.elapsedRealtime() - lastRealCloseElapsed
            if (since in 0 until POST_FRAME_COOLDOWN_MS) {
                val wait = POST_FRAME_COOLDOWN_MS - since
                CanonBleLog.d(TAG, "bulb: post-frame cooldown — waiting ${wait}ms before open (${since}ms since last close)")
                delay(wait)
            }
        }
        if (af && !isSmart) {
            client.writeControl((MODE_IMMEDIATE.toInt() or BUTTON_FOCUS.toInt()).toByte())
            delay(AF_HOLD_MS)
            client.writeControl(SHUTTER_RELEASE)
        }
        val wasOpen = bulbOpen
        bulbOpen = true
        // BR-E1: toggle the shutter OPEN (idempotent — no-op if already open). It
        // holds open until the stopBulb click closes it. Smartphone: BULB is a
        // [00,01] toggle too (open now, stopBulb's [00,01] closes) — send it only
        // when transitioning closed→open so a stray call can't toggle it shut.
        //
        // NOTE: there is deliberately NO wire-level verification here. The camera
        // has a ~3–4 s post-frame cooldown during which it EATS presses while
        // acking them — and BOTH the 0x001b ack AND a raw read of 00050004 report
        // the phantom state (card-proven 2026-07-02: 9× "open verified by read"
        // with 0.4–0.5 s frames on the card). No truth signal exists on the wire;
        // the real defense is the 4 s minimum interval enforced by the viewmodel
        // (canonBleSafeInterval). A dropped open leaves its retrospective
        // fingerprint as a 2-toggle close — flagged in [ensureShutter].
        if (isSmart) { if (!wasOpen) client.smartBulbToggle() } else ensureShutter(true)
    }

    /** Close the shutter. Always attempts the release — the
     *  previously-defensive `if (!bulbOpen) return` gate was masking a
     *  bug where startBulb's cancellation left the flag false while the
     *  camera was still in DOWN state. An extra `[00,02]` to an already-up
     *  camera is a no-op on every body tested. */
    override suspend fun stopBulb() = opLock.withLock {
        val wasOpen = bulbOpen
        bulbOpen = false
        // BR-E1: toggle the shutter CLOSED (idempotent — no-op if already closed,
        // so the defensive close can't open it). Smartphone: close with the
        // [00,01] toggle, but ONLY when we believe it's open — a [00,01] on an
        // already-closed shutter would OPEN it. Defensive closes (wasOpen=false)
        // send nothing. ([00,02] is inert in Bulb — it never closed the frame,
        // which was the manual-hold-won't-stop + every-other-shot bug.)
        if (isSmart) { if (wasOpen) client.smartBulbToggle() } else ensureShutter(false)
        if (wasOpen) {
            // A REAL frame just ended — anchor the cooldown (defensive no-op closes
            // must NOT, or they'd reset the timer and force a needless 4 s wait).
            lastRealCloseElapsed = SystemClock.elapsedRealtime()
        } else {
            CanonBleLog.d(TAG, "stopBulb: defensive close (bulbOpen was already false)")
        }
    }

    /** Abort whatever's in flight. Used by viewmodel.stopFlow(). */
    override suspend fun stop() {
        if (bulbOpen) {
            runCatching { if (isSmart) client.smartBulbToggle() else ensureShutter(false) }
            bulbOpen = false
        }
    }

    /** Belt-and-braces safety close, called on return to the main menu. Reads
     *  the TRUE shutter state and closes it if open — **independent of the
     *  [bulbOpen] flag**, because a dropped final close-toggle, an un-released
     *  manual "Hold Shutter", or an aborted run can leave the sensor exposing
     *  while the flag reads closed. The BR-E1 read-verify-retry means this is a
     *  cheap no-op when already closed. No-op if disconnected. */
    suspend fun ensureShutterClosedSafely() = opLock.withLock {
        if (!_connected.value) return@withLock
        // BR-E1 read-verifies the true state. Smart mode has no wire truth, so
        // trust the flag: toggle closed with [00,01] ONLY if we believe it's
        // open — a blind [00,01] would OPEN a closed shutter.
        if (isSmart) {
            if (bulbOpen) runCatching { client.smartBulbToggle() }
        } else {
            runCatching { ensureShutter(false) }
        }
        bulbOpen = false
    }

    /** Called by [CanonBleClient]'s disconnect callback on a spontaneous
     *  link drop. Flips [connected] false so the viewmodel can react;
     *  doesn't release the underlying client (auto-reconnect may want to
     *  reuse the bond). */
    internal fun markDisconnected() {
        _connected.value = false
        bulbOpen = false
    }

    override suspend fun release() {
        _connected.value = false
        if (bulbOpen) {
            // Best-effort: try to close the shutter on the way out so a
            // mid-run disconnect doesn't leave the body holding the
            // exposure indefinitely. Suppress errors — the cable / link
            // may already be gone.
            runCatching { if (isSmart) client.smartBulbToggle() else ensureShutter(false) }
            bulbOpen = false
        }
        client.close()
    }
}
