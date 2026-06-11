/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * The current cycle drawn as the signal it actually is (Eduardo's idea):
 * exposure = a rounded plateau pulse stroked in the live gradient, the
 * inter-shot gap = flat trace, widths time-proportional to the real duty
 * cycle — a timelapse reads as a narrow spike, a 240s astro sub as a wide
 * plateau. A glowing playhead rides the trace in real time; the textual
 * countdown stays separate (numbers remain first-class).
 */
@Composable
fun CyclePhaseTrace(
    exposureMs: Long,
    gapMs: Long,
    exposing: Boolean,
    /** 0..1 progress through the CURRENT phase (exposure or gap). */
    phaseFraction: Float,
    modifier: Modifier = Modifier,
) {
    if (exposureMs <= 0L && gapMs <= 0L) return
    val live = PulsarTheme.colors
    val gapColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)

    Canvas(modifier = modifier) {
        val w = size.width
        val base = size.height * 0.74f
        val top = size.height * 0.30f
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)

        // Time-proportional pulse width, clamped so a timelapse spike stays
        // visible and a bulb plateau keeps a readable gap.
        val total = (exposureMs + gapMs).coerceAtLeast(1L)
        val pulseFrac = (exposureMs.toFloat() / total).coerceIn(0.12f, 0.88f)
        val lead = w * 0.05f                 // flat anchor before the rise
        val shoulder = (w * 0.045f).coerceAtMost(14.dp.toPx())
        val x0 = lead                        // rise starts
        val x1 = lead + (w - 2 * lead) * pulseFrac  // fall ends
        val plateauL = x0 + shoulder
        val plateauR = (x1 - shoulder).coerceAtLeast(plateauL)

        // Gap trace: lead-in + everything after the fall.
        drawLine(gapColor, Offset(0f, base), Offset(x0 + 1, base), stroke.width, stroke.cap)
        drawLine(gapColor, Offset(x1 - 1, base), Offset(w, base), stroke.width, stroke.cap)

        // Exposure pulse: rounded shoulders + plateau, in the live gradient.
        val pulse = Path().apply {
            moveTo(x0, base)
            cubicTo(x0 + shoulder * 0.6f, base, plateauL - shoulder * 0.6f, top, plateauL, top)
            lineTo(plateauR, top)
            cubicTo(plateauR + shoulder * 0.6f, top, x1 - shoulder * 0.6f, base, x1, base)
        }
        drawPath(
            pulse,
            brush = Brush.horizontalGradient(
                listOf(live.liveStart, live.liveEnd), startX = x0, endX = x1,
            ),
            style = stroke,
        )

        // Playhead riding the trace. During exposure it crosses the pulse
        // (shoulders included — y interpolated); during the gap it slides
        // along the baseline toward the next cycle.
        val f = phaseFraction.coerceIn(0f, 1f)
        val (px, py) = if (exposing) {
            val x = x0 + (x1 - x0) * f
            val y = when {
                x < plateauL -> base - (base - top) * ((x - x0) / (plateauL - x0).coerceAtLeast(1f))
                x > plateauR -> base - (base - top) * ((x1 - x) / (x1 - plateauR).coerceAtLeast(1f))
                else -> top
            }
            x to y
        } else {
            (x1 + (w - x1) * f) to base
        }
        val headColor = if (exposing) live.liveEnd else gapColor
        drawCircle(headColor.copy(alpha = 0.30f), radius = 8.dp.toPx() * (if (exposing) 1f else 0.7f), center = Offset(px, py))
        drawCircle(headColor, radius = 3.5.dp.toPx(), center = Offset(px, py))
    }
}
