/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * "Flynn's Arcade" CRT overlay for the Grid style — a thin raster of dark
 * scanlines plus a bezel vignette (the corners fall off like an old arcade
 * tube). Deliberately STATIC: no per-frame flicker, so on otherwise-still
 * screens the overlay draws once and costs nothing afterwards. Kept very low
 * alpha so text underneath stays legible.
 *
 * Non-interactive (a bare [Canvas], no pointer input) — taps pass straight
 * through to the UI below. Drawn as the topmost layer over the whole app.
 */
@Composable
fun CrtOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // CRT raster: thin dark horizontal scanlines every few px.
        val gap = 3.dp.toPx().coerceAtLeast(4f)
        var y = 0f
        while (y < h) {
            drawLine(
                color = Color.Black.copy(alpha = 0.05f),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1f,
            )
            y += gap
        }

        // Bezel vignette: clear centre, darkened corners (the tube/cabinet edge).
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.4f)),
                center = Offset(w / 2f, h / 2f),
                radius = maxOf(w, h) * 0.72f,
            ),
        )
    }
}
