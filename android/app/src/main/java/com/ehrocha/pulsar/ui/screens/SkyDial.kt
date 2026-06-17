/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.AstroCalculator
import com.ehrocha.pulsar.astro.DashboardState
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

// Galactic centre (Sagittarius A*) — for the Milky-Way core band.
private const val GC_RA = 266.417
private const val GC_DEC = -28.94

private enum class Verdict(val labelRes: Int) {
    EXCELLENT(R.string.sky_dial_excellent),
    GOOD(R.string.sky_dial_good),
    FAIR(R.string.sky_dial_fair),
    POOR(R.string.sky_dial_poor),
}

/** One sampled instant of the night. */
private class NightSample(
    val quality: Float,
    val moonUp: Boolean,
    val coreUp: Boolean,
)

private class DialModel(
    val samples: List<NightSample>,
    val verdict: Verdict,
    val windowStartF: Float,
    val windowEndF: Float,
    val windowLabel: String,
    val moonIllumPct: Int,
    val moonEmoji: String,
)

@Composable
internal fun SkyDial(
    state: DashboardState,
    onTap: (() -> Unit)? = null,
    onOpenPage: ((DashPage) -> Unit)? = null,
) {
    val model = remember(state.weather, state.moon, state.sun, state.location, state.selectedDate) {
        buildDialModel(state)
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
                    .aspectRatio(1.12f),
            ) {
                val trackColor = scheme.onSurface.copy(alpha = 0.10f)
                val dimQuality = scheme.onSurfaceVariant.copy(alpha = 0.40f)
                val brightQuality = pc.positive
                val moonColor = pc.caution
                val coreColor = pc.trail

                Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.12f)) {
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
                            style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
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
                            style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round),
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
                }

                // ── Centre readout ──────────────────────────────────────
                val verdictGood = model.verdict == Verdict.EXCELLENT || model.verdict == Verdict.GOOD
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
                            Verdict.FAIR -> pc.caution
                            Verdict.POOR -> scheme.onSurfaceVariant
                            else -> Color.Unspecified
                        },
                    )
                    if (model.windowLabel.isNotEmpty()) {
                        Text(
                            model.windowLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                            color = scheme.onSurface,
                        )
                    }
                    Text(
                        "${model.moonEmoji} ${model.moonIllumPct}%",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                        color = scheme.onSurfaceVariant,
                    )
                }

                // ── Dusk / dawn — centred in the bottom gap (the corners now
                // carry the complications). ─────────────────────────────────
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    state.sun?.sunset?.let { DialEndCaption(stringResource(R.string.sky_dial_dusk), formatTime(it)) }
                    state.sun?.sunrise?.let { DialEndCaption(stringResource(R.string.sky_dial_dawn), formatTime(it)) }
                }

                // ── Wear-OS-style complications at the four corners: the
                // domain readouts, each tappable to its page (they replace the
                // row that used to sit below the dial). ─────────────────────
                if (onOpenPage != null) {
                    // Each figure is tinted by its quality — the per-domain
                    // green/amber/red verdict the old Summary chips carried,
                    // now right on the dial face.
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
                    DialComplication(
                        stringResource(R.string.dash_moon),
                        state.moon?.let { "${it.emoji} ${it.illuminationPct.toInt()}%" } ?: "—",
                        Alignment.TopStart, moonTint,
                    ) { onOpenPage(DashPage.MOON) }
                    DialComplication(
                        stringResource(R.string.dash_targets),
                        (state.planets.size + state.bestWindows.size).let { if (it > 0) it.toString() else "—" },
                        Alignment.TopEnd, targetsTint,
                    ) { onOpenPage(DashPage.TARGETS) }
                    DialComplication(
                        stringResource(R.string.dash_sky),
                        state.weather?.let { "${it.cloudCoverPct}%" } ?: "—",
                        Alignment.BottomStart, skyTint,
                    ) { onOpenPage(DashPage.SKY) }
                    DialComplication(
                        stringResource(R.string.dash_light),
                        state.bortle?.let { "B${it.bortleClass.toInt()}" } ?: "—",
                        Alignment.BottomEnd, lightTint,
                    ) { onOpenPage(DashPage.LIGHT) }
                }
            }
        }
    }
}

/** A corner complication: tiny label + figure, tappable to its page. */
@Composable
private fun BoxScope.DialComplication(
    label: String,
    value: String,
    align: Alignment,
    valueColor: Color,
    onClick: () -> Unit,
) {
    val end = align == Alignment.TopEnd || align == Alignment.BottomEnd
    Column(
        modifier = Modifier
            .align(align)
            .widthIn(max = 84.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
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

// ── Model derivation ────────────────────────────────────────────────────────

/** Open-Meteo (timezone=auto) returns LOCAL ISO times like
 *  "2026-06-17T19:27"; twilight strings are already "HH:mm". Extract the
 *  HH:mm either way — the same slice the Sun card's formatTime uses. */
private fun parseTime(s: String?): LocalTime? {
    if (s.isNullOrEmpty()) return null
    val hhmm = s.substringAfter("T", s).take(5)
    return runCatching { LocalTime.parse(hhmm) }.getOrNull()
}

private fun buildDialModel(state: DashboardState): DialModel? {
    val loc = state.location ?: return null
    // Sun times preferred; fall back to civil twilight so a missing sun
    // field doesn't blank the hero.
    val sunsetT = parseTime(state.sun?.sunset)
        ?: parseTime(state.twilight?.civilEnd) ?: return null
    val sunriseT = parseTime(state.sun?.sunrise)
        ?: parseTime(state.twilight?.civilStart) ?: return null

    val date = state.selectedDate
    val start = date.atTime(sunsetT)
    var end = date.atTime(sunriseT)
    if (!end.isAfter(start)) end = end.plusDays(1)
    val spanMin = Duration.between(start, end).toMinutes().toDouble()
    if (spanMin < 60) return null

    val n = 90
    val moonFactor = if (state.moon?.goodForAstro == false) 0.6 else 1.0

    val samples = (0 until n).map { i ->
        val t = start.plusMinutes((i * spanMin / n).toLong())
        val d = t.toLocalDate()
        val tzOff = d.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        val localHour = t.hour + t.minute / 60.0
        val utcHour = localHour - tzOff
        val lst = AstroCalculator.lst(d, utcHour, loc.longitude)

        val (sunRa, sunDec) = AstroCalculator.sunPosition(d)
        val sunAlt = AstroCalculator.altitude(loc.latitude, sunDec, lst - sunRa)
        val darkness = when {
            sunAlt >= 0 -> 0.05
            sunAlt >= -6 -> 0.12 + (-sunAlt / 6.0) * 0.18
            sunAlt >= -12 -> 0.30 + ((-sunAlt - 6) / 6.0) * 0.30
            sunAlt >= -18 -> 0.60 + ((-sunAlt - 12) / 6.0) * 0.40
            else -> 1.0
        }
        val (cloud, precip) = cloudPrecipAt(state, t)
        val cloudFactor = 1.0 - (cloud / 100.0) * 0.9
        val rainFactor = if (precip > 0.1) 0.25 else 1.0
        val quality = (darkness * cloudFactor * rainFactor * moonFactor)
            .toFloat().coerceIn(0.02f, 1f)

        val (moonRa, moonDec) = AstroCalculator.moonPosition(d, utcHour)
        val moonAlt = AstroCalculator.altitude(loc.latitude, moonDec, lst - moonRa)
        val gcAlt = AstroCalculator.altitude(loc.latitude, GC_DEC, lst - GC_RA)

        NightSample(
            quality = quality,
            moonUp = moonAlt > 0,
            coreUp = gcAlt > 10 && darkness > 0.6,
        )
    }

    val peak = samples.maxOf { it.quality }
    val verdict = when {
        peak >= 0.78f -> Verdict.EXCELLENT
        peak >= 0.58f -> Verdict.GOOD
        peak >= 0.38f -> Verdict.FAIR
        else -> Verdict.POOR
    }

    // Best window = longest contiguous run at/above a threshold tied to the
    // peak, so the blaze always wraps the genuinely best stretch.
    val thr = maxOf(0.5f, peak * 0.82f)
    var bestStart = -1
    var bestLen = 0
    var curStart = -1
    for (i in samples.indices) {
        if (samples[i].quality >= thr) {
            if (curStart < 0) curStart = i
            val len = i - curStart + 1
            if (len > bestLen) { bestLen = len; bestStart = curStart }
        } else curStart = -1
    }
    val (wStartF, wEndF, label) = if (bestStart >= 0 && bestLen >= 2) {
        val s = bestStart.toFloat() / n
        val e = (bestStart + bestLen).toFloat() / n
        val ts = start.plusMinutes((bestStart * spanMin / n).toLong())
        val te = start.plusMinutes(((bestStart + bestLen) * spanMin / n).toLong())
        Triple(s, e, "%s → %s".format(fmt(ts), fmt(te)))
    } else Triple(0f, 0f, "")

    return DialModel(
        samples = samples,
        verdict = verdict,
        windowStartF = wStartF,
        windowEndF = wEndF,
        windowLabel = label,
        moonIllumPct = state.moon?.illuminationPct?.toInt() ?: 0,
        moonEmoji = state.moon?.emoji ?: "",
    )
}

private fun fmt(t: LocalDateTime): String = "%02d:%02d".format(t.hour, t.minute)

/** Linear-interpolated cloud % + precip mm at [t] from the hourly forecast;
 *  falls back to the current snapshot, then clear. */
private fun cloudPrecipAt(state: DashboardState, t: LocalDateTime): Pair<Double, Double> {
    val hrs = state.weather?.hourlyForecast
        ?.mapNotNull { h ->
            runCatching { LocalDateTime.parse(h.time) }.getOrNull()
                ?.let { Triple(it, h.cloudCoverPct.toDouble(), h.precipitationMm) }
        }
        ?.sortedBy { it.first }
        ?: emptyList()
    if (hrs.isEmpty()) {
        val c = state.weather?.cloudCoverPct?.toDouble() ?: 0.0
        return c to 0.0
    }
    if (!t.isAfter(hrs.first().first)) return hrs.first().let { it.second to it.third }
    if (!t.isBefore(hrs.last().first)) return hrs.last().let { it.second to it.third }
    for (i in 0 until hrs.size - 1) {
        val a = hrs[i]; val b = hrs[i + 1]
        if (!t.isBefore(a.first) && t.isBefore(b.first)) {
            val span = Duration.between(a.first, b.first).toMinutes().toDouble().coerceAtLeast(1.0)
            val f = Duration.between(a.first, t).toMinutes() / span
            return (a.second + (b.second - a.second) * f) to (a.third + (b.third - a.third) * f)
        }
    }
    return hrs.last().let { it.second to it.third }
}
