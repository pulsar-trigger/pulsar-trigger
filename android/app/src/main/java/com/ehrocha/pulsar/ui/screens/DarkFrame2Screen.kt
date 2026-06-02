/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LensBlur
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
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

private enum class DfTab(val labelRes: Int) {
    EXPOSURE(R.string.iv2_tab_exposure),
    INTERVAL(R.string.iv2_tab_interval),
    SHOTS(R.string.iv2_tab_shots),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkFrame2Screen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    initialPresetId: String? = null,
) {
    val allModes by vm.userModes.collectAsState()
    val loadedPreset = remember(initialPresetId, allModes) {
        initialPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    var editingPresetId by rememberSaveable { mutableStateOf(initialPresetId) }

    var exposureMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.exposureMs ?: 0L)
    }
    var intervalMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.intervalMs ?: 0L)
    }
    var shotCount by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.shotCount ?: 0)
    }
    var useAutofocus by rememberSaveable {
        mutableStateOf(loadedPreset?.body?.useAutofocus ?: false)
    }
    var showSaveDialog by remember { mutableStateOf(false) }

    // "Pair with last lights" affordance — read the most-recent COMPLETED
    // bulb-style session and offer to auto-fill exposure + shot count from
    // it. Only meaningful when entering fresh (no preset loaded, no values
    // typed yet). Re-hidden once the user has dismissed it or values are set.
    val shotLog by vm.shotLog.entries.collectAsState()
    val pairCandidate = remember(loadedPreset, shotLog) {
        if (loadedPreset != null) null
        else vm.shotLog.findLastLightSession()
    }
    var pairDismissed by rememberSaveable { mutableStateOf(false) }
    val pairAvailable = pairCandidate != null && !pairDismissed &&
        exposureMs == 0L && shotCount == 0

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current
    val onCanon = vm.canonCcapiTransport.collectAsState().value != null
    val onPtp = vm.ptpTransport.collectAsState().value != null
    val onCanonBle = vm.canonBleTransport.collectAsState().value != null
    val onPtpIp = vm.ptpIpTransport.collectAsState().value != null
    val canControlAf = vm.activeTransportSupportsAf.collectAsState().value

    var tabIdx by rememberSaveable {
        mutableIntStateOf(if (loadedPreset != null) DfTab.entries.size - 1 else 0)
    }
    val tab = DfTab.entries[tabIdx]

    val totalMs = shotCount.toLong() * (exposureMs + intervalMs) - intervalMs.coerceAtMost(0L)

    val configComplete = exposureMs > 0L && intervalMs > 0L && shotCount > 0
    val currentTabValid = when (tab) {
        DfTab.EXPOSURE -> exposureMs > 0L
        DfTab.INTERVAL -> intervalMs > 0L
        DfTab.SHOTS -> shotCount > 0  // Dark frames need a finite count
    }
    // Sub-second host-timed bulb fails on every Canon transport, not just
    // CCAPI — gate on the same canControlAf flag as the other wizards
    // (was previously `onCanon` only, missing USB PTP / Canon BLE / PTP-IP).
    val subSecondCanon = canControlAf && exposureMs in 1L..999L
    val bottomHint = when {
        tab == DfTab.EXPOSURE && exposureMs == 0L -> stringResource(R.string.iv2_set_exposure)
        tab == DfTab.INTERVAL && intervalMs == 0L -> stringResource(R.string.iv2_set_interval)
        tab == DfTab.SHOTS && shotCount == 0 -> stringResource(R.string.df2_set_shots)
        else -> null
    }
    val wizardWarning = when {
        tab == DfTab.EXPOSURE && subSecondCanon ->
            stringResource(R.string.canon_sub_second_warning)
        else -> null
    }

    val editingPreset = remember(editingPresetId, allModes) {
        editingPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    val canSave = configComplete

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_dark_frame),
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
                tabCount = DfTab.entries.size,
                currentTabValid = currentTabValid,
                canStart = connected && !running,
                hint = if (running) null else bottomHint,
                onPrev = { if (tabIdx > 0) tabIdx-- },
                onNext = { if (tabIdx < DfTab.entries.size - 1) tabIdx++ },
                onStart = {
                    when {
                        exposureMs == 0L -> tabIdx = DfTab.EXPOSURE.ordinal
                        intervalMs == 0L -> tabIdx = DfTab.INTERVAL.ordinal
                        shotCount == 0 -> tabIdx = DfTab.SHOTS.ordinal
                        else -> {
                            vm.saveFlowSteps(
                                listOf(
                                    FlowStep.DarkFrame(
                                        shotCount = shotCount,
                                        exposureMs = exposureMs,
                                        gapMs = intervalMs,
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
                DfTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = tabIdx == i,
                        onClick = { tabIdx = i },
                        text = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
            if (pairAvailable && pairCandidate != null) {
                PairWithLastLightsCard(
                    candidate = pairCandidate,
                    onPair = {
                        exposureMs = pairCandidate.exposureMs
                        shotCount = pairCandidate.completedShots
                        // intervalMs default: 2s gap between darks
                        if (intervalMs == 0L) intervalMs = 2_000L
                        pairDismissed = true
                        // Jump to the last tab so the user can review + start.
                        tabIdx = DfTab.entries.size - 1
                    },
                    onDismiss = { pairDismissed = true },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (running) {
                    RunningView(
                        plannedShots = shotCount,
                        exposureMs = exposureMs,
                        gapMs = intervalMs,
                    )
                    return@Box
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    LensCapReminder(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    com.ehrocha.pulsar.ui.components.WizardWarning(
                        wizardWarning,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    when (tab) {
                        DfTab.EXPOSURE -> SegmentedTimeEditor(
                            ms = exposureMs,
                            onChange = { exposureMs = it },
                            rangeMs = 0L..86_400_000L,
                            enabled = !running,
                        )
                        DfTab.INTERVAL -> SegmentedTimeEditor(
                            ms = intervalMs,
                            onChange = { intervalMs = it },
                            rangeMs = 0L..3_600_000L,
                            enabled = !running,
                        )
                        DfTab.SHOTS -> Column(
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
            }
            SummaryStrip(
                shotCount = shotCount,
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
                    fwMode = TriggerMode.DARK_FRAME,
                    exposureMs = exposureMs,
                    intervalMs = intervalMs,
                    shotCount = shotCount,
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

/** Suggests matching dark-frame parameters to the user's most-recent
 *  completed bulb session (Astro / Intervalometer-bulb / Ramp). Tap →
 *  exposure + shot count auto-fill, jump to the last tab so the user can
 *  hit Start. Dismiss → hide for the rest of this wizard pass. */
@Composable
private fun PairWithLastLightsCard(
    candidate: com.ehrocha.pulsar.model.ShotLogEntry,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.LensBlur,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.df2_pair_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        R.string.df2_pair_body,
                        candidate.completedShots,
                        formatExposureMs(candidate.exposureMs),
                        candidate.modeLabel.lowercase(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            TextButton(onClick = onPair) {
                Text(stringResource(R.string.df2_pair_apply))
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.df2_pair_dismiss),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Quiet, persistent reminder that dark frames need the lens cap on. */
@Composable
private fun LensCapReminder(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.df2_lens_cap_reminder),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact human-readable exposure string for the pair card. Mirrors the
 *  formatting elsewhere in the wizards — sub-1s in ms, sub-60s in s,
 *  longer in mm:ss. */
private fun formatExposureMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1000}s"
    else -> {
        val totalSec = ms / 1000
        "%d:%02d".format(totalSec / 60, totalSec % 60)
    }
}
