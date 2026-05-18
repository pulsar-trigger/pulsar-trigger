/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Camera-control transport. The wizards talk to this — they don't know whether
 * the actual I/O is BLE-to-ESP32 or HTTP-to-Canon-CCAPI. Two implementations
 * live side-by-side:
 *  - `BleEspTransport` — wraps the existing `BleController` (TLV v2 over GATT).
 *  - `CcapiTransport`  — HTTP client for Canon EOS R-series WiFi cameras.
 *
 * Per `docs/ccapi.md`. The ViewModel holds the active transport in a state
 * flow; tapping a device in the scan card swaps it.
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
}

enum class TransportKind { BLE_ESP, CCAPI }
