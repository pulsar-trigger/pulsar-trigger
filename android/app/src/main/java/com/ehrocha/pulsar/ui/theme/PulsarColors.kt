/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour roles, resolved **per ThemeMode** — the design-system
 * keystone from the v0.411 UX audit. Screens must reference roles, never
 * `Color(0x…)` literals: a literal can't know that the user switched to
 * RedLight mode at the tripod.
 *
 * In [ThemeMode.RedLight] every role collapses onto a red/grey luminance
 * ramp — state is encoded by *brightness*, not hue (which is also the
 * colour-blind-safe encoding). Which mode is active is always the user's
 * choice; this type just makes every mode render faithfully.
 */
data class PulsarColors(
    // Generic status roles
    val positive: Color,
    /** Second-tier positive (e.g. a 2-of-3-star verdict). */
    val positiveMuted: Color,
    /** Unfavourable-but-not-an-error (cloudy night, moon up, backlit). */
    val negative: Color,
    val caution: Color,
    val critical: Color,
    val info: Color,
    // Night-sky data-vis ramp (twilight bars). Even data visualisation
    // follows the mode — a deep-blue gradient defeats RedLight.
    val skyTwilight: Color,
    val skyNautical: Color,
    val skyDark: Color,
    /** Selection highlight (map ring, selected-row border). */
    val selection: Color,
    /** Trail / path strokes on the map. */
    val trail: Color,
    // Banner surfaces (inline warnings: compass, GPS, …)
    val cautionContainer: Color,
    val onCautionContainer: Color,
    // Aircraft-Watch domain ramps
    val proximityNear: Color,
    val proximityMid: Color,
    val proximityFar: Color,
    val lightingGolden: Color,
    val lightingFront: Color,
    val lightingSide: Color,
    val lightingBack: Color,
    val lightingNight: Color,
    val badgeEmergency: Color,
    val badgeVintage: Color,
    val badgeMilitary: Color,
    val badgeHeavy: Color,
    /** Alpha used when a role colour becomes a chip/tint background. */
    val chipAlpha: Float = 0.18f,
)

/** Full-hue palette — Light + Dark modes. */
private val ChromaticPulsarColors = PulsarColors(
    positive = Color(0xFF2E7D32),
    positiveMuted = Color(0xFF558B2F),
    negative = Color(0xFFE65100),
    skyTwilight = Color(0xFF1A237E),
    skyNautical = Color(0xFF0D47A1),
    skyDark = Color(0xFF000033),
    caution = Color(0xFFF9A825),
    critical = Color(0xFFD32F2F),
    info = Color(0xFF5C6BC0),
    selection = Color(0xFFFFEB3B),
    trail = Color(0xFF00E5FF),
    cautionContainer = Color(0xFFFFF1C2),
    onCautionContainer = Color(0xFF6E4F00),
    proximityNear = Color(0xFF2E7D32),
    proximityMid = Color(0xFFF9A825),
    proximityFar = Color(0xFFE65100),
    lightingGolden = Color(0xFFFF8F00),
    lightingFront = Color(0xFF2E7D32),
    lightingSide = Color(0xFF5C6BC0),
    lightingBack = Color(0xFFD84315),
    lightingNight = Color(0xFF607D8B),
    badgeEmergency = Color(0xFFD32F2F),
    badgeVintage = Color(0xFF6D4C41),
    badgeMilitary = Color(0xFF455A64),
    badgeHeavy = Color(0xFF1565C0),
)

/** Outdoor: same hues, deepened for contrast under direct sunlight. */
private val OutdoorPulsarColors = ChromaticPulsarColors.copy(
    positive = Color(0xFF1B5E20),
    negative = Color(0xFFBF360C),
    caution = Color(0xFF8F6400),
    critical = Color(0xFFB00020),
    info = Color(0xFF283593),
    proximityNear = Color(0xFF1B5E20),
    proximityMid = Color(0xFF8F6400),
    proximityFar = Color(0xFFBF360C),
    chipAlpha = 0.26f,
)

/** RedLight: red/grey luminance ramp. Brighter = more relevant/urgent.
 *  No greens, yellows, blues — nothing that costs dark adaptation. */
private val RedPulsarColors = PulsarColors(
    positive = Color(0xFFCC4444),
    positiveMuted = Color(0xFFA83838),
    negative = Color(0xFF6E2424),
    skyTwilight = Color(0xFF2A0C0C),
    skyNautical = Color(0xFF1C0606),
    skyDark = Color(0xFF0E0202),
    caution = Color(0xFF993333),
    critical = Color(0xFFFF4444),
    info = Color(0xFF7A2A2A),
    selection = Color(0xFFE05050),
    trail = Color(0xFFB03838),
    cautionContainer = Color(0xFF2A0A0A),
    onCautionContainer = Color(0xFFCC4444),
    proximityNear = Color(0xFFD04040),
    proximityMid = Color(0xFF8F2C2C),
    proximityFar = Color(0xFF5A1F1F),
    lightingGolden = Color(0xFFE05050),
    lightingFront = Color(0xFFC04040),
    lightingSide = Color(0xFF8F2C2C),
    lightingBack = Color(0xFF6E2424),
    lightingNight = Color(0xFF4A1818),
    badgeEmergency = Color(0xFFFF4444),
    badgeVintage = Color(0xFF8F2C2C),
    badgeMilitary = Color(0xFF7A2A2A),
    badgeHeavy = Color(0xFFA03030),
    chipAlpha = 0.22f,
)

fun pulsarColorsFor(mode: ThemeMode): PulsarColors = when (mode) {
    ThemeMode.Light, ThemeMode.Dark -> ChromaticPulsarColors
    ThemeMode.Outdoor -> OutdoorPulsarColors
    ThemeMode.RedLight -> RedPulsarColors
}

val LocalPulsarColors = staticCompositionLocalOf { ChromaticPulsarColors }

/** `PulsarTheme.colors.positive` — the only sanctioned way for screens to
 *  obtain a semantic colour. */
object PulsarTheme {
    val colors: PulsarColors
        @Composable @ReadOnlyComposable get() = LocalPulsarColors.current
}
