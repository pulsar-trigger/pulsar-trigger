/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.astro.AstroCalculator
import com.ehrocha.pulsar.astro.DashboardState
import com.ehrocha.pulsar.ui.theme.Display
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SIGNAL's flagship moment: tonight rendered as a CP 1919-style stacked
 * ridgeline — the chart the app is named after. Each line is one hour;
 * within a line, **left→right is the 60 minutes of that hour** and the
 * height at each point is the actual shooting quality at that minute
 * (sky darkness × cloud × moonlight × rain). So a line that climbs through
 * the hour means the sky is improving (twilight deepening); a falling line
 * means it's degrading (cloud rolling in, moonrise, dawn). The row holding
 * the single best moment is drawn in the live gradient. Homage AND honest
 * chart — the horizontal axis carries real information, not decoration.
 */
internal data class HourSignal(
    val label: String,            // "19h"
    val hour: Int,                // 0..23, for formatting the peak time
    val samples: List<Float>,     // quality 0..1, left = :00, right = :59
    val cloudPct: Int,
    val best: Boolean = false,
)

/** Sub-samples per hour-row. Sun altitude is recomputed at each, so the
 *  curve traces real minute-level darkness (every 2.5 min). */
private const val SAMPLES_PER_HOUR = 25

/** Append a Catmull-Rom spline through [pts] (current point must be pts[0])
 *  as cubic béziers — graceful organic curves through the real samples,
 *  without inventing data between them. */
private fun Path.catmullRomTo(pts: List<Offset>) {
    for (i in 0 until pts.size - 1) {
        val p0 = pts[(i - 1).coerceAtLeast(0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[(i + 2).coerceAtMost(pts.size - 1)]
        cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
}

/** Quality model per forecast hour. Pure derivation from data the
 *  dashboard already fetched — no new network. */
internal fun buildTonightSignal(state: DashboardState): List<HourSignal> {
    val loc = state.location ?: return emptyList()
    val hourlyRaw = state.weather?.hourlyForecast ?: return emptyList()
    if (hourlyRaw.isEmpty()) return emptyList()

    // Parse + sort so we can interpolate cloud/rain between adjacent hours
    // across a row (the within-hour trend).
    data class HourPt(val t: LocalDateTime, val cloud: Int, val precip: Double)
    val pts = hourlyRaw.mapNotNull { h ->
        val t = runCatching { LocalDateTime.parse(h.time) }.getOrNull() ?: return@mapNotNull null
        HourPt(t, h.cloudCoverPct, h.precipitationMm)
    }.sortedBy { it.t }
    if (pts.isEmpty()) return emptyList()

    val now = LocalDateTime.now()
    val moonFactor = if (state.moon?.goodForAstro == false) 0.6f else 1f

    // Darkness at a given local hour-of-day — sun altitude through the
    // twilights (0 at the horizon → 1 at astronomical dark). Recomputed per
    // sub-sample so the curve reflects the sun genuinely sinking minute by
    // minute (most of the within-hour motion near dusk/dawn).
    fun darknessAt(localHour: Double, date: java.time.LocalDate): Double {
        val tzOff = date.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        val (sunRa, sunDec) = AstroCalculator.sunPosition(date)
        val sunAlt = AstroCalculator.altitude(
            loc.latitude, sunDec,
            AstroCalculator.lst(date, localHour - tzOff, loc.longitude) - sunRa,
        )
        return when {
            sunAlt >= 0 -> 0.05
            sunAlt >= -6 -> 0.12 + (-sunAlt / 6.0) * 0.18      // civil
            sunAlt >= -12 -> 0.30 + ((-sunAlt - 6) / 6.0) * 0.30 // nautical
            sunAlt >= -18 -> 0.60 + ((-sunAlt - 12) / 6.0) * 0.40 // astro
            else -> 1.0
        }
    }

    val out = mutableListOf<HourSignal>()
    for ((idx, pt) in pts.withIndex()) {
        val t = pt.t
        if (t.isBefore(now.minusMinutes(30)) || t.isAfter(now.plusHours(14))) continue
        val next = pts.getOrNull(idx + 1)
        val date = t.toLocalDate()
        val samples = (0 until SAMPLES_PER_HOUR).map { k ->
            val f = k.toDouble() / (SAMPLES_PER_HOUR - 1)   // 0..1 across the hour
            val darkness = darknessAt(t.hour + f, date)
            val cloud = if (next != null) pt.cloud + (next.cloud - pt.cloud) * f else pt.cloud.toDouble()
            val precip = if (next != null) pt.precip + (next.precip - pt.precip) * f else pt.precip
            val cloudFactor = 1.0 - (cloud / 100.0) * 0.9
            val rainFactor = if (precip > 0.1) 0.25 else 1.0
            (darkness * cloudFactor * rainFactor * moonFactor).toFloat().coerceIn(0.02f, 1f)
        }
        // Skip hours that are essentially daylight the whole way through.
        if ((samples.maxOrNull() ?: 0f) < 0.08f) continue
        out += HourSignal(
            label = "%02dh".format(t.hour),
            hour = t.hour,
            samples = samples,
            cloudPct = pt.cloud,
        )
        if (out.size >= 10) break
    }
    if (out.isEmpty()) return out
    // Highlight the row holding the single best MOMENT tonight.
    val bestIdx = out.indices.maxByOrNull { i -> out[i].samples.max() } ?: 0
    return out.mapIndexed { i, h -> if (i == bestIdx) h.copy(best = true) else h }
}

/** Tiny vertical level gauge: filled to [level] (0..1 = this hour's peak
 *  quality). The absolute reference the honest ridgeline needs — a flat-high
 *  and a flat-low line are obvious here even though both look flat. */
@Composable
private fun QualityPip(level: Float, best: Boolean) {
    val colors = PulsarTheme.colors
    Box(
        modifier = Modifier
            .width(5.dp)
            .height(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(level.coerceIn(0.06f, 1f))
                .then(
                    if (best) {
                        Modifier.background(
                            Brush.verticalGradient(listOf(colors.liveEnd, colors.liveStart)),
                        )
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    },
                ),
        )
    }
}

@Composable
internal fun TonightSignalCard(state: DashboardState) {
    val hours = remember(state.weather, state.moon, state.location) {
        buildTonightSignal(state)
    }
    if (hours.size < 3) return

    var showHelp by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    if (showHelp) {
        com.ehrocha.pulsar.ui.components.DetailSheet(
            onDismiss = { showHelp = false },
            title = {
                Text(
                    stringResource(R.string.tonight_signal_help_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        ) {
            Text(
                stringResource(R.string.tonight_signal_help_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tonight_signal_title).uppercase(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = Display,
                        letterSpacing = 2.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.IconButton(
                    onClick = { showHelp = true },
                    modifier = Modifier.height(28.dp).width(28.dp),
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.tonight_signal_help_title),
                        modifier = Modifier.height(18.dp).width(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val rowHeight = 26.dp
            // Headroom above the top row: a peak rises up to 1.55× the row
            // height above its baseline, so the topmost hour needs ~0.55×
            // row of clearance or its tall pulses get cut flat at the edge.
            val topPad = 18.dp
            val traceColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            val fillColor = MaterialTheme.colorScheme.surfaceContainerHigh
            // onSurface (near-white in dark) at low alpha — visible on the
            // dark card, unlike outline which is dark-on-dark.
            val guideColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
            val live = PulsarTheme.colors
            Row(verticalAlignment = Alignment.Top) {
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(rowHeight * hours.size + topPad + 8.dp),
                ) {
                    val rowPx = rowHeight.toPx()
                    val topPadPx = topPad.toPx()
                    val w = size.width
                    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                    // Painters order: draw the EARLIEST hour first; later
                    // (lower) rows occlude it with an opaque under-fill —
                    // the classic joyplot trick, and how the original
                    // CP 1919 plate reads.
                    val bloomStroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    hours.forEachIndexed { i, h ->
                        val baseY = topPadPx + rowPx * (i + 1)
                        val maxAmp = rowPx * 1.55f
                        val n = h.samples.size

                        // ENVELOPE = the real quality curve (smoothed). It
                        // shapes the occlusion fill + glow, and it caps the
                        // signal: the texture's peaks reach exactly here, so
                        // the silhouette you read is true.
                        val envPts = h.samples.mapIndexed { s, q ->
                            Offset(w * s / (n - 1), baseY - maxAmp * q)
                        }
                        val fill = Path().apply {
                            moveTo(envPts[0].x, baseY)
                            lineTo(envPts[0].x, envPts[0].y)
                            catmullRomTo(envPts)
                            lineTo(envPts.last().x, baseY)
                            close()
                        }
                        // 1) opaque under-fill — the joyplot occlusion.
                        drawPath(fill, fillColor)
                        // 2) luminous glow under the ridge — brighter toward
                        //    the peak, so good hours visibly glow.
                        val glowEnd = if (h.best) live.liveEnd else traceColor
                        drawPath(
                            fill,
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, glowEnd.copy(alpha = if (h.best) 0.30f else 0.17f)),
                                startY = baseY,
                                endY = baseY - maxAmp,
                            ),
                        )

                        // 3) The SIGNAL: a CP 1919 radio-noise trace whose
                        //    amplitude envelope is the real quality. Peaks of
                        //    the texture touch the true curve; valleys hang
                        //    below — exactly like the original pulsar plot, a
                        //    noisy signal modulated by a real intensity.
                        fun qAt(u: Float): Float {
                            val xx = (u * (n - 1)).coerceIn(0f, (n - 1).toFloat())
                            val lo = xx.toInt()
                            val hi = (lo + 1).coerceAtMost(n - 1)
                            val f = xx - lo
                            return h.samples[lo] * (1f - f) + h.samples[hi] * f
                        }
                        val steps = 150
                        val signal = Path()
                        for (s in 0..steps) {
                            val u = s / steps.toFloat()
                            // Four incommensurate octaves → dense, irregular
                            // radio-noise texture whose peaks vary in height
                            // (they rarely all align to 1), so the upper edge
                            // is jagged, not a smooth interpolated silhouette.
                            val nz = (
                                sin(u * 31f * PI + i * 1.7f) * 0.42f +
                                sin(u * 53f * PI + i * 3.3f) * 0.28f +
                                sin(u * 19f * PI + i * 5.1f) * 0.18f +
                                sin(u * 83f * PI + i * 0.7f) * 0.12f
                            ).toFloat()
                            val tex = (0.5f + 0.5f * nz).coerceIn(0.04f, 1f)
                            val y = baseY - maxAmp * qAt(u) * tex
                            val x = w * u
                            if (s == 0) signal.moveTo(x, y) else signal.lineTo(x, y)
                        }
                        if (h.best) {
                            val g = Brush.horizontalGradient(listOf(live.liveStart, live.liveEnd))
                            drawPath(signal, brush = g, style = bloomStroke, alpha = 0.35f)
                            drawPath(signal, brush = g, style = stroke)
                        } else {
                            drawPath(signal, traceColor.copy(alpha = 0.20f), style = bloomStroke)
                            drawPath(signal, traceColor, style = stroke)
                        }
                    }
                    // Quarter-hour guides (15 / 30 / 45 min) on top, so the
                    // within-hour time scale is always visible — read where
                    // in the hour a peak falls. Faint enough not to fight the
                    // traces.
                    for (qm in 1..3) {
                        val gx = w * qm / 4f
                        drawLine(guideColor, Offset(gx, 0f), Offset(gx, size.height), 0.8.dp.toPx())
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    // Match the canvas's top headroom so labels line up with
                    // their rows.
                    Spacer(Modifier.height(topPad))
                    hours.forEach { h ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(rowHeight),
                        ) {
                            // Absolute level gauge — disambiguates a flat-high
                            // (great) hour from a flat-low (poor) one, which
                            // the line shape alone can't.
                            QualityPip(level = h.samples.max(), best = h.best)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                h.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                                color = if (h.best) live.liveEnd
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(30.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val best = hours.first { it.best }
            // Pinpoint the single best moment: the peak sample's minute.
            val peakIdx = best.samples.indices.maxByOrNull { best.samples[it] } ?: 0
            val peakMin = (peakIdx.toFloat() / (best.samples.size - 1) * 60f)
                .roundToInt().coerceIn(0, 59)
            val peakTime = "%02d:%02d".format(best.hour, peakMin)
            Text(
                stringResource(R.string.tonight_signal_best, peakTime, best.cloudPct),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
/** The at-a-glance instrument strip under the hero (Eduardo's #1: "if the
 *  dashboard is complete enough" the card stack below can stay collapsed).
 *  Six numbers a night photographer triages by; everything deeper lives in
 *  the collapsed detail cards. */
@Composable
internal fun ConditionsStrip(state: DashboardState) {
    val chips = buildList {
        state.bortle?.let { add(Triple("BORTLE", "%.0f".format(it.bortleClass), null)) }
        state.moon?.let {
            add(Triple("MOON", "${it.illuminationPct.toInt()}%",
                if (it.goodForAstro) PulsarTheme.colors.positive else PulsarTheme.colors.negative))
        }
        state.weather?.let {
            add(Triple("CLOUD", "${it.cloudCoverPct}%",
                when {
                    it.cloudCoverPct <= 25 -> PulsarTheme.colors.positive
                    it.cloudCoverPct <= 60 -> PulsarTheme.colors.caution
                    else -> PulsarTheme.colors.negative
                }))
        }
        state.dewPoint?.let { add(Triple("DEW Δ", "%.1f°".format(it.spreadC), null)) }
        state.goldenBlue?.eveningGolden?.let { add(Triple("GOLDEN", it.start, null)) }
        state.milkyWay?.let {
            add(Triple("MW CORE", if (it.visible) "VIS" else "—",
                if (it.visible) PulsarTheme.colors.positive else null))
        }
    }
    if (chips.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
        modifier = Modifier.fillMaxWidth(),
    ) {
        chips.forEach { (label, value, tint) ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono),
                        color = tint ?: MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
