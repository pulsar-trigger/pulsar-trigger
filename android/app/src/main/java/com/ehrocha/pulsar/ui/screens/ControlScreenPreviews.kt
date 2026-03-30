/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(64.dp))
            
            // Brand Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "Pulsar",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "BLE Camera Trigger",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            Text(
                "Available Devices",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                "Select your Pulsar trigger to begin control session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            Surface(
                onClick = { },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Search for Devices",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            listOf(
                "Pulsar-Duza" to "78:21:84:7B:EF:CC",
                "Pulsar" to "AA:BB:CC:DD:EE:FF",
            ).forEach { (name, addr) ->
                Surface(
                    onClick = { },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(addr, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("󰁔", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Main Menu Preview ───────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 800, name = "Main Menu")
@Composable
fun MainMenuPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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

            Spacer(Modifier.height(24.dp))

            Text(
                "MODES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(12.dp))

            listOf(
                Triple("Intervalometer", "Automate timelapse sequences with precision timing", Icons.Default.Timer),
                Triple("Astro", "Star photography with calculated exposure times", Icons.Default.Stars),
                Triple("Manual", "Direct shutter control — hold or lock mode", Icons.Default.TouchApp),
            ).forEach { (title, desc, icon) ->
                Surface(
                    onClick = {},
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(20.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Surface(
                onClick = {},
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(20.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Device configuration and info", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }
}
