/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.model.displayName
import com.ehrocha.pulsar.model.summaryLabel
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

@Composable
fun CustomFlowScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val status by vm.status.collectAsState()
    val steps by vm.flowSteps.collectAsState()
    val running by vm.flowRunning.collectAsState()
    val paused by vm.flowPaused.collectAsState()
    val currentStep by vm.flowCurrentStep.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }

    BackHandler(enabled = running) { /* block back while running */ }

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
            Text("Custom Flow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
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
                            text = "${status!!.batteryPct}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (status!!.batteryPct < 20) Color(0xFFFF1744)
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(4.dp))
                        val battIcon = when {
                            status!!.batteryPct > 75 -> "󰁹"
                            status!!.batteryPct > 25 -> "󰁾"
                            else -> "󰁺"
                        }
                        Text(text = battIcon, fontSize = 16.sp)
                    }
                }
            }
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

            // Pause continue button
            if (isPaused) {
                Spacer(Modifier.height(8.dp))
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

                    FlowStepType.entries.forEach { type ->
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
                                Text(type.displayName(), style = MaterialTheme.typography.bodyLarge)
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
                Text(
                    step.type.displayName(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

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
    NumberField("Interval (ms)", step.intervalMs.toString()) {
        onChange(step.copy(intervalMs = it.toLongOrNull()?.coerceAtLeast(500) ?: step.intervalMs))
    }
    NumberField("Exposure (ms)", step.exposureMs.toString()) {
        onChange(step.copy(exposureMs = it.toLongOrNull()?.coerceAtLeast(50) ?: step.exposureMs))
    }
    NumberField("Shot count", step.shotCount.toString()) {
        onChange(step.copy(shotCount = it.toIntOrNull()?.coerceAtLeast(1) ?: step.shotCount))
    }
    NumberField("Start delay (ms)", step.delayMs.toString()) {
        onChange(step.copy(delayMs = it.toLongOrNull()?.coerceAtLeast(0) ?: step.delayMs))
    }
}

@Composable
private fun AstroStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    NumberField("Focal length (mm)", step.focalLength.toString()) {
        onChange(step.copy(focalLength = it.toIntOrNull()?.coerceIn(4, 1200) ?: step.focalLength))
    }
    NumberField("Crop factor (×10)", (step.cropFactor * 10).toInt().toString()) {
        val v = it.toIntOrNull()?.coerceIn(1, 100) ?: (step.cropFactor * 10).toInt()
        onChange(step.copy(cropFactor = v / 10f))
    }
    val expS = step.ruleDivisor.toDouble() / (step.focalLength * step.cropFactor)
    Text(
        "Calculated exposure: ${String.format("%.1f", expS)}s",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    NumberField("Gap between shots (ms)", step.gapMs.toString()) {
        onChange(step.copy(gapMs = it.toLongOrNull()?.coerceAtLeast(500) ?: step.gapMs))
    }
    NumberField("Shot count", step.shotCount.toString()) {
        onChange(step.copy(shotCount = it.toIntOrNull()?.coerceAtLeast(1) ?: step.shotCount))
    }
    NumberField("Start delay (ms)", step.delayMs.toString()) {
        onChange(step.copy(delayMs = it.toLongOrNull()?.coerceAtLeast(0) ?: step.delayMs))
    }

    // Rule selector
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("NPF Rule: ", style = MaterialTheme.typography.bodyMedium)
        FilterChip(
            selected = step.ruleDivisor == 500,
            onClick = { onChange(step.copy(ruleDivisor = 500)) },
            label = { Text("500") },
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = step.ruleDivisor == 400,
            onClick = { onChange(step.copy(ruleDivisor = 400)) },
            label = { Text("400") },
        )
    }
}

@Composable
private fun SoundStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    NumberField("Threshold (0–4095)", step.soundThreshold.toString()) {
        onChange(step.copy(soundThreshold = it.toIntOrNull()?.coerceIn(0, 4095) ?: step.soundThreshold))
    }
    NumberField("Exposure (ms)", step.exposureMs.toString()) {
        onChange(step.copy(exposureMs = it.toLongOrNull()?.coerceAtLeast(50) ?: step.exposureMs))
    }
    NumberField("Shots to capture", step.shotCount.toString()) {
        onChange(step.copy(shotCount = it.toIntOrNull()?.coerceAtLeast(1) ?: step.shotCount))
    }
}

@Composable
private fun LightningStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    NumberField("Sensitivity (1–5)", step.lightningSensitivity.toString()) {
        onChange(step.copy(lightningSensitivity = it.toIntOrNull()?.coerceIn(1, 5) ?: step.lightningSensitivity))
    }
    NumberField("Exposure (ms)", step.exposureMs.toString()) {
        onChange(step.copy(exposureMs = it.toLongOrNull()?.coerceAtLeast(50) ?: step.exposureMs))
    }
    NumberField("Shots to capture", step.shotCount.toString()) {
        onChange(step.copy(shotCount = it.toIntOrNull()?.coerceAtLeast(1) ?: step.shotCount))
    }
}

@Composable
private fun LaserStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    NumberField("Exposure (ms)", step.exposureMs.toString()) {
        onChange(step.copy(exposureMs = it.toLongOrNull()?.coerceAtLeast(50) ?: step.exposureMs))
    }
    NumberField("Shots to capture", step.shotCount.toString()) {
        onChange(step.copy(shotCount = it.toIntOrNull()?.coerceAtLeast(1) ?: step.shotCount))
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

@Composable
private fun NumberField(
    label: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter { c -> c.isDigit() } },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
