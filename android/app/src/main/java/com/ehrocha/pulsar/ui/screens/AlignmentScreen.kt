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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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

    val altError = pitch - targetAlt
    val rawAzError = shortestAngle(trueAz, targetAz)

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
            // ── Setup instructions ───────────────────────────────────
            SetupCard()

            Spacer(Modifier.height(8.dp))

            // ── Sensor status row ────────────────────────────────────
            SensorCard(sensorsActive = sensorsActive)

            Spacer(Modifier.height(8.dp))

            // ── Location row ─────────────────────────────────────────
            LocationCard(
                locationReady = locationReady,
                latitude = latitude,
                declination = declination,
                poleLabel = poleLabel,
                onRefresh = { alignVm.acquireLocation() },
            )

            Spacer(Modifier.height(16.dp))

            // ── Crosshair indicator ──────────────────────────────────
            CrosshairIndicator(
                altError = altError,
                azError = rawAzError,
                active = sensorsActive,
            )

            // ── Magnetic interference hint ───────────────────────────
            MagnetWarning()

            Spacer(Modifier.height(16.dp))

            // ── Numeric readouts ─────────────────────────────────────
            ReadoutSection(
                pitch = pitch,
                targetAlt = targetAlt,
                altError = altError,
                trueAz = trueAz,
                targetAz = targetAz,
                azError = rawAzError,
                poleLabel = poleLabel,
                active = sensorsActive,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Sensor Status Card ───────────────────────────────────────────────────

@Composable
private fun SensorCard(sensorsActive: Boolean) {
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
                Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = if (sensorsActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.alignment_sensors),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (sensorsActive) {
                        stringResource(R.string.alignment_sensors_active)
                    } else {
                        stringResource(R.string.alignment_sensors_waiting)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error
    val good = Color(0xFF4CAF50)

    // Clamp errors to ±30° for display; full range = 60°
    val maxAngle = 30f
    val clampedAlt = altError.coerceIn(-maxAngle, maxAngle)
    val clampedAz = azError.coerceIn(-maxAngle, maxAngle)

    val totalError = kotlin.math.sqrt(altError * altError + azError * azError)
    val dotColor = when {
        !active -> outline
        totalError < 1f -> good
        totalError < 5f -> primary
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

        // ── Crosshair lines ─────────────────────────────────────
        drawLine(
            color = outline.copy(alpha = 0.5f),
            start = Offset(cx - radius, cy),
            end = Offset(cx + radius, cy),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = outline.copy(alpha = 0.5f),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius),
            strokeWidth = 1.dp.toPx(),
        )

        // ── Current position dot ─────────────────────────────────
        // Map error to pixel offset: azError → X, altError → Y (inverted)
        val dotX = cx + (clampedAz / maxAngle) * radius
        val dotY = cy - (clampedAlt / maxAngle) * radius // up = positive altitude

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
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Altitude row ─────────────────────────────────────
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

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ── Azimuth row ──────────────────────────────────────
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
