/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

/**
 * Snapshot of the three camera-side exposure settings Pulsar can read
 * and (where supported) write programmatically. All fields are nullable —
 * `null` means "don't manage" / "leave whatever's on the body".
 *
 * Stored as strings to sidestep the encoding difference between CCAPI
 * (which speaks JSON strings like `"1600"`, `"f/2.8"`, `"1/30"`) and
 * Canon PTP (which uses vendor-specific integer codes). The transport
 * layer translates per-direction; the data model stays portable across
 * bodies and transports.
 */
data class CameraSettings(
    val iso: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
) {
    /** True if any field is non-null — useful as a "should I apply?" gate. */
    val hasAny: Boolean get() = iso != null || aperture != null || shutterSpeed != null

    companion object {
        val EMPTY = CameraSettings()
    }
}

/**
 * Outcome of [CameraTransport.applySettings]. The runner logs `skipped`
 * into the diagnostics ring and surfaces it in the wizard's
 * "incompatible preset" banner.
 */
data class SettingsApplyResult(
    val applied: CameraSettings,
    val skipped: CameraSettings,
) {
    companion object {
        val NOOP = SettingsApplyResult(CameraSettings.EMPTY, CameraSettings.EMPTY)
    }
}
