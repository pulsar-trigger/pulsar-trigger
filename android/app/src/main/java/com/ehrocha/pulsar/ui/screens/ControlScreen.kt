/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.update.AppUpdateState
import com.ehrocha.pulsar.BuildConfig
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.ehrocha.pulsar.viewmodel.PulsarViewModel.Companion.SAFE_OUTPUT_PINS

@Composable
internal fun DefaultActions(vm: PulsarViewModel, connected: Boolean, isRunning: Boolean) {
    DefaultActionsContent(
        connected = connected,
        isRunning = isRunning,
        onStart = { vm.start() },
        onStop = { vm.stop() },
        onSingleShot = { vm.singleShot() }
    )
}

@Composable
internal fun DefaultActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSingleShot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onSingleShot,
                enabled = connected && !isRunning,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp).weight(1f)
            ) {
                Text("SINGLE", style = MaterialTheme.typography.labelLarge)
            }

            Button(
                onClick = { if (isRunning) onStop() else onStart() },
                enabled = connected,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.height(56.dp).weight(2f)
            ) {
                Text(
                    text = if (isRunning) "STOP" else "START",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = if (isRunning) "Sequence running…" else "Ready to start sequence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ManualActions(vm: PulsarViewModel, connected: Boolean, mode: TriggerMode) {
    ManualActionsContent(
        connected = connected,
        mode = mode,
        onModeSelected = { vm.selectMode(it) },
        onShutterDown = { vm.shutterDown() },
        onShutterUp = { vm.shutterUp() }
    )
}

@Composable
internal fun ManualActionsContent(
    connected: Boolean,
    mode: TriggerMode,
    onModeSelected: (TriggerMode) -> Unit,
    onShutterDown: () -> Unit,
    onShutterUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHold = mode == TriggerMode.PRESS_HOLD
    var active by remember { mutableStateOf(false) }

    LaunchedEffect(mode) { active = false }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = mode == TriggerMode.PRESS_HOLD,
                onClick = { onModeSelected(TriggerMode.PRESS_HOLD) },
                enabled = connected,
                label = { Text("HOLD MODE", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = mode == TriggerMode.PRESS_LOCK,
                onClick = { onModeSelected(TriggerMode.PRESS_LOCK) },
                enabled = connected,
                label = { Text("LOCK MODE", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (active) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            tonalElevation = if (active) 8.dp else 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .pointerInput(connected, isHold) {
                    if (!connected) return@pointerInput
                    if (isHold) {
                        detectTapGestures(
                            onPress = {
                                active = true
                                onShutterDown()
                                try { awaitRelease() } finally {
                                    active = false
                                    onShutterUp()
                                }
                            },
                        )
                    } else {
                        detectTapGestures(
                            onPress = {
                                if (!active) {
                                    active = true
                                    onShutterDown()
                                } else {
                                    active = false
                                    onShutterUp()
                                }
                            },
                        )
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (isHold) {
                        if (active) "RELEASE SHUTTER" else "HOLD SHUTTER"
                    } else {
                        if (active) "CLOSE SHUTTER" else "OPEN SHUTTER"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Text(
            text = if (isHold) {
                if (active) "Shutter open…" else "Press and hold button"
            } else {
                if (active) "Shutter locked open" else "Tap button to toggle"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AstroActions(vm: PulsarViewModel, connected: Boolean, isRunning: Boolean) {
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()
    AstroActionsContent(
        connected = connected,
        isRunning = isRunning,
        ruleDivisor = ruleDivisor,
        onRuleChanged = { vm.astroRuleDivisor.value = it },
        onStart = { vm.start() },
        onStop = { vm.stop() }
    )
}

@Composable
internal fun AstroActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    ruleDivisor: Int,
    onRuleChanged: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = ruleDivisor == 500,
                onClick = { onRuleChanged(500) },
                enabled = connected && !isRunning,
                label = { Text("500 RULE", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = ruleDivisor == 400,
                onClick = { onRuleChanged(400) },
                enabled = connected && !isRunning,
                label = { Text("400 RULE", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            enabled = connected,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                text = if (isRunning) "STOP ASTRO" else "START ASTRO",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = if (isRunning) "Capturing stars…" else "Ready for astro sequence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun IntervalometerPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val interval by vm.intervalMs.collectAsState()
    val exposure by vm.exposureMs.collectAsState()
    val count by vm.shotCount.collectAsState()
    val delayVal by vm.delayMs.collectAsState()
    val maxShots by vm.maxShotCount.collectAsState()

    IntervalometerPanelContent(
        intervalMs = interval,
        exposureMs = exposure,
        shotCount = count,
        delayMs = delayVal,
        maxShotCount = maxShots,
        onIntervalChanged = { vm.intervalMs.value = it.coerceAtLeast(500) },
        onExposureChanged = { vm.exposureMs.value = it.coerceAtLeast(50) },
        onShotCountChanged = { vm.shotCount.value = it.coerceAtLeast(1) },
        onDelayChanged = { vm.delayMs.value = it },
        enabled = enabled
    )
}

@Composable
internal fun IntervalometerPanelContent(
    modifier: Modifier = Modifier,
    intervalMs: Long,
    exposureMs: Long,
    shotCount: Int,
    delayMs: Long,
    maxShotCount: Int = 999,
    onIntervalChanged: (Long) -> Unit,
    onExposureChanged: (Long) -> Unit,
    onShotCountChanged: (Int) -> Unit,
    onDelayChanged: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val totalSequenceTimeMs = delayMs + shotCount.toLong() * (exposureMs + intervalMs) - intervalMs

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Intervalometer", style = MaterialTheme.typography.titleLarge)
            Text(
                "Automate complex timelapse sequences or burst captures with precision timing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Configure individual timings below. For bulb mode, ensure the interval is longer than the exposure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "SEQUENCE ESTIMATE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SHOTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$shotCount", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TOTAL DURATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(totalSequenceTimeMs), style = MaterialTheme.typography.headlineLarge)
                    }
                }
                
                LinearProgressIndicator(
                    progress = { (exposureMs.toFloat() / (exposureMs + intervalMs)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                
                Text(
                    "Duty Cycle: ${(exposureMs.toFloat() / (exposureMs + intervalMs) * 100).toInt()}% active exposure",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(0.7f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TIMING PARAMETERS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            TimePicker(
                totalMs = intervalMs,
                onChanged = { onIntervalChanged(it) },
                label = "Interval (time between shots)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = exposureMs,
                onChanged = { onExposureChanged(it) },
                label = "Exposure duration",
                enabled = enabled,
            )

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayChanged(it) },
                label = "Start Delay",
                enabled = enabled,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Number of Shots",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$shotCount",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = shotCount.toFloat(),
                    onValueChange = { onShotCountChanged(it.toInt()) },
                    valueRange = 1f..maxShotCount.toFloat(),
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
internal fun ManualPanel(vm: PulsarViewModel) {
    val mode by vm.currentMode.collectAsState()
    ManualPanelContent(mode = mode)
}

@Composable
internal fun ManualPanelContent(
    modifier: Modifier = Modifier,
    mode: TriggerMode,
) {
    val isLock = mode == TriggerMode.PRESS_LOCK
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
    ) {
        Text("Manual Shutter", style = MaterialTheme.typography.titleLarge)

        Text(
            "Control the shutter directly. Choose Hold (press and hold) or Lock (toggle on/off).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Toggle behaviour using the chips in the action area below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isLock)
                        "LOCK: Tap the center button once to open the shutter, tap again to close. Best for long bulb exposures."
                    else
                        "HOLD: Shutter remains open as long as the center button is pressed. Ideal for quick bursts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun AstroPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val focalLength by vm.astroFocalLength.collectAsState()
    val cropFactor by vm.astroCropFactor.collectAsState()
    val shotCount by vm.astroShotCount.collectAsState()
    val delayVal by vm.astroDelayMs.collectAsState()
    val gapMs by vm.astroGapMs.collectAsState()
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()

    AstroPanelContent(
        focalLength = focalLength,
        cropFactor = cropFactor,
        shotCount = shotCount,
        delayMs = delayVal,
        gapMs = gapMs,
        ruleDivisor = ruleDivisor,
        onCropFactorChanged = { vm.astroCropFactor.value = it },
        onFocalLengthChanged = { vm.astroFocalLength.value = it },
        onGapMsChanged = { vm.astroGapMs.value = it.coerceAtLeast(500) },
        onShotCountChanged = { vm.astroShotCount.value = it.coerceAtLeast(1) },
        onDelayMsChanged = { vm.astroDelayMs.value = it },
        enabled = enabled
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AstroPanelContent(
    modifier: Modifier = Modifier,
    focalLength: Int,
    cropFactor: Float,
    shotCount: Int,
    delayMs: Long,
    gapMs: Long,
    ruleDivisor: Int,
    onCropFactorChanged: (Float) -> Unit,
    onFocalLengthChanged: (Int) -> Unit,
    onGapMsChanged: (Long) -> Unit,
    onShotCountChanged: (Int) -> Unit,
    onDelayMsChanged: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val maxExposureS = ruleDivisor.toDouble() / (focalLength * cropFactor)
    val maxExposureMs = (maxExposureS * 1000).toLong().coerceAtLeast(100)
    val intervalMs = maxExposureMs + gapMs
    val totalTimeMs = delayMs + shotCount.toLong() * (maxExposureMs + gapMs) - gapMs

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Astro Mode", style = MaterialTheme.typography.titleLarge)
            Text(
                "Pinpoint stars by calculating the maximum shutter speed to prevent trails.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Switch rules at the bottom. Rule 400 is ideal for high-resolution sensors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$ruleDivisor RULE READOUT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "${"%.1f".format(focalLength * cropFactor)}mm Effective", 
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MAX EXPOSURE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(maxExposureMs), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TOTAL TIME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(totalTimeMs), style = MaterialTheme.typography.headlineLarge)
                    }
                }
                
                LinearProgressIndicator(
                    progress = { (maxExposureMs.toFloat() / intervalMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )

                Text(
                    "Formula: $ruleDivisor / ($focalLength mm * ${cropFactor}x) = ${"%.1f".format(maxExposureS)}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().alpha(0.6f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("OPTICS CONFIGURATION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sensor Preset", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SENSOR_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = cropFactor == preset.crop,
                            onClick = { if (enabled) onCropFactorChanged(preset.crop) },
                            enabled = enabled,
                            label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Lens Focal Length",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$focalLength mm",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Slider(
                    value = focalLength.toFloat(),
                    onValueChange = { onFocalLengthChanged(it.toInt()) },
                    valueRange = 8f..600f,
                    enabled = enabled,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(14, 24, 35, 50, 85, 135, 200, 400, 600).forEach { mm ->
                        SuggestionChip(
                            onClick = { if (enabled) onFocalLengthChanged(mm) },
                            label = { Text("${mm}mm", style = MaterialTheme.typography.labelSmall) },
                            enabled = enabled,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("CAPTURE SEQUENCE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            TimePicker(
                totalMs = gapMs,
                onChanged = { onGapMsChanged(it) },
                label = "Gap between shots",
                enabled = enabled,
            )

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayMsChanged(it) },
                label = "Start Delay",
                enabled = enabled,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Number of Shots",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "$shotCount",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Slider(
                    value = shotCount.toFloat(),
                    onValueChange = { onShotCountChanged(it.toInt()) },
                    valueRange = 1f..999f,
                    enabled = enabled,
                )
            }
        }
    }
}

private data class SensorPreset(val label: String, val crop: Float)

private val SENSOR_PRESETS = listOf(
    SensorPreset("Full Frame", 1.0f),
    SensorPreset("APS-C (Canon)", 1.6f),
    SensorPreset("APS-C (Nikon/Sony)", 1.5f),
    SensorPreset("Micro 4/3", 2.0f),
)

internal fun formatDuration(ms: Long): String {
    val totalS = ms / 1000.0
    return if (totalS >= 60) {
        val m = (totalS / 60).toInt()
        val s = (totalS % 60).toInt()
        if (m >= 60) {
            val h = m / 60
            val rm = m % 60
            "${h}h ${rm}m"
        } else {
            "${m}m ${s}s"
        }
    } else {
        "${"%.1f".format(totalS)}s"
    }
}

// ─── Collapsible section ─────────────────────────────────────────────────────

@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun SettingsPanel(
    vm: PulsarViewModel,
    deviceName: String,
    connected: Boolean,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val simulatorActive by vm.simulatorActive.collectAsState()
    val hwConnected = connected && !simulatorActive

    val shutterPin by vm.pinShutter.collectAsState()
    val focusPin by vm.pinFocus.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.exportSettingsJson().toByteArray())
                }
                Toast.makeText(context, "Settings exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val json = stream.bufferedReader().readText()
                    vm.importSettingsJson(json)
                }
                Toast.makeText(context, "Settings imported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Device ───────────────────────────────────────────────────────
        CollapsibleSection("DEVICE", initiallyExpanded = true) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hwConnected) { showRenameDialog = true },
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
                        deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── GPIO Pins ────────────────────────────────────────────────────
        CollapsibleSection("GPIO PINS") {
            Text(
                "Choose which ESP32 GPIO pins drive the shutter and focus optocouplers. Changes are sent to the device and saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GpioPinSelector(
                label = "Shutter Pin",
                selectedPin = shutterPin,
                disabledPin = focusPin,
                onPinSelected = { vm.savePins(it, focusPin) },
                enabled = hwConnected,
            )

            GpioPinSelector(
                label = "Focus Pin",
                selectedPin = focusPin,
                disabledPin = shutterPin,
                onPinSelected = { vm.savePins(shutterPin, it) },
                enabled = hwConnected,
            )

            if (simulatorActive) {
                Text(
                    "GPIO configuration not available in simulator mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Backup & Restore ─────────────────────────────────────────────
        CollapsibleSection("BACKUP & RESTORE") {
            Text(
                "Export settings to Google Drive or local storage, and import them on another device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("pulsar-settings.json") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export")
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import")
                }
            }
        }

        // ── Firmware Update ──────────────────────────────────────────────
        FirmwareUpdateSection(vm = vm, connected = hwConnected)

        // ── App Update ───────────────────────────────────────────────────
        AppUpdateSection(vm = vm)

        // ── Device Hardware ──────────────────────────────────────────────
        DeviceInfoSection(vm = vm, connected = hwConnected)

        // ── About ────────────────────────────────────────────────────────
        CollapsibleSection("ABOUT") {
            val uriHandler = LocalUriHandler.current

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
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

            Text(
                "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "Created by Eduardo Rocha",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                "Licensed under GPL-3.0-or-later",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { uriHandler.openUri("https://github.com/pulsar-trigger/pulsar-trigger") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("View on GitHub")
            }

            OutlinedButton(
                onClick = { uriHandler.openUri("https://instagram.com/ehrocha.br") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("@ehrocha.br on Instagram")
            }
        }
    }

    if (showRenameDialog) {
        RenameDeviceDialog(
            onDismiss = { showRenameDialog = false },
            onConfirm = { suffix ->
                vm.renameDevice(suffix)
                showRenameDialog = false
            },
        )
    }
}

@Composable
private fun RenameDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (suffix: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val maxLen = 12

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter a custom name. The device will advertise as \"Pulsar-<name>\". " +
                    "Leave empty to reset to \"Pulsar\".",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= maxLen) text = it },
                    label = { Text("Device name") },
                    prefix = { Text("Pulsar-") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    supportingText = { Text("${text.length}/$maxLen characters") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun FirmwareUpdateSection(vm: PulsarViewModel, connected: Boolean) {
    val fwManager = vm.firmwareManager
    val otaState by fwManager.state.collectAsState()
    val progress by fwManager.progress.collectAsState()
    val latestRelease by fwManager.latestRelease.collectAsState()
    val errorMessage by fwManager.errorMessage.collectAsState()
    val status by vm.status.collectAsState()
    val currentVersion = status?.fwVersion ?: ""

    CollapsibleSection("FIRMWARE UPDATE") {
        if (currentVersion.isNotEmpty()) {
            Text(
                "Current: v$currentVersion",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (otaState) {
                OtaState.IDLE -> {
                    OutlinedButton(
                        onClick = { fwManager.checkForUpdate(currentVersion) },
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Check for Update")
                    }
                }

                OtaState.CHECKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OtaState.AVAILABLE -> {
                    latestRelease?.let { release ->
                        Text(
                            "New version available: v${release.version}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (release.body.isNotBlank()) {
                            Text(
                                release.body.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { fwManager.startUpdate() },
                            enabled = connected,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Install Update")
                        }
                    }
                }

                OtaState.DOWNLOADING -> {
                    Text("Downloading firmware…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { fwManager.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Cancel") }
                }

                OtaState.UPLOADING -> {
                    Text("Uploading to device…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { fwManager.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Cancel") }
                }

                OtaState.VALIDATING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Validating & rebooting…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                OtaState.COMPLETE -> {
                    Text(
                        "Update complete! Device is rebooting.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        onClick = { fwManager.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Done") }
                }

                OtaState.ERROR -> {
                    Text(
                        errorMessage ?: "Update failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { fwManager.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Dismiss") }
                }
            }
    }
}

@Composable
private fun AppUpdateSection(vm: PulsarViewModel) {
    val updateManager = vm.appUpdateManager
    val updateState by updateManager.state.collectAsState()
    val progress by updateManager.progress.collectAsState()
    val latestRelease by updateManager.latestRelease.collectAsState()
    val errorMessage by updateManager.errorMessage.collectAsState()
    val currentVersion = BuildConfig.VERSION_NAME

    CollapsibleSection("APP UPDATE") {
        Text(
            "Current: v$currentVersion",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (updateState) {
                AppUpdateState.IDLE -> {
                    OutlinedButton(
                        onClick = { updateManager.checkForUpdate(currentVersion) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Check for App Update")
                    }
                }

                AppUpdateState.CHECKING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                AppUpdateState.UP_TO_DATE -> {
                    Text(
                        "You're up to date!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = { updateManager.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("OK") }
                }

                AppUpdateState.AVAILABLE -> {
                    latestRelease?.let { release ->
                        Text(
                            "New version available: v${release.version}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (release.body.isNotBlank()) {
                            Text(
                                release.body.take(200),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { updateManager.downloadAndInstall() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download & Install")
                        }
                    }
                }

                AppUpdateState.DOWNLOADING -> {
                    Text("Downloading APK…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { updateManager.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Cancel") }
                }

                AppUpdateState.READY_TO_INSTALL -> {
                    Text(
                        "Download complete — the installer should open automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = { updateManager.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Done") }
                }

                AppUpdateState.ERROR -> {
                    Text(
                        errorMessage ?: "Update check failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(
                        onClick = { updateManager.reset() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Dismiss") }
                }
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpioPinSelector(
    label: String,
    selectedPin: Int,
    disabledPin: Int,
    onPinSelected: (Int) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = "GPIO $selectedPin",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
                singleLine = true,
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                SAFE_OUTPUT_PINS.forEach { pin ->
                    val isDisabled = pin == disabledPin
                    DropdownMenuItem(
                        text = {
                            Text(
                                "GPIO $pin" + if (isDisabled) " (in use)" else "",
                                color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            if (!isDisabled) {
                                onPinSelected(pin)
                                expanded = false
                            }
                        },
                        enabled = !isDisabled,
                    )
                }
            }
        }
    }
}

// ── Per-mode settings panels (accessed via gear icon on main menu) ───────────

@Composable
internal fun IntervalometerSettingsPanel(vm: PulsarViewModel, connected: Boolean) {
    val defInterval by vm.defaultIntervalMs.collectAsState()
    val defExposure by vm.defaultExposureMs.collectAsState()
    val defCount by vm.defaultShotCount.collectAsState()
    val defDelay by vm.defaultDelayMs.collectAsState()
    val maxShots by vm.maxShotCount.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("DEFAULT VALUES", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Set default values for the Intervalometer. These are applied when the app starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TimePicker(
                    totalMs = defInterval,
                    onChanged = { vm.saveIntervalometerDefaults(it.coerceAtLeast(500), defExposure, defCount, defDelay) },
                    label = "Default Interval (gap)",
                )

                TimePicker(
                    totalMs = defExposure,
                    onChanged = { vm.saveIntervalometerDefaults(defInterval, it.coerceAtLeast(50), defCount, defDelay) },
                    label = "Default Exposure",
                )

                TimePicker(
                    totalMs = defDelay,
                    onChanged = { vm.saveIntervalometerDefaults(defInterval, defExposure, defCount, it) },
                    label = "Default Start Delay",
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var defCountText by remember(defCount) { mutableStateOf(defCount.toString()) }
                    OutlinedTextField(
                        value = defCountText,
                        onValueChange = { defCountText = it.filter { c -> c.isDigit() } },
                        label = { Text("Default Shot Count") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            val v = defCountText.toIntOrNull()?.coerceIn(1, maxShots) ?: defCount
                            defCountText = v.toString()
                            vm.saveIntervalometerDefaults(defInterval, defExposure, v, defDelay)
                        }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("1 – $maxShots") },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var maxShotsText by remember(maxShots) { mutableStateOf(maxShots.toString()) }
                    OutlinedTextField(
                        value = maxShotsText,
                        onValueChange = { maxShotsText = it.filter { c -> c.isDigit() } },
                        label = { Text("Max Shot Count") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            val v = maxShotsText.toIntOrNull()?.coerceIn(10, 9999) ?: maxShots
                            maxShotsText = v.toString()
                            vm.saveMaxShotCount(v)
                        }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("10 – 9999") },
                    )
                }

                OutlinedButton(
                    onClick = { vm.resetIntervalometerDefaults() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Reset to Factory Defaults")
                }
            }
        }
    }
}

@Composable
internal fun AstroSettingsPanel(vm: PulsarViewModel, connected: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Astro mode calculates exposure times from your optics. " +
            "Configure lens and sensor defaults here, or adjust them in the mode screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AstroPanel(vm, enabled = true)
    }
}

@Composable
internal fun ManualSettingsPanel(vm: PulsarViewModel, connected: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Manual mode lets you control the shutter directly. " +
            "Choose between Hold (press and hold) or Lock (toggle on/off) in the mode screen.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ManualPanel(vm)
    }
}

// ── Device Hardware Info ─────────────────────────────────────────────────────

@Composable
private fun DeviceInfoSection(vm: PulsarViewModel, connected: Boolean) {
    val info by vm.deviceInfo.collectAsState()
    val simulatorActive by vm.simulatorActive.collectAsState()

    CollapsibleSection("DEVICE HARDWARE") {
        if (simulatorActive) {
            Text(
                "Hardware info not available in simulator mode",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (info != null) {
            val i = info!!
            InfoRow("Chip", "${i.chipModel} rev ${i.chipRevision}")
            InfoRow("CPU", "${i.cpuFreqMhz} MHz")
            InfoRow("Flash", formatKb(i.flashSizeKb))
            InfoRow("Free Heap", formatKb(i.freeHeapKb))
            if (i.psramKb > 0) {
                InfoRow("PSRAM", formatKb(i.psramKb.toLong()))
            }
            InfoRow("GPIO Pins", "${i.gpioCount} total, ${i.safeOutputCount} configurable outputs")
            InfoRow("Uptime", formatUptime(i.uptimeMinutes))

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { vm.requestDeviceInfo() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text("Refresh") }
        } else if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Querying device…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                "Connect to a device to see hardware info",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun formatKb(kb: Long): String = when {
    kb >= 1024 -> "${kb / 1024} MB"
    else -> "$kb KB"
}

private fun formatUptime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
