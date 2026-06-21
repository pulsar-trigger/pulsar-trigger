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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme

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
fun PcbField(modifier: Modifier = Modifier) {
    val colors = PulsarTheme.colors
    val outline = MaterialTheme.colorScheme.outline
    val decorTrace = colors.liveStart.copy(alpha = 0.12f)
    val decorVia = colors.liveEnd.copy(alpha = 0.19f)
    val compLine = colors.liveStart.copy(alpha = 0.20f)
    val compFill = colors.liveEnd.copy(alpha = 0.14f)
    val fieldDot = outline.copy(alpha = 0.055f)
    Canvas(modifier) {
        drawViaField(fieldDot)
        drawDecor(decorTrace, decorVia)
        drawComponents(compLine, compFill)
    }
}

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
private fun DrawScope.drawDecor(trace: Color, via: Color) {
    val sw = 1.4.dp.toPx()
    val viaR = 2.0.dp.toPx()
    for (poly in DECOR_TRACES) {
        for (k in 0 until poly.size - 1) {
            val a = Offset(poly[k].first * size.width, poly[k].second * size.height)
            val b = Offset(poly[k + 1].first * size.width, poly[k + 1].second * size.height)
            drawLine(trace, a, b, strokeWidth = sw, cap = StrokeCap.Round)
        }
        for (pt in poly) {
            drawCircle(via, radius = viaR, center = Offset(pt.first * size.width, pt.second * size.height))
        }
    }
}

/** Draw the scattered [DECOR_COMPONENTS] as faint SMD silkscreen. */
private fun DrawScope.drawComponents(line: Color, fill: Color) {
    val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
    for (comp in DECOR_COMPONENTS) {
        val c = Offset(comp.x * size.width, comp.y * size.height)
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
