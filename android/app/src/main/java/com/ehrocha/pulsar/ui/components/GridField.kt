/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * "Grid" visual-style backdrop — a neon perspective floor receding to a glowing
 * horizon (Tron-inspired). Electric-cyan grid lines on the carbon background,
 * with the SIGNAL live-gradient reserved for the horizon band + the scan sweep,
 * so it ties into the rest of the system. Static by default (calm behind forms);
 * [animated] adds a slow scan-line running down the floor.
 *
 * Sibling of [SpaceField] / [PcbField]: transparent, drawn behind the app.
 */
private val GridCyan = Color(0xFF35E0E8)

@Composable
fun GridField(modifier: Modifier = Modifier, animated: Boolean = true) {
    val line = GridCyan
    val colors = PulsarTheme.colors
    val glowA = colors.liveStart
    val glowB = colors.liveEnd

    val timeS by produceState(0f, animated) {
        if (!animated) {
            value = 0f
            return@produceState
        }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { value = (it - start) / 1_000_000_000f }
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.40f
        val vpx = w * 0.5f

        // ── horizon glow band ────────────────────────────────────────────────
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to glowA.copy(alpha = 0.14f),
                1f to Color.Transparent,
                startY = horizon - 40f,
                endY = horizon + 40f,
            ),
            topLeft = Offset(0f, horizon - 40f),
            size = Size(w, 80f),
        )

        // ── floor: receding horizontal lines (denser near the horizon) ───────
        val rows = 18
        for (i in 1..rows) {
            val t = i.toFloat() / rows
            val y = horizon + (h - horizon) * (t * t)
            drawLine(
                color = line.copy(alpha = 0.06f + 0.20f * t),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // ── floor: vertical lines converging to the vanishing point ──────────
        val cols = 16
        for (i in 0..cols) {
            val frac = i.toFloat() / cols
            val xBottom = -w * 0.6f + (w * 2.2f) * frac
            drawLine(
                color = line.copy(alpha = 0.10f),
                start = Offset(vpx, horizon),
                end = Offset(xBottom, h),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // ── bright horizon (the live gradient = identity tie-in) ─────────────
        drawLine(
            brush = Brush.horizontalGradient(listOf(glowA, glowB)),
            start = Offset(0f, horizon),
            end = Offset(w, horizon),
            strokeWidth = 2.dp.toPx(),
        )

        // ── animated: a scan-line sweeping down the floor ────────────────────
        if (animated) {
            val sweep = (timeS * 0.16f).mod(1f)
            val y = horizon + (h - horizon) * (sweep * sweep)
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, glowB.copy(alpha = 0.5f), Color.Transparent),
                ),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
