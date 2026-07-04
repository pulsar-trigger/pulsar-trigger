/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.astro.NightModel
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * The Night Strip — a session's whole night, dusk→dawn, on one horizontal
 * instrument. The **linear sibling of the Sky Dial**: it renders the same
 * single-source [NightModel] with the same visual vocabulary (per-sample
 * quality glow `dim→positive`, moon band in `caution` scaled by glare, the
 * Milky-Way core band in `trail`, the best window as the live-gradient blaze,
 * hour ticks, live "now" marker), so a user who knows the dial reads the strip
 * instantly — and stacked strips compare nights at a glance, which a ring
 * can't do.
 *
 * [compact] drops the text layer (hour labels + window label) for list rows;
 * the expanded form is for heroes / detail headers.
 */
@Composable
fun NightStrip(
    model: NightModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val pc = PulsarTheme.colors
    val trackColor = scheme.onSurface.copy(alpha = 0.10f)
    val dimQuality = scheme.onSurfaceVariant.copy(alpha = 0.40f)
    val brightQuality = pc.positive
    val moonColor = pc.caution
    val coreColor = pc.trail
    val labelColor = scheme.onSurfaceVariant
    val capColor = scheme.onSurface.copy(alpha = 0.35f)
    val liveStart = pc.liveStart
    val liveEnd = pc.liveEnd

    Canvas(modifier) {
        val w = size.width
        val n = model.samples.size
        if (n == 0 || w <= 0f) return@Canvas

        // Vertical layout: [moon rail][core rail][quality ridge][baseline(+labels)]
        val labelSpace = if (compact) 0f else 14.sp.toPx()
        val baseY = size.height - labelSpace - 2.dp.toPx()
        val moonY = 3.dp.toPx()
        val coreY = moonY + 5.dp.toPx()
        val ridgeTop = coreY + 5.dp.toPx()
        val ridgeH = (baseY - ridgeTop).coerceAtLeast(4.dp.toPx())
        fun xAt(f: Float) = f * w

        // Base track — the full night outline.
        drawLine(trackColor, Offset(0f, baseY), Offset(w, baseY), 2.dp.toPx(), StrokeCap.Round)

        // Quality ridge — one bar per sample, same dim→bright lerp as the dial.
        val barW = (w / n).coerceAtLeast(1f)
        model.samples.forEachIndexed { i, s ->
            val q = s.quality.coerceIn(0f, 1f)
            val x = xAt(i.toFloat() / n) + barW / 2f
            drawLine(
                lerp(dimQuality, brightQuality, q),
                Offset(x, baseY),
                Offset(x, baseY - ridgeH * q),
                barW * 0.85f,
            )
        }

        // Moon-up band — brightness = glare (same alpha law as the dial).
        val moonAlpha = 0.16f + 0.45f * model.moonIllumPct / 100f
        runBands(model.samples.map { it.moonUp }) { f0, f1 ->
            drawLine(
                moonColor.copy(alpha = moonAlpha),
                Offset(xAt(f0), moonY), Offset(xAt(f1), moonY),
                3.dp.toPx(), StrokeCap.Round,
            )
        }

        // Milky-Way core band.
        runBands(model.samples.map { it.coreUp }) { f0, f1 ->
            drawLine(
                coreColor.copy(alpha = 0.7f),
                Offset(xAt(f0), coreY), Offset(xAt(f1), coreY),
                3.dp.toPx(), StrokeCap.Round,
            )
        }

        // Best window — the live-gradient blaze over the ridge region.
        if (model.windowEndF > model.windowStartF) {
            val x0 = xAt(model.windowStartF)
            val x1 = xAt(model.windowEndF)
            val grad = Brush.horizontalGradient(
                listOf(liveStart, liveEnd), startX = x0, endX = x1,
            )
            drawRoundRect(
                grad,
                topLeft = Offset(x0, ridgeTop),
                size = Size(x1 - x0, baseY - ridgeTop),
                cornerRadius = CornerRadius(3.dp.toPx()),
                alpha = 0.18f,
            )
            drawLine(grad, Offset(x0, baseY), Offset(x1, baseY), 3.dp.toPx(), StrokeCap.Round)
        }

        // Dusk / dawn end caps.
        listOf(0f, w).forEach { x ->
            drawLine(capColor, Offset(x, baseY - 5.dp.toPx()), Offset(x, baseY + 3.dp.toPx()), 1.5.dp.toPx())
        }

        // Hour ticks (+ labels when expanded) — same every-3-hours hints.
        val hourPaint = if (compact) null else android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        model.hourMarks.forEach { mark ->
            val x = xAt(mark.fraction)
            drawLine(capColor, Offset(x, baseY - 2.dp.toPx()), Offset(x, baseY + 2.dp.toPx()), 1.dp.toPx())
            hourPaint?.let {
                drawContext.canvas.nativeCanvas.drawText(mark.label, x, baseY + labelSpace, it)
            }
        }

        // Best-window time label (expanded): the dial shows it in live magenta.
        if (!compact && model.windowLabel.isNotEmpty() && model.windowEndF > model.windowStartF) {
            val paint = android.graphics.Paint().apply {
                color = liveEnd.toArgb()
                textSize = 10.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val cx = xAt((model.windowStartF + model.windowEndF) / 2f)
                .coerceIn(30.dp.toPx(), w - 30.dp.toPx())
            drawContext.canvas.nativeCanvas.drawText(model.windowLabel, cx, ridgeTop + 10.sp.toPx(), paint)
        }

        // Live "now" marker — only present while tonight is actually running.
        model.nowFraction?.let { f ->
            val x = xAt(f)
            drawLine(
                Brush.verticalGradient(listOf(liveStart, liveEnd)),
                Offset(x, moonY), Offset(x, baseY),
                2.dp.toPx(), StrokeCap.Round,
            )
            drawCircle(liveEnd, 2.5.dp.toPx(), Offset(x, moonY))
        }
    }
}

/** Invoke [draw] for each contiguous `true` run in [flags], with the run's
 *  start/end as fractions 0..1. Mirrors the Sky Dial's run-band helper. */
private inline fun runBands(flags: List<Boolean>, draw: (Float, Float) -> Unit) {
    val n = flags.size
    var start = -1
    for (i in flags.indices) {
        if (flags[i] && start < 0) start = i
        val end = !flags[i] || i == n - 1
        if (start >= 0 && end) {
            val last = if (flags[i]) i + 1 else i
            draw(start.toFloat() / n, last.toFloat() / n)
            start = -1
        }
    }
}
