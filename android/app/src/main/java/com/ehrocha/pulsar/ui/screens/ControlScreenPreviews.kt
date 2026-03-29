/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ui.components.ScrollPicker
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.ui.theme.DarkColorScheme

// ── Intervalometer Preview ──────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 800, name = "Intervalometer")
@Composable
fun IntervalometerPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.weight(1f),
            ) {
                var interval by remember { mutableLongStateOf(2000L) }
                var exposure by remember { mutableLongStateOf(500L) }
                var count by remember { mutableIntStateOf(50) }
                var delay by remember { mutableLongStateOf(5000L) }

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

// ── Astro Preview ───────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 950, name = "Astro")
@Composable
fun AstroPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            var focalLength by remember { mutableIntStateOf(14) }
            var cropFactor by remember { mutableFloatStateOf(1.0f) }
            var ruleDivisor by remember { mutableIntStateOf(500) }
            var shotCount by remember { mutableIntStateOf(50) }
            var delay by remember { mutableLongStateOf(5000L) }
            var gapMs by remember { mutableLongStateOf(1000L) }

            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.weight(1f),
            ) {
                AstroPanelContent(
                    focalLength = focalLength,
                    cropFactor = cropFactor,
                    shotCount = shotCount,
                    delayMs = delay,
                    gapMs = gapMs,
                    ruleDivisor = ruleDivisor,
                    onCropFactorChanged = { cropFactor = it },
                    onFocalLengthChanged = { focalLength = it },
                    onGapMsChanged = { gapMs = it },
                    onShotCountChanged = { shotCount = it },
                    onDelayMsChanged = { delay = it },
                    modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())
                )
            }

            Spacer(Modifier.height(16.dp))

            AstroActionsContent(
                connected = true,
                isRunning = false,
                ruleDivisor = ruleDivisor,
                onRuleChanged = { ruleDivisor = it },
                onStart = {},
                onStop = {}
            )
        }
    }
}

// ── Sound Preview ───────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "Sound")
@Composable
fun SoundPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
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
                    var threshold by remember { mutableIntStateOf(2000) }
                    var exposure by remember { mutableLongStateOf(200L) }

                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Sound Trigger", style = MaterialTheme.typography.titleLarge)

                        Text(
                            "Triggers the shutter when a loud sound is detected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Adjust the threshold based on ambient noise. Higher values require louder sounds to trigger.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Threshold",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            ScrollPicker(
                                value = threshold,
                                range = 100..4000,
                                onValueChange = { threshold = it },
                                format = { "$it" },
                            )
                        }

                        TimePicker(
                            totalMs = exposure,
                            onChanged = { exposure = it.coerceAtLeast(50) },
                            label = "Exposure",
                        )
                    }
                }
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

// ── Lightning Preview ───────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "Lightning")
@Composable
fun LightningPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
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
                    var sensitivity by remember { mutableIntStateOf(3) }
                    var exposure by remember { mutableLongStateOf(500L) }

                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Lightning", style = MaterialTheme.typography.titleLarge)

                        Text(
                            "Detects sudden brightness changes to capture lightning.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Sensitivity 5 is most sensitive. Use lower values if triggers are too frequent from cloud flicker.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Sensitivity (1–5)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            ScrollPicker(
                                value = sensitivity,
                                range = 1..5,
                                onValueChange = { sensitivity = it },
                                format = { "$it" },
                            )
                        }

                        TimePicker(
                            totalMs = exposure,
                            onChanged = { exposure = it.coerceAtLeast(50) },
                            label = "Exposure",
                        )
                    }
                }
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

// ── Laser Preview ───────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 500, name = "Laser")
@Composable
fun LaserPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
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
                    var exposure by remember { mutableLongStateOf(500L) }

                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Laser Trigger", style = MaterialTheme.typography.titleLarge)

                        Text(
                            "Fires the shutter when a laser beam is broken.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Align your laser with the built-in sensor. The shutter fires instantly upon beam interruption.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }

                        TimePicker(
                            totalMs = exposure,
                            onChanged = { exposure = it.coerceAtLeast(50) },
                            label = "Exposure",
                        )
                    }
                }
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

// ── HDR Preview ─────────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "HDR")
@Composable
fun HdrPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
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
                    HdrPanel(enabled = true)
                }
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

// ── Manual Preview ──────────────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "Manual")
@Composable
fun ManualPanelPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            var mode by remember { mutableStateOf(TriggerMode.PRESS_HOLD) }

            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.weight(1f),
            ) {
                ManualPanelContent(
                    mode = mode,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            ManualActionsContent(
                connected = true,
                mode = mode,
                onModeSelected = { mode = it },
                onShutterDown = {},
                onShutterUp = {}
            )
        }
    }
}
