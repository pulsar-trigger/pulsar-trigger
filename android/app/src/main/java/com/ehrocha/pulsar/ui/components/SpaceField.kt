/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PlanetAmber
import com.ehrocha.pulsar.ui.theme.PlanetSlate
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The SPACE visual style's reusable backdrop: a deep-field starfield with a
 * faint on-palette nebula and the occasional **meteor** streaking across — the
 * Space analogue of the Circuit board's travelling beams.
 *
 * Everything here stays on the role palette (stars are [onSurface], the nebula
 * is the live/primary roles at very low alpha) so Phosphor-Red night mode keeps
 * resolving correctly; only the planets (drawn elsewhere) break the one-accent
 * discipline. Star positions + meteor lanes are seeded once and stable across
 * recomposition.
 */
private data class FieldStar(
    val xf: Float, val yf: Float, val r: Float,
    val alpha: Float, val phase: Float, val speed: Float,
)

private val FIELD_STARS: List<FieldStar> = run {
    val rnd = Random(0xC1A19)   // CP 1919 — the first pulsar
    List(132) {
        val bright = rnd.nextFloat() < 0.16f
        FieldStar(
            xf = rnd.nextFloat(),
            yf = rnd.nextFloat(),
            r = if (bright) 1.1f + rnd.nextFloat() * 1.4f else 0.4f + rnd.nextFloat() * 0.8f,
            alpha = if (bright) 0.55f + rnd.nextFloat() * 0.35f else 0.10f + rnd.nextFloat() * 0.42f,
            phase = rnd.nextFloat(),
            speed = 0.4f + rnd.nextFloat() * 1.3f,
        )
    }
}

private data class MeteorLane(
    val period: Float, val phase: Float,
    val startXf: Float, val startYf: Float,
    val angle: Float, val lenF: Float, val speedF: Float,
)

private val METEOR_LANES: List<MeteorLane> = run {
    val rnd = Random(1919)
    List(5) {
        // Vary the direction: angle 20°..160° spans down-right → down-left; the
        // entry edge follows the horizontal direction so the streak crosses the
        // field (no longer all left→right).
        val angle = (20f + rnd.nextFloat() * 140f) * (PI / 180f).toFloat()
        val goesRight = cos(angle) >= 0f
        MeteorLane(
            period = 6f + rnd.nextFloat() * 7f,                      // seconds between passes
            phase = rnd.nextFloat(),
            startXf = if (goesRight) -0.12f - rnd.nextFloat() * 0.08f else 1.12f + rnd.nextFloat() * 0.08f,
            startYf = -0.10f + rnd.nextFloat() * 0.40f,              // entry height fraction
            angle = angle,
            lenF = 0.10f + rnd.nextFloat() * 0.10f,                  // streak length (frac of width)
            speedF = 0.85f + rnd.nextFloat() * 0.5f,
        )
    }
}

// A handful of deep-sky objects — faint galaxies + a couple of distant planets
// — so the field reads as more than stars (kept sparse, "not too much").
private enum class SkyKind { GALAXY, PLANET }
private data class DeepSky(
    val kind: SkyKind, val xf: Float, val yf: Float,
    val sizeF: Float, val angle: Float, val tintIdx: Int,
)
private val DEEP_SKY = listOf(
    DeepSky(SkyKind.GALAXY, 0.80f, 0.15f, 0.11f, 28f, 0),
    DeepSky(SkyKind.GALAXY, 0.13f, 0.70f, 0.075f, -18f, 1),
    DeepSky(SkyKind.GALAXY, 0.60f, 0.89f, 0.060f, 12f, 2),
    DeepSky(SkyKind.PLANET, 0.90f, 0.54f, 0.022f, 0f, 0),
    DeepSky(SkyKind.PLANET, 0.34f, 0.24f, 0.016f, 0f, 1),
)

@Composable
fun SpaceField(modifier: Modifier = Modifier, animated: Boolean = true) {
    val colors = PulsarTheme.colors
    val starColor = MaterialTheme.colorScheme.onSurface
    val nebViolet = colors.liveStart
    val nebMagenta = colors.liveEnd
    val nebPrimary = MaterialTheme.colorScheme.primary

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
        val twoPi = (2f * PI).toFloat()

        // ── nebula: a few soft on-palette clouds, barely there ───────────────
        drawNebula(Offset(w * 0.22f, h * 0.28f), minOf(w, h) * 0.60f, nebViolet.copy(alpha = 0.10f))
        drawNebula(Offset(w * 0.84f, h * 0.66f), minOf(w, h) * 0.55f, nebPrimary.copy(alpha = 0.07f))
        drawNebula(Offset(w * 0.62f, h * 0.10f), minOf(w, h) * 0.42f, nebMagenta.copy(alpha = 0.06f))

        // ── deep-sky objects: faint tilted galaxies + a couple of planets ────
        DEEP_SKY.forEach { d ->
            val c = Offset(d.xf * w, d.yf * h)
            val r = d.sizeF * minOf(w, h)
            when (d.kind) {
                SkyKind.GALAXY -> {
                    val tint = when (d.tintIdx) { 0 -> nebViolet; 1 -> nebPrimary; else -> nebMagenta }
                    rotate(d.angle, c) {
                        scale(1f, 0.42f, c) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(tint.copy(alpha = 0.18f), Color.Transparent), center = c, radius = r,
                                ),
                                radius = r, center = c,
                            )
                        }
                    }
                    drawCircle(starColor.copy(alpha = 0.45f), radius = 1.2.dp.toPx(), center = c)
                }
                SkyKind.PLANET -> {
                    val hue = if (d.tintIdx == 0) PlanetSlate else PlanetAmber
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(lerp(hue, Color.White, 0.4f), hue, lerp(hue, Color.Black, 0.45f)),
                            center = Offset(c.x - r * 0.3f, c.y - r * 0.3f), radius = r * 1.3f,
                        ),
                        radius = r, center = c, alpha = 0.7f,
                    )
                }
            }
        }

        // ── stars (twinkling) ────────────────────────────────────────────────
        FIELD_STARS.forEach { s ->
            val tw = if (animated) {
                0.55f + 0.45f * sin(twoPi * (timeS * 0.15f * s.speed + s.phase))
            } else 1f
            drawCircle(
                color = starColor.copy(alpha = (s.alpha * tw).coerceIn(0f, 1f)),
                radius = s.r.dp.toPx(),
                center = Offset(s.xf * w, s.yf * h),
            )
        }

        // ── meteors: bright head + fading tail, crossing on a beat ───────────
        if (animated) {
            METEOR_LANES.forEach { m ->
                val cycle = ((timeS / m.period) + m.phase).mod(1f)
                val visWindow = 0.20f
                if (cycle < visWindow) {
                    val p = cycle / visWindow                       // 0..1 across the sky
                    val dirX = cos(m.angle)
                    val dirY = sin(m.angle)
                    val travel = (w + h) * 0.95f * m.speedF
                    val hx = m.startXf * w + dirX * travel * p
                    val hy = m.startYf * h + dirY * travel * p
                    val len = m.lenF * w
                    val tx = hx - dirX * len
                    val ty = hy - dirY * len
                    val fade = sin(PI.toFloat() * p).coerceIn(0f, 1f) // ease in + out
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(starColor.copy(alpha = 0f), starColor.copy(alpha = 0.9f * fade)),
                            start = Offset(tx, ty),
                            end = Offset(hx, hy),
                        ),
                        start = Offset(tx, ty),
                        end = Offset(hx, hy),
                        strokeWidth = 2f.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(starColor.copy(alpha = 0.95f * fade), radius = 1.7.dp.toPx(), center = Offset(hx, hy))
                }
            }
        }
    }
}

private fun DrawScope.drawNebula(center: Offset, radius: Float, color: Color) {
    drawCircle(
        brush = Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}
