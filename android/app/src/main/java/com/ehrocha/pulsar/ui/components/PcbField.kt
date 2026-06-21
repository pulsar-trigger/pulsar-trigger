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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import kotlin.math.hypot

/**
 * SIGNAL's printed-circuit backdrop: a faint via-dot grid, a routed decorative
 * trace bus, and scattered SMD silkscreen (resistors, LEDs, transistors, chip
 * caps, small ICs). Everything draws in the copper/magenta live roles at low
 * alpha, so it sits behind content and resolves to the Phosphor-Red luminance
 * ramp in night mode — no `Color(0x…)` literals.
 *
 * Static (no animation) → drawn once; cheap as a fixed backdrop. Used on the
 * transport board (under its IC + beacon layer), the Trigger tab and the Tools
 * tab. Place it as the first child of a `Box` with [Modifier.matchParentSize].
 */
@Composable
fun PcbField(modifier: Modifier = Modifier, animated: Boolean = true, variant: Int = 0) {
    val colors = PulsarTheme.colors
    val outline = MaterialTheme.colorScheme.outline
    val decorTrace = colors.liveStart.copy(alpha = 0.12f)
    val decorVia = colors.liveEnd.copy(alpha = 0.19f)
    val compLine = colors.liveStart.copy(alpha = 0.20f)
    val compFill = colors.liveEnd.copy(alpha = 0.14f)
    val fieldDot = outline.copy(alpha = 0.055f)
    val beamColor = colors.liveEnd
    // Beams travel each track, staggered, like signal flowing across the board.
    val pulse by rememberInfiniteTransition(label = "pcb").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "beam",
    )
    Box(modifier) {
        // Static board — no animation state read, so it draws once.
        Canvas(Modifier.matchParentSize()) {
            drawViaField(fieldDot)
            drawDecor(decorTrace, decorVia, variant)
            drawComponents(compLine, compFill, variant)
        }
        // Travelling beams — only this thin layer redraws each frame.
        if (animated) {
            Canvas(Modifier.matchParentSize()) { drawBeams(beamColor, pulse, variant) }
        }
    }
}

/** Per-region orientation so each slice of the continuous board looks distinct:
 *  0 = as-authored, 1 = flipped vertically, 2 = rotated 180°. */
private fun vx(x: Float, variant: Int) = if (variant == 2) 1f - x else x
private fun vy(y: Float, variant: Int) = if (variant == 1 || variant == 2) 1f - y else y

/** Decorative PCB bus routed behind content — fixed fractional polylines that
 *  pack the field with parallel runs, fan-outs and edge buses so the carbon
 *  reads like a populated board. */
private val DECOR_TRACES = listOf(
    // top ribbon bus (parallel runs)
    listOf(0.05f to 0.045f, 0.95f to 0.045f),
    listOf(0.07f to 0.065f, 0.93f to 0.065f),
    listOf(0.05f to 0.085f, 0.95f to 0.085f),
    listOf(0.09f to 0.105f, 0.42f to 0.105f),
    listOf(0.58f to 0.105f, 0.91f to 0.105f),
    // top fan-outs
    listOf(0.16f to 0.105f, 0.16f to 0.15f, 0.22f to 0.21f),
    listOf(0.27f to 0.085f, 0.27f to 0.15f),
    listOf(0.73f to 0.085f, 0.73f to 0.15f),
    listOf(0.84f to 0.105f, 0.84f to 0.15f, 0.78f to 0.21f),
    // left edge vertical bundle + branch stubs
    listOf(0.040f to 0.12f, 0.040f to 0.88f),
    listOf(0.065f to 0.16f, 0.065f to 0.45f),
    listOf(0.065f to 0.55f, 0.065f to 0.84f),
    listOf(0.040f to 0.28f, 0.12f to 0.28f),
    listOf(0.040f to 0.50f, 0.10f to 0.50f),
    listOf(0.040f to 0.72f, 0.12f to 0.72f),
    // right edge vertical bundle + branch stubs
    listOf(0.960f to 0.12f, 0.960f to 0.88f),
    listOf(0.935f to 0.16f, 0.935f to 0.45f),
    listOf(0.935f to 0.55f, 0.935f to 0.84f),
    listOf(0.960f to 0.28f, 0.88f to 0.28f),
    listOf(0.960f to 0.50f, 0.90f to 0.50f),
    listOf(0.960f to 0.72f, 0.88f to 0.72f),
    // bottom ribbon bus
    listOf(0.05f to 0.955f, 0.95f to 0.955f),
    listOf(0.07f to 0.935f, 0.93f to 0.935f),
    listOf(0.05f to 0.915f, 0.95f to 0.915f),
    listOf(0.09f to 0.895f, 0.42f to 0.895f),
    listOf(0.58f to 0.895f, 0.91f to 0.895f),
    // bottom fan-outs
    listOf(0.18f to 0.895f, 0.18f to 0.85f, 0.24f to 0.79f),
    listOf(0.30f to 0.915f, 0.30f to 0.85f),
    listOf(0.70f to 0.915f, 0.70f to 0.85f),
    listOf(0.82f to 0.895f, 0.82f to 0.85f, 0.76f to 0.79f),
    // extra density: corner diagonals, 3rd edge rails, mid stubs, short drops
    listOf(0.05f to 0.20f, 0.16f to 0.31f),
    listOf(0.95f to 0.20f, 0.84f to 0.31f),
    listOf(0.05f to 0.80f, 0.16f to 0.69f),
    listOf(0.95f to 0.80f, 0.84f to 0.69f),
    listOf(0.085f to 0.36f, 0.085f to 0.64f),
    listOf(0.915f to 0.36f, 0.915f to 0.64f),
    listOf(0.12f to 0.115f, 0.12f to 0.05f),
    listOf(0.88f to 0.115f, 0.88f to 0.05f),
    listOf(0.12f to 0.885f, 0.12f to 0.95f),
    listOf(0.88f to 0.885f, 0.88f to 0.95f),
    listOf(0.34f to 0.045f, 0.34f to 0.095f),
    listOf(0.66f to 0.045f, 0.66f to 0.095f),
    listOf(0.46f to 0.955f, 0.46f to 0.905f),
    listOf(0.54f to 0.955f, 0.54f to 0.905f),
    listOf(0.025f to 0.40f, 0.025f to 0.60f),
    listOf(0.975f to 0.40f, 0.975f to 0.60f),
)

private enum class Comp { RES, LED, NPN, SMD, IC }
private class DecorComp(val type: Comp, val x: Float, val y: Float)

/** Scattered SMD silkscreen placed in the field margins. */
private val DECOR_COMPONENTS = listOf(
    DecorComp(Comp.IC, 0.50f, 0.055f),
    DecorComp(Comp.RES, 0.31f, 0.05f),
    DecorComp(Comp.LED, 0.63f, 0.05f),
    DecorComp(Comp.NPN, 0.72f, 0.055f),
    DecorComp(Comp.SMD, 0.40f, 0.105f),
    DecorComp(Comp.SMD, 0.60f, 0.105f),
    DecorComp(Comp.RES, 0.10f, 0.34f),
    DecorComp(Comp.NPN, 0.90f, 0.34f),
    DecorComp(Comp.LED, 0.10f, 0.62f),
    DecorComp(Comp.SMD, 0.90f, 0.62f),
    DecorComp(Comp.RES, 0.35f, 0.93f),
    DecorComp(Comp.NPN, 0.50f, 0.93f),
    DecorComp(Comp.IC, 0.64f, 0.93f),
    DecorComp(Comp.LED, 0.45f, 0.055f),
    DecorComp(Comp.RES, 0.82f, 0.105f),
    DecorComp(Comp.SMD, 0.20f, 0.05f),
    DecorComp(Comp.RES, 0.10f, 0.50f),
    DecorComp(Comp.SMD, 0.90f, 0.50f),
    DecorComp(Comp.LED, 0.45f, 0.93f),
    DecorComp(Comp.RES, 0.80f, 0.895f),
)

/** A faint via-dot grid — the bare board. */
private fun DrawScope.drawViaField(color: Color) {
    val step = 40.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color, radius = r, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Draw the [DECOR_TRACES] polylines + a via at every vertex. */
private fun DrawScope.drawDecor(trace: Color, via: Color, variant: Int) {
    val sw = 1.4.dp.toPx()
    val viaR = 2.0.dp.toPx()
    for (poly in DECOR_TRACES) {
        for (k in 0 until poly.size - 1) {
            val a = Offset(vx(poly[k].first, variant) * size.width, vy(poly[k].second, variant) * size.height)
            val b = Offset(vx(poly[k + 1].first, variant) * size.width, vy(poly[k + 1].second, variant) * size.height)
            drawLine(trace, a, b, strokeWidth = sw, cap = StrokeCap.Round)
        }
        for (pt in poly) {
            drawCircle(via, radius = viaR, center = Offset(vx(pt.first, variant) * size.width, vy(pt.second, variant) * size.height))
        }
    }
}

/** Draw the scattered [DECOR_COMPONENTS] as faint SMD silkscreen. */
private fun DrawScope.drawComponents(line: Color, fill: Color, variant: Int) {
    val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
    for (comp in DECOR_COMPONENTS) {
        val c = Offset(vx(comp.x, variant) * size.width, vy(comp.y, variant) * size.height)
        when (comp.type) {
            Comp.RES -> {
                val bw = 16.dp.toPx(); val bh = 6.dp.toPx(); val lead = 6.dp.toPx()
                drawRoundRect(line, topLeft = Offset(c.x - bw / 2, c.y - bh / 2), size = Size(bw, bh),
                    cornerRadius = CornerRadius(2.dp.toPx()), style = stroke)
                drawLine(line, Offset(c.x - bw / 2 - lead, c.y), Offset(c.x - bw / 2, c.y), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x + bw / 2, c.y), Offset(c.x + bw / 2 + lead, c.y), strokeWidth = stroke.width)
            }
            Comp.LED -> {
                val r = 5.dp.toPx()
                drawCircle(line, radius = r, center = c, style = stroke)
                drawCircle(fill, radius = 1.6.dp.toPx(), center = c)
                drawLine(line, Offset(c.x - r - 5.dp.toPx(), c.y), Offset(c.x - r, c.y), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x + r, c.y), Offset(c.x + r + 5.dp.toPx(), c.y), strokeWidth = stroke.width)
            }
            Comp.NPN -> {
                val s = 13.dp.toPx(); val leg = 5.dp.toPx()
                drawRoundRect(line, topLeft = Offset(c.x - s / 2, c.y - s * 0.4f), size = Size(s, s * 0.8f),
                    cornerRadius = CornerRadius(2.dp.toPx()), style = stroke)
                drawLine(line, Offset(c.x - s * 0.25f, c.y + s * 0.4f), Offset(c.x - s * 0.25f, c.y + s * 0.4f + leg), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x + s * 0.25f, c.y + s * 0.4f), Offset(c.x + s * 0.25f, c.y + s * 0.4f + leg), strokeWidth = stroke.width)
                drawLine(line, Offset(c.x, c.y - s * 0.4f), Offset(c.x, c.y - s * 0.4f - leg), strokeWidth = stroke.width)
            }
            Comp.SMD -> {
                val bw = 12.dp.toPx(); val bh = 7.dp.toPx(); val pad = 3.dp.toPx()
                drawRoundRect(fill, topLeft = Offset(c.x - bw / 2, c.y - bh / 2), size = Size(bw, bh),
                    cornerRadius = CornerRadius(1.5.dp.toPx()))
                drawRect(fill, topLeft = Offset(c.x - bw / 2 - pad, c.y - bh / 2), size = Size(pad, bh))
                drawRect(fill, topLeft = Offset(c.x + bw / 2, c.y - bh / 2), size = Size(pad, bh))
            }
            Comp.IC -> {
                val s = 20.dp.toPx(); val leg = 4.dp.toPx()
                drawRoundRect(fill, topLeft = Offset(c.x - s / 2, c.y - s / 2), size = Size(s, s),
                    cornerRadius = CornerRadius(2.dp.toPx()))
                drawRoundRect(line, topLeft = Offset(c.x - s / 2, c.y - s / 2), size = Size(s, s),
                    cornerRadius = CornerRadius(2.dp.toPx()), style = stroke)
                for (k in 0 until 4) {
                    val lx = c.x - s / 2 + s * (k + 0.5f) / 4f
                    drawLine(line, Offset(lx, c.y - s / 2), Offset(lx, c.y - s / 2 - leg), strokeWidth = stroke.width)
                    drawLine(line, Offset(lx, c.y + s / 2), Offset(lx, c.y + s / 2 + leg), strokeWidth = stroke.width)
                }
                drawCircle(line, radius = 1.3.dp.toPx(), center = Offset(c.x - s / 2 + 3.dp.toPx(), c.y - s / 2 + 3.dp.toPx()))
            }
        }
    }
}

/** A travelling pulse (head + short tail) along every decorative track,
 *  staggered so they read as signal flowing across the board. */
private fun DrawScope.drawBeams(color: Color, pulse: Float, variant: Int) {
    val headR = 2.6.dp.toPx()
    val tailR = 0.8.dp.toPx()
    DECOR_TRACES.forEachIndexed { i, poly ->
        // Only the longer buses carry a beam — skip the short stubs / fans, so
        // the board reads as a few signals flowing, not a swarm.
        val fracLen = poly.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }.sum()
        if (fracLen < 0.35f) return@forEachIndexed
        val pts = poly.map { Offset(vx(it.first, variant) * size.width, vy(it.second, variant) * size.height) }
        val phase = (pulse + i * 0.137f) % 1f
        for (t in 0..2) {
            val f = phase - t * 0.05f
            if (f in 0f..1f) {
                val a = ((1f - t * 0.35f) * edgeFade(f) * 0.9f).coerceIn(0f, 1f)
                drawCircle(color.copy(alpha = a), radius = headR - t * tailR, center = pointAlong(pts, f))
            }
        }
    }
}

/** Position at fraction [f] (0..1) along a polyline, by arc length. */
private fun pointAlong(pts: List<Offset>, f: Float): Offset {
    if (pts.size < 2) return pts.first()
    var total = 0f
    for (k in 0 until pts.size - 1) total += (pts[k + 1] - pts[k]).getDistance()
    if (total <= 0f) return pts.first()
    var target = f * total
    for (k in 0 until pts.size - 1) {
        val len = (pts[k + 1] - pts[k]).getDistance()
        if (target <= len) {
            val r = if (len > 0f) target / len else 0f
            return Offset(pts[k].x + (pts[k + 1].x - pts[k].x) * r, pts[k].y + (pts[k + 1].y - pts[k].y) * r)
        }
        target -= len
    }
    return pts.last()
}

/** Fade the beam in/out near the track ends. */
private fun edgeFade(f: Float): Float = when {
    f < 0.1f -> f / 0.1f
    f > 0.9f -> (1f - f) / 0.1f
    else -> 1f
}
