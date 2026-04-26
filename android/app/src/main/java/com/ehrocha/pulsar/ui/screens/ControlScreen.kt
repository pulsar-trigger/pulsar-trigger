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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.graphics.vector.ImageVector
import com.ehrocha.pulsar.ui.components.IntStepperField
import com.ehrocha.pulsar.ui.components.ScrubField
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanelHelpHeader(title: String, helpText: String) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TooltipBox(
            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
            tooltip = {
                RichTooltip(
                    title = { Text(title) },
                    action = {
                        TextButton(onClick = { scope.launch { tooltipState.dismiss() } }) {
                            Text(stringResource(R.string.action_dismiss))
                        }
                    },
                ) {
                    Text(helpText)
                }
            },
            state = tooltipState,
        ) {
            IconButton(onClick = { scope.launch { tooltipState.show() } }) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.cd_help),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun DefaultActions(vm: PulsarViewModel, isRunning: Boolean) {
    val connected = LocalDeviceConnected.current
    val intervalMs by vm.intervalMs.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val shotCount by vm.shotCount.collectAsState()
    val delayMs by vm.delayMs.collectAsState()
    val totalMs = delayMs + shotCount.toLong() * (exposureMs + intervalMs)
    DefaultActionsContent(
        connected = connected,
        isRunning = isRunning,
        onStart = { vm.start() },
        onStop = { vm.stop() },
        estimatedDuration = formatDuration(totalMs),
    )
}

@Composable
internal fun DefaultActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    estimatedDuration: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            enabled = connected,
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isRunning) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (!isRunning && estimatedDuration != null) {
            Text(
                text = stringResource(R.string.status_estimated_duration, estimatedDuration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (isRunning) stringResource(R.string.status_sequence_running) else stringResource(R.string.status_ready_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == TriggerMode.PRESS_HOLD,
                onClick = { onModeSelected(TriggerMode.PRESS_HOLD) },
                enabled = connected,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.chip_hold_mode))
            }
            SegmentedButton(
                selected = mode == TriggerMode.PRESS_LOCK,
                onClick = { onModeSelected(TriggerMode.PRESS_LOCK) },
                enabled = connected,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.chip_lock_mode))
            }
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
    val focalLength by vm.astroFocalLength.collectAsState()
    val cropFactor by vm.astroCropFactor.collectAsState()
    val ruleDivisor by vm.astroRuleDivisor.collectAsState()
    val shotCount by vm.astroShotCount.collectAsState()
    val delayMs by vm.astroDelayMs.collectAsState()
    val gapMs by vm.astroGapMs.collectAsState()
    val expMs = AppConfig.astroExposureMs(focalLength, cropFactor, ruleDivisor)
    val totalMs = delayMs + shotCount.toLong() * (expMs + gapMs)
    AstroActionsContent(
        connected = connected,
        isRunning = isRunning,
        onStart = { vm.start() },
        onStop = { vm.stop() },
        estimatedDuration = formatDuration(totalMs),
    )
}

@Composable
internal fun AstroActionsContent(
    connected: Boolean,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    estimatedDuration: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = { if (isRunning) onStop() else onStart() },
            enabled = connected,
            shape = RoundedCornerShape(32.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                 else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(if (isRunning) R.string.btn_stop_astro else R.string.btn_start_astro),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (!isRunning && estimatedDuration != null) {
            Text(
                text = stringResource(R.string.status_estimated_duration, estimatedDuration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(if (isRunning) R.string.status_capturing_stars else R.string.status_ready_astro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Glanceable two-column summary for mode panels. */
@Composable
private fun HeroSummary(
    primaryLabel: String,
    primaryValue: String,
    secondaryLabel: String,
    secondaryValue: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    primaryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    primaryValue,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            VerticalDivider(
                modifier = Modifier.height(56.dp).padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    secondaryLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    secondaryValue,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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
        onShotCountChanged = { vm.setShotCount(it) },
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

    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = modifier) {
        HeroSummary(
            primaryLabel = stringResource(R.string.label_shots),
            primaryValue = "$shotCount",
            secondaryLabel = stringResource(R.string.label_total_duration),
            secondaryValue = formatDuration(totalSequenceTimeMs),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScrubField(
                label = stringResource(R.string.label_exposure),
                totalMs = exposureMs,
                onChanged = { onExposureChanged(it) },
                enabled = enabled,
            )

            TimePicker(
                totalMs = intervalMs,
                onChanged = { onIntervalChanged(it) },
                label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayChanged(it) },
                label = stringResource(R.string.label_start_delay) + " (hh:mm:ss)",
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
        onRuleChanged = { vm.setAstroRuleDivisor(it) },
        onShotCountChanged = { vm.setAstroShotCount(it) },
        enabled = enabled
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onRuleChanged: ((Int) -> Unit)? = null,
    onShotCountChanged: ((Int) -> Unit)? = null,
    enabled: Boolean = true,
) {
    val maxExposureMs = AppConfig.astroExposureMs(focalLength, cropFactor, ruleDivisor)
    val maxExposureS = AppConfig.astroExposureS(focalLength, cropFactor, ruleDivisor)
    val totalTimeMs = delayMs + shotCount.toLong() * (maxExposureMs + gapMs) - gapMs

    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ruleLabel = if (ruleDivisor == AppConfig.NPF_RULE_DIVISOR)
                        stringResource(R.string.label_npf_readout)
                    else
                        stringResource(R.string.label_rule_readout, ruleDivisor)
                    Text(
                        ruleLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.label_effective_focal, "%.1f".format(focalLength * cropFactor)),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_max_exposure), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(maxExposureMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    VerticalDivider(
                        modifier = Modifier.height(56.dp).padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_total_duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDuration(totalTimeMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                }

                val formulaText = if (ruleDivisor == AppConfig.NPF_RULE_DIVISOR)
                    stringResource(R.string.label_npf_formula, focalLength, "$cropFactor", "%.1f".format(maxExposureS))
                else
                    stringResource(R.string.label_astro_formula, ruleDivisor, focalLength, "$cropFactor", "%.1f".format(maxExposureS))
                Text(
                    formulaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().alpha(0.7f)
                )

                // NPF estimation note
                if (ruleDivisor == AppConfig.NPF_RULE_DIVISOR) {
                    var showNpfDialog by remember { mutableStateOf(false) }
                    val estimatedPitch = AppConfig.estimatedPixelPitchUm(cropFactor)
                    Text(
                        stringResource(R.string.npf_estimation_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showNpfDialog = true }
                            .padding(vertical = 2.dp),
                    )
                    if (showNpfDialog) {
                        AlertDialog(
                            onDismissRequest = { showNpfDialog = false },
                            title = { Text(stringResource(R.string.npf_dialog_title)) },
                            text = {
                                Text(stringResource(
                                    R.string.npf_dialog_body,
                                    "%.1f".format(estimatedPitch),
                                    "2.8",
                                ))
                            },
                            confirmButton = {
                                TextButton(onClick = { showNpfDialog = false }) {
                                    Text(stringResource(R.string.ok))
                                }
                            },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SENSOR_PRESETS.forEachIndexed { index, preset ->
                    SegmentedButton(
                        selected = cropFactor == preset.crop,
                        onClick = { if (enabled) onCropFactorChanged(preset.crop) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SENSOR_PRESETS.size),
                    ) {
                        Text("${preset.shortLabel}\n${preset.crop}×", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
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

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onRuleChanged != null) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = ruleDivisor == AppConfig.DEFAULT_RULE_DIVISOR,
                        onClick = { onRuleChanged(AppConfig.DEFAULT_RULE_DIVISOR) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    ) {
                        Text(stringResource(R.string.chip_500_rule))
                    }
                    SegmentedButton(
                        selected = ruleDivisor == AppConfig.TIGHT_RULE_DIVISOR,
                        onClick = { onRuleChanged(AppConfig.TIGHT_RULE_DIVISOR) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    ) {
                        Text(stringResource(R.string.chip_400_rule))
                    }
                    SegmentedButton(
                        selected = ruleDivisor == AppConfig.NPF_RULE_DIVISOR,
                        onClick = { onRuleChanged(AppConfig.NPF_RULE_DIVISOR) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    ) {
                        Text(stringResource(R.string.chip_npf_rule))
                    }
                }
            }

            TimePicker(
                totalMs = gapMs,
                onChanged = { onGapMsChanged(it) },
                label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = delayMs,
                onChanged = { onDelayMsChanged(it) },
                label = stringResource(R.string.label_start_delay) + " (hh:mm:ss)",
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
        }
    }
}

private data class SensorPreset(val labelRes: Int, val shortLabel: String, val crop: Float)

private val SENSOR_PRESETS = listOf(
    SensorPreset(R.string.preset_full_frame, "FF", 1.0f),
    SensorPreset(R.string.preset_aps_c_canon, "APS-C", 1.6f),
    SensorPreset(R.string.preset_aps_c_nikon_sony, "APS-C", 1.5f),
    SensorPreset(R.string.preset_micro_43, "M4/3", 2.0f),
)

@Composable
internal fun DarkFramePanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val count by vm.darkFrameCount.collectAsState()
    val exposureMs by vm.darkFrameExposureMs.collectAsState()
    val gapMs by vm.darkFrameGapMs.collectAsState()

    DarkFramePanelContent(
        count = count,
        exposureMs = exposureMs,
        gapMs = gapMs,
        onCountChanged = { vm.setDarkFrameCount(it) },
        onExposureMsChanged = { vm.setDarkFrameExposureMs(it) },
        onGapMsChanged = { vm.setDarkFrameGapMs(it) },
        enabled = enabled,
    )
}

@Composable
internal fun DarkFramePanelContent(
    modifier: Modifier = Modifier,
    count: Int,
    exposureMs: Long,
    gapMs: Long,
    onCountChanged: (Int) -> Unit,
    onExposureMsChanged: (Long) -> Unit,
    onGapMsChanged: (Long) -> Unit,
    enabled: Boolean = true,
) {
    val totalTimeMs = count.toLong() * (exposureMs + gapMs)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = modifier) {
        HeroSummary(
            primaryLabel = stringResource(R.string.label_exposure),
            primaryValue = formatDuration(exposureMs),
            secondaryLabel = stringResource(R.string.label_total_duration),
            secondaryValue = formatDuration(totalTimeMs),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TimePicker(
                totalMs = exposureMs,
                onChanged = { onExposureMsChanged(it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)) },
                label = stringResource(R.string.label_exposure) + " (hh:mm:ss)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = gapMs,
                onChanged = { onGapMsChanged(it.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS)) },
                label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
                enabled = enabled,
            )

            IntStepperField(
                label = stringResource(R.string.label_dark_frame_count),
                value = count,
                onValueChange = { onCountChanged(it.coerceAtLeast(1)) },
                min = 1,
                max = 999,
                enabled = enabled,
                presets = listOf(10, 20, 30, 50),
                presetLabel = { "$it" },
            )
}
    }
}

@Composable
internal fun RampPanel(vm: PulsarViewModel, enabled: Boolean = true) {
    val startExp by vm.rampStartExposureMs.collectAsState()
    val endExp by vm.rampEndExposureMs.collectAsState()
    val steps by vm.rampSteps.collectAsState()
    val intervalMs by vm.rampIntervalMs.collectAsState()

    RampPanelContent(
        startExposureMs = startExp,
        endExposureMs = endExp,
        steps = steps,
        intervalMs = intervalMs,
        onStartExposureChanged = { vm.setRampStartExposureMs(it) },
        onEndExposureChanged = { vm.setRampEndExposureMs(it) },
        onIntervalChanged = { vm.setRampIntervalMs(it) },
        onStepsChanged = { vm.setRampSteps(it) },
        enabled = enabled,
    )
}

@Composable
internal fun RampPanelContent(
    modifier: Modifier = Modifier,
    startExposureMs: Long,
    endExposureMs: Long,
    steps: Int,
    intervalMs: Long,
    onStartExposureChanged: (Long) -> Unit,
    onEndExposureChanged: (Long) -> Unit,
    onIntervalChanged: (Long) -> Unit,
    onStepsChanged: (Int) -> Unit,
    enabled: Boolean = true,
) {
    val avgExpMs = (startExposureMs + endExposureMs) / 2
    val totalTimeMs = steps.toLong() * (avgExpMs + intervalMs)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = modifier) {
        HeroSummary(
            primaryLabel = stringResource(R.string.label_shots),
            primaryValue = "$steps",
            secondaryLabel = stringResource(R.string.label_total_duration),
            secondaryValue = formatDuration(totalTimeMs),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TimePicker(
                totalMs = startExposureMs,
                onChanged = { onStartExposureChanged(it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)) },
                label = stringResource(R.string.label_ramp_start_exposure) + " (hh:mm:ss)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = endExposureMs,
                onChanged = { onEndExposureChanged(it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)) },
                label = stringResource(R.string.label_ramp_end_exposure) + " (hh:mm:ss)",
                enabled = enabled,
            )

            TimePicker(
                totalMs = intervalMs,
                onChanged = { onIntervalChanged(it.coerceAtLeast(AppConfig.MIN_INTERVAL_MS)) },
                label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
                enabled = enabled,
            )

            IntStepperField(
                label = stringResource(R.string.label_ramp_steps),
                value = steps,
                onValueChange = { onStepsChanged(it.coerceAtLeast(2)) },
                min = 2,
                max = 999,
                enabled = enabled,
                presets = listOf(20, 50, 100, 200),
                presetLabel = { "$it" },
            )
        }
    }
}

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
    USER_GUIDE(Icons.AutoMirrored.Filled.MenuBook, R.string.section_user_guide),
    LANGUAGE(Icons.Default.Language, R.string.section_language),
    DEVICE(Icons.Default.PhoneAndroid, R.string.section_device),
    PLANNER(Icons.Default.CalendarMonth, R.string.section_planner),
    BACKUP_RESTORE(Icons.Default.SaveAlt, R.string.section_backup_restore),
    UPDATES(Icons.Default.SystemUpdate, R.string.section_updates),
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

    val autoOff by vm.autoOffMinutes.collectAsState()
    val autoOffOptions = listOf(0, 5, 15, 30, 60, 120)

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

        // Auto-shutdown selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_auto_off), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    autoOffOptions.forEach { minutes ->
                        val label = if (minutes == 0) stringResource(R.string.auto_off_disabled)
                                    else stringResource(R.string.auto_off_minutes, minutes)
                        FilterChip(
                            selected = autoOff == minutes,
                            onClick = { vm.setAutoOff(minutes) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
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
    val safePins by vm.safeOutputPins.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_gpio_pins),
            helpText = stringResource(R.string.gpio_pins_help),
        )

        GpioPinSelector(
            label = stringResource(R.string.label_shutter_pin),
            selectedPin = shutterPin,
            disabledPin = focusPin,
            onPinSelected = { vm.savePins(it, focusPin) },
            enabled = hwConnected,
            pins = safePins,
        )

        GpioPinSelector(
            label = stringResource(R.string.label_focus_pin),
            selectedPin = focusPin,
            disabledPin = shutterPin,
            onPinSelected = { vm.savePins(shutterPin, it) },
            enabled = hwConnected,
            pins = safePins,
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
        PanelHelpHeader(
            title = stringResource(R.string.section_backup_restore),
            helpText = stringResource(R.string.backup_restore_help),
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
internal fun UserGuideSectionContent() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ── Overview ────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_overview_title),
            body = stringResource(R.string.guide_overview_body),
        )

        // ── Getting Started ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_getting_started_title),
            body = stringResource(R.string.guide_getting_started_body),
        )

        // ── Intervalometer ──────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_intervalometer_title),
            body = stringResource(R.string.guide_intervalometer_body),
        )

        // ── Astro Mode ──────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_astro_title),
            body = stringResource(R.string.guide_astro_body),
        )

        // ── Manual Mode ─────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_manual_title),
            body = stringResource(R.string.guide_manual_body),
        )

        // ── Dark Frames ─────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_dark_frames_title),
            body = stringResource(R.string.guide_dark_frames_body),
        )

        // ── Exposure Ramp ───────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_ramp_title),
            body = stringResource(R.string.guide_ramp_body),
        )

        // ── Flows & Presets ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_flows_title),
            body = stringResource(R.string.guide_flows_body),
        )

        // ── Astro Dashboard ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_dashboard_title),
            body = stringResource(R.string.guide_dashboard_body),
        )

        // ── Session Planner ─────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_planner_title),
            body = stringResource(R.string.guide_planner_body),
        )

        // ── Settings ────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_settings_title),
            body = stringResource(R.string.guide_settings_body),
        )

        // ── Tips ────────────────────────────────────────────────────────
        GuideSection(
            title = stringResource(R.string.guide_tips_title),
            body = stringResource(R.string.guide_tips_body),
        )
    }
}

@Composable
private fun GuideSection(title: String, body: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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

            OtaState.UP_TO_DATE -> {
                Text(
                    stringResource(R.string.status_up_to_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = { fwManager.reset() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.ok)) }
            }

            OtaState.AVAILABLE -> {
                fwRelease?.let { release ->
                    Text(
                        stringResource(R.string.label_new_version, release.version),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val releaseNotes = release.body
                        .lines()
                        .takeWhile { !it.startsWith("**Included") && !it.startsWith("Flash via") }
                        .joinToString("\n").trim()
                    if (releaseNotes.isNotBlank()) {
                        Text(
                            releaseNotes.take(200),
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
        Text(stringResource(R.string.label_app), style = MaterialTheme.typography.titleSmall)

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
    pins: List<Int>,
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
                pins.forEach { pin ->
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

    var currentThreshold by remember { mutableIntStateOf(vm.plannerManager.cloudClearThreshold) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelHelpHeader(
            title = stringResource(R.string.section_planner),
            helpText = stringResource(R.string.planner_cache_help),
        )

        // Cache interval selector
        Text(
            stringResource(R.string.planner_cache_interval),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            cacheOptions.forEachIndexed { index, hours ->
                SegmentedButton(
                    selected = currentInterval == hours,
                    onClick = {
                        currentInterval = hours
                        vm.plannerManager.cacheIntervalHours = hours
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = cacheOptions.size),
                ) {
                    Text(stringResource(R.string.planner_cache_hours, hours))
                }
            }
        }

        HorizontalDivider()

        // Cloud cover threshold slider
        Text(
            stringResource(R.string.planner_cloud_threshold),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(R.string.planner_cloud_threshold_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = currentThreshold.toFloat(),
                onValueChange = { currentThreshold = it.toInt() },
                onValueChangeFinished = { vm.plannerManager.cloudClearThreshold = currentThreshold },
                valueRange = 5f..80f,
                steps = 14,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.planner_cloud_threshold_value, currentThreshold),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
