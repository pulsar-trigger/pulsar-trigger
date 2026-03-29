/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ui.components.LiveStatusPanel
import com.ehrocha.pulsar.ui.theme.DarkColorScheme

// ── Intervalometer Preview ───────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 700, name = "Intervalometer")
@Composable
private fun IntervalometerPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        var interval by remember { mutableLongStateOf(5_000L) }
        var exposure by remember { mutableLongStateOf(500L) }
        var count by remember { mutableIntStateOf(0) }
        var delay by remember { mutableLongStateOf(5_000L) }

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            IntervalometerPanelContent(
                intervalMs = interval,
                exposureMs = exposure,
                shotCount = count,
                delayMs = delay,
                onIntervalChanged = { interval = it },
                onExposureChanged = { exposure = it },
                onShotCountChanged = { count = it },
                onDelayChanged = { delay = it },
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

// ── Astro Panel Preview ──────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 900, name = "Astro")
@Composable
private fun AstroPanelPreview() {
    var focalLength by remember { mutableIntStateOf(24) }
    var cropFactor by remember { mutableFloatStateOf(1.0f) }
    var ruleDivisor by remember { mutableIntStateOf(500) }
    var shotCount by remember { mutableIntStateOf(100) }
    var delayMs by remember { mutableLongStateOf(5_000L) }
    var gapMs by remember { mutableLongStateOf(2_000L) }

    MaterialTheme(colorScheme = DarkColorScheme) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            AstroPanelContent(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                focalLength = focalLength,
                cropFactor = cropFactor,
                shotCount = shotCount,
                delayMs = delayMs,
                gapMs = gapMs,
                ruleDivisor = ruleDivisor,
                onCropFactorChanged = { cropFactor = it },
                onFocalLengthChanged = { focalLength = it },
                onGapMsChanged = { gapMs = it },
                onShotCountChanged = { shotCount = it },
                onDelayMsChanged = { delayMs = it },
            )
        }
    }
}

// ── Manual Panel Preview ─────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 500, name = "Manual")
@Composable
private fun ManualPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            ManualPanelContent(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                mode = TriggerMode.PRESS_HOLD,
            )
        }
    }
}

// ── Settings Preview ────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "Settings")
@Composable
fun SettingsPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("DEVICE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(16.dp),
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Device Name", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Pulsar-Duza",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text("ABOUT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Pulsar Trigger", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "BLE Camera Remote Control",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Scan Screen Preview ─────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 700, name = "Scan Screen")
@Composable
fun ScanScreenPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text("Pulsar", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Scan for your Pulsar device", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan for Devices")
            }

            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))

            listOf(
                "Pulsar-Duza" to "78:21:84:7B:EF:CC",
                "Pulsar" to "AA:BB:CC:DD:EE:FF",
            ).forEach { (name, addr) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(addr, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Connect →", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ── Full Control Screen Preview ─────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 800, name = "Full Control Screen")
@Composable
fun FullControlScreenPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        var interval by remember { mutableLongStateOf(2000L) }
        var exposure by remember { mutableLongStateOf(500L) }
        var count by remember { mutableIntStateOf(50) }
        var delay by remember { mutableLongStateOf(5000L) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            LiveStatusPanel(
                connected = true,
                status = StatusFrame(
                    state = DeviceState.IDLE,
                    mode = 0x01,
                    shotsTaken = 0,
                    timeRemainingMs = 0,
                    batteryPct = 85,
                    errorCode = 0,
                ),
                currentMode = TriggerMode.INTERVALOMETER,
                deviceName = "Pulsar-Duza",
            )

            Spacer(Modifier.height(12.dp))

            ScrollableTabRow(
                selectedTabIndex = 0,
                edgePadding = 0.dp,
            ) {
                listOf("Interval", "Astro", "Manual", "Settings").forEachIndexed { index, title ->
                    Tab(
                        selected = index == 0,
                        onClick = {},
                        text = { Text(title) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.weight(1f),
            ) {
                IntervalometerPanelContent(
                    intervalMs = interval,
                    exposureMs = exposure,
                    shotCount = count,
                    delayMs = delay,
                    onIntervalChanged = { interval = it },
                    onExposureChanged = { exposure = it },
                    onShotCountChanged = { count = it },
                    onDelayChanged = { delay = it },
                    modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
                )
            }

            Spacer(Modifier.height(16.dp))

            DefaultActionsContent(
                connected = true,
                isRunning = false,
                onStart = {},
                onStop = {},
                onSingleShot = {}
            )
        }
    }
}
