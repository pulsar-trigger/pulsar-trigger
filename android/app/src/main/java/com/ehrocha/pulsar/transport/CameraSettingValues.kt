/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport

/**
 * Standard 1/3-stop value ladders for the "Adjust camera settings" Pause
 * editor when no camera is connected (offline flow building). When a CCAPI
 * body IS connected, the editor uses that body's exact accepted values
 * (`CameraTransport.list*Values`) instead — guaranteed to apply. These are
 * the common, "normally supported" formats so a value picked offline is a
 * best-effort match the body will usually accept (and the run-time verify
 * screen reports anything it rejects).
 */
object CameraSettingValues {
    val ISO: List<String> = listOf(
        "AUTO", "100", "125", "160", "200", "250", "320", "400", "500", "640",
        "800", "1000", "1250", "1600", "2000", "2500", "3200", "4000", "5000",
        "6400", "8000", "10000", "12800", "16000", "20000", "25600", "32000",
        "40000", "51200", "102400",
    )

    val APERTURE: List<String> = listOf(
        "f/1.0", "f/1.2", "f/1.4", "f/1.8", "f/2.0", "f/2.2", "f/2.8", "f/3.2",
        "f/3.5", "f/4.0", "f/4.5", "f/5.0", "f/5.6", "f/6.3", "f/7.1", "f/8.0",
        "f/9.0", "f/10", "f/11", "f/13", "f/14", "f/16", "f/18", "f/20", "f/22",
        "f/25", "f/29", "f/32",
    )

    val SHUTTER: List<String> = listOf(
        "30\"", "25\"", "20\"", "15\"", "13\"", "10\"", "8\"", "6\"", "5\"", "4\"",
        "3.2\"", "2.5\"", "2\"", "1.6\"", "1.3\"", "1\"", "0.8\"", "0.6\"", "0.5\"",
        "0.4\"", "0.3\"", "1/4", "1/5", "1/6", "1/8", "1/10", "1/13", "1/15", "1/20",
        "1/25", "1/30", "1/40", "1/50", "1/60", "1/80", "1/100", "1/125", "1/160",
        "1/200", "1/250", "1/320", "1/400", "1/500", "1/640", "1/800", "1/1000",
        "1/1250", "1/1600", "1/2000", "1/2500", "1/3200", "1/4000",
    )
}
