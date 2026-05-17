/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.UserMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/** List of user-authored modes. Tap to edit; "+" to create a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModesScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    onEdit: (modeId: String?) -> Unit,
) {
    val modes by vm.userModes.collectAsState()
    val connected by vm.connected.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.modes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (modes.size < UserMode.MAX_USER_MODES) {
                FloatingActionButton(onClick = { onEdit(null) }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.modes_add))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.modes_help, UserMode.MAX_USER_MODES),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )

            if (modes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.modes_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                modes.forEach { mode ->
                    UserModeRow(
                        mode = mode,
                        canRun = connected,
                        onRun = {
                            vm.runUserMode(mode)
                            onBack()
                        },
                        onEdit = { onEdit(mode.id) },
                        onDelete = { vm.removeUserMode(mode.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserModeRow(
    mode: UserMode,
    canRun: Boolean,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // Row body opens the editor (secondary action); the Play icon is the
    // primary tap target and runs the mode. Disabled when not connected to
    // a real device — user modes don't run against the simulator path.
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(mode.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${mode.body.fwMode.name} · ${mode.body.shotCount}× ${mode.body.exposureMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRun, enabled = canRun) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.btn_start),
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

/** Editor for a single user mode. New mode if [editingId] is null.
 *  Reuses the same panel composables as the live mode screens, so the editor
 *  shows scrub fields with presets / sub-second support rather than raw ms. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeEditorScreen(
    vm: PulsarViewModel,
    editingId: String?,
    onBack: () -> Unit,
) {
    val modes by vm.userModes.collectAsState()
    val existing = remember(editingId, modes) { modes.firstOrNull { it.id == editingId } }
    val initial = existing?.body ?: UserMode.Body()

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var fwMode by remember { mutableStateOf(initial.fwMode) }
    // Shared params (Intervalometer / Astro / DarkFrame use these)
    var intervalMs by remember { mutableStateOf(initial.intervalMs) }
    var exposureMs by remember { mutableStateOf(initial.exposureMs) }
    var shotCount by remember { mutableStateOf(initial.shotCount) }
    var delayMs by remember { mutableStateOf(initial.delayMs) }
    // Astro-only
    var focalLength by remember { mutableStateOf(initial.focalLength) }
    var cropFactor by remember { mutableStateOf(initial.cropFactor) }
    var ruleDivisor by remember { mutableStateOf(initial.ruleDivisor) }
    // Ramp-only
    var rampStartExposureMs by remember { mutableStateOf(initial.rampStartExposureMs) }
    var rampEndExposureMs by remember { mutableStateOf(initial.rampEndExposureMs) }
    var rampSteps by remember { mutableStateOf(initial.rampSteps) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.modes_new else R.string.modes_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val canSave = name.isNotBlank()
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val mode = UserMode(
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                body = UserMode.Body(
                                    fwMode = fwMode,
                                    intervalMs = intervalMs,
                                    exposureMs = exposureMs,
                                    shotCount = shotCount,
                                    delayMs = delayMs,
                                    focalLength = focalLength,
                                    cropFactor = cropFactor,
                                    ruleDivisor = ruleDivisor,
                                    rampStartExposureMs = rampStartExposureMs,
                                    rampEndExposureMs = rampEndExposureMs,
                                    rampSteps = rampSteps,
                                ),
                            )
                            vm.upsertUserMode(mode)
                            onBack()
                        },
                    ) { Text(stringResource(R.string.modes_save)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.modes_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Firmware mode picker — drives which panel is shown below.
            val supported = listOf(TriggerMode.INTERVALOMETER, TriggerMode.ASTRO,
                TriggerMode.DARK_FRAME, TriggerMode.RAMP)
            var fwExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = fwExpanded, onExpandedChange = { fwExpanded = it }) {
                OutlinedTextField(
                    value = labelFor(fwMode),
                    onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.modes_field_fwmode)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fwExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = fwExpanded, onDismissRequest = { fwExpanded = false }) {
                    supported.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(labelFor(m)) },
                            onClick = { fwMode = m; fwExpanded = false },
                        )
                    }
                }
            }

            // Mode-specific panel — same composable as the live trigger view.
            when (fwMode) {
                TriggerMode.INTERVALOMETER -> IntervalometerPanelContent(
                    intervalMs = intervalMs,
                    exposureMs = exposureMs,
                    shotCount = shotCount,
                    delayMs = delayMs,
                    onIntervalChanged = { intervalMs = it },
                    onExposureChanged = { exposureMs = it },
                    onDelayChanged = { delayMs = it },
                    onShotCountChanged = { shotCount = it },
                )
                TriggerMode.ASTRO -> AstroPanelContent(
                    focalLength = focalLength,
                    cropFactor = cropFactor,
                    shotCount = shotCount,
                    delayMs = delayMs,
                    gapMs = intervalMs,
                    ruleDivisor = ruleDivisor,
                    onCropFactorChanged = { cropFactor = it },
                    onFocalLengthChanged = { focalLength = it },
                    onGapMsChanged = { intervalMs = it },
                    onDelayMsChanged = { delayMs = it },
                    onRuleChanged = { ruleDivisor = it },
                    onShotCountChanged = { shotCount = it },
                )
                TriggerMode.DARK_FRAME -> DarkFramePanelContent(
                    count = shotCount,
                    exposureMs = exposureMs,
                    gapMs = intervalMs,
                    onCountChanged = { shotCount = it },
                    onExposureMsChanged = { exposureMs = it },
                    onGapMsChanged = { intervalMs = it },
                )
                TriggerMode.RAMP -> RampPanelContent(
                    startExposureMs = rampStartExposureMs,
                    endExposureMs = rampEndExposureMs,
                    steps = rampSteps,
                    intervalMs = intervalMs,
                    onStartExposureChanged = { rampStartExposureMs = it },
                    onEndExposureChanged = { rampEndExposureMs = it },
                    onStepsChanged = { rampSteps = it },
                    onIntervalChanged = { intervalMs = it },
                )
                else -> Unit
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun labelFor(mode: TriggerMode): String = when (mode) {
    TriggerMode.INTERVALOMETER -> stringResource(R.string.mode_intervalometer)
    TriggerMode.ASTRO          -> stringResource(R.string.mode_astro)
    TriggerMode.DARK_FRAME     -> stringResource(R.string.mode_dark_frame)
    TriggerMode.RAMP           -> stringResource(R.string.mode_ramp)
    else                       -> mode.name
}
