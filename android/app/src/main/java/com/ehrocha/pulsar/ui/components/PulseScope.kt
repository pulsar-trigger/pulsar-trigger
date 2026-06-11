/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import kotlin.math.sin

/**
 * SIGNAL's run trace: an oscilloscope line that grows one pulse per frame
 * captured — a 300-shot star-trail run literally builds a pulse train.
 * Fired pulses wear the live gradient; the next slot breathes while the
 * shutter is open. Long runs aggregate into at most [MAX_SLOTS] pulses;
 * continuous runs sweep and wrap like a real scope.
 */
private const val MAX_SLOTS = 48

@Composable
fun PulseScope(
    shotsTaken: Int,
    plannedShots: Int,   // 0 = continuous
    exposing: Boolean,
    modifier: Modifier = Modifier,
) {
    val live = PulsarTheme.colors
    val baseColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

    // New-pulse pop: the most recent pulse lands with a spring overshoot.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(shotsTaken) {
        if (shotsTaken > 0) {
            pop.snapTo(1.55f)
            pop.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
            )
        }
    }
    // Breathing glow on the slot currently exposing.
    val breathe by rememberInfiniteTransition(label = "scope")
        .animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "breathe",
        )

    Canvas(modifier = modifier) {
        val midY = size.height * 0.62f
        val w = size.width
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)

        val slots: Int
        val fired: Int
        if (plannedShots in 1..MAX_SLOTS) {
            slots = plannedShots
            fired = shotsTaken.coerceAtMost(slots)
        } else if (plannedShots > MAX_SLOTS) {
            slots = MAX_SLOTS
            fired = ((shotsTaken.toFloat() / plannedShots) * slots).toInt().coerceIn(0, slots)
        } else {
            // Continuous: the sweep wraps like a real scope.
            slots = MAX_SLOTS
            fired = shotsTaken % (slots + 1)
        }
        val slotW = w / slots

        // Baseline behind everything.
        drawLine(baseColor, Offset(0f, midY), Offset(w, midY), stroke.width * 0.7f, stroke.cap)

        val liveBrush = Brush.horizontalGradient(listOf(live.liveStart, live.liveEnd))
        for (i in 0 until fired) {
            val isNewest = i == fired - 1
            val ampScale = if (isNewest) pop.value else 1f
            drawPulse(i, slotW, midY, ampScale, stroke) { path ->
                drawPath(path, liveBrush, style = stroke)
            }
        }
        // The slot being exposed right now breathes at the boundary.
        if (exposing && fired < slots) {
            drawPulse(fired, slotW, midY, 0.9f, stroke) { path ->
                drawPath(path, live.liveEnd.copy(alpha = breathe), style = stroke)
            }
        }
    }
}

/** One pulse in slot [i]: sharp rise, rounded peak, slight undershoot,
 *  with deterministic per-slot height jitter for the organic CP 1919
 *  texture. */
private inline fun DrawScope.drawPulse(
    i: Int,
    slotW: Float,
    midY: Float,
    ampScale: Float,
    stroke: Stroke,
    draw: (Path) -> Unit,
) {
    val cx = slotW * (i + 0.5f)
    val half = slotW * 0.42f
    // jitter 0.72..1.0, stable per slot
    val jitter = 0.72f + 0.28f * (0.5f + 0.5f * sin(i * 12.9898f) )
    val amp = midY * 0.78f * jitter * ampScale
    val path = Path().apply {
        moveTo(cx - half, midY)
        cubicTo(cx - half * 0.30f, midY, cx - half * 0.26f, midY - amp, cx, midY - amp)
        cubicTo(cx + half * 0.26f, midY - amp, cx + half * 0.20f, midY + amp * 0.16f,
            cx + half * 0.55f, midY + amp * 0.10f)
        cubicTo(cx + half * 0.75f, midY + amp * 0.06f, cx + half * 0.85f, midY, cx + half, midY)
    }
    draw(path)
}
