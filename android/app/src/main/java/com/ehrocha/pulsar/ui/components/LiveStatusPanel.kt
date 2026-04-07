/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ble.TriggerMode
import java.text.DateFormat
import java.util.Date

@Composable
fun LiveStatusPanel(
    connected: Boolean,
    status: StatusFrame?,
    currentMode: TriggerMode,
    deviceName: String = "Pulsar",
    rssi: Int? = null,
    modifier: Modifier = Modifier,
) {
    val stateColor by animateColorAsState(
        targetValue = when {
            !connected -> Color(0xFF666666)
            status?.state == DeviceState.RUNNING -> Color(0xFF00E676)
            status?.state == DeviceState.WAITING -> Color(0xFFFFD600)
            status?.state == DeviceState.ERROR -> Color(0xFFFF1744)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor",
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        tonalElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color.Transparent
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Status Indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor),
                )
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (!connected) stringResource(R.string.status_system_offline) else deviceName.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = when {
                            !connected -> stringResource(R.string.status_disconnected)
                            status == null -> stringResource(R.string.status_syncing)
                            else -> status.state.name
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Battery Badge
                if (status != null) {
                    // Signal strength mini-bars
                    if (connected && rssi != null) {
                        val bars = when {
                            rssi >= AppConfig.BLE_RSSI_GOOD -> 3
                            rssi >= AppConfig.BLE_RSSI_WEAK -> 2
                            else -> 1
                        }
                        val sigColor = when (bars) {
                            3 -> Color(0xFF4CAF50)
                            2 -> Color(0xFFFFA726)
                            else -> Color(0xFFFF1744)
                        }
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                            modifier = Modifier.padding(end = 6.dp),
                        ) {
                            listOf(5.dp, 8.dp, 11.dp).forEachIndexed { i, h ->
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(h)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(if (i < bars) sigColor else Color(0xFF3A3A3A)),
                                )
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${status.batteryPct}%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (status.batteryPct < 20) Color(0xFFFF1744) else MaterialTheme.colorScheme.onSurface
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

            if (status != null) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                Spacer(Modifier.height(20.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusStat(stringResource(R.string.stat_mode), currentMode.name.replace('_', ' '))
                    StatusStat(stringResource(R.string.stat_captured), "${status.shotsTaken}")
                    if (status.timeRemainingMs > 0) {
                        StatusStat(stringResource(R.string.stat_est_remaining), formatTimeRemaining(status.timeRemainingMs))
                        val finishTime = remember(status.timeRemainingMs) {
                            DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(Date(System.currentTimeMillis() + status.timeRemainingMs))
                        }
                        StatusStat(stringResource(R.string.stat_finishes_at), finishTime)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatTimeRemaining(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}
