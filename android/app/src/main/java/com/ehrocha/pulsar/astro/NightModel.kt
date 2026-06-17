/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.astro

import com.ehrocha.pulsar.R
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tonight reduced to a single, view-agnostic model: a per-instant quality
 * sampling (sky darkness × cloud × moon × rain — the same honest model the
 * CP 1919 ridgeline uses), the overall verdict, the best window, and the moon.
 *
 * Lives here, free of Compose, so BOTH the in-app Sky Dial (drawn with
 * Compose Canvas) and the home-screen widget (built from live Glance views)
 * share one definition of "tonight". Neither renders the other; they both
 * render THIS.
 */

private const val GC_RA = 266.417
private const val GC_DEC = -28.94

enum class NightVerdict(val labelRes: Int) {
    EXCELLENT(R.string.sky_dial_excellent),
    GOOD(R.string.sky_dial_good),
    FAIR(R.string.sky_dial_fair),
    POOR(R.string.sky_dial_poor),
}

/** One sampled instant of the night. */
class NightSample(
    val quality: Float,
    val moonUp: Boolean,
    val coreUp: Boolean,
)

/** A clock-hour hint on the ring: where it falls (0..1 across the night) and
 *  its label ("21", "00", "03"). */
class HourMark(val fraction: Float, val label: String)

class NightModel(
    val samples: List<NightSample>,
    val verdict: NightVerdict,
    val windowStartF: Float,
    val windowEndF: Float,
    val windowLabel: String,
    val moonIllumPct: Int,
    val moonEmoji: String,
    /** Where "now" falls along the night (0..1), or null if it's not tonight
     *  or the sun is up. Drives the live "now" marker. */
    val nowFraction: Float?,
    /** Every-3-hour clock hint that falls within the night, for the ring. */
    val hourMarks: List<HourMark>,
)

/** Open-Meteo (timezone=auto) returns LOCAL ISO times like "2026-06-17T19:27";
 *  twilight strings are already "HH:mm". Extract the HH:mm either way. */
private fun parseTime(s: String?): LocalTime? {
    if (s.isNullOrEmpty()) return null
    val hhmm = s.substringAfter("T", s).take(5)
    return runCatching { LocalTime.parse(hhmm) }.getOrNull()
}

fun buildNightModel(state: DashboardState): NightModel? {
    val loc = state.location ?: return null
    // Sun times preferred; fall back to civil twilight so a missing sun field
    // doesn't blank the model.
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

    // Where "now" sits along tonight's night (only meaningful for today, and
    // only while the sun is down).
    val now = LocalDateTime.now()
    val nowFraction = if (date == LocalDate.now() && !now.isBefore(start) && now.isBefore(end)) {
        (Duration.between(start, now).toMinutes() / spanMin).toFloat().coerceIn(0f, 1f)
    } else null

    // Clock-hour hints every 3h (… 18, 21, 00, 03, 06 …) that land in the night.
    val hourMarks = buildList {
        for (h in 0..21 step 3) {
            var dt = start.toLocalDate().atTime(h, 0)
            if (dt.isBefore(start)) dt = dt.plusDays(1)
            if (dt.isBefore(end)) {
                val f = (Duration.between(start, dt).toMinutes() / spanMin).toFloat()
                if (f in 0f..1f) add(HourMark(f, "%02d".format(h)))
            }
        }
    }

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
        peak >= 0.78f -> NightVerdict.EXCELLENT
        peak >= 0.58f -> NightVerdict.GOOD
        peak >= 0.38f -> NightVerdict.FAIR
        else -> NightVerdict.POOR
    }

    // Best window = longest contiguous run at/above a threshold tied to the
    // peak, so it always wraps the genuinely best stretch.
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
        Triple(s, e, "%s → %s".format(fmtHm(ts), fmtHm(te)))
    } else Triple(0f, 0f, "")

    return NightModel(
        samples = samples,
        verdict = verdict,
        windowStartF = wStartF,
        windowEndF = wEndF,
        windowLabel = label,
        moonIllumPct = state.moon?.illuminationPct?.toInt() ?: 0,
        moonEmoji = state.moon?.emoji ?: "",
        nowFraction = nowFraction,
        hourMarks = hourMarks,
    )
}

private fun fmtHm(t: LocalDateTime): String = "%02d:%02d".format(t.hour, t.minute)

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
