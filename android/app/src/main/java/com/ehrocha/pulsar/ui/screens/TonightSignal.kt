/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.exp
import kotlin.math.sin

/**
 * SIGNAL's flagship moment: tonight rendered as a CP 1919-style stacked
 * pulse plot — the chart the app is named after. Each line is one hour;
 * the pulse amplitude is that hour's shooting quality (sky darkness ×
 * cloud cover × moonlight × rain). The best hour's trace is drawn in the
 * live gradient. It's a homage AND a real chart.
 */
internal data class HourSignal(
    val label: String,
    val quality: Float,   // 0..1
    val cloudPct: Int,
    val best: Boolean = false,
)

/** Quality model per forecast hour. Pure derivation from data the
 *  dashboard already fetched — no new network. */
internal fun buildTonightSignal(state: DashboardState): List<HourSignal> {
    val loc = state.location ?: return emptyList()
    val hourly = state.weather?.hourlyForecast ?: return emptyList()
    if (hourly.isEmpty()) return emptyList()

    val now = LocalDateTime.now()
    val moonFactor = when (state.moon?.goodForAstro) {
        false -> 0.6f
        else -> 1f
    }

    val out = mutableListOf<HourSignal>()
    for (h in hourly) {
        val t = runCatching { LocalDateTime.parse(h.time) }.getOrNull() ?: continue
        if (t.isBefore(now.minusMinutes(30)) || t.isAfter(now.plusHours(14))) continue

        // Sun altitude at this hour — same maths goldenBlueHours uses.
        val date = t.toLocalDate()
        val tzOff = date.atStartOfDay(ZoneId.systemDefault()).offset.totalSeconds / 3600.0
        val (sunRa, sunDec) = AstroCalculator.sunPosition(date)
        val localHour = t.hour + t.minute / 60.0
        val sunAlt = AstroCalculator.altitude(
            loc.latitude, sunDec,
            AstroCalculator.lst(date, localHour - tzOff, loc.longitude) - sunRa,
        )
        if (sunAlt > 5.0) continue  // daylight hours don't make the chart

        // Darkness ramps through the twilights: 0 at the horizon, 1 at
        // astronomical darkness.
        val darkness = when {
            sunAlt >= 0 -> 0.05
            sunAlt >= -6 -> 0.12 + (-sunAlt / 6.0) * 0.18      // civil
            sunAlt >= -12 -> 0.30 + ((-sunAlt - 6) / 6.0) * 0.30 // nautical
            sunAlt >= -18 -> 0.60 + ((-sunAlt - 12) / 6.0) * 0.40 // astro
            else -> 1.0
        }
        val cloudFactor = 1.0 - (h.cloudCoverPct / 100.0) * 0.9
        val rainFactor = if (h.precipitationMm > 0.1) 0.25 else 1.0
        val q = (darkness * cloudFactor * rainFactor * moonFactor).toFloat()
        out += HourSignal(
            label = "%02dh".format(t.hour),
            quality = q.coerceIn(0.02f, 1f),
            cloudPct = h.cloudCoverPct,
        )
        if (out.size >= 10) break
    }
    if (out.isEmpty()) return out
    val bestIdx = out.indices.maxBy { out[it].quality }
    return out.mapIndexed { i, h -> if (i == bestIdx) h.copy(best = true) else h }
}

@Composable
internal fun TonightSignalCard(state: DashboardState) {
    val hours = remember(state.weather, state.moon, state.location) {
        buildTonightSignal(state)
    }
    if (hours.size < 3) return

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.tonight_signal_title).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = Display,
                    letterSpacing = 2.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            val rowHeight = 26.dp
            val traceColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            val fillColor = MaterialTheme.colorScheme.surfaceContainerHigh
            val live = PulsarTheme.colors
            Row(verticalAlignment = Alignment.Top) {
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(rowHeight * hours.size + 8.dp),
                ) {
                    val rowPx = rowHeight.toPx()
                    val w = size.width
                    val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                    // Painters order: draw the EARLIEST hour first; later
                    // (lower) rows occlude it with an opaque under-fill —
                    // the classic joyplot trick, and how the original
                    // CP 1919 plate reads.
                    hours.forEachIndexed { i, h ->
                        val baseY = rowPx * (i + 1)
                        val amp = rowPx * 1.55f * h.quality
                        val path = Path()
                        val fill = Path()
                        var first = true
                        val steps = 72
                        for (s in 0..steps) {
                            val x = w * s / steps
                            val u = s / steps.toFloat()
                            // organic pulse cluster: gaussian envelope
                            // around 42% width × two incommensurate sines,
                            // phase-seeded per row so the plot is stable
                            val env = exp(-((u - 0.42f) * (u - 0.42f)) / 0.022f)
                            val n = (
                                sin(u * 19f * PI + i * 2.39f) * 0.55f +
                                sin(u * 7f * PI + i * 5.07f) * 0.45f
                            ).toFloat()
                            val y = baseY - amp * env * (0.55f + 0.45f * n).coerceAtLeast(0.04f)
                            if (first) {
                                path.moveTo(x, y); fill.moveTo(x, baseY); fill.lineTo(x, y)
                                first = false
                            } else {
                                path.lineTo(x, y); fill.lineTo(x, y)
                            }
                        }
                        fill.lineTo(w, baseY); fill.close()
                        drawPath(fill, fillColor)
                        if (h.best) {
                            drawPath(
                                path,
                                brush = Brush.horizontalGradient(
                                    listOf(live.liveStart, live.liveEnd),
                                ),
                                style = stroke,
                            )
                        } else {
                            drawPath(path, traceColor, style = stroke)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    hours.forEach { h ->
                        Text(
                            h.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                            color = if (h.best) live.liveEnd
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.height(rowHeight).width(34.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val best = hours.first { it.best }
            Text(
                stringResource(R.string.tonight_signal_best, best.label, best.cloudPct),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
