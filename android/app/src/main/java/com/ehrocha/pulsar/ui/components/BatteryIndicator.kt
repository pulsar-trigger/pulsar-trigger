/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceLatency
import com.ehrocha.pulsar.ui.theme.LocalDeviceRssi
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.LocalNightModeLocked
import com.ehrocha.pulsar.ui.theme.StatusGreen
import com.ehrocha.pulsar.ui.theme.StatusOrange
import com.ehrocha.pulsar.ui.theme.StatusOff
import com.ehrocha.pulsar.ui.theme.StatusRed
import com.ehrocha.pulsar.ui.theme.ThemeMode

private val LedIdle = StatusGreen
private val LedRunning = StatusRed
private val LedWaiting = StatusOrange
private val LedError = StatusRed
private val LedOff = StatusOff

@Composable
private fun StateLed(label: String, color: Color, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.size(width = 14.dp, height = 6.dp)) {
            drawRoundRect(
                color = if (active) color else LedOff,
                cornerRadius = CornerRadius(3.dp.toPx()),
                size = Size(size.width, size.height),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 7.sp,
            color = if (active) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            lineHeight = 8.sp,
        )
    }
}

@Composable
fun BatteryIndicator() {
    val status = LocalDeviceStatus.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StateLed("IDL", LedIdle, status?.state == DeviceState.IDLE)
            StateLed("RUN", LedRunning, status?.state == DeviceState.RUNNING)
            StateLed("WAI", LedWaiting, status?.state == DeviceState.WAITING)
            StateLed("ERR", LedError, status?.state == DeviceState.ERROR)
            Spacer(Modifier.width(4.dp))
            val battText = if (status != null) "${status.batteryPct}%" else "—"
            Text(
                text = battText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = when {
                    status == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    status.batteryPct < 20 -> StatusRed
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            val battIcon = when {
                status == null -> "󰁺"
                status.batteryPct > 75 -> "󰁹"
                status.batteryPct > 25 -> "󰁾"
                else -> "󰁺"
            }
            Text(
                text = battIcon,
                fontSize = 16.sp,
                color = if (status == null)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                else Color.Unspecified,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NightModeToggle() {
    val nightMode = LocalNightMode.current
    val locked = LocalNightModeLocked.current

    Box(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                role = Role.Button,
                onClick = {
                    if (!locked.value) {
                        nightMode.value = when (nightMode.value) {
                            ThemeMode.Light -> ThemeMode.Outdoor
                            ThemeMode.Outdoor -> ThemeMode.Dark
                            ThemeMode.Dark -> ThemeMode.RedLight
                            ThemeMode.RedLight -> ThemeMode.Light
                        }
                    }
                },
                onLongClick = {
                    locked.value = !locked.value
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            when (nightMode.value) {
                ThemeMode.Light -> Icons.Default.LightMode
                ThemeMode.Outdoor -> Icons.Default.WbSunny
                ThemeMode.Dark -> Icons.Default.Nightlight
                ThemeMode.RedLight -> Icons.Default.Nightlight
            },
            contentDescription = stringResource(
                if (locked.value) R.string.night_mode_locked else R.string.night_mode_toggle,
            ),
            modifier = Modifier.size(20.dp),
            tint = when (nightMode.value) {
                ThemeMode.Light -> MaterialTheme.colorScheme.onSurfaceVariant
                ThemeMode.Outdoor -> MaterialTheme.colorScheme.primary
                ThemeMode.Dark -> MaterialTheme.colorScheme.onSurfaceVariant
                ThemeMode.RedLight -> Color(0xFFCC4444)
            },
        )
        // Lock badge
        if (locked.value) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── Signal Strength Indicator ─────────────────────────────────────────────

private val SignalGood = StatusGreen
private val SignalMedium = StatusOrange
private val SignalWeak = StatusRed
private val SignalOff = StatusOff

/**
 * Compact BLE signal strength indicator showing 3 bars sized S/M/L.
 * Colour reflects the current RSSI against [AppConfig] thresholds.
 * Hidden when not connected.
 */
@Composable
fun SignalStrengthIndicator() {
    val connected = LocalDeviceConnected.current
    if (!connected) return

    val rssi = LocalDeviceRssi.current
    val bars = when {
        rssi == null -> 0
        rssi >= AppConfig.BLE_RSSI_GOOD -> 3
        rssi >= AppConfig.BLE_RSSI_WEAK -> 2
        else -> 1
    }
    val color = when (bars) {
        3 -> SignalGood
        2 -> SignalMedium
        1 -> SignalWeak
        else -> SignalOff
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        val barHeights = listOf(6.dp, 10.dp, 14.dp)
        barHeights.forEachIndexed { index, height ->
            Canvas(modifier = Modifier.size(width = 4.dp, height = height)) {
                drawRoundRect(
                    color = if (index < bars) color else SignalOff,
                    cornerRadius = CornerRadius(1.dp.toPx()),
                    size = Size(size.width, size.height),
                )
            }
        }
    }
}

/**
 * Compact BLE command round-trip latency readout in ms.
 * Hidden when not connected or before the first ACK is received.
 */
@Composable
fun LatencyIndicator() {
    val connected = LocalDeviceConnected.current
    if (!connected) return
    val ms = LocalDeviceLatency.current ?: return

    val color = when {
        ms < 80 -> StatusGreen
        ms < 200 -> StatusOrange
        else -> StatusRed
    }
    Text(
        text = "${ms}ms",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        color = color,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
