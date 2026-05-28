/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.BUTTON_FOCUS
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.MODE_IMMEDIATE
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.SHUTTER_PRESS
import com.ehrocha.pulsar.canonble.CanonBleClient.Companion.SHUTTER_RELEASE
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.transport.TransportKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

        /** Open a GATT session, optionally pair-write on first connect.
         *  Returns null on any failure (camera off, out of range, user
         *  denied the OS pair dialog, missing characteristics, etc.).
         *  Caller has nothing to clean up on null — we close internally.
         *
         *  [onSpontaneousDisconnect] fires when the link drops *after* a
         *  successful connect (camera powered off, out of range, etc.).
         *  Not invoked for an explicit [release]. The viewmodel uses this
         *  to drive the auto-reconnect banner + re-scan. */
        @SuppressLint("MissingPermission")
        suspend fun connect(
            ctx: Context,
            device: BluetoothDevice,
            onSpontaneousDisconnect: () -> Unit = {},
        ): CanonBleTransport? {
            var transportRef: CanonBleTransport? = null
            val client = CanonBleClient(ctx, device, onSpontaneousDisconnect = {
                transportRef?.markDisconnected()
                onSpontaneousDisconnect()
            })
            if (!client.connect()) {
                Log.w(TAG, "GATT connect failed for ${device.address}")
                client.close()
                return null
            }
            // Write the BR-E1 pair name on EVERY connection, right after
            // service discovery, before any control write. This is the
            // "arm this device as the active remote" step — Canon bodies
            // expect it each session, not just on first bond. The working
            // Android reference (iebyt/cbremote) calls its equivalent
            // `pairAndConnect()` in onServicesDiscovered on every connect;
            // skipping it (as we did when already-bonded) leaves the camera
            // OS-bonded but un-armed, so it silently drops shutter writes.
            // First time this also triggers Android's OS pair dialog;
            // later connects reuse the bond and the write is silent.
            // See docs/canon-ble.md → Connect flow.
            if (!client.writePairName(PAIR_NAME)) {
                Log.w(TAG, "pair/arm write failed for ${device.address}; aborting")
                client.close()
                return null
            }
            val label = client.name?.takeIf { it.isNotBlank() } ?: "Canon BLE camera"
            Log.i(TAG, "connected + armed $label (${device.address})")
            return CanonBleTransport(device, client).also {
                it._label.value = label
                it._connected.value = true
                transportRef = it
            }
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

    /** Tracks whether a bulb exposure is in flight. `stopBulb` is a no-op
     *  if false — guards against double-release after a cable-pull mid-run. */
    @Volatile private var bulbOpen = false

    /** Single shot. With AF: half-press → wait → full-press → release.
     *  Without AF: full-press → release. Matches the cbremote pattern. */
    override suspend fun fireShutter(af: Boolean) {
        if (!_connected.value) return
        if (af) {
            // Half-press → camera does AF → release the half-press
            client.writeControl((MODE_IMMEDIATE.toInt() or BUTTON_FOCUS.toInt()).toByte())
            delay(AF_HOLD_MS)
            client.writeControl(SHUTTER_RELEASE)
        }
        client.writeControl(SHUTTER_PRESS)
        delay(SHUTTER_TAP_MS)
        client.writeControl(SHUTTER_RELEASE)
    }

    /** BR-E1 has no body-settings access. The user has to set Bulb on the
     *  mode dial themselves; we log + no-op so the existing runner code
     *  that calls this before every bulb flow doesn't have to know. */
    override suspend fun setShutterMode(bulb: Boolean) {
        Log.d(TAG, "setShutterMode($bulb): no-op on Canon BLE (set Bulb on the body's dial)")
    }

    /** Open the shutter for a bulb exposure. With AF: do a quick AF
     *  half-press first so the body has focus locked before we hold the
     *  release. Caller must pair with [stopBulb] before the next op. */
    override suspend fun startBulb(af: Boolean) {
        if (!_connected.value) return
        if (af) {
            client.writeControl((MODE_IMMEDIATE.toInt() or BUTTON_FOCUS.toInt()).toByte())
            delay(AF_HOLD_MS)
            client.writeControl(SHUTTER_RELEASE)
        }
        client.writeControl(SHUTTER_PRESS)
        bulbOpen = true
    }

    /** Close the shutter — idempotent. */
    override suspend fun stopBulb() {
        if (!bulbOpen) return
        client.writeControl(SHUTTER_RELEASE)
        bulbOpen = false
    }

    /** Abort whatever's in flight. Used by viewmodel.stopFlow(). */
    override suspend fun stop() {
        if (bulbOpen) {
            runCatching { client.writeControl(SHUTTER_RELEASE) }
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
            runCatching { client.writeControl(SHUTTER_RELEASE) }
            bulbOpen = false
        }
        client.close()
    }
}
