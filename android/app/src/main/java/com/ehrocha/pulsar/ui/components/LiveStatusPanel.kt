/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ble.TriggerMode

@Composable
fun LiveStatusPanel(
    connected: Boolean,
    status: StatusFrame?,
    currentMode: TriggerMode,
    modifier: Modifier = Modifier,
) {
    val stateColor by animateColorAsState(
        targetValue = when {
            !connected -> Color(0xFF666666)
            status?.state == DeviceState.RUNNING -> Color(0xFF4CAF50)
            status?.state == DeviceState.WAITING -> Color(0xFFFFC107)
            status?.state == DeviceState.ERROR -> Color(0xFFF44336)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor",
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top row: state indicator + mode name + battery
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // State dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(stateColor),
                )
                Spacer(Modifier.width(8.dp))

                // State label
                Text(
                    text = when {
                        !connected -> "Disconnected"
                        status == null -> "Connecting…"
                        else -> status.state.name
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )

                // Battery
                if (status != null) {
                    val battIcon = when {
                        status.batteryPct > 75 -> "🔋"
                        status.batteryPct > 25 -> "🪫"
                        else -> "⚠️"
                    }
                    Text(
                        text = "$battIcon ${status.batteryPct}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))

                // Stats row
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatItem("Mode", currentMode.name.replace('_', ' '))
                    StatItem("Shots", "${status.shotsTaken}")
                    if (status.timeRemainingMs > 0) {
                        StatItem("Remaining", formatTimeRemaining(status.timeRemainingMs))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
