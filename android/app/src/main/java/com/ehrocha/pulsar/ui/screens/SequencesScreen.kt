/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.stacking.SequenceFolder
import com.ehrocha.pulsar.stacking.SequenceLabels
import com.ehrocha.pulsar.stacking.SequenceRepository
import com.ehrocha.pulsar.stacking.Stacker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private enum class SortMode { NEWEST, OLDEST, NAME }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SequencesScreen(
    onBack: () -> Unit,
    onOpenSequence: (String) -> Unit,
) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<SequenceFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sortMode by remember { mutableStateOf(SortMode.NEWEST) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<SequenceFolder?>(null) }
    var renameTarget by remember { mutableStateOf<SequenceFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<SequenceFolder?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        loading = true
        folders = withContext(Dispatchers.IO) { SequenceRepository.list(context) }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    val displayFolders = remember(folders, sortMode) {
        when (sortMode) {
            SortMode.NEWEST -> folders.sortedByDescending { it.mostRecentMs }
            SortMode.OLDEST -> folders.sortedBy { it.mostRecentMs }
            SortMode.NAME -> folders.sortedBy { SequenceLabels.get(context, it.path) ?: it.name }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.sequences_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // Sort menu
            Box {
                TextButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (sortMode) {
                            SortMode.NEWEST -> stringResource(R.string.sort_newest)
                            SortMode.OLDEST -> stringResource(R.string.sort_oldest)
                            SortMode.NAME -> stringResource(R.string.sort_name)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_newest)) },
                        onClick = { sortMode = SortMode.NEWEST; sortMenuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_oldest)) },
                        onClick = { sortMode = SortMode.OLDEST; sortMenuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_name)) },
                        onClick = { sortMode = SortMode.NAME; sortMenuOpen = false },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            displayFolders.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.sequences_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(displayFolders, key = { it.path }) { folder ->
                        SequenceFolderRow(
                            folder = folder,
                            onClick = { onOpenSequence(folder.path) },
                            onLongPress = { actionTarget = folder },
                        )
                    }
                }
            }
        }
    }

    // Action sheet (rename / delete)
    if (actionTarget != null) {
        val target = actionTarget!!
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(SequenceLabels.get(context, target.path) ?: target.name) },
            text = { Text(stringResource(R.string.sequences_action_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    actionTarget = null
                    renameTarget = target
                }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    actionTarget = null
                    deleteTarget = target
                }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    if (renameTarget != null) {
        val target = renameTarget!!
        var label by remember(target.path) {
            mutableStateOf(SequenceLabels.get(context, target.path) ?: target.name)
        }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.dialog_rename_sequence)) },
            text = {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SequenceLabels.set(context, target.path, label)
                    renameTarget = null
                    scope.launch { reload() }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.dialog_delete_sequence)) },
            text = {
                Text(stringResource(R.string.dialog_delete_sequence_msg, target.frames.size,
                    SequenceLabels.get(context, target.path) ?: target.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        withContext(Dispatchers.IO) { SequenceRepository.deleteSequence(context, target) }
                        reload()
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SequenceFolderRow(
    folder: SequenceFolder,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val customLabel = SequenceLabels.get(context, folder.path)
    val date = remember(folder.mostRecentMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(folder.mostRecentMs))
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                Icons.Default.Layers,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(customLabel ?: folder.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.sequences_frame_count, folder.frames.size, date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SequenceDetailScreen(
    sequencePath: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var folder by remember { mutableStateOf<SequenceFolder?>(null) }
    var loading by remember { mutableStateOf(true) }
    var processing by remember { mutableStateOf(false) }
    var processedFrames by remember { mutableIntStateOf(0) }
    var totalFrames by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf<Uri?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sequencePath) {
        loading = true
        folder = withContext(Dispatchers.IO) { SequenceRepository.get(context, sequencePath) }
        loading = false
    }

    var lastInfo by remember { mutableStateOf<String?>(null) }

    fun runStack(type: Stacker.Type) {
        val frames = folder?.frames ?: return
        if (frames.isEmpty() || processing) return
        processing = true
        processedFrames = 0
        totalFrames = frames.size
        lastResult = null
        lastError = null
        lastInfo = null
        scope.launch {
            try {
                if (type == Stacker.Type.LIGHTNING) {
                    val res = withContext(Dispatchers.Default) {
                        Stacker.lightningCompose(context, frames) { current, total ->
                            processedFrames = current
                            totalFrames = total
                        }
                    }
                    if (res == null) {
                        lastError = context.getString(R.string.stack_decode_failed)
                    } else if (res.composite == null || res.winnerIndices.isEmpty()) {
                        lastInfo = context.getString(R.string.stack_no_strikes, res.totalFrames)
                    } else {
                        val uri = withContext(Dispatchers.IO) {
                            Stacker.saveResult(context, res.composite, sequencePath, type)
                        }
                        res.composite.recycle()
                        if (uri != null) {
                            lastResult = uri
                            lastInfo = context.getString(
                                R.string.stack_strikes_found,
                                res.winnerIndices.size,
                                res.totalFrames,
                            )
                        } else {
                            lastError = context.getString(R.string.stack_save_failed)
                        }
                    }
                } else if (type == Stacker.Type.NIGHTSCAPE) {
                    val res = withContext(Dispatchers.Default) {
                        Stacker.nightscapeCompose(context, frames) { current, total ->
                            processedFrames = current
                            totalFrames = total
                        }
                    }
                    when {
                        res == null ->
                            lastError = context.getString(R.string.stack_decode_failed)
                        res.composite == null ->
                            lastInfo = context.getString(R.string.stack_no_horizon)
                        else -> {
                            val uri = withContext(Dispatchers.IO) {
                                Stacker.saveResult(context, res.composite, sequencePath, type)
                            }
                            res.composite.recycle()
                            if (uri != null) {
                                lastResult = uri
                                lastInfo = context.getString(R.string.stack_nightscape_done, res.horizonRow)
                            } else {
                                lastError = context.getString(R.string.stack_save_failed)
                            }
                        }
                    }
                } else {
                    val result = withContext(Dispatchers.Default) {
                        val cb = Stacker.ProgressCallback { current, total ->
                            processedFrames = current
                            totalFrames = total
                        }
                        when (type) {
                            Stacker.Type.LIGHTEN -> Stacker.lightenBlend(context, frames, cb)
                            Stacker.Type.MEAN -> Stacker.meanStack(context, frames, cb)
                            Stacker.Type.LIGHTNING -> null  // handled above
                            Stacker.Type.NIGHTSCAPE -> null  // handled above
                        }
                    }
                    if (result != null) {
                        val uri = withContext(Dispatchers.IO) {
                            Stacker.saveResult(context, result, sequencePath, type)
                        }
                        result.recycle()
                        if (uri != null) lastResult = uri
                        else lastError = context.getString(R.string.stack_save_failed)
                    } else {
                        lastError = context.getString(R.string.stack_decode_failed)
                    }
                }
            } catch (e: OutOfMemoryError) {
                lastError = context.getString(R.string.stack_out_of_memory)
            } catch (e: Exception) {
                lastError = e.message ?: context.getString(R.string.stack_unknown_error)
            } finally {
                processing = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !processing) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            val headerName = folder?.let { SequenceLabels.get(context, it.path) ?: it.name }
            Text(
                headerName ?: stringResource(R.string.sequences_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            folder == null || folder!!.frames.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.sequences_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                        ) {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "${folder!!.frames.size}",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.sequences_frames_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Progress / status
        if (processing) {
            val pct = if (totalFrames > 0) processedFrames.toFloat() / totalFrames else 0f
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.stack_progress, processedFrames, totalFrames),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (lastResult != null) {
            val resultUri = lastResult!!
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp),
                ) {
                    Icon(Icons.Default.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lastInfo ?: stringResource(R.string.stack_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(resultUri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }) { Text(stringResource(R.string.stack_view)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else if (lastInfo != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    lastInfo!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (lastError != null) {
            Text(
                lastError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Action buttons
        val canRun = !processing && !loading && (folder?.frames?.isNotEmpty() == true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { runStack(Stacker.Type.LIGHTEN) },
                enabled = canRun,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(stringResource(R.string.stack_lighten), fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { runStack(Stacker.Type.MEAN) },
                enabled = canRun,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(stringResource(R.string.stack_mean), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { runStack(Stacker.Type.LIGHTNING) },
                enabled = canRun,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(stringResource(R.string.stack_lightning), fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { runStack(Stacker.Type.NIGHTSCAPE) },
                enabled = canRun,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                ),
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Text(stringResource(R.string.stack_nightscape), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.stack_explanation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
