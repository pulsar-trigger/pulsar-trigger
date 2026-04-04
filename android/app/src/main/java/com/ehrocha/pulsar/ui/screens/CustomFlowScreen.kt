/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.StatusFrame
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.model.SavedFlow
import com.ehrocha.pulsar.model.displayName
import com.ehrocha.pulsar.model.summaryLabel
import com.ehrocha.pulsar.ui.components.IntStepperField
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

private enum class FlowScreenState { LIBRARY, EDITOR }

@Composable
fun CustomFlowScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val status by vm.status.collectAsState()
    val steps by vm.flowSteps.collectAsState()
    val saved by vm.savedFlows.collectAsState()
    val running by vm.flowRunning.collectAsState()
    val paused by vm.flowPaused.collectAsState()
    val currentStep by vm.flowCurrentStep.collectAsState()

    var screenState by remember { mutableStateOf(FlowScreenState.LIBRARY) }
    var editingFlowName by remember { mutableStateOf<String?>(null) }

    // When a flow is running, always show the editor
    LaunchedEffect(running) {
        if (running) screenState = FlowScreenState.EDITOR
    }

    BackHandler(enabled = running || screenState == FlowScreenState.EDITOR) {
        if (!running) {
            screenState = FlowScreenState.LIBRARY
            editingFlowName = null
        }
    }

    when (screenState) {
        FlowScreenState.LIBRARY -> FlowLibraryView(
            saved = saved,
            status = status,
            connected = connected,
            onBack = onBack,
            onNewFlow = {
                vm.saveFlowSteps(emptyList())
                editingFlowName = null
                screenState = FlowScreenState.EDITOR
            },
            onEditFlow = { flow ->
                vm.loadSavedFlow(flow.name)
                editingFlowName = flow.name
                screenState = FlowScreenState.EDITOR
            },
            onDeleteFlow = { name -> vm.deleteSavedFlow(name) },
            onRunFlow = { flow ->
                vm.loadSavedFlow(flow.name)
                editingFlowName = flow.name
                screenState = FlowScreenState.EDITOR
                vm.startFlow()
            },
        )
        FlowScreenState.EDITOR -> FlowEditorView(
            vm = vm,
            steps = steps,
            saved = saved,
            status = status,
            connected = connected,
            running = running,
            paused = paused,
            currentStep = currentStep,
            editingFlowName = editingFlowName,
            onBack = {
                screenState = FlowScreenState.LIBRARY
                editingFlowName = null
            },
            onFlowNameChanged = { editingFlowName = it },
        )
    }
}

// ─── Flow Library (landing page) ─────────────────────────────────────────────

@Composable
private fun FlowLibraryView(
    saved: List<SavedFlow>,
    status: StatusFrame?,
    connected: Boolean,
    onBack: () -> Unit,
    onNewFlow: () -> Unit,
    onEditFlow: (SavedFlow) -> Unit,
    onDeleteFlow: (String) -> Unit,
    onRunFlow: (SavedFlow) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Header ───────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.flow_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            BatteryIndicator(status)
        }

        Spacer(Modifier.height(12.dp))

        // ── Saved flows list ─────────────────────────────────────────────
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (saved.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.flow_no_saved),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.flow_create_instruction),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                saved.forEach { flow ->
                    SavedFlowCard(
                        flow = flow,
                        connected = connected,
                        onEdit = { onEditFlow(flow) },
                        onDelete = { confirmDelete = flow.name },
                        onRun = { onRunFlow(flow) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── New flow button ──────────────────────────────────────────────
        Button(
            onClick = onNewFlow,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.btn_new_flow), fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))
    }

    // ── Delete confirmation dialog ───────────────────────────────────────
    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_flow)) },
            text = { Text(stringResource(R.string.dialog_delete_flow_msg, confirmDelete!!)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFlow(confirmDelete!!)
                    confirmDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SavedFlowCard(
    flow: SavedFlow,
    connected: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flow.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (flow.steps.size != 1) stringResource(R.string.flow_step_count_plural, flow.steps.size)
                        else stringResource(R.string.flow_step_count, flow.steps.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete, contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(
                    onClick = onRun,
                    enabled = connected && flow.steps.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow, contentDescription = stringResource(R.string.run),
                        modifier = Modifier.size(20.dp),
                        tint = if (connected && flow.steps.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    )
                }
            }

            // Step type summary chips
            if (flow.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    flow.steps.take(6).forEach { step ->
                        val context = LocalContext.current
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Icon(
                                    stepIcon(step.type),
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    step.type.displayName(context),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    if (flow.steps.size > 6) {
                        Text(
                            "+${flow.steps.size - 6}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
            }
        }
    }
}

// ─── Flow Editor (step list + execution) ─────────────────────────────────────

@Composable
private fun FlowEditorView(
    vm: PulsarViewModel,
    steps: List<FlowStep>,
    saved: List<SavedFlow>,
    status: StatusFrame?,
    connected: Boolean,
    running: Boolean,
    paused: Boolean,
    currentStep: Int,
    editingFlowName: String?,
    onBack: () -> Unit,
    onFlowNameChanged: (String?) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showSaveDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Header ───────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !running) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    if (editingFlowName != null) editingFlowName else stringResource(R.string.flow_new),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (editingFlowName != null) {
                    Text(
                        if (steps.size != 1) stringResource(R.string.flow_step_count_plural, steps.size)
                        else stringResource(R.string.flow_step_count, steps.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            BatteryIndicator(status)
        }

        Spacer(Modifier.height(12.dp))

        // ── Step list ────────────────────────────────────────────────────
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (steps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.flow_no_steps),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.flow_add_steps_instruction),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                steps.forEachIndexed { index, step ->
                    val isCurrent = running && index == currentStep
                    val isDone = running && index < currentStep

                    FlowStepCard(
                        step = step,
                        index = index,
                        totalSteps = steps.size,
                        isCurrent = isCurrent,
                        isDone = isDone,
                        isPaused = isCurrent && paused,
                        status = if (isCurrent) status else null,
                        enabled = !running,
                        onEdit = { editingIndex = index },
                        onDelete = {
                            vm.saveFlowSteps(steps.toMutableList().apply { removeAt(index) })
                        },
                        onMoveUp = {
                            if (index > 0) {
                                val mutable = steps.toMutableList()
                                val tmp = mutable[index]
                                mutable[index] = mutable[index - 1]
                                mutable[index - 1] = tmp
                                vm.saveFlowSteps(mutable)
                            }
                        },
                        onMoveDown = {
                            if (index < steps.size - 1) {
                                val mutable = steps.toMutableList()
                                val tmp = mutable[index]
                                mutable[index] = mutable[index + 1]
                                mutable[index + 1] = tmp
                                vm.saveFlowSteps(mutable)
                            }
                        },
                        onContinue = { vm.continueFlow() },
                    )
                }

                if (!running) {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btn_add_step))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Save button ──────────────────────────────────────────────────
        if (!running && steps.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    if (editingFlowName != null) {
                        vm.saveFlowAs(editingFlowName)
                    } else {
                        showSaveDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (editingFlowName != null) stringResource(R.string.save) else stringResource(R.string.btn_save_as))
            }

            Spacer(Modifier.height(8.dp))
        }

        // ── Action buttons ───────────────────────────────────────────────
        if (running) {
            Button(
                onClick = { vm.stopFlow() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_stop_flow), fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { vm.startFlow() },
                enabled = connected && steps.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_start_flow), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ── Dialogs ──────────────────────────────────────────────────────────
    if (showAddDialog) {
        AddStepDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { step ->
                vm.saveFlowSteps(steps + step)
                showAddDialog = false
            },
        )
    }

    if (editingIndex >= 0 && editingIndex < steps.size) {
        EditStepDialog(
            step = steps[editingIndex],
            onDismiss = { editingIndex = -1 },
            onSave = { updated ->
                val mutable = steps.toMutableList()
                mutable[editingIndex] = updated
                vm.saveFlowSteps(mutable)
                editingIndex = -1
            },
        )
    }

    if (showSaveDialog) {
        SaveFlowDialog(
            existingNames = saved.map { it.name },
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                vm.saveFlowAs(name)
                onFlowNameChanged(name)
                showSaveDialog = false
            },
        )
    }
}

// ─── Battery indicator (shared) ──────────────────────────────────────────────

@Composable
private fun BatteryIndicator(status: StatusFrame?) {
    if (status != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${status.batteryPct}%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (status.batteryPct < 20) Color(0xFFFF1744)
                            else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                val battIcon = when {
                    status.batteryPct > 75 -> "󰁹"
                    status.batteryPct > 25 -> "󰁾"
                    else -> "󰁺"
                }
                Text(text = battIcon, fontSize = 16.sp)
            }
        }
    }
}

// ─── Step card ───────────────────────────────────────────────────────────────

@Composable
private fun FlowStepCard(
    step: FlowStep,
    index: Int,
    totalSteps: Int,
    isCurrent: Boolean,
    isDone: Boolean,
    isPaused: Boolean,
    status: StatusFrame?,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onContinue: () -> Unit,
) {
    val containerColor = when {
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        isDone -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }
    val elevation = if (isCurrent) 4.dp else 1.dp

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = elevation,
        color = containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Step number badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (isDone) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    val context = LocalContext.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            stepIcon(step.type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.type.displayName(context),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (isCurrent) {
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    Text(
                        step.summaryLabel(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (enabled) {
                    // Reorder buttons
                    Column {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = index > 0,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_move_up),
                                modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = index < totalSteps - 1,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_move_down),
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit),
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ── Live execution info ──────────────────────────────────
            if (isCurrent && status != null && step.type != FlowStepType.PAUSE) {
                Spacer(Modifier.height(8.dp))

                // State label
                val stateLabel = when (status.state) {
                    DeviceState.RUNNING -> stringResource(R.string.flow_state_exposing)
                    DeviceState.WAITING -> stringResource(R.string.flow_state_waiting)
                    DeviceState.IDLE -> stringResource(R.string.flow_state_idle)
                    DeviceState.ERROR -> stringResource(R.string.flow_state_error)
                }
                val stateColor = when (status.state) {
                    DeviceState.RUNNING -> Color(0xFF4CAF50)
                    DeviceState.WAITING -> Color(0xFFFFA726)
                    DeviceState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    DeviceState.ERROR -> Color(0xFFFF1744)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // State badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = stateColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            stateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = stateColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Shot counter
                    Text(
                        stringResource(R.string.flow_shot_count, status.shotsTaken, step.shotCount),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                // Progress bar
                val targetCount = step.shotCount
                if (targetCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (status.shotsTaken.toFloat() / targetCount).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = stateColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                // Time remaining
                if (status.timeRemainingMs > 0) {
                    Spacer(Modifier.height(4.dp))
                    val secs = status.timeRemainingMs / 1000
                    val timeStr = if (secs >= 60) stringResource(R.string.flow_time_remaining_long, secs / 60, secs % 60)
                                  else stringResource(R.string.flow_time_remaining_short, secs)
                    Text(
                        timeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }

            // Pause continue button
            if (isPaused) {
                Spacer(Modifier.height(8.dp))

                // Pause step gets a "Waiting for user" badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFA726).copy(alpha = 0.15f),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.flow_waiting_for_user),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFA726),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.continue_label), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun stepIcon(type: FlowStepType) = when (type) {
    FlowStepType.INTERVALOMETER -> Icons.Default.Timer
    FlowStepType.ASTRO -> Icons.Default.Stars
    FlowStepType.PAUSE -> Icons.Default.PauseCircle
}

// ─── Add step dialog ─────────────────────────────────────────────────────────

@Composable
private fun AddStepDialog(
    onDismiss: () -> Unit,
    onAdd: (FlowStep) -> Unit,
) {
    var selectedType by remember { mutableStateOf<FlowStepType?>(null) }

    if (selectedType == null) {
        // Step type picker
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        stringResource(R.string.btn_add_step),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(16.dp))

                    FlowStepType.entries.forEach { type ->
                        val context = LocalContext.current
                        Surface(
                            onClick = { selectedType = type },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp),
                            ) {
                                Icon(
                                    stepIcon(type),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    type.displayName(context),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    } else {
        // Edit params for chosen type
        EditStepDialog(
            step = FlowStep(type = selectedType!!),
            onDismiss = { selectedType = null },
            onSave = onAdd,
        )
    }
}

// ─── Edit step dialog ────────────────────────────────────────────────────────

@Composable
private fun EditStepDialog(
    step: FlowStep,
    onDismiss: () -> Unit,
    onSave: (FlowStep) -> Unit,
) {
    var current by remember { mutableStateOf(step) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Intervalometer/Astro panels include their own title
                if (step.type != FlowStepType.INTERVALOMETER && step.type != FlowStepType.ASTRO) {
                    val context = LocalContext.current
                    Text(
                        step.type.displayName(context),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                when (step.type) {
                    FlowStepType.INTERVALOMETER -> IntervalometerStepEditor(current) { current = it }
                    FlowStepType.ASTRO -> AstroStepEditor(current) { current = it }
                    FlowStepType.PAUSE -> PauseStepEditor(current) { current = it }
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(current) }, shape = RoundedCornerShape(12.dp)) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

// ─── Step editors ────────────────────────────────────────────────────────────

@Composable
private fun IntervalometerStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    IntervalometerPanelContent(
        intervalMs = step.intervalMs,
        exposureMs = step.exposureMs,
        shotCount = step.shotCount,
        delayMs = step.delayMs,
        onIntervalChanged = { onChange(step.copy(intervalMs = it.coerceAtLeast(500))) },
        onExposureChanged = { onChange(step.copy(exposureMs = it.coerceAtLeast(50))) },
        onShotCountChanged = { onChange(step.copy(shotCount = it.coerceAtLeast(1))) },
        onDelayChanged = { onChange(step.copy(delayMs = it)) },
    )
}

@Composable
private fun AstroStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    AstroPanelContent(
        focalLength = step.focalLength,
        cropFactor = step.cropFactor,
        shotCount = step.shotCount,
        delayMs = step.delayMs,
        gapMs = step.gapMs,
        ruleDivisor = step.ruleDivisor,
        onCropFactorChanged = { onChange(step.copy(cropFactor = it)) },
        onFocalLengthChanged = { onChange(step.copy(focalLength = it)) },
        onGapMsChanged = { onChange(step.copy(gapMs = it.coerceAtLeast(500))) },
        onShotCountChanged = { onChange(step.copy(shotCount = it.coerceAtLeast(1))) },
        onDelayMsChanged = { onChange(step.copy(delayMs = it)) },
    )
}

@Composable
private fun PauseStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    OutlinedTextField(
        value = step.pauseLabel,
        onValueChange = { onChange(step.copy(pauseLabel = it)) },
        label = { Text(stringResource(R.string.label_pause_message)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(stringResource(R.string.pause_message_hint)) },
    )
}

// ─── Save Flow dialog ────────────────────────────────────────────────────────

@Composable
private fun SaveFlowDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val nameExists = existingNames.any { it.equals(name.trim(), ignoreCase = true) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(stringResource(R.string.dialog_save_flow), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_flow_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nameExists) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.flow_name_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name.trim()) },
                        enabled = name.isNotBlank(),
                    ) { Text(if (nameExists) stringResource(R.string.replace) else stringResource(R.string.save)) }
                }
            }
        }
    }
}


