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

/** The app's visual identity, user-switchable and persisted. CIRCUIT is the
 *  printed-circuit-board look; CLASSIC is the original card-based SIGNAL look
 *  (SPACE — pulsar + orbits + starfield — is planned as a third). Distinct
 *  from [ThemeMode], which only swaps the colour palette. */
enum class VisualStyle { CIRCUIT, CLASSIC, SPACE }

// ── SIGNAL palette ──────────────────────────────────────────────────────
// Carbon surfaces, one electric accent. The violet→magenta gradient is
// reserved for LIVE elements (a running flow, an armed control); idle UI
// stays calm monochrome. See PulsarColors for the per-mode role ramps.
val PulsarViolet = Color(0xFFB15CFF)
val PulsarMagenta = Color(0xFFFF4FA3)
val PulsarDark = Color(0xFF0C0C0F)        // carbon, not Material gray
val PulsarSurface = Color(0xFF131318)
val PulsarOnSurface = Color(0xFFECECF1)
val PulsarSecondary = Color(0xFF1C1C24)

// "Chart Paper" — black traces on warm paper, like a pen plotter. NOT a
// white SaaS app: the background is cream, the primary is deep violet.
val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6A30D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DBFB),
    onPrimaryContainer = Color(0xFF26104A),
    secondary = Color(0xFFC8327E),
    onSecondary = Color.White,
    background = Color(0xFFF7F4EE),
    surface = Color(0xFFF7F4EE),
    onBackground = Color(0xFF17171C),
    onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFEAE5DA),
    onSurfaceVariant = Color(0xFF4A4A52),
    surfaceContainerHigh = Color(0xFFEFEBE2),
    surfaceContainerHighest = Color(0xFFE8E3D8),
    outline = Color(0xFF74747E),
)

// "Carbon" — the default. Near-black with violet reserved for what's live.
val DarkColorScheme = darkColorScheme(
    primary = PulsarViolet,
    onPrimary = Color(0xFF1C0A33),
    primaryContainer = Color(0xFF2A1247),
    onPrimaryContainer = Color(0xFFE4CCFF),
    secondary = PulsarMagenta,
    onSecondary = Color(0xFF330D20),
    secondaryContainer = Color(0xFF2A1430),
    onSecondaryContainer = Color(0xFFFFD6E8),
    background = PulsarDark,
    surface = PulsarSurface,
    onBackground = PulsarOnSurface,
    onSurface = PulsarOnSurface,
    surfaceVariant = PulsarSecondary,
    onSurfaceVariant = Color(0xFF9C9CAC),
    surfaceContainerHigh = Color(0xFF1C1C24),
    surfaceContainerHighest = Color(0xFF24242E),
    outline = Color(0xFF3A3A46),
    error = Color(0xFFFF5C5C),
)

// ── Outdoor / high-contrast mode ───────────────────────────────────────────
// Maximum perceived contrast for daylight readability. Pure white surfaces,
// pure-ish black text, saturated accents — survives the brightness ceiling
// of cheap phones under direct sun.

val OutdoorColorScheme = lightColorScheme(
    primary = Color(0xFF4B1FB8),          // deep violet, 8:1 on white — brand at full sun
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3D8FF),
    onPrimaryContainer = Color(0xFF1C0A4D),
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

/** Global visual style — survives recomposition; loaded/persisted by MainActivity.
 *  Defaults to SPACE (the showcase identity). */
val LocalVisualStyle = compositionLocalOf { mutableStateOf(VisualStyle.SPACE) }

// ── Space style — planet hues ───────────────────────────────────────────────
// The SPACE visual style is the ONE place we step outside the single-accent
// SIGNAL discipline: each transport is a distinctly-coloured world. Literals
// live here in the theme (never in screens). Everything else in Space (stars,
// nebula, the pulsar, orbits) stays on the role palette. In RedLight night
// mode these are red-shifted at the draw site to preserve dark adaptation.
val PlanetAmber = Color(0xFFE2A23A)   // Canon CCAPI — ringed gas giant
val PlanetSlate = Color(0xFF7C90C4)   // Canon PTP-IP — banded blue-grey
val PlanetCopper = Color(0xFFC9743F)  // USB-PTP — rocky, close-in
val PlanetAzure = Color(0xFF4F9DF2)   // Pulsar BLE (ESP32) — your own world
val PlanetRose = Color(0xFFD9648F)    // Canon direct BLE — small moon
val PlanetMint = Color(0xFF49C9A8)    // Simulator — ghost/wireframe world

// ── Status / semantic colors (shared across all themes) ────────────────────
val StatusGreen = Color(0xFF4CAF50)
val StatusOrange = Color(0xFFFFA726)
val StatusRed = Color(0xFFFF1744)
val StatusOff = Color(0xFF3A3A3A)

// Caution palette — used by WizardWarning. Yellow signals "you can still
// proceed, but heads-up" (e.g. interval-too-short, sub-second bulb),
// distinct from error red which would imply a hard blocker.
val WarningContainer = Color(0xFFFFF1C2)   // light cream-yellow surface
val OnWarningContainer = Color(0xFF6E4F00) // dark amber for icon + text
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
