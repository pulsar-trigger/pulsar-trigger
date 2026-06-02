/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.astro

import java.time.LocalDate

/**
 * Suggests Deep-Sky targets to shoot tonight given the observer's
 * latitude / longitude and the dark window in [TwilightInfo].
 *
 * The math:
 *   For each [DsoCatalog.Dso] sample its altitude at evenly-spaced points
 *   across the astro-dark window, track the peak (max altitude + time),
 *   and keep only objects that clear a minimum altitude (default 30°).
 *   Score = peak altitude in degrees, minus a small magnitude penalty
 *   so a bright 5° lower object beats a dim same-altitude one.
 *
 *   Output is the top N targets sorted by score, each tagged with the
 *   civic-time of its peak altitude so the user can plan the sequence.
 */
object DsoRecommender {

    /** Minimum peak altitude (°) for a target to be considered. Below
     *  this, atmospheric extinction + horizon obstructions ruin the
     *  shot regardless of magnitude. */
    private const val MIN_PEAK_ALT_DEG = 30.0

    /** Number of altitude samples taken across the dark window. 12 is
     *  enough granularity to find peaks within ~5 minutes. */
    private const val SAMPLES = 12

    data class Recommendation(
        val target: DsoCatalog.Dso,
        val peakAltitudeDeg: Double,
        /** UTC hour-of-day at the moment of peak altitude — caller can
         *  format to local time for display. */
        val peakUtcHour: Double,
    )

    /**
     * Compute recommendations.
     *
     * @param date Local date the user is planning for (defaults to today).
     * @param latDeg Observer latitude in degrees, positive = north.
     * @param lonDeg Observer longitude in degrees, positive = east.
     * @param darkStartUtcH UTC hour at the start of the astro-dark window,
     *   parsed via [AstroCalculator.parseIsoHour].
     * @param darkEndUtcH UTC hour at the end of the astro-dark window.
     * @param limit How many recommendations to return.
     */
    fun recommend(
        date: LocalDate,
        latDeg: Double,
        lonDeg: Double,
        darkStartUtcH: Double,
        darkEndUtcH: Double,
        limit: Int = 5,
    ): List<Recommendation> {
        // Handle the wrap across midnight: dark window starts in the
        // evening (e.g. 22.5) and ends in the morning (e.g. 5.2). Sample
        // forward through the wrap.
        val span = if (darkEndUtcH >= darkStartUtcH) darkEndUtcH - darkStartUtcH
                   else (24.0 - darkStartUtcH) + darkEndUtcH
        if (span <= 0.0 || span > 14.0) return emptyList()  // no astro dark

        val results = mutableListOf<Recommendation>()
        for (dso in DsoCatalog.ALL) {
            var bestAlt = -90.0
            var bestT = darkStartUtcH
            for (i in 0..SAMPLES) {
                val frac = i.toDouble() / SAMPLES
                val t = (darkStartUtcH + frac * span) % 24.0
                val sampleDate = if (t < darkStartUtcH) date.plusDays(1) else date
                val lst = AstroCalculator.lst(sampleDate, t, lonDeg)
                val alt = AstroCalculator.altitude(latDeg, dso.decDeg, lst - dso.raDeg)
                if (alt > bestAlt) {
                    bestAlt = alt
                    bestT = t
                }
            }
            if (bestAlt >= MIN_PEAK_ALT_DEG) {
                results += Recommendation(dso, bestAlt, bestT)
            }
        }
        return results
            .sortedByDescending { score(it) }
            .take(limit)
    }

    /** Heuristic score that rewards high peak altitude but doesn't let
     *  dim targets win over moderately-bright lower ones. Each
     *  magnitude step costs ~3°. Tweakable. */
    private fun score(r: Recommendation): Double =
        r.peakAltitudeDeg - 3.0 * r.target.magnitude
}
