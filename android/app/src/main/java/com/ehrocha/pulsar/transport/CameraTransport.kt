/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import kotlinx.coroutines.flow.MutableStateFlow
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


    /** Transport can serve a live-view JPEG stream. CCAPI: true (used by
     *  Star Focus). For UI gating that needs to react to **runtime
     *  downgrade** (PTP bodies that advertise the op but reject the wire
     *  call — see EOS R / RP), observe [liveViewSupportedFlow] instead. */
    val supportsLiveView: Boolean

    /** Reactive form of [supportsLiveView]. Most transports just emit the
     *  initial value once; PTP transports flip this to false on the first
     *  `rc=0x200A PARAMETER_NOT_SUPPORTED` from the body, so Compose
     *  consumers (the Star Focus tile gate) update without a restart. */
    val liveViewSupportedFlow: StateFlow<Boolean>
        get() = MutableStateFlow(supportsLiveView)

    /** Transport can report what lens is mounted (name, focal length).
     *  Used by the Astro wizard's focal-length auto-fill. CCAPI: true. */
    val supportsLensInfo: Boolean

    /** Transport can report camera battery state for the run-screen chip.
     *  CCAPI: true (event polling + `/devicestatus/battery`). See
     *  [batterySupportedFlow] for the reactive form (PTP bodies can
     *  runtime-downgrade if the body advertises 0x5001 but rejects the
     *  read). */
    val supportsBatteryReadout: Boolean

    /** Reactive form of [supportsBatteryReadout] — see
     *  [liveViewSupportedFlow] for the rationale. */
    val batterySupportedFlow: StateFlow<Boolean>
        get() = MutableStateFlow(supportsBatteryReadout)

    /** Transport honors the [fireShutter]/[startBulb] `af` flag on the wire.
     *  Wizards hide the per-shot AF toggle when false — the camera body's
     *  own AF setting still governs whether focus runs, but Pulsar can't
     *  override it shot-to-shot. PTP/IP: false (EOS R rejects no-AF mode
     *  with DEVICE_BUSY, forcing the with-AF path on the wire). */
    val supportsAfToggle: Boolean
        get() = true

    /** Snapshot of what lens is mounted, used by the Astro wizard to
     *  auto-fill the focal length. Returns null when [supportsLensInfo] is
     *  false, the read fails, or no lens is mounted. */
    suspend fun getLensInfo(): LensInfo? = null

    // ── Live view (Star Focus wizard) ────────────────────────────────────
    // CCAPI calls /shooting/liveview + /shooting/liveview/flip + drivefocus.
    // PTP calls Canon GetViewFinderData + SetDevicePropValue(EvfOutput) +
    // DriveLens. Transports that don't support live view (BLE-ESP, older
    // PTP bodies without 0x9153) leave these as the no-op defaults; the
    // wizard's tile is gated on [supportsLiveView].

    /** Most recent reason a [startLiveView] attempt failed, or null if the
     *  last attempt succeeded / none was made. Surfaced by the Star Focus
     *  wizard so failures are visible on-screen, not just in logcat. */
    val lastLiveViewError: String? get() = null

    /** Begin the EVF stream. Returns true on success. */
    suspend fun startLiveView(): Boolean = false

    /** Stop the EVF stream. Idempotent. */
    suspend fun stopLiveView() {}

    /** Fetch one JPEG frame from the running EVF stream, or null on
     *  transport error / parse failure. The wizard paces the frame rate. */
    suspend fun getLiveViewFrame(): ByteArray? = null

    /** Step the focus motor. [action] is one of `near1`/`near2`/`near3`
     *  (1 = fine, 3 = coarse) or `far1`/`far2`/`far3`. Requires the lens
     *  to be in AF on the body's AF/MF switch (motor is disconnected
     *  when the switch is set to MF). No-op if the transport doesn't
     *  support live view. */
    suspend fun driveFocus(action: String) {}

    // ── Content transfer (photo-transfer gallery) ───────────────────────
    // Enumerate / preview / download images off the camera's card. Only the
    // transports that can actually move image data implement these: CCAPI
    // (/contents) and the two PTP wires (USB + Wi-Fi, via standard PTP object
    // ops). The BLE transports (Pulsar ESP32, Canon BR-E1) physically can't
    // carry image payloads and keep the no-op defaults, so the UI dims the
    // transfer tool for them via [supportsContentTransfer].

    /** Transport can enumerate + download images from the camera's storage.
     *  Gates the photo-transfer tool in the UI. */
    val supportsContentTransfer: Boolean get() = false

    /** Transport can deliver a JPEG rendition of a RAW file (so the gallery's
     *  JPEG-default download works even for CR3s). CCAPI does this natively via
     *  `?kind=display`; the PTP wires can't yet (would need to extract the
     *  embedded preview), so RAW downloads as RAW there. When false the UI's
     *  RAW/JPEG choice is hidden — RAW files just download as the original. */
    val supportsJpegRendition: Boolean get() = false

    /** Enumerate images on the camera (newest-first where the protocol
     *  allows). Empty list when unsupported or the card has no images.
     *  May be slow on a full card — callers run it off the main thread and
     *  show progress. */
    suspend fun listContents(): List<CameraImage> = emptyList()

    /** Fetch a small thumbnail JPEG for [image] to populate the preview grid,
     *  or null on failure. Never returns the full-resolution file. */
    suspend fun getThumbnail(image: CameraImage): ByteArray? = null

    /** Stream the full-resolution file for [image] into [sink], reporting
     *  `(bytesWritten, totalBytes)` via [onProgress] (totalBytes may be 0 if
     *  the protocol didn't declare a size up front). Returns true on success.
     *  Streams rather than buffering so multi-MB RAW files don't sit in
     *  memory. The caller owns [sink] (a MediaStore OutputStream) and closes
     *  it. */
    /** When [asJpeg] is true, stream a JPEG rendition of [image] instead of the
     *  original file — used for the gallery's JPEG-default download of a RAW.
     *  Only honoured by transports with [supportsJpegRendition]; ignored (the
     *  original is streamed) otherwise. For a non-RAW image it's a no-op since
     *  the original is already a JPEG. */
    suspend fun downloadImage(
        image: CameraImage,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        asJpeg: Boolean = false,
    ): Boolean = false
}

enum class TransportKind { BLE_ESP, CCAPI, PTP_USB, CANON_BLE, PTP_IP }
