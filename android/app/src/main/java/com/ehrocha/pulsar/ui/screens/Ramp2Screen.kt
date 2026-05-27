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
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.model.UserMode
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

private enum class RampTab(val labelRes: Int) {
    START(R.string.ramp2_tab_start),
    END(R.string.ramp2_tab_end),
    INTERVAL(R.string.iv2_tab_interval),
    STEPS(R.string.ramp2_tab_steps),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ramp2Screen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    initialPresetId: String? = null,
) {
    val allModes by vm.userModes.collectAsState()
    val loadedPreset = remember(initialPresetId, allModes) {
        initialPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    var editingPresetId by rememberSaveable { mutableStateOf(initialPresetId) }

    var startExposureMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.rampStartExposureMs ?: 0L)
    }
    var endExposureMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.rampEndExposureMs ?: 0L)
    }
    var intervalMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.intervalMs ?: 0L)
    }
    var steps by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.rampSteps ?: 0)
    }
    var useAutofocus by rememberSaveable {
        mutableStateOf(loadedPreset?.body?.useAutofocus ?: false)
    }
    var showSaveDialog by remember { mutableStateOf(false) }

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current
    val onCanon = vm.canonCcapiTransport.collectAsState().value != null
    val canControlAf = onCanon || vm.ptpTransport.collectAsState().value != null

    var tabIdx by rememberSaveable {
        mutableIntStateOf(if (loadedPreset != null) RampTab.entries.size - 1 else 0)
    }
    val tab = RampTab.entries[tabIdx]

    val avgExpMs = (startExposureMs + endExposureMs) / 2
    val totalMs = steps.toLong() * (avgExpMs + intervalMs)

    val configComplete = startExposureMs > 0L && endExposureMs > 0L
        && intervalMs > 0L && steps >= 2
    val currentTabValid = when (tab) {
        RampTab.START -> startExposureMs > 0L
        RampTab.END -> endExposureMs > 0L
        RampTab.INTERVAL -> intervalMs > 0L
        RampTab.STEPS -> steps >= 2
    }
    val subSecondStart = onCanon && startExposureMs in 1L..999L
    val subSecondEnd = onCanon && endExposureMs in 1L..999L
    val bottomHint = when {
        tab == RampTab.START && startExposureMs == 0L -> stringResource(R.string.ramp2_set_start)
        tab == RampTab.START && subSecondStart -> stringResource(R.string.canon_sub_second_warning)
        tab == RampTab.END && endExposureMs == 0L -> stringResource(R.string.ramp2_set_end)
        tab == RampTab.END && subSecondEnd -> stringResource(R.string.canon_sub_second_warning)
        tab == RampTab.INTERVAL && intervalMs == 0L -> stringResource(R.string.iv2_set_interval)
        tab == RampTab.STEPS && steps < 2 -> stringResource(R.string.ramp2_set_steps)
        else -> null
    }

    val editingPreset = remember(editingPresetId, allModes) {
        editingPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    val canSave = configComplete

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_ramp),
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
                                tint = if (editingPreset.bookmarked) MaterialTheme.colorScheme.primary
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
                tabCount = RampTab.entries.size,
                currentTabValid = currentTabValid,
                canStart = connected && !running,
                hint = if (running) null else bottomHint,
                onPrev = { if (tabIdx > 0) tabIdx-- },
                onNext = { if (tabIdx < RampTab.entries.size - 1) tabIdx++ },
                onStart = {
                    when {
                        startExposureMs == 0L -> tabIdx = RampTab.START.ordinal
                        endExposureMs == 0L -> tabIdx = RampTab.END.ordinal
                        intervalMs == 0L -> tabIdx = RampTab.INTERVAL.ordinal
                        steps < 2 -> tabIdx = RampTab.STEPS.ordinal
                        else -> {
                            vm.saveFlowSteps(
                                listOf(
                                    FlowStep.Ramp(
                                        startExposureMs = startExposureMs,
                                        endExposureMs = endExposureMs,
                                        steps = steps,
                                        intervalMs = intervalMs,
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
                RampTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = tabIdx == i,
                        onClick = { tabIdx = i },
                        text = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (running) {
                    RunningView(plannedShots = steps)
                    return@Box
                }
                when (tab) {
                    RampTab.START -> SegmentedTimeEditor(
                        ms = startExposureMs,
                        onChange = { startExposureMs = it },
                        rangeMs = 0L..86_400_000L,
                        enabled = !running,
                    )
                    RampTab.END -> SegmentedTimeEditor(
                        ms = endExposureMs,
                        onChange = { endExposureMs = it },
                        rangeMs = 0L..86_400_000L,
                        enabled = !running,
                    )
                    RampTab.INTERVAL -> SegmentedTimeEditor(
                        ms = intervalMs,
                        onChange = { intervalMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    RampTab.STEPS -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        ShotsEditor(
                            value = steps,
                            onChange = { steps = it },
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
                shotCount = steps,
                continuous = false,
                totalMs = totalMs,
                cameraHintRes = R.string.cam_hint_bulb,
            )
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            initialName = editingPreset?.name ?: "",
            isUpdate = editingPreset != null,
            onConfirm = { name ->
                val body = UserMode.Body(
                    fwMode = TriggerMode.RAMP,
                    rampStartExposureMs = startExposureMs,
                    rampEndExposureMs = endExposureMs,
                    rampSteps = steps,
                    intervalMs = intervalMs,
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
