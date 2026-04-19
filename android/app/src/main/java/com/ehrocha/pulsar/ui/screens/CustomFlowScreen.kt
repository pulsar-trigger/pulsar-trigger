/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ehrocha.pulsar.AppConfig
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

import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.StatusGreen
import com.ehrocha.pulsar.ui.theme.StatusOrange
import com.ehrocha.pulsar.ui.theme.StatusRed
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class FlowScreenState { LIBRARY, EDITOR }

@Composable
fun CustomFlowScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    quickLaunch: Boolean = false,
) {
    val steps by vm.flowSteps.collectAsState()
    val saved by vm.savedFlows.collectAsState()
    val running by vm.flowRunning.collectAsState()
    val paused by vm.flowPaused.collectAsState()
    val currentStep by vm.flowCurrentStep.collectAsState()

    var screenState by remember { mutableStateOf(FlowScreenState.LIBRARY) }
    var editingFlowName by remember { mutableStateOf<String?>(null) }
    var savedSnapshot by remember { mutableStateOf<List<FlowStep>>(emptyList()) }
    // Remember which library tab the user was on so back navigation restores it
    var lastLibraryTab by remember { mutableIntStateOf(0) }

    // When a flow is running, always show the editor
    // When quick-launched flow finishes, return to previous screen
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            screenState = FlowScreenState.EDITOR
            wasRunning = true
        } else if (wasRunning && quickLaunch) {
            onBack()
        }
    }

    var showExitDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = running || screenState == FlowScreenState.EDITOR) {
        if (running) {
            showExitDialog = true
        } else if (editingFlowName != null && steps != savedSnapshot) {
            showUnsavedDialog = true
        } else if (quickLaunch) {
            onBack()
        } else {
            screenState = FlowScreenState.LIBRARY
            editingFlowName = null
        }
    }

    when (screenState) {
        FlowScreenState.LIBRARY -> FlowLibraryView(
            vm = vm,
            saved = saved,
            initialTab = lastLibraryTab,
            onTabChanged = { lastLibraryTab = it },
            onBack = onBack,
            onNewFlow = {
                vm.saveFlowSteps(emptyList())
                editingFlowName = null
                savedSnapshot = emptyList()
                screenState = FlowScreenState.EDITOR
            },
            onEditFlow = { flow ->
                vm.loadSavedFlow(flow.name)
                editingFlowName = flow.name
                savedSnapshot = flow.steps
                screenState = FlowScreenState.EDITOR
            },
            onDeleteFlow = { name -> vm.deleteSavedFlow(name) },
            onRunFlow = { flow ->
                vm.loadSavedFlow(flow.name)
                editingFlowName = flow.name
                savedSnapshot = flow.steps
                screenState = FlowScreenState.EDITOR
                vm.startFlow()
            },
        )
        FlowScreenState.EDITOR -> FlowEditorView(
            vm = vm,
            steps = steps,
            saved = saved,
            running = running,
            paused = paused,
            currentStep = currentStep,
            editingFlowName = editingFlowName,
            onBack = {
                if (editingFlowName != null && steps != savedSnapshot) {
                    showUnsavedDialog = true
                } else {
                    screenState = FlowScreenState.LIBRARY
                    editingFlowName = null
                }
            },
            onFlowNameChanged = { editingFlowName = it },
        )
    }

    // ── Exit confirmation dialog while flow is running ───────────────
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.dialog_exit_running_title)) },
            text = { Text(stringResource(R.string.dialog_exit_running_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    vm.stopFlow()
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_stop_and_exit), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.btn_keep_running))
                }
            },
        )
    }

    // ── Unsaved changes dialog ───────────────────────────────────────
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.dialog_unsaved_title)) },
            text = { Text(stringResource(R.string.dialog_unsaved_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    editingFlowName?.let { vm.saveFlowAs(it) }
                    screenState = FlowScreenState.LIBRARY
                    editingFlowName = null
                }) {
                    Text(stringResource(R.string.btn_save_and_exit))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    screenState = FlowScreenState.LIBRARY
                    editingFlowName = null
                }) {
                    Text(stringResource(R.string.btn_discard), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun FlowLibraryView(
    vm: PulsarViewModel,
    saved: List<SavedFlow>,
    initialTab: Int = 0,
    onTabChanged: (Int) -> Unit = {},
    onBack: () -> Unit,
    onNewFlow: () -> Unit,
    onEditFlow: (SavedFlow) -> Unit,
    onDeleteFlow: (String) -> Unit,
    onRunFlow: (SavedFlow) -> Unit,
) {
    val connected = LocalDeviceConnected.current
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var editTagsFlow by remember { mutableStateOf<SavedFlow?>(null) }
    val allTags by vm.allTags.collectAsState()
    var activeTagFilter by remember { mutableStateOf<String?>(null) }
    var showFavoritesOnly by remember { mutableStateOf(false) }

    val presets = saved.filter { it.builtIn }
    val userFlows = saved.filter { !it.builtIn }
    val hasFavorites = remember(userFlows) { userFlows.any { it.favorite } }

    // Sort: favorites first, then alphabetical
    val sortedUserFlows = remember(userFlows) {
        userFlows.sortedWith(compareByDescending<SavedFlow> { it.favorite }.thenBy { it.name })
    }

    // Apply tag + favorites filter
    val filteredUserFlows = remember(sortedUserFlows, activeTagFilter, showFavoritesOnly) {
        sortedUserFlows
            .let { list -> if (showFavoritesOnly) list.filter { it.favorite } else list }
            .let { list -> if (activeTagFilter != null) list.filter { activeTagFilter in it.tags } else list }
    }
    val filteredPresets = remember(presets, activeTagFilter) {
        if (activeTagFilter == null) presets
        else presets.filter { activeTagFilter in it.tags }
    }

    val tabs = listOf(
        stringResource(R.string.flow_tab_my_flows),
        stringResource(R.string.flow_tab_recommended),
    )
    val pagerState = rememberPagerState(initialPage = initialTab) { tabs.size }
    val coroutineScope = rememberCoroutineScope()

    // Notify parent when the settled page changes so it survives LIBRARY↔EDITOR transitions
    LaunchedEffect(pagerState.currentPage) {
        onTabChanged(pagerState.currentPage)
    }

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
            PanelHelpHeader(
                title = stringResource(R.string.flow_title),
                helpText = stringResource(R.string.flow_help),
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Tab row (synced with pager) ─────────────────────────────────
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) },
                )
            }
        }

        // ── Filter chips (favorites + tags) ─────────────────────────────
        if (allTags.isNotEmpty() || hasFavorites) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = activeTagFilter == null && !showFavoritesOnly,
                    onClick = { activeTagFilter = null; showFavoritesOnly = false },
                    label = { Text(stringResource(R.string.flow_filter_all)) },
                    modifier = Modifier.height(32.dp),
                )
                if (hasFavorites) {
                    FilterChip(
                        selected = showFavoritesOnly,
                        onClick = {
                            if (showFavoritesOnly) {
                                showFavoritesOnly = false
                            } else {
                                showFavoritesOnly = true
                                activeTagFilter = null
                            }
                        },
                        label = { Text(stringResource(R.string.flow_filter_favorites)) },
                        leadingIcon = {
                            Icon(
                                if (showFavoritesOnly) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        modifier = Modifier.height(32.dp),
                    )
                }
                allTags.forEach { tag ->
                    FilterChip(
                        selected = activeTagFilter == tag,
                        onClick = {
                            if (activeTagFilter == tag) {
                                activeTagFilter = null
                            } else {
                                activeTagFilter = tag
                                showFavoritesOnly = false
                            }
                        },
                        label = { Text(tag) },
                        modifier = Modifier.height(32.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Swipeable tab content ───────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (page) {
                        0 -> { // My Flows (user-created)
                            if (filteredUserFlows.isEmpty()) {
                                FlowEmptyState()
                            } else {
                                filteredUserFlows.forEach { flow ->
                                    SavedFlowCard(
                                        flow = flow,
                                        onEdit = { onEditFlow(flow) },
                                        onDelete = { confirmDelete = flow.name },
                                        onRun = { onRunFlow(flow) },
                                        onToggleFavorite = { vm.toggleFavorite(flow.name) },
                                        onEditTags = { editTagsFlow = flow },
                                    )
                                }
                            }
                        }
                        1 -> { // Out of box (built-in presets)
                            if (filteredPresets.isEmpty()) {
                                FlowEmptyState()
                            } else {
                                filteredPresets.forEach { flow ->
                                    SavedFlowCard(
                                        flow = flow,
                                        onEdit = { onEditFlow(flow) },
                                        onDelete = { },
                                        onRun = { onRunFlow(flow) },
                                    )
                                }
                            }
                        }
                    }
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

    // ── Edit tags dialog ────────────────────────────────────────────────
    if (editTagsFlow != null) {
        EditTagsDialog(
            flowName = editTagsFlow!!.name,
            currentTags = editTagsFlow!!.tags,
            allTags = allTags,
            onDismiss = { editTagsFlow = null },
            onSave = { tags ->
                vm.updateFlowTags(editTagsFlow!!.name, tags)
                editTagsFlow = null
            },
        )
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
private fun EditTagsDialog(
    flowName: String,
    currentTags: List<String>,
    allTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var selectedTags by remember { mutableStateOf(currentTags.toSet()) }
    var showNewTagField by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.label_tags),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    flowName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                FlowLayout(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 6.dp,
                ) {
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTags,
                            onClick = {
                                selectedTags = if (tag in selectedTags) selectedTags - tag
                                               else selectedTags + tag
                            },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(32.dp),
                        )
                    }
                    if (!showNewTagField) {
                        FilterChip(
                            selected = false,
                            onClick = { showNewTagField = true },
                            label = { Text(stringResource(R.string.label_new_tag), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(32.dp),
                        )
                    }
                }
                if (showNewTagField) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text(stringResource(R.string.label_add_tag)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val trimmed = newTag.trim()
                                if (trimmed.isNotBlank()) {
                                    selectedTags = selectedTags + trimmed
                                    newTag = ""
                                    showNewTagField = false
                                }
                            }),
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {
                            val trimmed = newTag.trim()
                            if (trimmed.isNotBlank()) {
                                selectedTags = selectedTags + trimmed
                                newTag = ""
                            }
                            showNewTagField = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(selectedTags.toList()) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowEmptyState() {
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

@Composable
private fun SavedFlowCard(
    flow: SavedFlow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    onEditTags: (() -> Unit)? = null,
) {
    val connected = LocalDeviceConnected.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Favorite star (only for user flows)
                if (onToggleFavorite != null) {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (flow.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.flow_filter_favorites),
                            modifier = Modifier.size(20.dp),
                            tint = if (flow.favorite) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flow.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (flow.steps.size != 1) stringResource(R.string.flow_step_count_plural, flow.steps.size)
                            else stringResource(R.string.flow_step_count, flow.steps.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (flow.tags.isNotEmpty()) {
                            Text(
                                " · ${flow.tags.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                if (!flow.builtIn) {
                    if (onEditTags != null) {
                        IconButton(onClick = onEditTags, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Label, contentDescription = stringResource(R.string.label_tags), modifier = Modifier.size(20.dp))
                        }
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

            // Step flow summary with arrows
            if (flow.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    flow.steps.take(6).forEachIndexed { idx, step ->
                        val context = LocalContext.current
                        if (idx > 0) {
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
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
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Text(
                            "+${flow.steps.size - 6}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                // Total estimated time
                val totalMs = flow.steps.sumOf { it.estimatedDurationMs() }
                if (totalMs > 0) {
                    Spacer(Modifier.height(4.dp))
                    val totalSec = totalMs / 1000
                    val h = totalSec / 3600
                    val m = (totalSec % 3600) / 60
                    val s = totalSec % 60
                    val timeStr = when {
                        h > 0 -> "%d:%02d:%02d".format(h, m, s)
                        else -> "%d:%02d".format(m, s)
                    }
                    Text(
                        stringResource(R.string.flow_total_time, timeStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    running: Boolean,
    paused: Boolean,
    currentStep: Int,
    editingFlowName: String?,
    onBack: () -> Unit,
    onFlowNameChanged: (String?) -> Unit,
) {
    val connected = LocalDeviceConnected.current
    val status = LocalDeviceStatus.current
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
        }

        Spacer(Modifier.height(12.dp))

        // ── Flow timeline progress bar ───────────────────────────────────
        if (running && steps.isNotEmpty()) {
            FlowTimelineBar(
                steps = steps,
                currentStep = currentStep,
                status = status,
            )
            Spacer(Modifier.height(12.dp))
        }

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
                colors = ButtonDefaults.buttonColors(containerColor = StatusRed),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.btn_stop_flow), fontWeight = FontWeight.Bold)
            }
        } else {
            // Duration estimate before starting
            if (steps.isNotEmpty()) {
                val totalMs = steps.sumOf { it.estimatedDurationMs() }
                if (totalMs > 0) {
                    Text(
                        stringResource(R.string.status_estimated_duration, formatDuration(totalMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
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
        val allTags by vm.allTags.collectAsState()
        SaveFlowDialog(
            existingNames = saved.map { it.name },
            allTags = allTags,
            onDismiss = { showSaveDialog = false },
            onSave = { name, tags ->
                vm.saveFlowAs(name, tags)
                onFlowNameChanged(name)
                showSaveDialog = false
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
    val status = if (isCurrent) LocalDeviceStatus.current else null
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

                // ── Local countdown: tick down from firmware baseline ──
                var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var lastRemainingMs by remember { mutableLongStateOf(status.timeRemainingMs) }
                var liveRemainingMs by remember { mutableLongStateOf(status.timeRemainingMs) }

                var phaseStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var lastState by remember { mutableStateOf(status.state) }

                LaunchedEffect(status.timeRemainingMs, status.shotsTaken) {
                    lastUpdateTime = System.currentTimeMillis()
                    lastRemainingMs = status.timeRemainingMs
                    liveRemainingMs = status.timeRemainingMs
                }

                LaunchedEffect(status.state) {
                    if (status.state != lastState) {
                        phaseStartTime = System.currentTimeMillis()
                        lastState = status.state
                    }
                }

                LaunchedEffect(lastUpdateTime) {
                    while (true) {
                        delay(100L)
                        val elapsed = System.currentTimeMillis() - lastUpdateTime
                        liveRemainingMs = (lastRemainingMs - elapsed).coerceAtLeast(0)
                    }
                }

                // ── Per-phase countdown ──
                val exposureMs = when (step.type) {
                    FlowStepType.ASTRO -> AppConfig.astroExposureMs(step.focalLength, step.cropFactor, step.ruleDivisor)
                    FlowStepType.DARK_FRAME -> step.darkFrameExposureMs
                    FlowStepType.RAMP -> (step.rampStartExposureMs + step.rampEndExposureMs) / 2
                    else -> step.exposureMs
                }
                val gapMs = when (step.type) {
                    FlowStepType.ASTRO -> step.gapMs
                    FlowStepType.DARK_FRAME -> step.darkFrameGapMs
                    FlowStepType.RAMP -> step.rampIntervalMs
                    else -> step.intervalMs
                }
                val phaseDurationMs = when (status.state) {
                    DeviceState.RUNNING -> exposureMs
                    DeviceState.WAITING -> gapMs
                    else -> 0L
                }
                val phaseElapsed = System.currentTimeMillis() - phaseStartTime
                val phaseRemainingMs = (phaseDurationMs - phaseElapsed).coerceAtLeast(0)

                // State label + phase countdown
                val stateLabel = when (status.state) {
                    DeviceState.RUNNING -> stringResource(R.string.flow_state_exposing)
                    DeviceState.WAITING -> stringResource(R.string.flow_state_waiting)
                    DeviceState.IDLE -> stringResource(R.string.flow_state_idle)
                    DeviceState.ERROR -> stringResource(R.string.flow_state_error)
                }
                val stateColor = when (status.state) {
                    DeviceState.RUNNING -> StatusGreen
                    DeviceState.WAITING -> StatusOrange
                    DeviceState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                    DeviceState.ERROR -> StatusRed
                }

                // Shot display: always +1 while active so count starts at 1
                val displayShots = when (status.state) {
                    DeviceState.RUNNING, DeviceState.WAITING -> status.shotsTaken + 1
                    else -> status.shotsTaken
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // State badge with phase countdown
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = stateColor.copy(alpha = 0.15f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                stateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = stateColor,
                            )
                            if (phaseDurationMs > 0 && phaseRemainingMs > 0) {
                                Spacer(Modifier.width(4.dp))
                                val pSec = phaseRemainingMs / 1000
                                Text(
                                    "%d:%02d".format(pSec / 60, pSec % 60),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = stateColor,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // Shot counter (starts at 1)
                    val totalForDisplay = when (step.type) {
                        FlowStepType.DARK_FRAME -> step.darkFrameCount
                        FlowStepType.RAMP -> step.rampSteps
                        else -> step.shotCount
                    }
                    Text(
                        stringResource(R.string.flow_shot_count, displayShots, totalForDisplay),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                // Per-phase progress bars
                val phaseElapsedForBar = (System.currentTimeMillis() - phaseStartTime).coerceAtLeast(0)
                val exposureBarProgress = when (status.state) {
                    DeviceState.RUNNING -> if (exposureMs > 0) (phaseElapsedForBar.toFloat() / exposureMs).coerceIn(0f, 1f) else 0f
                    else -> 0f
                }
                val waitBarProgress = when (status.state) {
                    DeviceState.WAITING -> if (gapMs > 0) (phaseElapsedForBar.toFloat() / gapMs).coerceIn(0f, 1f) else 0f
                    else -> 0f
                }
                val smoothExposure by animateFloatAsState(
                    targetValue = exposureBarProgress,
                    animationSpec = tween(durationMillis = 300),
                    label = "flowExposureProgress",
                )
                val smoothWait by animateFloatAsState(
                    targetValue = waitBarProgress,
                    animationSpec = tween(durationMillis = 300),
                    label = "flowWaitProgress",
                )

                Spacer(Modifier.height(6.dp))

                // Exposure bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.flow_state_exposing),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusGreen,
                        modifier = Modifier.width(60.dp),
                    )
                    LinearProgressIndicator(
                        progress = { smoothExposure },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                        color = StatusGreen,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Wait bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.flow_state_waiting),
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusOrange,
                        modifier = Modifier.width(60.dp),
                    )
                    LinearProgressIndicator(
                        progress = { smoothWait },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                        color = StatusOrange,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                // Time remaining + finish time
                if (liveRemainingMs > 0) {
                    Spacer(Modifier.height(4.dp))
                    val totalSec = liveRemainingMs / 1000
                    val timeStr = if (totalSec >= 60)
                        stringResource(R.string.flow_time_remaining_long, totalSec / 60, totalSec % 60)
                    else
                        stringResource(R.string.flow_time_remaining_short, totalSec)
                    Text(
                        timeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    val finishTime = remember(liveRemainingMs / 1000) {
                        DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(Date(System.currentTimeMillis() + liveRemainingMs))
                    }
                    Text(
                        stringResource(R.string.finishes_at, finishTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    )
                }
            }

            // Pause continue button
            if (isPaused) {
                Spacer(Modifier.height(8.dp))

                // Pause step gets a "Waiting for user" badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StatusOrange.copy(alpha = 0.15f),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.flow_waiting_for_user),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = StatusOrange,
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
    FlowStepType.DARK_FRAME -> Icons.Default.LensBlur
    FlowStepType.RAMP -> Icons.AutoMirrored.Filled.TrendingUp
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

// ─── Edit step (full-screen) ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditStepDialog(
    step: FlowStep,
    onDismiss: () -> Unit,
    onSave: (FlowStep) -> Unit,
) {
    var current by remember { mutableStateOf(step) }
    val context = LocalContext.current

    BackHandler(onBack = onDismiss)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(step.type.displayName(context)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(current) }) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step.type) {
                FlowStepType.INTERVALOMETER -> IntervalometerStepEditor(current) { current = it }
                FlowStepType.ASTRO -> AstroStepEditor(current) { current = it }
                FlowStepType.PAUSE -> PauseStepEditor(current) { current = it }
                FlowStepType.DARK_FRAME -> DarkFrameStepEditor(current) { current = it }
                FlowStepType.RAMP -> RampStepEditor(current) { current = it }
            }

            Spacer(Modifier.height(16.dp))
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
        onIntervalChanged = { onChange(step.copy(intervalMs = it.coerceAtLeast(AppConfig.MIN_INTERVAL_MS))) },
        onExposureChanged = { onChange(step.copy(exposureMs = it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS))) },
        onShotCountChanged = { onChange(step.copy(shotCount = it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT))) },
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
        onGapMsChanged = { onChange(step.copy(gapMs = it.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS))) },
        onShotCountChanged = { onChange(step.copy(shotCount = it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT))) },
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.label_wake_on_pause),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.wake_on_pause_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = step.wakeOnPause,
            onCheckedChange = { onChange(step.copy(wakeOnPause = it)) },
        )
    }
}

@Composable
private fun DarkFrameStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.dark_frame_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    IntStepperField(
        label = stringResource(R.string.label_dark_frame_count),
        value = step.darkFrameCount,
        onValueChange = { onChange(step.copy(darkFrameCount = it.coerceAtLeast(1))) },
        min = 1,
        max = 999,
        enabled = true,
        presets = emptyList(),
    )
    TimePicker(
        totalMs = step.darkFrameExposureMs,
        onChanged = { onChange(step.copy(darkFrameExposureMs = it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS))) },
        label = stringResource(R.string.label_exposure) + " (hh:mm:ss)",
        enabled = true,
    )
    TimePicker(
        totalMs = step.darkFrameGapMs,
        onChanged = { onChange(step.copy(darkFrameGapMs = it.coerceAtLeast(AppConfig.MIN_ASTRO_GAP_MS))) },
        label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
        enabled = true,
    )
}

@Composable
private fun RampStepEditor(step: FlowStep, onChange: (FlowStep) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ramp_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    TimePicker(
        totalMs = step.rampStartExposureMs,
        onChanged = { onChange(step.copy(rampStartExposureMs = it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS))) },
        label = stringResource(R.string.label_ramp_start_exposure) + " (hh:mm:ss)",
        enabled = true,
    )
    TimePicker(
        totalMs = step.rampEndExposureMs,
        onChanged = { onChange(step.copy(rampEndExposureMs = it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS))) },
        label = stringResource(R.string.label_ramp_end_exposure) + " (hh:mm:ss)",
        enabled = true,
    )
    IntStepperField(
        label = stringResource(R.string.label_ramp_steps),
        value = step.rampSteps,
        onValueChange = { onChange(step.copy(rampSteps = it.coerceAtLeast(2))) },
        min = 2,
        max = 999,
        enabled = true,
        presets = listOf(20, 50, 100, 200),
        presetLabel = { "$it" },
    )
    TimePicker(
        totalMs = step.rampIntervalMs,
        onChanged = { onChange(step.copy(rampIntervalMs = it.coerceAtLeast(AppConfig.MIN_INTERVAL_MS))) },
        label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
        enabled = true,
    )
}

// ─── Save Flow dialog ────────────────────────────────────────────────────────

@Composable
private fun SaveFlowDialog(
    existingNames: List<String>,
    allTags: List<String>,
    initialTags: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (name: String, tags: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(initialTags.toSet()) }
    var showNewTagField by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
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

                // ── Tag selector ────────────────────────────────────────
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.label_tags), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowLayout(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalSpacing = 6.dp,
                    verticalSpacing = 6.dp,
                ) {
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTags,
                            onClick = {
                                selectedTags = if (tag in selectedTags) selectedTags - tag
                                               else selectedTags + tag
                            },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(32.dp),
                        )
                    }
                    // "New tag…" chip
                    if (!showNewTagField) {
                        FilterChip(
                            selected = false,
                            onClick = { showNewTagField = true },
                            label = { Text(stringResource(R.string.label_new_tag), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp)) },
                            modifier = Modifier.height(32.dp),
                        )
                    }
                }
                if (showNewTagField) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text(stringResource(R.string.label_add_tag)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val trimmed = newTag.trim()
                                if (trimmed.isNotBlank()) {
                                    selectedTags = selectedTags + trimmed
                                    newTag = ""
                                    showNewTagField = false
                                }
                            }),
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {
                            val trimmed = newTag.trim()
                            if (trimmed.isNotBlank()) {
                                selectedTags = selectedTags + trimmed
                                newTag = ""
                            }
                            showNewTagField = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name.trim(), selectedTags.toList()) },
                        enabled = name.isNotBlank(),
                    ) { Text(if (nameExists) stringResource(R.string.replace) else stringResource(R.string.save)) }
                }
            }
        }
    }
}

/** Simple flow layout for wrapping chips. */
@Composable
private fun FlowLayout(
    modifier: Modifier = Modifier,
    horizontalSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    verticalSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val hSpacingPx = horizontalSpacing.roundToPx()
        val vSpacingPx = verticalSpacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = placeables.map { placeable ->
            if (x + placeable.width > constraints.maxWidth && x > 0) {
                x = 0
                y += rowHeight + vSpacingPx
                rowHeight = 0
            }
            val pos = Pair(x, y)
            x += placeable.width + hSpacingPx
            rowHeight = maxOf(rowHeight, placeable.height)
            pos
        }
        val totalHeight = if (placeables.isEmpty()) 0 else y + rowHeight
        layout(constraints.maxWidth, totalHeight) {
            placeables.forEachIndexed { i, placeable ->
                placeable.placeRelative(positions[i].first, positions[i].second)
            }
        }
    }
}

// ─── Flow Timeline Progress Bar ──────────────────────────────────────────────

/** Estimate total duration for a single flow step (in ms). */
private fun FlowStep.estimatedDurationMs(): Long = when (type) {
    FlowStepType.PAUSE -> 0L
    FlowStepType.ASTRO -> {
        val expMs = AppConfig.astroExposureMs(focalLength, cropFactor, ruleDivisor)
        delayMs + shotCount * (expMs + gapMs)
    }
    FlowStepType.DARK_FRAME -> darkFrameCount.toLong() * (darkFrameExposureMs + darkFrameGapMs)
    FlowStepType.RAMP -> {
        val avgExpMs = (rampStartExposureMs + rampEndExposureMs) / 2
        rampSteps.toLong() * (avgExpMs + rampIntervalMs)
    }
    else -> delayMs + shotCount * (exposureMs + intervalMs)
}

@Composable
private fun FlowTimelineBar(
    steps: List<FlowStep>,
    currentStep: Int,
    status: StatusFrame?,
) {
    val durations = remember(steps) { steps.map { it.estimatedDurationMs() } }
    val totalMs = remember(durations) { durations.sum().coerceAtLeast(1L) }

    // Per-step weight (pause steps get a small fixed slice)
    val weights = remember(durations, totalMs) {
        durations.map { d -> if (d <= 0L) 0.02f else (d.toFloat() / totalMs) }
    }

    // Compute within-step fraction for the current step
    val withinStepFraction = if (
        currentStep in steps.indices &&
        status != null &&
        steps[currentStep].type != FlowStepType.PAUSE &&
        steps[currentStep].type != FlowStepType.RAMP
    ) {
        val step = steps[currentStep]
        val total = when (step.type) {
            FlowStepType.DARK_FRAME -> step.darkFrameCount
            else -> step.shotCount
        }
        val taken = status.shotsTaken.coerceAtLeast(0)
        (taken.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    } else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = withinStepFraction,
        animationSpec = tween(durationMillis = 400),
        label = "stepFraction",
    )

    // Summary text
    val completedSteps = if (currentStep >= 0) currentStep else 0
    val label = stringResource(
        R.string.flow_timeline_progress,
        completedSteps + 1,
        steps.size,
    )

    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            weights.forEachIndexed { index, weight ->
                val color = when {
                    index < currentStep -> MaterialTheme.colorScheme.primary
                    index == currentStep -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(
                            if (index == currentStep)
                                MaterialTheme.colorScheme.surfaceVariant
                            else color,
                        ),
                ) {
                    if (index == currentStep) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(animatedFraction)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                // Thin gap between segments
                if (index < weights.lastIndex) {
                    Spacer(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
    }
}

