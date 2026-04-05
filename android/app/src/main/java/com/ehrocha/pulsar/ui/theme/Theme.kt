/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

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
