package com.ehrocha.pulsar.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val PulsarOrange = Color(0xFFFF6B00)
val PulsarDark = Color(0xFF1A1A2E)
val PulsarSurface = Color(0xFF16213E)
val PulsarOnSurface = Color(0xFFE0E0E0)

val DarkColorScheme = darkColorScheme(
    primary = PulsarOrange,
    onPrimary = Color.White,
    background = PulsarDark,
    surface = PulsarSurface,
    onBackground = PulsarOnSurface,
    onSurface = PulsarOnSurface,
)

val LightColorScheme = lightColorScheme(
    primary = PulsarOrange,
    onPrimary = Color.White,
)
