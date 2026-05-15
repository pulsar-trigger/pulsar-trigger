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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
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
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.modes_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.modes_delete))
            }
        }
    }
}

/** Editor for a single user mode. New mode if [editingId] is null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeEditorScreen(
    vm: PulsarViewModel,
    editingId: String?,
    onBack: () -> Unit,
) {
    val modes by vm.userModes.collectAsState()
    val existing = remember(editingId, modes) { modes.firstOrNull { it.id == editingId } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var fwMode by remember { mutableStateOf(existing?.body?.fwMode ?: TriggerMode.INTERVALOMETER) }
    var intervalMs by remember { mutableStateOf((existing?.body?.intervalMs ?: 5000L).toString()) }
    var exposureMs by remember { mutableStateOf((existing?.body?.exposureMs ?: 200L).toString()) }
    var shotCount by remember { mutableStateOf((existing?.body?.shotCount ?: 30).toString()) }
    var delayMs by remember { mutableStateOf((existing?.body?.delayMs ?: 0L).toString()) }

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
                        && intervalMs.toLongOrNull() != null
                        && exposureMs.toLongOrNull() != null
                        && shotCount.toIntOrNull() != null
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val mode = UserMode(
                                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                body = UserMode.Body(
                                    fwMode = fwMode,
                                    intervalMs = intervalMs.toLongOrNull() ?: 5000L,
                                    exposureMs = exposureMs.toLongOrNull() ?: 200L,
                                    shotCount = shotCount.toIntOrNull() ?: 30,
                                    delayMs = delayMs.toLongOrNull() ?: 0L,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.modes_field_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val supported = listOf(TriggerMode.INTERVALOMETER, TriggerMode.ASTRO,
                TriggerMode.DARK_FRAME, TriggerMode.RAMP)
            var fwExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = fwExpanded, onExpandedChange = { fwExpanded = it }) {
                OutlinedTextField(
                    value = fwMode.name,
                    onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.modes_field_fwmode)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fwExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = fwExpanded, onDismissRequest = { fwExpanded = false }) {
                    supported.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = { fwMode = m; fwExpanded = false })
                    }
                }
            }

            OutlinedTextField(
                value = intervalMs, onValueChange = { intervalMs = it },
                label = { Text(stringResource(R.string.modes_field_interval_ms)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = exposureMs, onValueChange = { exposureMs = it },
                label = { Text(stringResource(R.string.modes_field_exposure_ms)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = shotCount, onValueChange = { shotCount = it },
                label = { Text(stringResource(R.string.modes_field_shot_count)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = delayMs, onValueChange = { delayMs = it },
                label = { Text(stringResource(R.string.modes_field_delay_ms)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
