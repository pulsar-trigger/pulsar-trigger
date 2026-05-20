/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.ehrocha.pulsar.ble.StatusFrame

enum class ThemeMode { Light, Outdoor, Dark, RedLight }

val PulsarViolet = Color(0xFFB15CFF)
val PulsarDark = Color(0xFF1C1B1F)
val PulsarSurface = Color(0xFF252429)
val PulsarOnSurface = Color(0xFFE6E1E5)
val PulsarSecondary = Color(0xFF353439)

val LightColorScheme = lightColorScheme(
    primary = PulsarViolet,
    onPrimary = Color.White,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
)

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

// ── Outdoor / high-contrast mode ───────────────────────────────────────────
// Maximum perceived contrast for daylight readability. Pure white surfaces,
// pure-ish black text, saturated accents — survives the brightness ceiling
// of cheap phones under direct sun.

val OutdoorColorScheme = lightColorScheme(
    primary = Color(0xFF0050B0),          // saturated blue, visible at low brightness
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8FF),
    onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF005530),
    onSecondary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFF555555),
    error = Color(0xFFB00020),
    onError = Color.White,
    errorContainer = Color(0xFFFFD8D8),
    onErrorContainer = Color(0xFF400000),
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

/** Global theme mode — survives recomposition. */
val LocalNightMode = compositionLocalOf { mutableStateOf(ThemeMode.Dark) }

/** When true, night mode cannot be changed by a single tap — long-press to unlock. */
val LocalNightModeLocked = compositionLocalOf { mutableStateOf(false) }

// ── Status / semantic colors (shared across all themes) ────────────────────
val StatusGreen = Color(0xFF4CAF50)
val StatusOrange = Color(0xFFFFA726)
val StatusRed = Color(0xFFFF1744)
val StatusOff = Color(0xFF3A3A3A)
val ExposureGreen = Color(0xFF00E676)
val WaitingYellow = Color(0xFFFFD600)

// Planner verdict colors
val VerdictExcellent = Color(0xFF2E7D32)
val VerdictGood = Color(0xFF558B2F)
val VerdictFair = Color(0xFFF9A825)
val VerdictPoor = Color(0xFFE65100)

/** Global device status — available to every composable without parameter threading. */
val LocalDeviceStatus = compositionLocalOf<StatusFrame?> { null }

/** Canonical run state (Phase 3 of the refactor). Prefer this to
 *  [LocalDeviceStatus] + flowRunning/flowPaused/flowCurrentStep for new code. */
val LocalRunState = compositionLocalOf<com.ehrocha.pulsar.model.RunState> {
    com.ehrocha.pulsar.model.RunState.Idle
}

/** The FlowStep currently executing — null when idle. Read by RunningView to
 *  render mode-specific settings (exposure, interval, focal length) without
 *  each wizard passing them in. */
val LocalCurrentFlowStep = compositionLocalOf<com.ehrocha.pulsar.model.FlowStep?> { null }

/** Global connection flag — true when a BLE device is connected. */
val LocalDeviceConnected = compositionLocalOf { false }

/** Global BLE RSSI value — null when not connected or not yet polled. */
val LocalDeviceRssi = compositionLocalOf<Int?> { null }

/** Smoothed BLE command-to-ACK round-trip latency in ms. */
val LocalDeviceLatency = compositionLocalOf<Int?> { null }
