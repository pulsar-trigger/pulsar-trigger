/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.ehrocha.pulsar.ble.StatusFrame

val PulsarViolet = Color(0xFFB15CFF)
val PulsarDark = Color(0xFF1C1B1F)
val PulsarSurface = Color(0xFF252429)
val PulsarOnSurface = Color(0xFFE6E1E5)
val PulsarSecondary = Color(0xFF353439)

val DarkColorScheme = darkColorScheme(
    primary = PulsarViolet,
    onPrimary = Color.White,
    background = PulsarDark,
    surface = PulsarSurface,
    onBackground = PulsarOnSurface,
    onSurface = PulsarOnSurface,
    surfaceVariant = PulsarSecondary,
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99)
)

// ── Red-light / Night mode ──────────────────────────────────────────────────
// Dim red tones to preserve night vision at the telescope.

private val RedDark = Color(0xFF120000)
private val RedSurface = Color(0xFF1A0000)
private val RedOnSurface = Color(0xFFCC4444)
private val RedSecondary = Color(0xFF220808)
private val RedAccent = Color(0xFFCC2222)

val RedLightColorScheme = darkColorScheme(
    primary = RedAccent,
    onPrimary = Color.White,
    background = RedDark,
    surface = RedSurface,
    onBackground = RedOnSurface,
    onSurface = RedOnSurface,
    surfaceVariant = RedSecondary,
    onSurfaceVariant = Color(0xFF993333),
    outline = Color(0xFF662222),
    error = Color(0xFFFF4444),
    secondaryContainer = Color(0xFF330808),
    onSecondaryContainer = Color(0xFFCC4444),
)

/** Global flag for night mode — survives recomposition. */
val LocalNightMode = compositionLocalOf { mutableStateOf(false) }

/** Global device status — available to every composable without parameter threading. */
val LocalDeviceStatus = compositionLocalOf<StatusFrame?> { null }

/** Global connection flag — true when a BLE device is connected. */
val LocalDeviceConnected = compositionLocalOf { false }
