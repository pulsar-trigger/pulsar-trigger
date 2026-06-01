/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.BUTTON_FOCUS
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.MODE_IMMEDIATE
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.SHUTTER_PRESS
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.SHUTTER_RELEASE
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.transport.TransportKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
                    // BR-E1: `[0x03, name]` on every connect — the "arm as the
                    // active remote" step the body expects each session (matches
                    // iebyt/cbremote's onServicesDiscovered). First time this
                    // also pops the OS pair dialog. See docs/canon-ble.md.
                    if (!client.writePairName(PAIR_NAME)) {
                        CanonBleLog.w(TAG, "BR-E1 pair/arm write failed for ${device.address}; aborting")
                        client.close()
                        return CanonBleConnectResult.Failed
                    }
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

    override val supportsSettings: Boolean = false
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

    private val isSmart get() = client.protocol == CanonProtocol.SMART

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

    /** Single shot. BR-E1 with AF: half-press → wait → full-press → release.
     *  Smartphone mode folds AF into the release, so there's no separate AF
     *  step — just press → brief tap → release.
     *
     *  Smartphone-mode release uses `[00,02]` (distinct release event), NOT
     *  the bulb-path `[00,01]` toggle. In M (non-bulb) mode the camera reads
     *  two `[00,01]`s as "press, press" and leaves the button DOWN
     *  (confirmed on EOS RP via diagnostics log 2026-05-29). Bulb is unaffected
     *  — `startBulb` / `stopBulb` still use the `[00,01]` toggle path. */
    override suspend fun fireShutter(af: Boolean) {
        if (!_connected.value) return
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
        }
    }

    /** BR-E1 has no body-settings access. The user has to set Bulb on the
     *  mode dial themselves; we log + no-op so the existing runner code
     *  that calls this before every bulb flow doesn't have to know. */
    override suspend fun setShutterMode(bulb: Boolean) {
        CanonBleLog.d(TAG, "setShutterMode($bulb): no-op on Canon BLE (set Bulb on the body's dial)")
    }

    /** Open the shutter for a bulb exposure. With AF: do a quick AF
     *  half-press first so the body has focus locked before we hold the
     *  release. Caller must pair with [stopBulb] before the next op. */
    override suspend fun startBulb(af: Boolean) {
        if (!_connected.value) return
        if (af && !isSmart) {
            client.writeControl((MODE_IMMEDIATE.toInt() or BUTTON_FOCUS.toInt()).toByte())
            delay(AF_HOLD_MS)
            client.writeControl(SHUTTER_RELEASE)
        }
        pressShutter()
        bulbOpen = true
    }

    /** Close the shutter — idempotent. */
    override suspend fun stopBulb() {
        if (!bulbOpen) return
        releaseShutter()
        bulbOpen = false
    }

    /** Abort whatever's in flight. Used by viewmodel.stopFlow(). */
    override suspend fun stop() {
        if (bulbOpen) {
            runCatching { releaseShutter() }
            bulbOpen = false
        }
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
            // Best-effort: try to release the shutter on the way out so a
            // mid-run disconnect doesn't leave the body holding the
            // exposure indefinitely. Suppress errors — the cable / link
            // may already be gone.
            runCatching { releaseShutter() }
            bulbOpen = false
        }
        client.close()
    }
}
