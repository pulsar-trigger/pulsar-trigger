/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.ThemeMode

private val LedIdle = Color(0xFF4CAF50)
private val LedRunning = Color(0xFFFF1744)
private val LedWaiting = Color(0xFFFFA726)
private val LedError = Color(0xFFFF1744)
private val LedOff = Color(0xFF3A3A3A)

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
                    status.batteryPct < 20 -> Color(0xFFFF1744)
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

@Composable
fun NightModeToggle() {
    val nightMode = LocalNightMode.current
    IconButton(onClick = {
        nightMode.value = when (nightMode.value) {
            ThemeMode.Light -> ThemeMode.Dark
            ThemeMode.Dark -> ThemeMode.RedLight
            ThemeMode.RedLight -> ThemeMode.Light
        }
    }) {
        Icon(
            when (nightMode.value) {
                ThemeMode.Light -> Icons.Default.LightMode
                ThemeMode.Dark -> Icons.Default.Nightlight
                ThemeMode.RedLight -> Icons.Default.Nightlight
            },
            contentDescription = stringResource(R.string.night_mode_toggle),
            modifier = Modifier.size(20.dp),
            tint = when (nightMode.value) {
                ThemeMode.Light -> MaterialTheme.colorScheme.onSurfaceVariant
                ThemeMode.Dark -> MaterialTheme.colorScheme.onSurfaceVariant
                ThemeMode.RedLight -> Color(0xFFCC4444)
            },
        )
    }
}
