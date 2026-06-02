/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.astro

/**
 * Small built-in catalog of popular Deep-Sky targets — Messier highlights
 * plus a hand-picked set of well-known NGC/IC nebulae. Coordinates are
 * J2000 in decimal degrees. Magnitudes are integrated/apparent.
 *
 * Used by [DsoRecommender] to suggest targets for tonight given the
 * observer's latitude and dark-window times. Not exhaustive — the goal is
 * "a curated list a novice would actually attempt", not Skyatlas
 * completeness.
 */
object DsoCatalog {

    enum class DsoType { GALAXY, NEBULA, CLUSTER_GLOBULAR, CLUSTER_OPEN, SUPERNOVA_REMNANT, PLANETARY_NEBULA, OTHER }

    data class Dso(
        val id: String,            // canonical: "M31", "NGC 7000"
        val commonName: String,    // "Andromeda Galaxy"
        val type: DsoType,
        val raDeg: Double,         // J2000, decimal degrees (0–360)
        val decDeg: Double,        // J2000, decimal degrees (-90..+90)
        val magnitude: Double,     // integrated/visual; lower is brighter
        val sizeArcmin: Double,    // major-axis angular size
    ) {
        val emoji: String get() = when (type) {
            DsoType.GALAXY -> "🌌"
            DsoType.NEBULA -> "🌫️"
            DsoType.PLANETARY_NEBULA -> "⭕"
            DsoType.SUPERNOVA_REMNANT -> "💥"
            DsoType.CLUSTER_GLOBULAR -> "✨"
            DsoType.CLUSTER_OPEN -> "✦"
            DsoType.OTHER -> "·"
        }
    }

    val ALL: List<Dso> = listOf(
        // ── Messier highlights ────────────────────────────────────────
        Dso("M1",  "Crab Nebula",         DsoType.SUPERNOVA_REMNANT,  83.633,  22.014,  8.4,  6.0),
        Dso("M8",  "Lagoon Nebula",       DsoType.NEBULA,            270.904, -24.387,  6.0, 90.0),
        Dso("M13", "Hercules Cluster",    DsoType.CLUSTER_GLOBULAR,  250.422,  36.461,  5.8, 20.0),
        Dso("M16", "Eagle Nebula",        DsoType.NEBULA,            274.700, -13.808,  6.0, 35.0),
        Dso("M17", "Omega Nebula",        DsoType.NEBULA,            275.196, -16.171,  6.0, 11.0),
        Dso("M20", "Trifid Nebula",       DsoType.NEBULA,            270.625, -23.030,  6.3, 28.0),
        Dso("M22", "Sagittarius Cluster", DsoType.CLUSTER_GLOBULAR,  279.100, -23.905,  5.1, 32.0),
        Dso("M27", "Dumbbell Nebula",     DsoType.PLANETARY_NEBULA,  299.901,  22.721,  7.4,  8.0),
        Dso("M31", "Andromeda Galaxy",    DsoType.GALAXY,             10.685,  41.269,  3.4, 178.0),
        Dso("M33", "Triangulum Galaxy",   DsoType.GALAXY,             23.462,  30.660,  5.7, 70.0),
        Dso("M42", "Orion Nebula",        DsoType.NEBULA,             83.822,  -5.391,  4.0, 85.0),
        Dso("M45", "Pleiades",            DsoType.CLUSTER_OPEN,       56.750,  24.117,  1.6, 110.0),
        Dso("M51", "Whirlpool Galaxy",    DsoType.GALAXY,            202.470,  47.195,  8.4, 11.0),
        Dso("M57", "Ring Nebula",         DsoType.PLANETARY_NEBULA,  283.396,  33.029,  8.8,  1.4),
        Dso("M63", "Sunflower Galaxy",    DsoType.GALAXY,            198.955,  42.029,  9.3, 12.0),
        Dso("M64", "Black Eye Galaxy",    DsoType.GALAXY,            194.182,  21.683,  8.5, 10.0),
        Dso("M65", "Leo Triplet (1/3)",   DsoType.GALAXY,            169.733,  13.092,  9.3,  8.0),
        Dso("M66", "Leo Triplet (2/3)",   DsoType.GALAXY,            170.062,  12.992,  8.9,  9.0),
        Dso("M78", "Reflection Nebula",   DsoType.NEBULA,             86.690,   0.078,  8.3,  8.0),
        Dso("M81", "Bode's Galaxy",       DsoType.GALAXY,            148.888,  69.066,  6.9, 26.0),
        Dso("M82", "Cigar Galaxy",        DsoType.GALAXY,            148.969,  69.679,  8.4, 11.0),
        Dso("M97", "Owl Nebula",          DsoType.PLANETARY_NEBULA,  168.699,  55.019,  9.9,  3.0),
        Dso("M101","Pinwheel Galaxy",     DsoType.GALAXY,            210.802,  54.349,  7.9, 29.0),
        Dso("M104","Sombrero Galaxy",     DsoType.GALAXY,            189.998, -11.623,  8.0,  9.0),

        // ── Popular NGC / IC nebulae ─────────────────────────────────
        Dso("NGC 281",   "Pacman Nebula",       DsoType.NEBULA,  13.075,  56.617,  7.4, 35.0),
        Dso("NGC 891",   "Silver Sliver Galaxy",DsoType.GALAXY,  35.640,  42.349, 10.0, 14.0),
        Dso("NGC 1499",  "California Nebula",   DsoType.NEBULA,  60.500,  36.417,  5.0,150.0),
        Dso("NGC 2174",  "Monkey Head Nebula",  DsoType.NEBULA,  91.700,  20.483,  6.8, 30.0),
        Dso("NGC 2237",  "Rosette Nebula",      DsoType.NEBULA,  97.965,   4.950,  9.0, 80.0),
        Dso("NGC 2392",  "Eskimo Nebula",       DsoType.PLANETARY_NEBULA, 112.292, 20.913,  9.2, 0.7),
        Dso("NGC 3372",  "Eta Carinae Nebula",  DsoType.NEBULA, 161.265, -59.867,  1.0,120.0),
        Dso("NGC 5128",  "Centaurus A",         DsoType.GALAXY, 201.365, -43.019,  6.8, 26.0),
        Dso("NGC 5907",  "Splinter Galaxy",     DsoType.GALAXY, 228.974,  56.328, 10.4, 13.0),
        Dso("NGC 6888",  "Crescent Nebula",     DsoType.NEBULA, 303.000,  38.342,  7.4, 20.0),
        Dso("NGC 6960",  "Veil Nebula (West)",  DsoType.SUPERNOVA_REMNANT, 312.700, 30.700, 7.0, 180.0),
        Dso("NGC 6992",  "Veil Nebula (East)",  DsoType.SUPERNOVA_REMNANT, 313.300, 31.700, 7.0, 60.0),
        Dso("NGC 7000",  "North America Nebula",DsoType.NEBULA, 314.750,  44.333,  4.0,120.0),
        Dso("NGC 7293",  "Helix Nebula",        DsoType.PLANETARY_NEBULA, 337.411, -20.837, 7.3, 25.0),
        Dso("NGC 7635",  "Bubble Nebula",       DsoType.NEBULA, 350.200,  61.200, 11.0,15.0),
        Dso("IC 405",    "Flaming Star Nebula", DsoType.NEBULA,  79.083,  34.367,  6.0, 37.0),
        Dso("IC 410",    "Tadpoles Nebula",     DsoType.NEBULA,  80.717,  33.500,  7.5, 40.0),
        Dso("IC 434",    "Horsehead Nebula",    DsoType.NEBULA,  85.300,  -2.450,  6.8,  8.0),
        Dso("IC 1396",   "Elephant's Trunk",    DsoType.NEBULA, 324.667,  57.500,  3.5,170.0),
        Dso("IC 1805",   "Heart Nebula",        DsoType.NEBULA,  38.000,  61.500,  6.5,150.0),
        Dso("IC 1848",   "Soul Nebula",         DsoType.NEBULA,  43.450,  60.450,  6.5,100.0),
    )
}
