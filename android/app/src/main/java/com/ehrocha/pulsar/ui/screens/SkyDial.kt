/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.AstroCalculator
import com.ehrocha.pulsar.astro.DashboardState
import com.ehrocha.pulsar.astro.NightVerdict
import com.ehrocha.pulsar.astro.buildNightModel
import com.ehrocha.pulsar.ui.theme.Display
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/**
 * THE SKY DIAL — the unified Astro Dashboard hero. Tonight drawn as a radial
 * night-clock: an arc swept from dusk (lower-left) up over the top (deep
 * dark, ~midnight) down to dawn (lower-right). The arc's glow IS the shooting
 * quality minute by minute (sky darkness × cloud × moon × rain — the same
 * honest model as the CP 1919 ridgeline). The single best window blazes in
 * the live gradient; a concentric band marks when the Moon is up (brightness
 * = its glare) and another when the Milky-Way core clears the horizon. Centre
 * holds the verdict and the best window. One glance: is tonight worth it, and
 * when.
 *
 * Phase 1 of the daring dashboard redesign — the radial instrument. The
 * 4 tap-domains + secondary pages land next.
 */

// Arc geometry: 0° = 3 o'clock, +sweep = clockwise (screen y-down). Start at
// the lower-left, sweep 260° over the top to the lower-right, leaving a 100°
// gap at the bottom for the dusk/dawn labels.
private const val ARC_START = 140f
private const val ARC_SWEEP = 260f


@Composable
internal fun SkyDial(
    state: DashboardState,
    onTap: (() -> Unit)? = null,
    onOpenPage: ((DashPage) -> Unit)? = null,
) {
    val model = remember(state.weather, state.moon, state.sun, state.location, state.selectedDate) {
        buildNightModel(state)
    } ?: return

    val pc = PulsarTheme.colors
    val scheme = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier),
    ) {
        Box(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f),
            ) {
                val trackColor = scheme.onSurface.copy(alpha = 0.10f)
                val dimQuality = scheme.onSurfaceVariant.copy(alpha = 0.40f)
                val brightQuality = pc.positive
                val moonColor = pc.caution
                val coreColor = pc.trail

                Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.2f)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val pad = 18.dp.toPx()
                    val r = (minOf(cx, cy)) - pad
                    val qWidth = 16.dp.toPx()
                    val topLeft = Offset(cx - r, cy - r)
                    val arcSize = Size(r * 2, r * 2)
                    val n = model.samples.size

                    // Base track — the full night outline.
                    drawArc(
                        color = trackColor,
                        startAngle = ARC_START,
                        sweepAngle = ARC_SWEEP,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(qWidth, cap = StrokeCap.Round),
                    )

                    // Quality glow — one coloured segment per sample.
                    val seg = ARC_SWEEP / n
                    model.samples.forEachIndexed { i, s ->
                        val a = ARC_START + seg * i
                        val c = lerp(dimQuality, brightQuality, s.quality.coerceIn(0f, 1f))
                        drawArc(
                            color = c,
                            startAngle = a,
                            sweepAngle = seg + 0.6f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(qWidth, cap = StrokeCap.Butt),
                        )
                    }

                    // Moon-up band (outer concentric) — brightness = glare.
                    val moonR = r + qWidth * 0.75f
                    drawRunBands(model.samples.map { it.moonUp }) { f0, f1 ->
                        drawArc(
                            color = moonColor.copy(alpha = 0.16f + 0.45f * model.moonIllumPct / 100f),
                            startAngle = ARC_START + ARC_SWEEP * f0,
                            sweepAngle = ARC_SWEEP * (f1 - f0),
                            useCenter = false,
                            topLeft = Offset(cx - moonR, cy - moonR),
                            size = Size(moonR * 2, moonR * 2),
                            style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }

                    // Milky-Way core band (inner concentric).
                    val coreR = r - qWidth * 0.75f
                    drawRunBands(model.samples.map { it.coreUp }) { f0, f1 ->
                        drawArc(
                            color = coreColor.copy(alpha = 0.7f),
                            startAngle = ARC_START + ARC_SWEEP * f0,
                            sweepAngle = ARC_SWEEP * (f1 - f0),
                            useCenter = false,
                            topLeft = Offset(cx - coreR, cy - coreR),
                            size = Size(coreR * 2, coreR * 2),
                            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }

                    // Best window — the live gradient blaze + glow.
                    if (model.windowEndF > model.windowStartF) {
                        val wStart = ARC_START + ARC_SWEEP * model.windowStartF
                        val wSweep = ARC_SWEEP * (model.windowEndF - model.windowStartF)
                        val grad = Brush.sweepGradient(
                            listOf(pc.liveStart, pc.liveEnd, pc.liveStart),
                            center = Offset(cx, cy),
                        )
                        drawArc(grad, wStart, wSweep, false, topLeft, arcSize,
                            style = Stroke(qWidth * 2.0f, cap = StrokeCap.Round), alpha = 0.22f)
                        drawArc(grad, wStart, wSweep, false, topLeft, arcSize,
                            style = Stroke(qWidth, cap = StrokeCap.Round))
                    }

                    // Dusk / dawn end caps.
                    listOf(0f, 1f).forEach { f ->
                        val a = Math.toRadians((ARC_START + ARC_SWEEP * f).toDouble())
                        val p0 = Offset(cx + (r - qWidth) * cos(a).toFloat(), cy + (r - qWidth) * sin(a).toFloat())
                        val p1 = Offset(cx + (r + qWidth) * cos(a).toFloat(), cy + (r + qWidth) * sin(a).toFloat())
                        drawLine(scheme.onSurface.copy(alpha = 0.35f), p0, p1, 1.5.dp.toPx())
                    }

                    // Hour hints (every 3h that falls in the night): a short
                    // tick crossing the ring + a small clock-hour label just
                    // inside it, so 21 / 00 / 03 are findable on the arc.
                    if (model.hourMarks.isNotEmpty()) {
                        val labelPaint = android.graphics.Paint().apply {
                            color = scheme.onSurfaceVariant.toArgb()
                            textSize = 9.sp.toPx()
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        model.hourMarks.forEach { mark ->
                            val a = Math.toRadians((ARC_START + ARC_SWEEP * mark.fraction).toDouble())
                            val ca = cos(a).toFloat()
                            val sa = sin(a).toFloat()
                            drawLine(
                                scheme.onSurface.copy(alpha = 0.30f),
                                Offset(cx + (r - qWidth * 0.6f) * ca, cy + (r - qWidth * 0.6f) * sa),
                                Offset(cx + (r + qWidth * 0.6f) * ca, cy + (r + qWidth * 0.6f) * sa),
                                1.dp.toPx(),
                            )
                            val lr = r - qWidth * 1.7f
                            drawContext.canvas.nativeCanvas.drawText(
                                mark.label,
                                cx + lr * ca,
                                cy + lr * sa + labelPaint.textSize * 0.35f,
                                labelPaint,
                            )
                        }
                    }
                }

                // ── Centre readout ──────────────────────────────────────
                val verdictGood = model.verdict == NightVerdict.EXCELLENT || model.verdict == NightVerdict.GOOD
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(model.verdict.labelRes).uppercase(),
                        style = TextStyle(
                            fontFamily = Display,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            letterSpacing = 1.sp,
                            brush = if (verdictGood) {
                                Brush.linearGradient(listOf(pc.liveStart, pc.liveEnd))
                            } else null,
                        ),
                        color = when (model.verdict) {
                            NightVerdict.FAIR -> pc.caution
                            NightVerdict.POOR -> scheme.onSurfaceVariant
                            else -> Color.Unspecified
                        },
                    )
                    // Best window, right below the verdict (Eduardo: it read
                    // well here). Live magenta to tie it to the best-window
                    // blaze on the arc.
                    if (model.windowLabel.isNotEmpty()) {
                        Text(
                            model.windowLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                            color = pc.liveEnd,
                        )
                    }
                    // (Moon lives in the upper-left complication — no need to
                    // repeat it here.)
                }

                // ── Bottom: dusk/dawn, then the four readout complications in
                // a single finger-friendly row (Moon · Sky · Light · Targets) —
                // all tap targets gathered at the thumb-reachable base. ──────
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        state.sun?.sunset?.let { DialEndCaption(stringResource(R.string.sky_dial_dusk), formatTime(it)) }
                        state.sun?.sunrise?.let { DialEndCaption(stringResource(R.string.sky_dial_dawn), formatTime(it)) }
                    }
                    if (onOpenPage != null) {
                        // Each figure tinted by its quality — the per-domain
                        // green/amber/red verdict, on the dial face.
                        val onSurf = scheme.onSurface
                        val moonTint = state.moon?.let {
                            when {
                                it.illuminationPct <= 25 -> pc.positive
                                it.illuminationPct <= 55 -> pc.caution
                                else -> pc.negative
                            }
                        } ?: onSurf
                        val lightTint = state.bortle?.let {
                            when (it.bortleClass.toInt()) {
                                in 1..4 -> pc.positive
                                in 5..6 -> pc.caution
                                else -> pc.negative
                            }
                        } ?: onSurf
                        val skyTint = state.weather?.let {
                            when {
                                it.cloudCoverPct <= 25 -> pc.positive
                                it.cloudCoverPct <= 60 -> pc.caution
                                else -> pc.negative
                            }
                        } ?: onSurf
                        val targetsTint =
                            if (state.planets.size + state.bestWindows.size > 0) pc.positive
                            else scheme.onSurfaceVariant
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
                            DialComplication(
                                Modifier.weight(1f),
                                stringResource(R.string.dash_moon),
                                state.moon?.let { "${it.emoji} ${it.illuminationPct.toInt()}%" } ?: "—",
                                moonTint,
                            ) { onOpenPage(DashPage.MOON) }
                            DialComplication(
                                Modifier.weight(1f),
                                stringResource(R.string.dash_sky),
                                state.weather?.let { "${it.cloudCoverPct}%" } ?: "—",
                                skyTint,
                            ) { onOpenPage(DashPage.SKY) }
                            DialComplication(
                                Modifier.weight(1f),
                                stringResource(R.string.dash_light),
                                state.bortle?.let { "B${it.bortleClass.toInt()}" } ?: "—",
                                lightTint,
                            ) { onOpenPage(DashPage.LIGHT) }
                            DialComplication(
                                Modifier.weight(1f),
                                stringResource(R.string.dash_targets),
                                (state.planets.size + state.bestWindows.size).let { if (it > 0) it.toString() else "—" },
                                targetsTint,
                            ) { onOpenPage(DashPage.TARGETS) }
                        }
                    }
                }
            }
        }
    }
}

/** A readout complication: tiny label + figure, tappable to its page.
 *  Weighted into the finger-friendly bottom row via [modifier]. */
@Composable
private fun DialComplication(
    modifier: Modifier,
    label: String,
    value: String,
    valueColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
            color = valueColor,
            maxLines = 1,
        )
    }
}

/** Dusk / dawn caption stacked under its time, centred in the bottom gap. */
@Composable
private fun DialEndCaption(caption: String, time: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            caption.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            time,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Invoke [draw] for each contiguous run of `true` in [flags], passing the
 *  run's start/end as fractions of the arc (0..1). */
private inline fun drawRunBands(flags: List<Boolean>, draw: (Float, Float) -> Unit) {
    val n = flags.size
    if (n == 0) return
    var i = 0
    while (i < n) {
        if (flags[i]) {
            var j = i
            while (j < n && flags[j]) j++
            draw(i.toFloat() / n, j.toFloat() / n)
            i = j
        } else i++
    }
}

