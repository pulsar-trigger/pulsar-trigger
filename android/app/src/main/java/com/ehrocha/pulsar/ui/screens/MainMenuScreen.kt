/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

@Composable
fun MainMenuScreen(
    vm: PulsarViewModel,
    onModeSelected: (TriggerMode) -> Unit,
    onModeSettingsSelected: (TriggerMode) -> Unit,
    onSettingsSelected: () -> Unit,
    onCustomFlowSelected: () -> Unit = {},
    onDashboardSelected: () -> Unit = {},
) {
    val status by vm.status.collectAsState()
    val deviceName by vm.deviceName.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = deviceName.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
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
                            text = "${status!!.batteryPct}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (status!!.batteryPct < 20) Color(0xFFFF1744)
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(4.dp))
                        val battIcon = when {
                            status!!.batteryPct > 75 -> "󰁹"
                            status!!.batteryPct > 25 -> "󰁾"
                            else -> "󰁺"
                        }
                        Text(text = battIcon, fontSize = 16.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            stringResource(R.string.section_modes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(12.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MenuCard(
                title = stringResource(R.string.mode_intervalometer),
                description = stringResource(R.string.mode_intervalometer_desc),
                icon = Icons.Default.Timer,
                onClick = { onModeSelected(TriggerMode.INTERVALOMETER) },
                onGearClick = { onModeSettingsSelected(TriggerMode.INTERVALOMETER) },
            )
            MenuCard(
                title = stringResource(R.string.mode_astro),
                description = stringResource(R.string.mode_astro_desc),
                icon = Icons.Default.Stars,
                onClick = { onModeSelected(TriggerMode.ASTRO) },
                onGearClick = { onModeSettingsSelected(TriggerMode.ASTRO) },
            )
            MenuCard(
                title = stringResource(R.string.mode_manual),
                description = stringResource(R.string.mode_manual_desc),
                icon = Icons.Default.TouchApp,
                onClick = { onModeSelected(TriggerMode.PRESS_HOLD) },
                onGearClick = { onModeSettingsSelected(TriggerMode.PRESS_HOLD) },
            )
            MenuCard(
                title = stringResource(R.string.mode_custom_flow),
                description = stringResource(R.string.mode_custom_flow_desc),
                icon = Icons.AutoMirrored.Filled.ViewList,
                onClick = onCustomFlowSelected,
            )
            MenuCard(
                title = stringResource(R.string.mode_astro_dashboard),
                description = stringResource(R.string.mode_astro_dashboard_desc),
                icon = Icons.Default.NightsStay,
                onClick = onDashboardSelected,
            )
        }

        Spacer(Modifier.weight(1f))

        MenuCard(
            title = stringResource(R.string.menu_settings),
            description = stringResource(R.string.menu_settings_desc),
            icon = Icons.Default.Settings,
            onClick = onSettingsSelected,
        )

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = { vm.disconnect() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.disconnect)) }
    }
}

@Composable
private fun MenuCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onGearClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(20.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (onGearClick != null) {
                IconButton(
                    onClick = onGearClick,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
