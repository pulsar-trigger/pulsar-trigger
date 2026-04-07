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
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.update.AppUpdateState
import com.ehrocha.pulsar.BuildConfig
import com.ehrocha.pulsar.R
import androidx.annotation.StringRes
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehrocha.pulsar.ui.components.IntStepperField
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.ehrocha.pulsar.viewmodel.PulsarViewModel.Companion.SAFE_OUTPUT_PINS
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus

@Composable
internal fun DefaultActions(vm: PulsarViewModel, isRunning: Boolean) {
    val connected = LocalDeviceConnected.current
    val shotCount by vm.shotCount.collectAsState()
    val maxShots by vm.maxShotCount.collectAsState()
    DefaultActionsContent(
        connected = connected,
        isRunning = isRunning,
        shotCount = shotCount,
        maxShotCount = maxShots,
        onShotCountChanged = { vm.setShotCount(it) },
        onStart = { vm.start() },
        onStop = { vm.stop() },
    )
}

@Composable
internal fun DefaultActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    shotCount: Int,
    maxShotCount: Int,
    onShotCountChanged: (Int) -> Unit,
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                IntStepperField(
                    label = stringResource(R.string.label_number_of_shots),
                    value = shotCount,
                    onValueChange = { onShotCountChanged(it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT)) },
                    min = AppConfig.MIN_SHOT_COUNT,
                    max = maxShotCount,
                    enabled = connected && !isRunning,
                    presets = emptyList(),
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
                modifier = Modifier.height(56.dp).weight(1f)
            ) {
                Text(
                    text = if (isRunning) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = if (isRunning) stringResource(R.string.status_sequence_running) else stringResource(R.string.status_ready_start),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ManualActions(vm: PulsarViewModel, mode: TriggerMode) {
    val connected = LocalDeviceConnected.current
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
                label = { Text(stringResource(R.string.chip_hold_mode), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = mode == TriggerMode.PRESS_LOCK,
                onClick = { onModeSelected(TriggerMode.PRESS_LOCK) },
                enabled = connected,
                label = { Text(stringResource(R.string.chip_lock_mode), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
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
                        if (active) stringResource(R.string.btn_release_shutter) else stringResource(R.string.btn_hold_shutter)
                    } else {
                        if (active) stringResource(R.string.btn_close_shutter) else stringResource(R.string.btn_open_shutter)
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
                if (active) stringResource(R.string.status_shutter_open) else stringResource(R.string.status_press_hold)
            } else {
                if (active) stringResource(R.string.status_shutter_locked) else stringResource(R.string.status_tap_toggle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AstroActions(vm: PulsarViewModel, isRunning: Boolean) {
    val connected = LocalDeviceConnected.current
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()
    val shotCount by vm.astroShotCount.collectAsState()
    AstroActionsContent(
        connected = connected,
        isRunning = isRunning,
        ruleDivisor = ruleDivisor,
        shotCount = shotCount,
        onRuleChanged = { vm.setAstroRuleDivisor(it) },
        onShotCountChanged = { vm.setAstroShotCount(it) },
        onStart = { vm.start() },
        onStop = { vm.stop() }
    )
}

@Composable
internal fun AstroActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    ruleDivisor: Int,
    shotCount: Int,
    onRuleChanged: (Int) -> Unit,
    onShotCountChanged: (Int) -> Unit,
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
                selected = ruleDivisor == AppConfig.DEFAULT_RULE_DIVISOR,
                onClick = { onRuleChanged(AppConfig.DEFAULT_RULE_DIVISOR) },
                enabled = connected && !isRunning,
                label = { Text(stringResource(R.string.chip_500_rule), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = ruleDivisor == AppConfig.TIGHT_RULE_DIVISOR,
                onClick = { onRuleChanged(AppConfig.TIGHT_RULE_DIVISOR) },
                enabled = connected && !isRunning,
                label = { Text(stringResource(R.string.chip_400_rule), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                IntStepperField(
                    label = stringResource(R.string.label_number_of_shots),
                    value = shotCount,
                    onValueChange = { onShotCountChanged(it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT)) },
                    min = AppConfig.MIN_SHOT_COUNT,
                    max = AppConfig.DEFAULT_MAX_SHOTS,
                    enabled = connected && !isRunning,
                    presets = emptyList(),
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
                modifier = Modifier.height(56.dp).weight(1f)
            ) {
                Text(
                    text = stringResource(if (isRunning) R.string.btn_stop_astro else R.string.btn_start_astro),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = stringResource(if (isRunning) R.string.status_capturing_stars else R.string.status_ready_astro),
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

    IntervalometerPanelContent(
        intervalMs = interval,
        exposureMs = exposure,
        shotCount = count,
        delayMs = delayVal,
        onIntervalChanged = { vm.setIntervalMs(it) },
        onExposureChanged = { vm.setExposureMs(it) },
        onDelayChanged = { vm.setDelayMs(it) },
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
    onIntervalChanged: (Long) -> Unit,
    onExposureChanged: (Long) -> Unit,
    onDelayChanged: (Long) -> Unit,
    onShotCountChanged: ((Int) -> Unit)? = null,
    maxShotCount: Int = 999,
    enabled: Boolean = true,
) {
    val totalSequenceTimeMs = delayMs + shotCount.toLong() * (exposureMs + intervalMs) - intervalMs

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = modifier) {
        Text(
            stringResource(R.string.panel_intervalometer_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.label_sequence_estimate),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_shots), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$shotCount", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_total_duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    stringResource(R.string.label_duty_cycle, (exposureMs.toFloat() / (exposureMs + intervalMs) * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(0.7f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.label_capture_sequence), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            TimePicker(
                totalMs = exposureMs,
                onChanged = { onExposureChanged(it) },
                label = stringResource(R.string.label_exposure) + " (hh:mm:ss)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = intervalMs,
                onChanged = { onIntervalChanged(it) },
                label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
                enabled = enabled,
            )

            if (onShotCountChanged != null) {
                IntStepperField(
                    label = stringResource(R.string.label_number_of_shots),
                    value = shotCount,
                    onValueChange = { onShotCountChanged(it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT)) },
                    min = AppConfig.MIN_SHOT_COUNT,
                    max = maxShotCount,
                    enabled = enabled,
                    presets = emptyList(),
                )
            }

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayChanged(it) },
                label = stringResource(R.string.label_start_delay) + " (hh:mm:ss)",
                enabled = enabled,
            )
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
        Text(
            stringResource(R.string.panel_manual_desc),
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
                    stringResource(R.string.panel_manual_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isLock)
                        stringResource(R.string.panel_manual_lock_info)
                    else
                        stringResource(R.string.panel_manual_hold_info),
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
        onCropFactorChanged = { vm.setAstroCropFactor(it) },
        onFocalLengthChanged = { vm.setAstroFocalLength(it) },
        onGapMsChanged = { vm.setAstroGapMs(it) },
        onDelayMsChanged = { vm.setAstroDelayMs(it) },
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
    onDelayMsChanged: (Long) -> Unit,
    onShotCountChanged: ((Int) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val maxExposureS = ruleDivisor.toDouble() / (focalLength * cropFactor)
    val maxExposureMs = (maxExposureS * 1000).toLong().coerceAtLeast(AppConfig.MIN_ASTRO_EXPOSURE_MS)
    val intervalMs = maxExposureMs + gapMs
    val totalTimeMs = delayMs + shotCount.toLong() * (maxExposureMs + gapMs) - gapMs

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.panel_astro_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.label_rule_readout, ruleDivisor),
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
                            stringResource(R.string.label_effective_focal, "%.1f".format(focalLength * cropFactor)), 
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_max_exposure), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(maxExposureMs), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_total_duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    stringResource(R.string.label_astro_formula, ruleDivisor, focalLength, "$cropFactor", "%.1f".format(maxExposureS)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().alpha(0.6f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.label_optics_configuration), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.label_sensor_preset), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var sensorExpanded by remember { mutableStateOf(false) }
                val selectedPreset = SENSOR_PRESETS.find { it.crop == cropFactor }
                ExposedDropdownMenuBox(
                    expanded = sensorExpanded,
                    onExpandedChange = { if (enabled) sensorExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedPreset?.let { stringResource(it.labelRes) } ?: "$cropFactor×",
                        onValueChange = {},
                        readOnly = true,
                        enabled = enabled,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sensorExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    ExposedDropdownMenu(
                        expanded = sensorExpanded,
                        onDismissRequest = { sensorExpanded = false },
                    ) {
                        SENSOR_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(stringResource(preset.labelRes)) },
                                onClick = {
                                    onCropFactorChanged(preset.crop)
                                    sensorExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            IntStepperField(
                label = stringResource(R.string.label_focal_length),
                value = focalLength,
                onValueChange = { onFocalLengthChanged(it) },
                min = AppConfig.MIN_FOCAL_LENGTH,
                max = AppConfig.MAX_FOCAL_LENGTH,
                enabled = enabled,
                presets = AppConfig.FOCAL_LENGTH_PRESETS,
                presetLabel = { "${it}mm" },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.label_capture_sequence), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            
            TimePicker(
                totalMs = gapMs,
                onChanged = { onGapMsChanged(it) },
                label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
                enabled = enabled,
            )

            if (onShotCountChanged != null) {
                IntStepperField(
                    label = stringResource(R.string.label_number_of_shots),
                    value = shotCount,
                    onValueChange = { onShotCountChanged(it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT)) },
                    min = AppConfig.MIN_SHOT_COUNT,
                    max = AppConfig.DEFAULT_MAX_SHOTS,
                    enabled = enabled,
                    presets = emptyList(),
                )
            }

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayMsChanged(it) },
                label = stringResource(R.string.label_start_delay) + " (hh:mm:ss)",
                enabled = enabled,
            )
        }
    }
}

private data class SensorPreset(val labelRes: Int, val crop: Float)

private val SENSOR_PRESETS = listOf(
    SensorPreset(R.string.preset_full_frame, 1.0f),
    SensorPreset(R.string.preset_aps_c_canon, 1.6f),
    SensorPreset(R.string.preset_aps_c_nikon_sony, 1.5f),
    SensorPreset(R.string.preset_micro_43, 2.0f),
)

internal fun formatDuration(ms: Long): String {
    val totalS = (ms + 500) / 1000
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
        "${totalS}s"
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
                    contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
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

// ── Settings section enum & menu ─────────────────────────────────────────────

enum class SettingsSection(val icon: ImageVector, @StringRes val titleRes: Int) {
    LANGUAGE(Icons.Default.Language, R.string.section_language),
    DEVICE(Icons.Default.PhoneAndroid, R.string.section_device),
    GPIO_PINS(Icons.Default.Memory, R.string.section_gpio_pins),
    PLANNER(Icons.Default.CalendarMonth, R.string.section_planner),
    BACKUP_RESTORE(Icons.Default.SaveAlt, R.string.section_backup_restore),
    UPDATES(Icons.Default.SystemUpdate, R.string.section_updates),
    DEVICE_INFO(Icons.Default.DeveloperBoard, R.string.section_device_hardware),
    ABOUT(Icons.Outlined.Info, R.string.section_about),
}

@Composable
internal fun SettingsMenu(onSectionSelected: (SettingsSection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSection.entries.forEach { section ->
            Surface(
                onClick = { onSectionSelected(section) },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(section.titleRes),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Individual settings section content ──────────────────────────────────────

@Composable
internal fun LanguageSectionContent() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    val currentTag = if (currentLocale.isEmpty) "" else currentLocale.toLanguageTags()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // System default option
        val isSystemDefault = currentTag.isEmpty()
        Surface(
            onClick = {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                )
            },
            shape = RoundedCornerShape(12.dp),
            tonalElevation = if (isSystemDefault) 4.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                RadioButton(selected = isSystemDefault, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.lang_system_default),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        // Each supported language
        com.ehrocha.pulsar.AppConfig.SUPPORTED_LOCALES.forEach { (tag, label) ->
            val selected = currentTag.startsWith(tag)
            Surface(
                onClick = {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                        androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                    )
                },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = if (selected) 4.dp else 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (tag != "en") {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${java.util.Locale(tag).getDisplayLanguage(java.util.Locale.ENGLISH)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        // Panic button — always in English for discoverability
        OutlinedButton(
            onClick = {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags("en")
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("Reset to English", fontWeight = FontWeight.Bold)
        }
        Text(
            "If the app is in a language you can't read, tap the button above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DeviceSectionContent(
    vm: PulsarViewModel,
    deviceName: String,
) {
    val connected = LocalDeviceConnected.current
    var showRenameDialog by remember { mutableStateOf(false) }
    val simulatorActive by vm.simulatorActive.collectAsState()
    val hwConnected = connected && !simulatorActive

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text(stringResource(R.string.label_device_name), style = MaterialTheme.typography.titleSmall)
                Text(
                    deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
internal fun GpioPinsSectionContent(vm: PulsarViewModel) {
    val connected = LocalDeviceConnected.current
    val simulatorActive by vm.simulatorActive.collectAsState()
    val hwConnected = connected && !simulatorActive
    val shutterPin by vm.pinShutter.collectAsState()
    val focusPin by vm.pinFocus.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.gpio_pins_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GpioPinSelector(
            label = stringResource(R.string.label_shutter_pin),
            selectedPin = shutterPin,
            disabledPin = focusPin,
            onPinSelected = { vm.savePins(it, focusPin) },
            enabled = hwConnected,
        )

        GpioPinSelector(
            label = stringResource(R.string.label_focus_pin),
            selectedPin = focusPin,
            disabledPin = shutterPin,
            onPinSelected = { vm.savePins(shutterPin, it) },
            enabled = hwConnected,
        )

        if (simulatorActive) {
            Text(
                stringResource(R.string.gpio_simulator_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun BackupRestoreSectionContent(vm: PulsarViewModel) {
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(vm.exportSettingsJson().toByteArray())
                }
                Toast.makeText(context, context.getString(R.string.toast_settings_exported), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.toast_export_failed, e.message), Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, context.getString(R.string.toast_settings_imported), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.toast_import_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.backup_restore_desc),
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
                Text(stringResource(R.string.export_label))
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_label))
            }
        }
    }
}

@Composable
internal fun UpdatesSectionContent(vm: PulsarViewModel) {
    UpdatesSection(vm = vm)
}

@Composable
internal fun DeviceInfoSectionContent(vm: PulsarViewModel) {
    val connected = LocalDeviceConnected.current
    val info by vm.deviceInfo.collectAsState()
    val simulatorActive by vm.simulatorActive.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (simulatorActive) {
            Text(
                stringResource(R.string.hw_simulator_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (info != null) {
            val i = info!!
            InfoRow(stringResource(R.string.hw_chip), stringResource(R.string.hw_chip_value, i.chipModel, i.chipRevision))
            InfoRow(stringResource(R.string.hw_cpu), stringResource(R.string.hw_cpu_value, i.cpuFreqMhz))
            InfoRow(stringResource(R.string.hw_flash), formatKb(i.flashSizeKb))
            InfoRow(stringResource(R.string.hw_free_heap), formatKb(i.freeHeapKb))
            if (i.psramKb > 0) {
                InfoRow(stringResource(R.string.hw_psram), formatKb(i.psramKb.toLong()))
            }
            InfoRow(stringResource(R.string.hw_gpio), stringResource(R.string.hw_gpio_value, i.gpioCount, i.safeOutputCount))
            InfoRow(stringResource(R.string.hw_uptime), formatUptime(i.uptimeMinutes))

            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { vm.requestDeviceInfo() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) { Text(stringResource(R.string.refresh)) }
        } else if (connected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.status_querying_device), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                stringResource(R.string.hw_connect_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AboutSectionContent() {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.about_tagline),
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
            stringResource(R.string.about_author),
            style = MaterialTheme.typography.bodyMedium,
        )

        Text(
            stringResource(R.string.about_license),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { uriHandler.openUri("https://github.com/pulsar-trigger/pulsar-trigger") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.about_github))
        }

        OutlinedButton(
            onClick = { uriHandler.openUri("https://instagram.com/ehrocha.br") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.about_instagram))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.about_data_sources_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.about_data_sources),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        title = { Text(stringResource(R.string.dialog_rename_device)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.dialog_rename_instructions),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= maxLen) text = it },
                    label = { Text(stringResource(R.string.label_device_name_input)) },
                    prefix = { Text(stringResource(R.string.prefix_pulsar)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                    supportingText = { Text(stringResource(R.string.char_count, text.length, maxLen)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun UpdatesSection(vm: PulsarViewModel) {
    val connected = LocalDeviceConnected.current
    // Firmware state
    val fwManager = vm.firmwareManager
    val otaState by fwManager.state.collectAsState()
    val fwProgress by fwManager.progress.collectAsState()
    val fwRelease by fwManager.latestRelease.collectAsState()
    val fwError by fwManager.errorMessage.collectAsState()
    val status = LocalDeviceStatus.current
    val fwVersion = status?.fwVersion ?: ""

    // App state
    val updateManager = vm.appUpdateManager
    val updateState by updateManager.state.collectAsState()
    val appProgress by updateManager.progress.collectAsState()
    val appRelease by updateManager.latestRelease.collectAsState()
    val appError by updateManager.errorMessage.collectAsState()
    val appVersion = BuildConfig.VERSION_NAME

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ── Firmware ─────────────────────────────────────────────────
        Text(stringResource(R.string.label_firmware), style = MaterialTheme.typography.titleSmall)

        if (fwVersion.isNotEmpty()) {
            Text(
                stringResource(R.string.label_current_version, fwVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (otaState) {
            OtaState.IDLE -> {
                OutlinedButton(
                    onClick = { fwManager.checkForUpdate(fwVersion) },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_check_firmware))
                }
            }

            OtaState.CHECKING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_checking_github), style = MaterialTheme.typography.bodyMedium)
                }
            }

            OtaState.AVAILABLE -> {
                fwRelease?.let { release ->
                    Text(
                        stringResource(R.string.label_new_version, release.version),
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
                        Text(stringResource(R.string.btn_install_update))
                    }
                }
            }

            OtaState.DOWNLOADING -> {
                Text(stringResource(R.string.status_downloading_firmware), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { fwProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(fwProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { fwManager.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.cancel)) }
            }

            OtaState.UPLOADING -> {
                Text(stringResource(R.string.status_uploading_device), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { fwProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(fwProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { fwManager.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.cancel)) }
            }

            OtaState.VALIDATING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_validating_rebooting), style = MaterialTheme.typography.bodyMedium)
                }
            }

            OtaState.COMPLETE -> {
                Text(
                    stringResource(R.string.status_update_complete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = { fwManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.done)) }
            }

            OtaState.ERROR -> {
                Text(
                    fwError ?: stringResource(R.string.status_update_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { fwManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.dismiss)) }
            }
        }

        HorizontalDivider()

        // ── App ──────────────────────────────────────────────────────
        Text("App", style = MaterialTheme.typography.titleSmall)

        Text(
            "Current: v$appVersion",
            style = MaterialTheme.typography.bodyMedium,
        )

        when (updateState) {
            AppUpdateState.IDLE -> {
                OutlinedButton(
                    onClick = { updateManager.checkForUpdate(appVersion) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_check_app_update))
                }
            }

            AppUpdateState.CHECKING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.status_checking_github), style = MaterialTheme.typography.bodyMedium)
                }
            }

            AppUpdateState.UP_TO_DATE -> {
                Text(
                    stringResource(R.string.status_up_to_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { updateManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.ok)) }
            }

            AppUpdateState.AVAILABLE -> {
                appRelease?.let { release ->
                    Text(
                        stringResource(R.string.label_new_version, release.version),
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
                        Text(stringResource(R.string.btn_download_install))
                    }
                }
            }

            AppUpdateState.DOWNLOADING -> {
                Text(stringResource(R.string.status_downloading_apk), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { appProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${(appProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { updateManager.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.cancel)) }
            }

            AppUpdateState.READY_TO_INSTALL -> {
                Text(
                    stringResource(R.string.status_download_complete),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { updateManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.done)) }
            }

            AppUpdateState.ERROR -> {
                Text(
                    appError ?: stringResource(R.string.status_update_check_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { updateManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.dismiss)) }
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
                value = stringResource(R.string.gpio_value, selectedPin),
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
                                if (isDisabled) stringResource(R.string.gpio_in_use, pin)
                                else stringResource(R.string.gpio_value, pin),
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
internal fun IntervalometerSettingsPanel(vm: PulsarViewModel) {
    val defInterval by vm.defaultIntervalMs.collectAsState()
    val defExposure by vm.defaultExposureMs.collectAsState()
    val defCount by vm.defaultShotCount.collectAsState()
    val defDelay by vm.defaultDelayMs.collectAsState()
    val maxShots by vm.maxShotCount.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.section_default_values), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

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
                    stringResource(R.string.default_values_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TimePicker(
                    totalMs = defInterval,
                    onChanged = { vm.saveIntervalometerDefaults(it.coerceAtLeast(AppConfig.MIN_INTERVAL_MS), defExposure, defCount, defDelay) },
                    label = stringResource(R.string.label_default_interval),
                )

                TimePicker(
                    totalMs = defExposure,
                    onChanged = { vm.saveIntervalometerDefaults(defInterval, it.coerceAtLeast(AppConfig.MIN_DEFAULT_EXPOSURE_MS), defCount, defDelay) },
                    label = stringResource(R.string.label_default_exposure),
                )

                TimePicker(
                    totalMs = defDelay,
                    onChanged = { vm.saveIntervalometerDefaults(defInterval, defExposure, defCount, it) },
                    label = stringResource(R.string.label_default_start_delay),
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var defCountText by remember(defCount) { mutableStateOf(defCount.toString()) }
                    OutlinedTextField(
                        value = defCountText,
                        onValueChange = { defCountText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.label_default_shot_count)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            val v = defCountText.toIntOrNull()?.coerceIn(AppConfig.MIN_SHOT_COUNT, maxShots) ?: defCount
                            defCountText = v.toString()
                            vm.saveIntervalometerDefaults(defInterval, defExposure, v, defDelay)
                        }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.range_shot_count, maxShots)) },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var maxShotsText by remember(maxShots) { mutableStateOf(maxShots.toString()) }
                    OutlinedTextField(
                        value = maxShotsText,
                        onValueChange = { maxShotsText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.label_max_shot_count)) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            val v = maxShotsText.toIntOrNull()?.coerceIn(AppConfig.MIN_MAX_SHOTS, AppConfig.MAX_MAX_SHOTS) ?: maxShots
                            maxShotsText = v.toString()
                            vm.saveMaxShotCount(v)
                        }),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.range_max_shot_count)) },
                    )
                }

                OutlinedButton(
                    onClick = { vm.resetIntervalometerDefaults() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(stringResource(R.string.btn_reset_defaults))
                }
            }
        }
    }
}

@Composable
internal fun AstroSettingsPanel(vm: PulsarViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.settings_astro_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AstroPanel(vm, enabled = true)
    }
}

// ── Device Hardware Info helpers ──────────────────────────────────────────────

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

// ── Planner settings section ─────────────────────────────────────────────────

@Composable
internal fun PlannerSettingsSectionContent(vm: PulsarViewModel) {
    val cacheOptions = listOf(6L, 12L, 24L, 48L, 72L)
    var currentInterval by remember { mutableLongStateOf(vm.plannerManager.cacheIntervalHours) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.section_planner),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        Text(
            stringResource(R.string.planner_cache_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Cache interval selector
        Text(
            stringResource(R.string.planner_cache_interval),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            cacheOptions.forEach { hours ->
                FilterChip(
                    selected = currentInterval == hours,
                    onClick = {
                        currentInterval = hours
                        vm.plannerManager.cacheIntervalHours = hours
                    },
                    label = { Text(stringResource(R.string.planner_cache_hours, hours)) },
                )
            }
        }
    }
}
