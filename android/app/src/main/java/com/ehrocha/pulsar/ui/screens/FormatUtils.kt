/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import kotlin.math.roundToInt

/** Format a duration in seconds for a photographer: sub-second as a
 *  fraction, then "X.X s", then "Mm Ss", then "Hh Mm". Shared by the ND
 *  calculator and the Star Trails wizard. */
internal fun formatExposure(sec: Double): String = when {
    sec < 1.0 -> "1/${(1.0 / sec).roundToInt()} s"
    sec < 60.0 -> String.format(java.util.Locale.US, "%.1f s", sec)
    sec < 3600.0 -> {
        val m = (sec / 60).toInt(); val s = (sec % 60).roundToInt()
        if (s == 0) "${m}m" else "${m}m ${s}s"
    }
    else -> {
        val h = (sec / 3600).toInt(); val m = ((sec % 3600) / 60).roundToInt()
        if (m == 0) "${h}h" else "${h}h ${m}m"
    }
}
