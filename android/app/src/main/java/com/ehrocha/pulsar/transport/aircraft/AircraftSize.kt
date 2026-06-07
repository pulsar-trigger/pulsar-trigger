/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.aircraft

/**
 * Coarse size classes for marker scaling on the Aircraft Watch map.
 * Driven by ICAO 4-letter type code (`typeCode`) with a model-string
 * fallback, both populated from the OpenSky metadata enrichment.
 *
 * Bucketing matches ICAO Doc 8643 wake categories conceptually but ignores
 * the exact MTOW thresholds — we only need enough resolution to make a
 * Cessna 172 look smaller than a 787 on the map, not to compute
 * separation minima.
 */
enum class AircraftSize(val scale: Float) {
    LIGHT(0.65f),    // Cessna 172, R44, PA28, light singles + helicopters
    MEDIUM(0.85f),   // Default: B737/A320 family, regional jets, biz jets
    LARGE(1.10f),    // B757, B767, A330, B787, A350, B777
    HEAVY(1.35f),    // B747, A380, AN225, military transports
}

/** Pick a size class for a sighting. Returns MEDIUM if the metadata
 *  enrichment hasn't populated `typeCode` / `model` yet — better to show
 *  a marker at typical size than to wait for the lookup. */
fun aircraftSizeFor(typeCode: String?, model: String?): AircraftSize {
    val code = typeCode?.uppercase()
    val m = model?.uppercase().orEmpty()
    // Type-code prefixes are the cleanest signal — they're the ICAO 4-letter
    // designators (B738, A359, etc.). Substring match on the prefix covers
    // sub-variants (B7378, B739 → all under "B73").
    if (code != null) {
        when {
            // Heavy
            code.startsWith("A38") || code == "B744" || code.startsWith("B747") ||
            code.startsWith("A124") || code.startsWith("AN12") || code == "C5" -> return AircraftSize.HEAVY
            // Large
            code.startsWith("A33") || code.startsWith("A35") || code.startsWith("B77") ||
            code.startsWith("B76") || code.startsWith("B78") || code.startsWith("B75") ||
            code.startsWith("MD11") || code.startsWith("A340") || code.startsWith("DC10") -> return AircraftSize.LARGE
            // Medium — explicit catalogue so we don't accidentally tag a
            // Cessna 172 as medium just because we don't know it.
            code.startsWith("A31") || code.startsWith("A32") || code.startsWith("A22") ||
            code.startsWith("B73") || code.startsWith("B71") || code.startsWith("E17") ||
            code.startsWith("E19") || code.startsWith("E29") || code.startsWith("CRJ") ||
            code.startsWith("AT4") || code.startsWith("AT7") || code.startsWith("DH8") ||
            code.startsWith("MD8") || code.startsWith("MD9") || code.startsWith("F70") ||
            code.startsWith("F100") -> return AircraftSize.MEDIUM
            // Light (single-engine pistons, light helicopters, light biz jets)
            code.startsWith("C1") || code.startsWith("C2") || code.startsWith("PA") ||
            code.startsWith("R44") || code.startsWith("R22") || code.startsWith("EC") ||
            code.startsWith("R66") || code.startsWith("B06") || code.startsWith("DA") ||
            code.startsWith("SR2") || code.startsWith("BE2") || code.startsWith("BE3") ||
            code.startsWith("DV2") || code.startsWith("TBM") -> return AircraftSize.LIGHT
        }
    }
    // Model-string fallback for the gaps. Crude but useful when typeCode is
    // missing — e.g. older entries in the OpenSky DB.
    return when {
        "747" in m || "A380" in m || "AN-225" in m -> AircraftSize.HEAVY
        "777" in m || "787" in m || "A330" in m || "A340" in m ||
            "A350" in m || "767" in m || "757" in m || "MD-11" in m -> AircraftSize.LARGE
        "CESSNA 1" in m || "PIPER" in m || "R44" in m || "R22" in m ||
            "DIAMOND" in m || "CIRRUS" in m -> AircraftSize.LIGHT
        else -> AircraftSize.MEDIUM
    }
}
