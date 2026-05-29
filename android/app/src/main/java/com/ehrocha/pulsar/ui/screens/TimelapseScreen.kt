/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.model.UserMode
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

private enum class TlTab(val labelRes: Int) {
    INTERVAL(R.string.iv2_tab_interval),
    DELAY(R.string.iv2_tab_delay),
    SHOTS(R.string.iv2_tab_shots),
}

/**
 * Timelapse mode — the camera controls exposure via its own shutter-speed
 * setting (M / A / S / P). Pulsar just pulses the shutter
 * [AppConfig.TIMELAPSE_PULSE_MS] long, every [intervalMs] ms, [shotCount]
 * times. No exposure tab; otherwise the same wizard pattern as Iv2.
 *
 * Storage uses `fwMode = TIMELAPSE` so presets stay separate from
 * Intervalometer presets in the picker. The firmware-side payload is the
 * usual INTERVALOMETER FlowStep with `exposureMs = TIMELAPSE_PULSE_MS`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelapseScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    initialPresetId: String? = null,
) {
    val allModes by vm.userModes.collectAsState()
    val loadedPreset = remember(initialPresetId, allModes) {
        initialPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    var editingPresetId by rememberSaveable { mutableStateOf(initialPresetId) }

    var intervalMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.intervalMs ?: 0L)
    }
    var delayMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.delayMs ?: 0L)
    }
    var shotCount by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.shotCount ?: 0)
    }
    // Daylight Timelapse usually wants per-shot AF; default ON for this mode
    // unlike the bulb-based wizards.
    var useAutofocus by rememberSaveable {
        mutableStateOf(loadedPreset?.body?.useAutofocus ?: true)
    }
    var showSaveDialog by remember { mutableStateOf(false) }

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current
    val onCanon = vm.canonCcapiTransport.collectAsState().value != null
    val onPtp = vm.ptpTransport.collectAsState().value != null
    val onCanonBle = vm.canonBleTransport.collectAsState().value != null
    val canControlAf = onCanon || onPtp || onCanonBle

    var tabIdx by rememberSaveable {
        mutableIntStateOf(if (loadedPreset != null) TlTab.entries.size - 1 else 0)
    }
    val tab = TlTab.entries[tabIdx]

    val continuous = shotCount == 0
    val pulseMs = AppConfig.TIMELAPSE_PULSE_MS
    val totalMs = if (continuous) 0L
                  else delayMs + shotCount.toLong() * (pulseMs + intervalMs) - intervalMs

    val configComplete = intervalMs > 0L
    val currentTabValid = when (tab) {
        TlTab.INTERVAL -> intervalMs > 0L
        TlTab.DELAY -> true
        TlTab.SHOTS -> true
    }
    val bottomHint = when {
        tab == TlTab.INTERVAL && intervalMs == 0L -> stringResource(R.string.iv2_set_interval)
        tab == TlTab.INTERVAL && intervalMs in 1L..1999L -> stringResource(R.string.interval_short_warning)
        tab == TlTab.SHOTS && continuous && configComplete ->
            stringResource(R.string.iv2_continuous_warning)
        tab == TlTab.SHOTS && !configComplete -> stringResource(R.string.iv2_set_interval)
        else -> null
    }
    val hintIsContinuous = continuous && configComplete

    val editingPreset = remember(editingPresetId, allModes) {
        editingPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    val canSave = intervalMs > 0L

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_timelapse),
                onBack = onBack,
                actions = {
                    if (editingPreset != null) {
                        IconButton(onClick = { vm.toggleUserModeBookmark(editingPreset.id) }) {
                            Icon(
                                if (editingPreset.bookmarked) Icons.Default.Bookmark
                                else Icons.Default.BookmarkBorder,
                                contentDescription = stringResource(
                                    if (editingPreset.bookmarked) R.string.preset_picker_unbookmark
                                    else R.string.preset_picker_bookmark,
                                ),
                                tint = if (editingPreset.bookmarked)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { showSaveDialog = true }, enabled = canSave && !running) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.preset_save_action),
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomBar(
                running = running,
                currentTabIdx = tabIdx,
                tabCount = TlTab.entries.size,
                currentTabValid = currentTabValid,
                canStart = connected && !running,
                hint = if (running) null else bottomHint,
                hintIsAccent = hintIsContinuous,
                onPrev = { if (tabIdx > 0) tabIdx-- },
                onNext = { if (tabIdx < TlTab.entries.size - 1) tabIdx++ },
                onStart = {
                    when {
                        intervalMs == 0L -> tabIdx = TlTab.INTERVAL.ordinal
                        else -> {
                            vm.saveFlowSteps(
                                listOf(
                                    FlowStep.Intervalometer(
                                        intervalMs = intervalMs,
                                        exposureMs = pulseMs,
                                        shotCount = shotCount,
                                        delayMs = delayMs,
                                        useAutofocus = useAutofocus,
                                    )
                                )
                            )
                            vm.startFlow()
                        }
                    }
                },
                onStop = { vm.stopFlow() },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tabIdx) {
                TlTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = tabIdx == i,
                        onClick = { tabIdx = i },
                        text = { Text(stringResource(t.labelRes)) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (running) {
                    RunningView(plannedShots = shotCount)
                    return@Box
                }
                when (tab) {
                    TlTab.INTERVAL -> SegmentedTimeEditor(
                        ms = intervalMs,
                        onChange = { intervalMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    TlTab.DELAY -> SegmentedTimeEditor(
                        ms = delayMs,
                        onChange = { delayMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    TlTab.SHOTS -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        ShotsEditor(
                            value = shotCount,
                            onChange = { shotCount = it },
                            enabled = !running,
                        )
                        if (canControlAf) {
                            com.ehrocha.pulsar.ui.components.AutofocusToggle(
                                checked = useAutofocus,
                                onCheckedChange = { useAutofocus = it },
                                enabled = !running,
                            )
                        }
                    }
                }
            }

            SummaryStrip(
                shotCount = shotCount,
                continuous = continuous,
                totalMs = totalMs,
                cameraHintRes = R.string.cam_hint_pulse,
            )
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            initialName = editingPreset?.name ?: "",
            isUpdate = editingPreset != null,
            onConfirm = { name ->
                val body = UserMode.Body(
                    fwMode = TriggerMode.TIMELAPSE,
                    intervalMs = intervalMs,
                    exposureMs = pulseMs,
                    shotCount = shotCount,
                    delayMs = delayMs,
                    useAutofocus = useAutofocus,
                )
                val mode = if (editingPreset != null) {
                    editingPreset.copy(name = name.trim(), body = body)
                } else {
                    UserMode(name = name.trim(), body = body)
                }
                vm.upsertUserMode(mode)
                editingPresetId = mode.id
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}
