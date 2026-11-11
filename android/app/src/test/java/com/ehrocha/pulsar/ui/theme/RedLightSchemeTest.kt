/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phosphor Red must stay on the red/near-black ramp in EVERY role (audit
 * D-H1): a role left undefined falls back to Material's baseline — the
 * near-white snackbars and purple containers this test exists to catch.
 */
class RedLightSchemeTest {

    private fun ColorScheme.roles(): Map<String, Color> = mapOf(
        "primary" to primary, "onPrimary" to onPrimary,
        "primaryContainer" to primaryContainer, "onPrimaryContainer" to onPrimaryContainer,
        "inversePrimary" to inversePrimary,
        "secondary" to secondary, "onSecondary" to onSecondary,
        "secondaryContainer" to secondaryContainer, "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary, "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer, "onTertiaryContainer" to onTertiaryContainer,
        "background" to background, "onBackground" to onBackground,
        "surface" to surface, "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant, "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to surfaceTint,
        "inverseSurface" to inverseSurface, "inverseOnSurface" to inverseOnSurface,
        "error" to error, "onError" to onError,
        "errorContainer" to errorContainer, "onErrorContainer" to onErrorContainer,
        "outline" to outline, "outlineVariant" to outlineVariant, "scrim" to scrim,
        "surfaceBright" to surfaceBright, "surfaceDim" to surfaceDim,
        "surfaceContainer" to surfaceContainer, "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceContainerLow" to surfaceContainerLow, "surfaceContainerLowest" to surfaceContainerLowest,
    )

    private fun PulsarColors.roles(): Map<String, Color> = mapOf(
        "positive" to positive, "positiveMuted" to positiveMuted, "negative" to negative,
        "caution" to caution, "critical" to critical, "info" to info,
        "cautionContainer" to cautionContainer, "onCautionContainer" to onCautionContainer,
        "liveStart" to liveStart, "liveEnd" to liveEnd,
        "statusGood" to statusGood, "statusWarn" to statusWarn, "statusBad" to statusBad,
        "statusOff" to statusOff, "exposing" to exposing, "waiting" to waiting,
        "onLive" to onLive, "flash" to flash,
    )

    /** Red channel dominates, green and blue stay dim: no white, no hue but red. */
    private fun assertRed(name: String, c: Color) {
        val g = c.green
        val b = c.blue
        assertTrue("$name $c is not on the red ramp", c.red >= g && c.red >= b && g <= 0.45f && b <= 0.45f)
    }

    @Test fun everyMaterialRoleIsRed() {
        RedLightColorScheme.roles().forEach { (n, c) -> assertRed(n, c) }
    }

    @Test fun everyPulsarRoleIsRed() {
        pulsarColorsFor(ThemeMode.RedLight).roles().forEach { (n, c) -> assertRed(n, c) }
    }

    /** Snackbars must not be a bright panel in the dark. */
    @Test fun snackbarSurfaceIsDim() {
        assertTrue(RedLightColorScheme.inverseSurface.red < 0.3f)
    }
}
