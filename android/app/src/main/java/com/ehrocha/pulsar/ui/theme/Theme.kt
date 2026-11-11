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
enum class VisualStyle { CIRCUIT, CLASSIC, SPACE, GRID }

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

// Every Material 3 colour role is set in all four schemes (audit D-H1).
// A role left out falls back to Material's baseline palette — near-white
// snackbars, purple containers, pink tertiary, violet-grey cards and
// dialogs — which in Phosphor Red is a flashlight at the tripod. Each
// scheme derives its roles from its own palette only.

// "Chart Paper" — black traces on warm paper, like a pen plotter. NOT a
// white SaaS app: the background is cream, the primary is deep violet.
val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6A30D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DBFB),
    onPrimaryContainer = Color(0xFF26104A),
    inversePrimary = PulsarViolet,
    secondary = Color(0xFFC8327E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8DCE9),
    onSecondaryContainer = Color(0xFF3E0A26),
    // Tertiary pinned at Material's light baseline: the look it always had.
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFF7F4EE),
    onBackground = Color(0xFF17171C),
    surface = Color(0xFFF7F4EE),
    onSurface = Color(0xFF17171C),
    surfaceVariant = Color(0xFFEAE5DA),
    onSurfaceVariant = Color(0xFF4A4A52),
    surfaceTint = Color(0xFF6A30D9),
    inverseSurface = Color(0xFF2A2A30),
    inverseOnSurface = Color(0xFFF2EFE8),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF74747E),
    outlineVariant = Color(0xFFD6D0C4),
    scrim = Color.Black,
    surfaceBright = Color(0xFFF7F4EE),
    surfaceDim = Color(0xFFE3DED2),
    surfaceContainerLowest = Color(0xFFFDFBF7),
    surfaceContainerLow = Color(0xFFF4F1EA),
    surfaceContainer = Color(0xFFF2EEE6),
    surfaceContainerHigh = Color(0xFFEFEBE2),
    surfaceContainerHighest = Color(0xFFE8E3D8),
)

// "Carbon" — the default. Near-black with violet reserved for what's live.
val DarkColorScheme = darkColorScheme(
    primary = PulsarViolet,
    onPrimary = Color(0xFF1C0A33),
    primaryContainer = Color(0xFF2A1247),
    onPrimaryContainer = Color(0xFFE4CCFF),
    inversePrimary = Color(0xFF6A30D9),
    secondary = PulsarMagenta,
    onSecondary = Color(0xFF330D20),
    secondaryContainer = Color(0xFF2A1430),
    onSecondaryContainer = Color(0xFFFFD6E8),
    // Tertiary pinned at Material's dark baseline: the look it always had.
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = PulsarDark,
    onBackground = PulsarOnSurface,
    surface = PulsarSurface,
    onSurface = PulsarOnSurface,
    surfaceVariant = PulsarSecondary,
    onSurfaceVariant = Color(0xFF9C9CAC),
    surfaceTint = PulsarViolet,
    inverseSurface = PulsarOnSurface,
    inverseOnSurface = Color(0xFF1C1C24),
    error = Color(0xFFFF5C5C),
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFD7D4),
    outline = Color(0xFF3A3A46),
    outlineVariant = Color(0xFF2A2A34),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C2C37),
    surfaceDim = PulsarDark,
    surfaceContainerLowest = Color(0xFF08080A),
    surfaceContainerLow = Color(0xFF101014),
    surfaceContainer = Color(0xFF17171D),
    surfaceContainerHigh = Color(0xFF1C1C24),
    surfaceContainerHighest = Color(0xFF24242E),
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
    inversePrimary = Color(0xFFC9B5FF),
    secondary = Color(0xFF005530),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEBDB),
    onSecondaryContainer = Color(0xFF00210F),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF000000),
    surfaceTint = Color(0xFF4B1FB8),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFFFFFFF),
    error = Color(0xFFB00020),
    onError = Color.White,
    errorContainer = Color(0xFFFFD8D8),
    onErrorContainer = Color(0xFF400000),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFF555555),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8D8D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE0E0E0),
)

// ── Red-light / Night mode ──────────────────────────────────────────────────
// Dim red tones to preserve night vision at the telescope. EVERY role sits on
// one red/near-black luminance ramp — no white, no hue but red. Containers
// and inverse surfaces (snackbars) are dim reds a step brighter than the
// background; "on" colours on bright red fills are near-black, never white.

private val RedDark = Color(0xFF120000)
private val RedSurface = Color(0xFF1A0000)
private val RedOnSurface = Color(0xFFCC4444)
private val RedSecondary = Color(0xFF220808)
private val RedAccent = Color(0xFFCC2222)
private val RedInk = Color(0xFF0A0000)        // text/icons on a red fill

val RedLightColorScheme = darkColorScheme(
    primary = RedAccent,
    onPrimary = RedInk,
    primaryContainer = Color(0xFF3A0C0C),
    onPrimaryContainer = Color(0xFFE05050),
    inversePrimary = Color(0xFFE86060),
    secondary = Color(0xFFB03838),
    onSecondary = RedInk,
    secondaryContainer = Color(0xFF330808),
    onSecondaryContainer = RedOnSurface,
    tertiary = Color(0xFF993333),
    onTertiary = RedInk,
    tertiaryContainer = Color(0xFF2A0A0A),
    onTertiaryContainer = Color(0xFFB84040),
    background = RedDark,
    onBackground = RedOnSurface,
    surface = RedSurface,
    onSurface = RedOnSurface,
    surfaceVariant = RedSecondary,
    onSurfaceVariant = Color(0xFF993333),
    surfaceTint = RedAccent,
    inverseSurface = Color(0xFF3A0C0C),
    inverseOnSurface = Color(0xFFE05050),
    error = Color(0xFFFF4444),
    onError = RedInk,
    errorContainer = Color(0xFF4A0E0E),
    onErrorContainer = Color(0xFFFF6A6A),
    outline = Color(0xFF662222),
    outlineVariant = Color(0xFF401414),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2E0808),
    surfaceDim = RedDark,
    surfaceContainerLowest = Color(0xFF0E0000),
    surfaceContainerLow = Color(0xFF160000),
    surfaceContainer = Color(0xFF1E0404),
    surfaceContainerHigh = Color(0xFF260606),
    surfaceContainerHighest = Color(0xFF300A0A),
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

// Status, warning and verdict colours are PulsarColors roles, resolved per
// ThemeMode (statusGood/Warn/Bad/Off, exposing/waiting, cautionContainer,
// positive/positiveMuted/caution/negative). Shared constants here could not
// follow the user into RedLight mode (audit D-H1).

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

/** True while a Canon BLE run is waking the body and settling before the first
 *  frame (the wake nudge + settle window). The run screen shows a "Preparing…"
 *  state so the ~2 s gap before the first exposure doesn't read as a stall. */
val LocalPreparing = compositionLocalOf { false }

/** Global connection flag — true when a BLE device is connected. */
val LocalDeviceConnected = compositionLocalOf { false }

/** Global BLE RSSI value — null when not connected or not yet polled. */
val LocalDeviceRssi = compositionLocalOf<Int?> { null }

/** Smoothed BLE command-to-ACK round-trip latency in ms. */
val LocalDeviceLatency = compositionLocalOf<Int?> { null }
