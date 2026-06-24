/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import kotlin.math.sin

/**
 * "Grid" visual-style backdrop — a neon perspective floor receding to a glowing
 * horizon (Tron-inspired). Electric-cyan grid lines on the carbon background,
 * with the SIGNAL live-gradient reserved for the horizon band + the runners,
 * so it ties into the rest of the system. Static behind forms (calm); when
 * [animated] (menu + scan), Tron light cycles run the floor leaving neon trails,
 * and an identity disc drifts the sky band.
 *
 * Sibling of [SpaceField] / [PcbField]: transparent, drawn behind the app.
 */
private val GridCyan = Color(0xFF35E0E8)

/**
 * Light-cycle paths in normalized floor space (x: 0..1 across, y: 0..1 from
 * horizon to front). Consecutive points share exactly one coordinate, so every
 * turn is a hard 90° — the trail bends through the corners like NIBBLES.
 */
private val CYCLE_PATHS: List<List<Offset>> = listOf(
    listOf(Offset(0.04f, 0.18f), Offset(0.04f, 0.85f), Offset(0.42f, 0.85f), Offset(0.42f, 0.42f), Offset(0.97f, 0.42f)),
    listOf(Offset(0.96f, 0.12f), Offset(0.60f, 0.12f), Offset(0.60f, 0.70f), Offset(0.18f, 0.70f), Offset(0.18f, 0.98f)),
    listOf(Offset(0.50f, 0.97f), Offset(0.50f, 0.52f), Offset(0.10f, 0.52f), Offset(0.10f, 0.22f), Offset(0.82f, 0.22f), Offset(0.82f, 0.62f)),
    listOf(Offset(0.30f, 0.10f), Offset(0.30f, 0.45f), Offset(0.72f, 0.45f), Offset(0.72f, 0.90f), Offset(0.95f, 0.90f)),
)

/** Per-cycle screen-space geometry (projected points + segment lengths),
 *  recomputed only when the canvas size changes — NOT every animation frame.
 *  The trail head/tail move each frame, but these don't. */
private class CycleGeom(val sp: List<Offset>, val segLens: List<Float>, val pathLen: Float)

private class CycleCache {
    private var w = -1f
    private var h = -1f
    var geoms: List<CycleGeom> = emptyList()
        private set

    fun update(width: Float, height: Float) {
        if (width == w && height == h) return
        w = width; h = height
        val horizon = height * 0.40f
        geoms = CYCLE_PATHS.map { path ->
            val sp = path.map { Offset(it.x * width, horizon + it.y * (height - horizon)) }
            val segLens = sp.zipWithNext { a, b -> (b - a).getDistance() }
            CycleGeom(sp, segLens, segLens.sum())
        }
    }
}

@Composable
fun GridField(modifier: Modifier = Modifier, animated: Boolean = true) {
    val cycleCache = remember { CycleCache() }
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

        // ── animated: light cycles running the grid + an identity disc ───────
        if (animated) {
            // Light cycles run the floor like NIBBLES — each follows a fixed
            // axis-aligned path, turning 90° at corners and dragging a long neon
            // ribbon that bends through the turns and tapers/fades to the tail.
            // After a run it parks in a gap, then re-enters.
            cycleCache.update(w, h)
            for (i in cycleCache.geoms.indices) {
                val geom = cycleCache.geoms[i]
                val sp = geom.sp
                val segLens = geom.segLens
                val pathLen = geom.pathLen
                if (pathLen <= 1f) continue
                val trailLen = pathLen * 0.5f
                val gap = pathLen * 0.55f
                val pxPerSec = (h - horizon) * (0.5f + 0.16f * i)
                val travel = (timeS * pxPerSec + i * pathLen * 0.37f).mod(pathLen + gap)
                if (travel > pathLen) continue  // parked in the gap
                fun pointAt(d: Float): Offset {
                    var rem = d.coerceIn(0f, pathLen)
                    for (k in segLens.indices) {
                        if (rem <= segLens[k] || k == segLens.lastIndex) {
                            val t = if (segLens[k] > 0f) (rem / segLens[k]).coerceIn(0f, 1f) else 0f
                            return Offset(
                                sp[k].x + (sp[k + 1].x - sp[k].x) * t,
                                sp[k].y + (sp[k + 1].y - sp[k].y) * t,
                            )
                        }
                        rem -= segLens[k]
                    }
                    return sp.last()
                }
                val tail = (travel - trailLen).coerceAtLeast(0f)
                val span = (travel - tail).coerceAtLeast(1f)
                val col = if (i % 2 == 0) line else glowB  // cyan / magenta runners
                val steps = (span / 7f).toInt().coerceIn(1, 90)
                var prev = pointAt(tail)
                for (s in 1..steps) {
                    val d = tail + span * s / steps
                    val cur = pointAt(d)
                    val tf = (d - tail) / span  // 0 at tail → 1 at head
                    drawLine(
                        col.copy(alpha = 0.85f * tf * tf),
                        prev, cur,
                        strokeWidth = (1.4f + 1.5f * tf).dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    prev = cur
                }
                val hp = pointAt(travel)
                drawCircle(col.copy(alpha = 0.30f), radius = 5.dp.toPx(), center = hp)
                drawCircle(col, radius = 2.4.dp.toPx(), center = hp)
            }
            // identity disc — a glowing ring drifting across the sky band
            val dp = (timeS * 0.05f).mod(1f)
            val dc = Offset(w * (0.1f + 0.8f * dp), horizon * 0.5f)
            val dr = minOf(w, h) * 0.045f
            drawCircle(glowB.copy(alpha = 0.5f), radius = dr, center = dc, style = Stroke(2.dp.toPx()))
            drawCircle(glowB.copy(alpha = 0.16f), radius = dr * 0.7f, center = dc)

            // I/O tower — a beam of light rising from the vanishing point, pulsing
            val beam = 0.45f + 0.35f * sin(timeS * 1.6f)
            val beamTop = horizon * 0.12f
            drawLine(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, glowA.copy(alpha = 0.55f * beam)),
                    startY = beamTop, endY = horizon,
                ),
                start = Offset(vpx, beamTop), end = Offset(vpx, horizon),
                strokeWidth = 3.dp.toPx(),
            )
            drawLine(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, glowA.copy(alpha = 0.16f * beam)),
                    startY = beamTop, endY = horizon,
                ),
                start = Offset(vpx, beamTop), end = Offset(vpx, horizon),
                strokeWidth = 11.dp.toPx(),
            )

            // recognizers — sentinels drifting the sky: a top block + two end
            // legs, the iconic hollow-centred silhouette.
            for (i in 0 until 2) {
                val rp = ((timeS * (0.02f + 0.007f * i)) + i * 0.55f).mod(1.35f)
                if (rp <= 1f) {
                    val rx = rp * w
                    val ry = horizon * (0.26f + 0.20f * i)
                    val rw = w * 0.085f
                    val rh = rw * 0.6f
                    val col = line.copy(alpha = 0.20f)
                    drawRect(col, topLeft = Offset(rx - rw / 2f, ry), size = Size(rw, rh * 0.34f))
                    drawRect(col, topLeft = Offset(rx - rw / 2f, ry), size = Size(rw * 0.22f, rh))
                    drawRect(col, topLeft = Offset(rx + rw / 2f - rw * 0.22f, ry), size = Size(rw * 0.22f, rh))
                }
            }
        }
    }
}
