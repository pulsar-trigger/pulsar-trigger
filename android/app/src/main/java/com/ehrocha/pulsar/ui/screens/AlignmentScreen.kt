/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.viewmodel.AlignmentViewModel
import kotlin.math.abs
import kotlin.math.min

// ── Night-mode palette (deep red on black) ───────────────────────────────
private val NightBg = Color.Black
private val NightDim = Color(0xFF661111)       // dim red for outlines/labels
private val NightText = Color(0xFFCC3333)       // medium red for text
private val NightBright = Color(0xFFFF4444)     // bright red for values/icons
private val NightGood = Color(0xFF33FF33)       // bright green when aligned
private val NightSurface = Color(0xFF0D0000)    // near-black card surface

@Composable
fun AlignmentScreen(
    onBack: () -> Unit,
    alignVm: AlignmentViewModel = viewModel(),
) {
    val pitch by alignVm.pitch.collectAsState()
    val sensorsActive by alignVm.sensorsActive.collectAsState()
    val trueAz by alignVm.trueAzimuth.collectAsState()
    val latitude by alignVm.latitude.collectAsState()
    val declination by alignVm.declination.collectAsState()
    val targetAlt by alignVm.targetAltitude.collectAsState()
    val targetAz by alignVm.targetAzimuth.collectAsState()
    val locationReady by alignVm.locationReady.collectAsState()
    val roll by alignVm.roll.collectAsState()

    val haptic = LocalHapticFeedback.current

    // Acquire location on entry
    LaunchedEffect(Unit) { alignVm.acquireLocation() }

    // Keep screen awake
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val altError = pitch - targetAlt
    val azError = shortestAngle(trueAz, targetAz)

    // Haptic: fire once when ALL three axes enter ±0.5° tolerance
    val threshold = 0.5f
    val allAligned = sensorsActive &&
        abs(altError) < threshold &&
        abs(azError) < threshold &&
        abs(roll) < threshold
    var wasAligned by remember { mutableStateOf(false) }
    LaunchedEffect(allAligned) {
        if (allAligned && !wasAligned) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasAligned = allAligned
    }

    val isSouthern = latitude < 0
    val poleLabel = if (isSouthern) {
        stringResource(R.string.alignment_true_south)
    } else {
        stringResource(R.string.alignment_true_north)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightBg)
            .padding(horizontal = 16.dp),
    ) {
        // ── Top bar ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = NightText,
                )
            }
            Text(
                stringResource(R.string.alignment_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = NightBright,
                modifier = Modifier.weight(1f),
            )
        }

        // ── Location row ─────────────────────────────────────────────
        LocationRow(
            locationReady = locationReady,
            latitude = latitude,
            declination = declination,
            poleLabel = poleLabel,
            onRefresh = { alignVm.acquireLocation() },
        )

        Spacer(Modifier.height(8.dp))

        // ── Bullseye reticle (fills available space) ─────────────────
        BullseyeReticle(
            altError = altError,
            azError = azError,
            roll = roll,
            active = sensorsActive,
            allAligned = allAligned,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // ── Readout panel ────────────────────────────────────────────
        ReadoutPanel(
            pitch = pitch,
            targetAlt = targetAlt,
            altError = altError,
            trueAz = trueAz,
            targetAz = targetAz,
            azError = azError,
            roll = roll,
            poleLabel = poleLabel,
            active = sensorsActive,
            allAligned = allAligned,
        )

        Spacer(Modifier.height(12.dp))
    }
}

// ── Location Row ─────────────────────────────────────────────────────────

@Composable
private fun LocationRow(
    locationReady: Boolean,
    latitude: Double,
    declination: Float,
    poleLabel: String,
    onRefresh: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Default.MyLocation,
            contentDescription = null,
            tint = if (locationReady) NightBright else NightDim,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (locationReady) {
            Text(
                stringResource(
                    R.string.alignment_location_info,
                    "%.2f".format(latitude),
                    "%.1f".format(declination),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NightText,
                modifier = Modifier.weight(1f),
            )
            Text(
                poleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = NightDim,
            )
        } else {
            Text(
                stringResource(R.string.alignment_no_location),
                style = MaterialTheme.typography.bodySmall,
                color = NightDim,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) {
                Text(
                    stringResource(R.string.alignment_refresh),
                    color = NightBright,
                )
            }
        }
    }
}

// ── Bullseye Reticle ─────────────────────────────────────────────────────

@Composable
private fun BullseyeReticle(
    altError: Float,
    azError: Float,
    roll: Float,
    active: Boolean,
    allAligned: Boolean,
    modifier: Modifier = Modifier,
) {
    // Colors switch to green when fully aligned
    val reticleColor = if (allAligned) NightGood else NightDim
    val bubbleColor = if (allAligned) NightGood else NightBright

    // Clamp errors to ±30° for display; full range = 60°
    val maxAngle = 30f
    val clampedAlt = altError.coerceIn(-maxAngle, maxAngle)
    val clampedAz = azError.coerceIn(-maxAngle, maxAngle)

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .padding(8.dp),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy)

        // ── Concentric rings (10° increments) ────────────────────
        for (i in 1..3) {
            val r = radius * i / 3f
            drawCircle(
                color = reticleColor.copy(alpha = 0.4f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // ── Crosshair lines ──────────────────────────────────────
        drawLine(
            color = reticleColor.copy(alpha = 0.5f),
            start = Offset(cx - radius, cy),
            end = Offset(cx + radius, cy),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = reticleColor.copy(alpha = 0.5f),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius),
            strokeWidth = 1.dp.toPx(),
        )

        // ── Center target dot (the "bullseye") ──────────────────
        drawCircle(
            color = reticleColor,
            radius = 4.dp.toPx(),
            center = Offset(cx, cy),
        )

        // ── Moving bubble (current orientation) ──────────────────
        if (active) {
            val dotX = cx + (clampedAz / maxAngle) * radius
            val dotY = cy - (clampedAlt / maxAngle) * radius

            // Outer bubble
            drawCircle(
                color = bubbleColor,
                radius = 12.dp.toPx(),
                center = Offset(dotX, dotY),
            )
            // Inner contrast dot
            drawCircle(
                color = NightBg,
                radius = 4.dp.toPx(),
                center = Offset(dotX, dotY),
            )
        }

        // ── Roll indicator bar ───────────────────────────────────
        // A short horizontal bar at the bottom of the reticle that
        // tilts to show roll angle
        val rollBarHalf = radius * 0.3f
        val rollRad = Math.toRadians(roll.coerceIn(-30f, 30f).toDouble())
        val rollY = cy + radius + 16.dp.toPx()
        val rollDx = (rollBarHalf * Math.cos(rollRad)).toFloat()
        val rollDy = (rollBarHalf * Math.sin(rollRad)).toFloat()

        val rollColor = when {
            allAligned -> NightGood
            abs(roll) < 1f -> NightGood
            abs(roll) < 5f -> NightBright
            else -> Color(0xFFFF6666)
        }

        drawLine(
            color = rollColor,
            start = Offset(cx - rollDx, rollY + rollDy),
            end = Offset(cx + rollDx, rollY - rollDy),
            strokeWidth = 3.dp.toPx(),
        )
        // Center tick mark
        drawCircle(
            color = rollColor,
            radius = 3.dp.toPx(),
            center = Offset(cx, rollY),
        )
    }
}

// ── Readout Panel ────────────────────────────────────────────────────────

@Composable
private fun ReadoutPanel(
    pitch: Float,
    targetAlt: Float,
    altError: Float,
    trueAz: Float,
    targetAz: Float,
    azError: Float,
    roll: Float,
    poleLabel: String,
    active: Boolean,
    allAligned: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Altitude row
        ReadoutRow(
            label = stringResource(R.string.alignment_altitude),
            current = if (active) "%.1f°".format(pitch) else "—",
            target = "%.1f°".format(targetAlt),
            error = if (active) "%+.1f°".format(altError) else "—",
            errorValue = altError,
            active = active,
            allAligned = allAligned,
        )
        // Azimuth row
        ReadoutRow(
            label = stringResource(R.string.alignment_azimuth_label, poleLabel),
            current = if (active) "%.1f°".format(trueAz) else "—",
            target = "%.0f°".format(targetAz),
            error = if (active) "%+.1f°".format(azError) else "—",
            errorValue = azError,
            active = active,
            allAligned = allAligned,
        )
        // Roll row
        ReadoutRow(
            label = stringResource(R.string.alignment_roll_label),
            current = if (active) "%+.1f°".format(roll) else "—",
            target = "0.0°",
            error = if (active) "%+.1f°".format(roll) else "—",
            errorValue = roll,
            active = active,
            allAligned = allAligned,
        )
    }
}

@Composable
private fun ReadoutRow(
    label: String,
    current: String,
    target: String,
    error: String,
    errorValue: Float,
    active: Boolean,
    allAligned: Boolean,
) {
    val errColor = when {
        allAligned -> NightGood
        !active -> NightDim
        abs(errorValue) < 0.5f -> NightGood
        abs(errorValue) < 5f -> NightBright
        else -> Color(0xFFFF6666)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = NightDim,
            modifier = Modifier.weight(1.2f),
        )
        Text(
            current,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = NightText,
            modifier = Modifier.weight(1f),
        )
        Text(
            target,
            style = MaterialTheme.typography.bodySmall,
            color = NightDim,
            modifier = Modifier.weight(0.8f),
        )
        Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = errColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Shortest signed angular distance (result in −180..180). */
private fun shortestAngle(from: Float, to: Float): Float {
    val d = (to - from).mod(360f)
    return if (d > 180f) d - 360f else d
}
