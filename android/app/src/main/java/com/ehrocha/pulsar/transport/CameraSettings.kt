/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

/**
 * Snapshot of the three camera-side exposure settings Pulsar can write
 * programmatically. All fields are nullable — `null` means "don't manage" /
 * "leave whatever's on the body".
 *
 * Stored as strings to sidestep the encoding difference between CCAPI
 * (which speaks JSON strings like `"1600"`, `"f/2.8"`, `"1/30"`) and Canon
 * PTP (vendor integer codes). Carried by a [com.ehrocha.pulsar.model.FlowStep.Pause]
 * "Adjust camera settings" step and applied (where supported) when the flow
 * reaches it.
 */
data class CameraSettings(
    val iso: String? = null,
    val aperture: String? = null,
    val shutterSpeed: String? = null,
) {
    /** True if any field is non-null — the "should I apply / show?" gate. */
    val hasAny: Boolean get() = iso != null || aperture != null || shutterSpeed != null

    companion object {
        val EMPTY = CameraSettings()
    }
}

/**
 * Outcome of [CameraTransport.applySettings]: what we managed to set on the
 * body vs. what the body rejected. The Pause "Adjust camera settings" verify
 * screen shows `applied` as confirmed and asks the user to set `skipped` by
 * hand before continuing.
 */
data class SettingsApplyResult(
    val applied: CameraSettings,
    val skipped: CameraSettings,
) {
    companion object {
        val NOOP = SettingsApplyResult(CameraSettings.EMPTY, CameraSettings.EMPTY)
    }
}
