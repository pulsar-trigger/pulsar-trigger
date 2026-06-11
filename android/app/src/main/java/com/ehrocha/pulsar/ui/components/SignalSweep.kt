/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * SIGNAL's "searching" indicator: a pulse sweeping along a flat trace —
 * the app hunting for a signal. Drop-in replacement for the small inline
 * CircularProgressIndicators on scanning / connecting / probing states.
 * When the state resolves the composable simply leaves composition; the
 * arrival of real UI is the lock.
 */
@Composable
fun SignalSweep(
    modifier: Modifier = Modifier.width(36.dp).height(14.dp),
    color: Color = Color.Unspecified,
) {
    val pulseColor = if (color == Color.Unspecified) PulsarTheme.colors.liveStart else color
    val baseColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val sweep by rememberInfiniteTransition(label = "sweep")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 1100, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "sweepX",
        )

    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        val w = size.width
        val stroke = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(baseColor, Offset(0f, midY), Offset(w, midY), stroke.width * 0.7f, stroke.cap)
        // The travelling pulse plus two fading ghosts behind it.
        for (g in 0..2) {
            val gx = sweep - g * 0.16f
            if (gx < 0f) continue
            val cx = w * gx
            val half = w * 0.18f
            val amp = size.height * 0.42f
            val alpha = 1f - g * 0.38f
            val path = Path().apply {
                moveTo((cx - half).coerceAtLeast(0f), midY)
                cubicTo(cx - half * 0.3f, midY, cx - half * 0.26f, midY - amp, cx, midY - amp)
                cubicTo(cx + half * 0.26f, midY - amp, cx + half * 0.2f, midY + amp * 0.2f,
                    (cx + half * 0.6f).coerceAtMost(w), midY)
            }
            drawPath(path, pulseColor.copy(alpha = alpha), style = stroke)
        }
    }
}
