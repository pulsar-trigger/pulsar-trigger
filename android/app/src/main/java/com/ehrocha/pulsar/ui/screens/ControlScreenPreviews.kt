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
import com.ehrocha.pulsar.ui.components.ScrollPicker
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.ui.theme.DarkColorScheme

// ── Intervalometer Preview ───────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 700, name = "Intervalometer")
@Composable
private fun IntervalometerPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                var interval by remember { mutableLongStateOf(5_000L) }
                var exposure by remember { mutableLongStateOf(500L) }
                var count by remember { mutableIntStateOf(0) }
                var delay by remember { mutableLongStateOf(5_000L) }

                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Intervalometer", style = MaterialTheme.typography.titleLarge)

                    TimePicker(
                        totalMs = interval,
                        onChanged = { interval = it.coerceAtLeast(500) },
                        label = "Interval (gap between shots)",
                    )
                    TimePicker(
                        totalMs = exposure,
                        onChanged = { exposure = it.coerceAtLeast(50) },
                        label = "Exposure",
                    )
                    TimePicker(
                        totalMs = delay,
                        onChanged = { delay = it },
                        label = "Start Delay",
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Number of Shots (0 = ∞)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ScrollPicker(
                            value = count,
                            range = 0..999,
                            onValueChange = { count = it },
                            format = { "$it" },
                        )
                    }
                }
            }
        }
    }
}

// ── Astro Panel Preview ──────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, widthDp = 380, heightDp = 900, name = "Astro")
@Composable
private fun AstroPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                var focalLength by remember { mutableIntStateOf(24) }
                var cropFactor by remember { mutableFloatStateOf(1.0f) }
                var ruleDivisor by remember { mutableIntStateOf(500) }
                var shotCount by remember { mutableIntStateOf(100) }
                var delay by remember { mutableLongStateOf(5_000L) }
                var gapMs by remember { mutableLongStateOf(2_000L) }

                val maxExposureS = ruleDivisor.toDouble() / (focalLength * cropFactor)
                val maxExposureMs = (maxExposureS * 1000).toLong().coerceAtLeast(100)
                val intervalMs = maxExposureMs + gapMs

                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Astro Mode", style = MaterialTheme.typography.titleLarge)

                    Text(
                        "Calculates optimal exposure to avoid star trails.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Rule selector
                    Text(
                        "Exposure Rule",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500 to "500 Rule", 400 to "400 Rule").forEach { (d, label) ->
                            FilterChip(
                                selected = ruleDivisor == d,
                                onClick = { ruleDivisor = d },
                                label = { Text(label) },
                            )
                        }
                    }

                    // Sensor type
                    Text(
                        "Sensor Type",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(
                            "Full Frame" to 1.0f,
                            "APS-C (Canon)" to 1.6f,
                            "APS-C (Nikon/Sony)" to 1.5f,
                            "Micro 4/3" to 2.0f,
                        ).forEach { (label, crop) ->
                            FilterChip(
                                selected = cropFactor == crop,
                                onClick = { cropFactor = crop },
                                label = { Text(label) },
                            )
                        }
                    }

                    // Focal length
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Focal Length",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ScrollPicker(
                            value = focalLength,
                            range = 8..600,
                            onValueChange = { focalLength = it },
                            format = { "$it" },
                            label = "mm",
                        )
                    }

                    // Calculated result
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "$ruleDivisor Rule Result",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Max Exposure", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        formatDuration(maxExposureMs),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Interval", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        formatDuration(intervalMs),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    TimePicker(
                        totalMs = gapMs,
                        onChanged = { gapMs = it.coerceAtLeast(500) },
                        label = "Interval (gap between shots)",
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Number of Shots (0 = ∞)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ScrollPicker(
                            value = shotCount,
                            range = 0..999,
                            onValueChange = { shotCount = it },
                            format = { "$it" },
                        )
                    }

                    TimePicker(
                        totalMs = delay,
                        onChanged = { delay = it },
                        label = "Start Delay",
                    )
                }
            }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                var isLock by remember { mutableStateOf(false) }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Manual Shutter", style = MaterialTheme.typography.titleLarge)

                    Text(
                        "Control the shutter directly. Choose Hold (press and hold) or Lock (toggle on/off).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        "Behaviour",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isLock,
                            onClick = { isLock = false },
                            label = { Text("Hold") },
                        )
                        FilterChip(
                            selected = isLock,
                            onClick = { isLock = true },
                            label = { Text("Lock") },
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (isLock)
                                "Tap the button to open the shutter, tap again to close."
                            else
                                "Press and hold the button to keep the shutter open. Release to close.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
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
