/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Camera-control transport. The wizards talk to this — they don't know whether
 * the actual I/O is BLE-to-ESP32 or HTTP-to-Canon-CCAPI. Implementations:
 *  - `CcapiTransport`  — HTTP client for Canon EOS R-series WiFi cameras
 *    (`docs/ccapi.md`).
 *
 * The ESP32 BLE path doesn't go through this interface today — that path uses
 * firmware-side mode loops (`MODE_INTERVALOMETER` etc.) so the phone sets the
 * params once and the firmware runs the schedule on its own. The interface is
 * for transports the *phone* drives shot-by-shot.
 *
 * The ViewModel holds the active transport in a state flow; tapping a device
 * in the scan card swaps it.
 */
interface CameraTransport {

    /** Human-readable label, e.g. "Pulsar-AB12" or "EOS R10". */
    val label: StateFlow<String>

    /** True when the transport has an active session with the camera. */
    val connected: StateFlow<Boolean>

    /**
     * Disconnect / release resources. Idempotent. After [release] the transport
     * is not reusable — discard it.
     */
    suspend fun release()

    /**
     * Fire a single shutter event. For BLE this drops a shutter-pulse via the
     * existing firmware path; for CCAPI it POSTs `/shutterbutton`. The
     * implementation owns the camera-side exposure (e.g. CCAPI Timelapse uses
     * the camera's shutter-speed setting, not Pulsar's).
     */
    suspend fun fireShutter(af: Boolean = true)

    /**
     * Put the camera into bulb (or back to manual) before a bulb-style run.
     * On Canon bodies with a physical mode dial this may also flip
     * `ignoreshootingmodedialmode` so the PUT actually takes effect. Best
     * effort — failure is logged, not thrown, so the caller can still try
     * the bulb sequence in case the user pre-set the mode on the body.
     */
    suspend fun setShutterMode(bulb: Boolean)

    /** Begin a bulb exposure (full-press hold). Pair with [stopBulb]. */
    suspend fun startBulb(af: Boolean = true)

    /** Release the shutter — closes the bulb exposure started by [startBulb]. */
    suspend fun stopBulb()

    /** Abort any in-flight bulb exposure or shutter press. */
    suspend fun stop()

    /** Transport kind, for UI/log branching. */
    val kind: TransportKind

    // ── Capability flags ────────────────────────────────────────────────
    // Per-body / per-protocol capability the wizards check before exposing
    // features. Future non-CCAPI transports (USB PTP, direct vendor BLE)
    // would set these to advertise what they can and can't do.

    /** Body advertises a manual-bulb endpoint (CCAPI: `/shutterbutton/manual`).
     *  On bodies that lack bulb the Intervalometer / Astro / Dark-Frame / Ramp
     *  tiles are dimmed; only Timelapse + Manual still work. */
    val supportsBulb: Boolean

    /** Transport can read or write exposure settings (ISO / aperture /
     *  shutter speed). CCAPI: true (endpoints exist; the camera-params UI
     *  tab is parked). */
    val supportsSettings: Boolean

    /** Transport can serve a live-view JPEG stream. CCAPI: true (used by
     *  Star Focus). */
    val supportsLiveView: Boolean

    /** Transport can report what lens is mounted (name, focal length).
     *  Used by the Astro wizard's focal-length auto-fill. CCAPI: true. */
    val supportsLensInfo: Boolean

    /** Transport can report camera battery state for the run-screen chip.
     *  CCAPI: true (event polling + `/devicestatus/battery`). */
    val supportsBatteryReadout: Boolean
}

enum class TransportKind { BLE_ESP, CCAPI }
