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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text("Custom Flow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                                "No saved flows",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Create a flow to chain multiple shooting\nmodes, pauses, and sequences.",
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
            Text("NEW FLOW", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))
    }

    // ── Delete confirmation dialog ───────────────────────────────────────
    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete Flow") },
            text = { Text("Delete \"$confirmDelete\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFlow(confirmDelete!!)
                    confirmDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
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
                        "${flow.steps.size} step${if (flow.steps.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete, contentDescription = "Delete",
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
                        Icons.Default.PlayArrow, contentDescription = "Run",
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
                                    step.type.displayName(),
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    if (editingFlowName != null) editingFlowName else "New Flow",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (editingFlowName != null) {
                    Text(
                        "${steps.size} step${if (steps.size != 1) "s" else ""}",
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
                                "No steps yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Add steps to build your custom shooting flow.\nMix modes, pauses, and sequences.",
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
                        Text("Add Step")
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
                Text(if (editingFlowName != null) "Save" else "Save As…")
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
                Text("STOP FLOW", fontWeight = FontWeight.Bold)
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
                Text("START FLOW", fontWeight = FontWeight.Bold)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            stepIcon(step.type),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.type.displayName(),
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
                        step.summaryLabel(),
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
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up",
                                modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = index < totalSteps - 1,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down",
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit",
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete",
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
                    DeviceState.RUNNING -> "Exposing"
                    DeviceState.WAITING -> "Waiting"
                    DeviceState.IDLE -> "Idle"
                    DeviceState.ERROR -> "Error"
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
                    if (step.type != FlowStepType.HDR) {
                        Text(
                            "Shot ${status.shotsTaken} of ${step.shotCount}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Text(
                            "Bracket ${status.shotsTaken} of ${step.hdrExposures.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }

                // Progress bar
                val targetCount = if (step.type == FlowStepType.HDR) step.hdrExposures.size else step.shotCount
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
                    val timeStr = if (secs >= 60) "${secs / 60}m ${secs % 60}s remaining"
                                  else "${secs}s remaining"
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
                        "Waiting for user",
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
                    Text("Continue", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun stepIcon(type: FlowStepType) = when (type) {
    FlowStepType.INTERVALOMETER -> Icons.Default.Timer
    FlowStepType.ASTRO -> Icons.Default.Stars
    FlowStepType.SOUND -> Icons.Default.Mic
    FlowStepType.LIGHTNING -> Icons.Default.FlashOn
    FlowStepType.LASER -> Icons.Default.Sensors
    FlowStepType.HDR -> Icons.Default.HdrOn
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
                        "Add Step",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(16.dp))

                    val enabledTypes = setOf(
                        FlowStepType.INTERVALOMETER,
                        FlowStepType.ASTRO,
                        FlowStepType.PAUSE,
                    )

                    FlowStepType.entries.forEach { type ->
                        val enabled = type in enabledTypes
                        Surface(
                            onClick = { if (enabled) selectedType = type },
                            enabled = enabled,
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
                                    tint = if (enabled) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        type.displayName(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                    )
                                    if (!enabled) {
                                        Text(
                                            "Coming soon",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text("Cancel")
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
                    Text(
                        step.type.displayName(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                when (step.type) {
                    FlowStepType.INTERVALOMETER -> IntervalometerStepEditor(current) { current = it }
                    FlowStepType.ASTRO -> AstroStepEditor(current) { current = it }
                    FlowStepType.SOUND -> SoundStepEditor(current) { current = it }
                    FlowStepType.LIGHTNING -> LightningStepEditor(current) { current = it }
                    FlowStepType.LASER -> LaserStepEditor(current) { current = it }
                    FlowStepType.HDR -> HdrStepEditor(current) { current = it }
                    FlowStepType.PAUSE -> PauseStepEditor(current) { current = it }
                }

                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(current) }, shape = RoundedCornerShape(12.dp)) {
                        Text("Save")
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
private fun SoundStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IntStepperField(
            label = "Sound Threshold",
            value = step.soundThreshold,
            onValueChange = { onChange(step.copy(soundThreshold = it)) },
            min = 0,
            max = 4095,
            step = 10,
        )

        TimePicker(
            totalMs = step.exposureMs,
            onChanged = { onChange(step.copy(exposureMs = it.coerceAtLeast(50))) },
            label = "Exposure duration",
            showMs = true,
        )

        IntStepperField(
            label = "Shots to capture",
            value = step.shotCount,
            onValueChange = { onChange(step.copy(shotCount = it.coerceAtLeast(1))) },
            min = 1,
            max = 999,
        )
    }
}

@Composable
private fun LightningStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IntStepperField(
            label = "Sensitivity",
            value = step.lightningSensitivity,
            onValueChange = { onChange(step.copy(lightningSensitivity = it)) },
            min = 1,
            max = 5,
        )

        TimePicker(
            totalMs = step.exposureMs,
            onChanged = { onChange(step.copy(exposureMs = it.coerceAtLeast(50))) },
            label = "Exposure duration",
            showMs = true,
        )

        IntStepperField(
            label = "Shots to capture",
            value = step.shotCount,
            onValueChange = { onChange(step.copy(shotCount = it.coerceAtLeast(1))) },
            min = 1,
            max = 999,
        )
    }
}

@Composable
private fun LaserStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TimePicker(
            totalMs = step.exposureMs,
            onChanged = { onChange(step.copy(exposureMs = it.coerceAtLeast(50))) },
            label = "Exposure duration",
            showMs = true,
        )

        IntStepperField(
            label = "Shots to capture",
            value = step.shotCount,
            onValueChange = { onChange(step.copy(shotCount = it.coerceAtLeast(1))) },
            min = 1,
            max = 999,
        )
    }
}

@Composable
private fun HdrStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    Text(
        "Bracket exposures (ms), comma-separated:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var text by remember { mutableStateOf(step.hdrExposures.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Exposures") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            val parsed = text.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
            if (parsed.isNotEmpty()) {
                onChange(step.copy(hdrExposures = parsed.take(5)))
            }
        }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Max 5 brackets, e.g. 100, 200, 400, 800, 1600") },
    )
}

@Composable
private fun PauseStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    OutlinedTextField(
        value = step.pauseLabel,
        onValueChange = { onChange(step.copy(pauseLabel = it)) },
        label = { Text("Pause message") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Shown when flow reaches this step") },
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
                Text("Save Flow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Flow name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nameExists) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A flow with this name already exists and will be replaced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name.trim()) },
                        enabled = name.isNotBlank(),
                    ) { Text(if (nameExists) "Replace" else "Save") }
                }
            }
        }
    }
}


