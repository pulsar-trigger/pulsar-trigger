/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ble.StatusFrame

@Composable
fun BatteryIndicator(status: StatusFrame?) {
    if (status != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
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
