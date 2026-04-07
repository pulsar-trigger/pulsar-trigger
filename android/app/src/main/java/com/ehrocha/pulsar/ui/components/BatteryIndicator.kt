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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.ThemeMode

@Composable
fun BatteryIndicator() {
    val status = LocalDeviceStatus.current
    if (status != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                val ledColor = when (status.state) {
                    DeviceState.RUNNING, DeviceState.WAITING -> Color(0xFFFF1744)
                    DeviceState.ERROR -> Color(0xFFFFA000)
                    else -> Color(0xFF4CAF50)
                }
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = ledColor)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${status.batteryPct}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (status.batteryPct < 20) Color(0xFFFF1744)
                            else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                val battIcon = when {
                    status.batteryPct > 75 -> "󰁹"
                    status.batteryPct > 25 -> "󰁾"
                    else -> "󰁺"
                }
                Text(text = battIcon, fontSize = 16.sp)
            }
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
