/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

@Composable
fun ModeScreen(
    vm: PulsarViewModel,
    targetMode: TriggerMode,
    onBack: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val status by vm.status.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val mode by vm.currentMode.collectAsState()
    val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

    LaunchedEffect(Unit) { vm.selectMode(targetMode) }

    // Block system back button while a job is running
    BackHandler(enabled = isRunning) { /* swallow – user must press STOP */ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !isRunning) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            val title = when (targetMode) {
                TriggerMode.INTERVALOMETER -> "Intervalometer"
                TriggerMode.ASTRO -> "Astro"
                TriggerMode.PRESS_HOLD -> "Manual"
                else -> targetMode.name.replace('_', ' ')
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
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

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            if (isRunning && targetMode != TriggerMode.PRESS_HOLD) {
                val totalShots = when (targetMode) {
                    TriggerMode.INTERVALOMETER -> vm.shotCount.collectAsState().value
                    TriggerMode.ASTRO -> vm.astroShotCount.collectAsState().value
                    else -> 0
                }
                RunningStatusContent(
                    status = status!!,
                    totalShots = totalShots,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    when (targetMode) {
                        TriggerMode.INTERVALOMETER -> IntervalometerPanel(vm, enabled = !isRunning)
                        TriggerMode.ASTRO -> AstroPanel(vm, enabled = !isRunning)
                        TriggerMode.PRESS_HOLD -> ManualPanel(vm)
                        else -> {}
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LaunchedEffect(isRunning, status?.shotsTaken) {
            if (isRunning) vm.updateNotification()
        }

        when (targetMode) {
            TriggerMode.PRESS_HOLD -> ManualActions(vm, connected, mode)
            TriggerMode.ASTRO -> AstroActions(vm, connected, isRunning)
            else -> DefaultActions(vm, connected, isRunning)
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SettingsScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val status by vm.status.collectAsState()
    val deviceName by vm.deviceName.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
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

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                SettingsPanel(vm, deviceName, connected)
            }
        }
    }
}

@Composable
private fun RunningStatusContent(
    status: StatusFrame,
    totalShots: Int,
) {
    val stateColor by animateColorAsState(
        targetValue = when (status.state) {
            DeviceState.RUNNING -> Color(0xFF00E676)
            DeviceState.WAITING -> Color(0xFFFFD600)
            DeviceState.ERROR -> Color(0xFFFF1744)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // State badge
        Surface(
            color = stateColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(stateColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (status.state) {
                        DeviceState.RUNNING -> "EXPOSING"
                        DeviceState.WAITING -> "WAITING"
                        else -> status.state.name
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Shot counter
        Text(
            text = "${status.shotsTaken}",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "of $totalShots shots",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { if (totalShots > 0) status.shotsTaken.toFloat() / totalShots else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )

        Spacer(Modifier.height(24.dp))

        // Time remaining
        if (status.timeRemainingMs > 0) {
            Text(
                text = formatTimeRemaining(status.timeRemainingMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "remaining",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
