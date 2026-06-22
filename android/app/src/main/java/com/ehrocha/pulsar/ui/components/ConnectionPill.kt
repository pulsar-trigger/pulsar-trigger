/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.StatusRed

/**
 * Compact connection status pill for a single top bar — the whole persistent
 * identity bar (device name + battery + signal + latency) collapses into this.
 * Shows a link glyph + battery% at a glance; tapping opens a menu with the
 * device name, signal/latency, and Disconnect (so the stray disconnect icon
 * gets a home). Reads battery/signal/latency from the device CompositionLocals.
 */
@Composable
fun ConnectionPill(
    deviceName: String,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LocalDeviceStatus.current
    var expanded by remember { mutableStateOf(false) }

    val unknown = status != null && status.batteryPct < 0
    val battText = if (status != null && !unknown) "${status.batteryPct}%" else "—"
    val battColor = when {
        status == null || unknown -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        status.batteryPct < 20 -> StatusRed
        else -> MaterialTheme.colorScheme.onSurface
    }
    val appName = stringResource(R.string.app_name)
    val shownName = deviceName.ifBlank { appName }

    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    battText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = battColor,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                shownName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SignalStrengthIndicator()
                LatencyIndicator()
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.disconnect)) },
                leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDisconnect()
                },
            )
        }
    }
}
