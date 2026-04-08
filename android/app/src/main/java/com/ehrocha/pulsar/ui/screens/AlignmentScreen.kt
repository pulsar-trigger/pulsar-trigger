/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.view.WindowManager
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.viewmodel.AlignmentViewModel
import kotlin.math.abs
import kotlin.math.min

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

    // Acquire location on entry
    LaunchedEffect(Unit) {
        alignVm.acquireLocation()
    }

    // Keep screen awake while alignment is active
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val altError = pitch - targetAlt
    val rawAzError = shortestAngle(trueAz, targetAz)

    var step by remember { mutableIntStateOf(1) }

    val isSouthern = latitude < 0
    val poleLabel = if (isSouthern) {
        stringResource(R.string.alignment_true_south)
    } else {
        stringResource(R.string.alignment_true_north)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                )
            }
            Text(
                stringResource(R.string.alignment_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Setup instructions (step 1 only) ─────────────────────
            if (step == 1) {
                SetupCard()
                Spacer(Modifier.height(8.dp))
            }

            // ── Location row ─────────────────────────────────────────
            LocationCard(
                locationReady = locationReady,
                latitude = latitude,
                declination = declination,
                poleLabel = poleLabel,
                onRefresh = { alignVm.acquireLocation() },
            )

            Spacer(Modifier.height(12.dp))

            // ── Step header ──────────────────────────────────────────
            StepHeader(step = step, poleLabel = poleLabel)

            Spacer(Modifier.height(8.dp))

            // ── Altitude locked chip (step 2) ────────────────────────
            if (step == 2) {
                AltitudeLockedChip(
                    pitch = pitch,
                    altError = altError,
                    active = sensorsActive,
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Crosshair indicator ──────────────────────────────────
            CrosshairIndicator(
                altError = altError,
                azError = rawAzError,
                active = sensorsActive,
                step = step,
            )

            // ── Magnetic interference hint ───────────────────────────
            MagnetWarning()

            Spacer(Modifier.height(16.dp))

            // ── Numeric readouts (active axis only) ──────────────────
            ReadoutSection(
                pitch = pitch,
                targetAlt = targetAlt,
                altError = altError,
                trueAz = trueAz,
                targetAz = targetAz,
                azError = rawAzError,
                poleLabel = poleLabel,
                active = sensorsActive,
                step = step,
            )

            Spacer(Modifier.height(16.dp))

            // ── Step navigation ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (step == 2) {
                    OutlinedButton(onClick = { step = 1 }) {
                        Text(stringResource(R.string.back))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (step == 1) {
                    Button(onClick = { step = 2 }) {
                        Text(stringResource(R.string.alignment_next))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Location Card ────────────────────────────────────────────────────────

@Composable
private fun LocationCard(
    locationReady: Boolean,
    latitude: Double,
    declination: Float,
    poleLabel: String,
    onRefresh: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = null,
                tint = if (locationReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (locationReady) {
                    Text(
                        stringResource(
                            R.string.alignment_location_info,
                            "%.2f".format(latitude),
                            "%.1f".format(declination),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.alignment_target_pole, poleLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.alignment_no_location),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.alignment_refresh))
            }
        }
    }
}

// ── Crosshair Indicator ──────────────────────────────────────────────────

@Composable
private fun CrosshairIndicator(
    altError: Float,
    azError: Float,
    active: Boolean,
    step: Int,
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error
    val good = Color(0xFF4CAF50)

    // Clamp errors to ±30° for display; full range = 60°
    val maxAngle = 30f
    val clampedAlt = altError.coerceIn(-maxAngle, maxAngle)
    val clampedAz = azError.coerceIn(-maxAngle, maxAngle)

    // Color dot based on active axis error only
    val relevantError = if (step == 1) abs(altError) else abs(azError)
    val dotColor = when {
        !active -> outline
        relevantError < 1f -> good
        relevantError < 5f -> primary
        else -> error
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .padding(16.dp),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy)

        // ── Concentric rings (10° increments) ────────────────────
        for (i in 1..3) {
            val r = radius * i / 3f
            drawCircle(
                color = outline.copy(alpha = 0.3f),
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // ── Crosshair lines (active axis highlighted) ────────────
        drawLine(
            color = outline.copy(alpha = if (step == 2) 0.6f else 0.2f),
            start = Offset(cx - radius, cy),
            end = Offset(cx + radius, cy),
            strokeWidth = if (step == 2) 2.dp.toPx() else 1.dp.toPx(),
        )
        drawLine(
            color = outline.copy(alpha = if (step == 1) 0.6f else 0.2f),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius),
            strokeWidth = if (step == 1) 2.dp.toPx() else 1.dp.toPx(),
        )

        // ── Current position dot ─────────────────────────────────
        // Step 1: dot moves vertically only (altitude)
        // Step 2: dot moves horizontally only (azimuth)
        val dotX = if (step == 1) cx else cx + (clampedAz / maxAngle) * radius
        val dotY = if (step == 2) cy else cy - (clampedAlt / maxAngle) * radius

        drawCircle(
            color = dotColor,
            radius = 10.dp.toPx(),
            center = Offset(dotX, dotY),
        )

        // Inner white dot
        drawCircle(
            color = Color.White,
            radius = 3.dp.toPx(),
            center = Offset(dotX, dotY),
        )

        // ── Directional arrows ───────────────────────────────────
        // Show arrows on the active axis when error > 1° to guide adjustment
        val arrowSize = 12.dp.toPx()
        val arrowColor = dotColor.copy(alpha = 0.8f)
        val arrowOffset = radius + 20.dp.toPx() // just outside the outermost ring

        if (active && relevantError > 1f) {
            if (step == 1) {
                // Vertical arrows — altitude
                // Arrow points toward center: if dot is below center, arrow points up (tilt back more)
                val arrowY = if (clampedAlt < 0) cy - arrowOffset else cy + arrowOffset
                val arrowDir = if (clampedAlt < 0) -1f else 1f  // point toward center
                val path = Path().apply {
                    moveTo(cx, arrowY - arrowDir * arrowSize)
                    lineTo(cx - arrowSize * 0.6f, arrowY)
                    lineTo(cx + arrowSize * 0.6f, arrowY)
                    close()
                }
                drawPath(path, arrowColor)
            } else {
                // Horizontal arrows — azimuth
                val arrowX = if (clampedAz > 0) cx + arrowOffset else cx - arrowOffset
                val arrowDir = if (clampedAz > 0) 1f else -1f
                val path = Path().apply {
                    moveTo(arrowX - arrowDir * arrowSize, cy)
                    lineTo(arrowX, cy - arrowSize * 0.6f)
                    lineTo(arrowX, cy + arrowSize * 0.6f)
                    close()
                }
                drawPath(path, arrowColor)
            }
        }
    }
}

// ── Numeric Readouts ─────────────────────────────────────────────────────

@Composable
private fun ReadoutSection(
    pitch: Float,
    targetAlt: Float,
    altError: Float,
    trueAz: Float,
    targetAz: Float,
    azError: Float,
    poleLabel: String,
    active: Boolean,
    step: Int,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (step == 1) {
                // ── Altitude row ─────────────────────────────────
                Text(
                    stringResource(R.string.alignment_altitude),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    ReadoutValue(
                        label = stringResource(R.string.alignment_current),
                        value = if (active) "%.1f°".format(pitch) else "—",
                        modifier = Modifier.weight(1f),
                    )
                    ReadoutValue(
                        label = stringResource(R.string.alignment_target),
                        value = "%.1f°".format(targetAlt),
                        modifier = Modifier.weight(1f),
                    )
                    ReadoutValue(
                        label = stringResource(R.string.alignment_error),
                        value = if (active) "%+.1f°".format(altError) else "—",
                        modifier = Modifier.weight(1f),
                        valueColor = errorColor(altError, active),
                    )
                }
            } else {
                // ── Azimuth row ──────────────────────────────────
                Text(
                    stringResource(R.string.alignment_azimuth_label, poleLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    ReadoutValue(
                        label = stringResource(R.string.alignment_current),
                        value = "%.1f°".format(trueAz),
                        modifier = Modifier.weight(1f),
                    )
                    ReadoutValue(
                        label = stringResource(R.string.alignment_target),
                        value = "%.0f°".format(targetAz),
                        modifier = Modifier.weight(1f),
                    )
                    ReadoutValue(
                        label = stringResource(R.string.alignment_error),
                        value = "%+.1f°".format(azError),
                        modifier = Modifier.weight(1f),
                        valueColor = errorColor(azError, true),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadoutValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}

@Composable
private fun errorColor(error: Float, active: Boolean): Color {
    if (!active) return MaterialTheme.colorScheme.onSurfaceVariant
    val absErr = abs(error)
    return when {
        absErr < 1f -> Color(0xFF4CAF50)
        absErr < 5f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
}

// ── Setup Instructions ───────────────────────────────────────────────────

@Composable
private fun SetupCard() {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.alignment_setup_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.alignment_setup_step1),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.alignment_setup_step2),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.alignment_setup_step3),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// ── Step Header ──────────────────────────────────────────────────────────

@Composable
private fun StepHeader(step: Int, poleLabel: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            if (step == 1) stringResource(R.string.alignment_step_altitude)
            else stringResource(R.string.alignment_step_azimuth, poleLabel),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (step == 1) stringResource(R.string.alignment_step_alt_hint)
            else stringResource(R.string.alignment_step_az_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Altitude Locked Chip ─────────────────────────────────────────────────

@Composable
private fun AltitudeLockedChip(pitch: Float, altError: Float, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF4CAF50).copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (active) stringResource(
                    R.string.alignment_alt_locked,
                    "%.1f".format(pitch),
                    "%+.1f".format(altError),
                ) else "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── Magnetic Interference Warning ────────────────────────────────────────

@Composable
private fun MagnetWarning() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.alignment_magnet_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shortest signed angular distance (result in −180..180). */
private fun shortestAngle(from: Float, to: Float): Float {
    val d = (to - from).mod(360f)
    return if (d > 180f) d - 360f else d
}
