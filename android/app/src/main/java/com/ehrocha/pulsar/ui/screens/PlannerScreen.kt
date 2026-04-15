/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ehrocha.pulsar.R

import com.ehrocha.pulsar.planner.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// ── Main screen ──────────────────────────────────────────────────────────────

/** Data returned from the map location picker. */
data class MapPickerResult(val name: String, val lat: Double, val lon: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    plannerManager: PlannerManager,
    onBack: () -> Unit,
    onEventSessions: (PlannerEvent) -> Unit = {},
) {
    val state by plannerManager.state.collectAsState()
    var showAddEvent by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    if (showAddEvent) {
        AddEventDialog(
            onDismiss = { showAddEvent = false },
            onConfirm = { name, startDate, endDate ->
                plannerManager.addEvent(name, startDate, endDate)
                showAddEvent = false
            },
        )
    }

    if (showImportDialog) {
        ImportEventDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                val imported = plannerManager.importEvent(json)
                if (imported != null) {
                    showImportDialog = false
                }
                imported != null
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ── Top bar ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.planner_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showImportDialog = true }) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.event_import))
            }
            IconButton(onClick = { showAddEvent = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.planner_add))
            }
        }

        // ── Events list (tabbed: Upcoming / Past) ────────────────────
        val today = LocalDate.now()
        val upcoming = state.events
            .filter { it.endDate >= today }
            .sortedBy { it.startDate }
        val past = state.events
            .filter { it.endDate < today }
            .sortedByDescending { it.startDate }

        if (state.events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Event,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.event_no_events),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { showAddEvent = true }) {
                        Text(stringResource(R.string.event_add))
                    }
                }
            }
        } else {
            var selectedTab by remember { mutableIntStateOf(0) }
            val tabs = listOf(
                stringResource(R.string.tab_upcoming) to upcoming,
                stringResource(R.string.tab_past) to past,
            )

            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, (title, events) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                if (events.isNotEmpty()) "$title (${events.size})"
                                else title,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val displayedEvents = tabs[selectedTab].second
            if (displayedEvents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(
                            if (selectedTab == 0) R.string.planner_no_upcoming
                            else R.string.planner_no_past,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(displayedEvents, key = { it.id }) { event ->
                        val sessions = state.sessions.filter { it.eventId == event.id }
                        EventCard(
                            event = event,
                            sessionCount = sessions.size,
                            bestVerdict = sessions.maxByOrNull { it.verdict.ordinal }?.verdict
                                ?: PlannerVerdict.UNKNOWN,
                            onClick = { onEventSessions(event) },
                            onShare = { ctx ->
                                plannerManager.exportEvent(event.id)?.let { json ->
                                    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
                                    val safeName = event.name.replace(Regex("[^\\w.-]"), "_")
                                    val file = File(dir, "$safeName.pulsar")
                                    file.writeText(json)
                                    val uri = FileProvider.getUriForFile(
                                        ctx, "${ctx.packageName}.fileprovider", file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pulsar-event"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(intent, null))
                                }
                            },
                            onDelete = { plannerManager.removeEvent(event.id) },
                        )
                    }
                }
            }
        }
    }
}

// ── Event sessions screen (shown when clicking an event) ─────────────────────

private enum class SessionSortMode { DATE, VERDICT, NAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSessionsScreen(
    event: PlannerEvent,
    plannerManager: PlannerManager,
    onBack: () -> Unit,
    onSessionDetail: (PlannerSession, PlannerEvent) -> Unit = { _, _ -> },
    onPickOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    mapResult: MapPickerResult? = null,
) {
    val state by plannerManager.state.collectAsState()
    var sortMode by remember { mutableStateOf(SessionSortMode.DATE) }
    val sessions = state.sessions
        .filter { it.eventId == event.id }
        .let { list ->
            when (sortMode) {
                SessionSortMode.DATE -> list.sortedWith(compareBy({ it.date }, { it.startTime }))
                SessionSortMode.VERDICT -> list.sortedByDescending { it.verdict.ordinal }
                SessionSortMode.NAME -> list.sortedBy { it.name.lowercase() }
            }
        }
    var showAddSession by remember { mutableStateOf(mapResult != null) }
    var editingSession by remember { mutableStateOf<PlannerSession?>(null) }
    val scope = rememberCoroutineScope()

    // Bulk delete state
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    if (showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirm = false },
            title = { Text(stringResource(R.string.event_delete_title)) },
            text = { Text(stringResource(R.string.planner_bulk_delete_confirm, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    selectedIds.forEach { plannerManager.removeSession(it) }
                    selectedIds = emptySet()
                    selectionMode = false
                    showBulkDeleteConfirm = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showAddSession) {
        AddSessionDialog(
            event = event,
            onDismiss = { showAddSession = false },
            onConfirm = { name, lat, lon, date, startTime, endTime ->
                val session = plannerManager.addSession(event.id, name, lat, lon, date, startTime, endTime)
                showAddSession = false
                if (session != null) {
                    scope.launch {
                        try { plannerManager.checkSessionConditions(session) } catch (_: Exception) {}
                    }
                }
            },
            onPickOnMap = { lat, lon ->
                showAddSession = false
                onPickOnMap(lat, lon)
            },
            initialName = mapResult?.name.orEmpty(),
            initialLat = mapResult?.let { String.format(Locale.US, "%.5f", it.lat) } ?: "",
            initialLon = mapResult?.let { String.format(Locale.US, "%.5f", it.lon) } ?: "",
        )
    }

    if (editingSession != null) {
        val s = editingSession!!
        AddSessionDialog(
            event = event,
            onDismiss = { editingSession = null },
            onConfirm = { name, lat, lon, date, startTime, endTime ->
                val updated = s.copy(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    date = date,
                    startTime = startTime,
                    endTime = endTime,
                )
                plannerManager.updateSession(updated)
                editingSession = null
                scope.launch {
                    try { plannerManager.checkSessionConditions(updated) } catch (_: Exception) {}
                }
            },
            onPickOnMap = { _, _ -> /* map picking not supported during edit */ },
            initialName = s.name,
            initialLat = String.format(Locale.US, "%.5f", s.latitude),
            initialLon = String.format(Locale.US, "%.5f", s.longitude),
            editMode = true,
            initialDate = s.date,
            initialStartTime = s.startTime,
            initialEndTime = s.endTime,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // ── Top bar ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            IconButton(onClick = {
                if (selectionMode) {
                    selectionMode = false
                    selectedIds = emptySet()
                } else {
                    onBack()
                }
            }) {
                Icon(
                    if (selectionMode) Icons.Default.Close
                    else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (selectionMode) "${selectedIds.size} selected"
                    else event.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!selectionMode) {
                    Text(
                        buildString {
                            append(event.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                            if (event.startDate != event.endDate) {
                                append(" – ")
                                append(event.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selectionMode) {
                IconButton(
                    onClick = {
                        selectedIds = if (selectedIds.size == sessions.size)
                            emptySet() else sessions.map { it.id }.toSet()
                    },
                ) {
                    Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.planner_select_all))
                }
                IconButton(
                    onClick = { showBulkDeleteConfirm = true },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.planner_bulk_delete),
                        tint = if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Sort dropdown
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.planner_sort_date)) },
                            onClick = { sortMode = SessionSortMode.DATE; showSortMenu = false },
                            leadingIcon = { if (sortMode == SessionSortMode.DATE) Icon(Icons.Default.Check, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.planner_sort_verdict)) },
                            onClick = { sortMode = SessionSortMode.VERDICT; showSortMenu = false },
                            leadingIcon = { if (sortMode == SessionSortMode.VERDICT) Icon(Icons.Default.Check, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.planner_sort_name)) },
                            onClick = { sortMode = SessionSortMode.NAME; showSortMenu = false },
                            leadingIcon = { if (sortMode == SessionSortMode.NAME) Icon(Icons.Default.Check, contentDescription = null) },
                        )
                    }
                }
                IconButton(onClick = { showAddSession = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.session_add))
                }
            }
        }

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        @Suppress("DEPRECATION") Icons.Default.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.planner_no_sessions),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { showAddSession = true }) {
                        Text(stringResource(R.string.session_add))
                    }
                }
            }
        } else {
            // Group by date for agenda view (or flat list if sorting by non-date)
            if (sortMode == SessionSortMode.DATE) {
                val byDate = sessions.groupBy { it.date }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    byDate.forEach { (date, daySessions) ->
                        item(key = "header_$date") {
                            Text(
                                date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(daySessions, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (session.id in selectedIds)
                                            selectedIds - session.id else selectedIds + session.id
                                    } else {
                                        onSessionDetail(session, event)
                                    }
                                },
                                onDelete = { plannerManager.removeSession(session.id) },
                                onEdit = { editingSession = session },
                                selected = session.id in selectedIds,
                                selectionMode = selectionMode,
                                onLongClick = {
                                    selectionMode = true
                                    selectedIds = setOf(session.id)
                                },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(
                            session = session,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (session.id in selectedIds)
                                        selectedIds - session.id else selectedIds + session.id
                                } else {
                                    onSessionDetail(session, event)
                                }
                            },
                            onDelete = { plannerManager.removeSession(session.id) },
                            onEdit = { editingSession = session },
                            selected = session.id in selectedIds,
                            selectionMode = selectionMode,
                            onLongClick = {
                                selectionMode = true
                                selectedIds = setOf(session.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Event card ───────────────────────────────────────────────────────────────

@Composable
private fun EventCard(
    event: PlannerEvent,
    sessionCount: Int,
    bestVerdict: PlannerVerdict,
    onClick: () -> Unit,
    onShare: (Context) -> Unit,
    onDelete: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.event_delete_title)) },
            text = { Text(stringResource(R.string.event_delete_msg, event.name)) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onDelete() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Event,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        event.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                VerdictChip(bestVerdict)
            }

            Spacer(Modifier.height(8.dp))

            // Date range
            val dateStr = if (event.startDate == event.endDate) {
                event.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            } else {
                "${event.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))} – ${
                    event.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
                }"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DateRange,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    dateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.event_session_count, sessionCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = { onShare(context) }) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = stringResource(R.string.event_share),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = { showConfirm = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Session card ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    session: PlannerSession,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {},
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val isPast = session.date.isBefore(LocalDate.now())

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (selected) 6.dp else 2.dp,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    // Location
                    Text(
                        String.format(Locale.US, "%.4f, %.4f", session.latitude, session.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Time window
                    if (session.startTime != null || session.endTime != null) {
                        val timeStr = buildString {
                            session.startTime?.let { append(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) }
                                ?: append("--:--")
                            append(" – ")
                            session.endTime?.let { append(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) }
                                ?: append("--:--")
                        }
                        Text(
                            timeStr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                VerdictChip(session.verdict)
                if (!selectionMode) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.session_edit_title),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    session.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (session.lastChecked > 0) {
                val ago = (System.currentTimeMillis() - session.lastChecked) / 3_600_000
                Text(
                    stringResource(R.string.planner_last_checked, ago),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// ── Verdict chip ─────────────────────────────────────────────────────────────

@Composable
private fun VerdictChip(verdict: PlannerVerdict) {
    val (color, label) = when (verdict) {
        PlannerVerdict.EXCELLENT -> Color(0xFF2E7D32) to stringResource(R.string.verdict_excellent)
        PlannerVerdict.GOOD -> Color(0xFF558B2F) to stringResource(R.string.verdict_good)
        PlannerVerdict.FAIR -> Color(0xFFF9A825) to stringResource(R.string.verdict_fair)
        PlannerVerdict.POOR -> Color(0xFFE65100) to stringResource(R.string.verdict_poor)
        PlannerVerdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.verdict_unknown)
    }
    val animatedColor by animateColorAsState(color, label = "verdict")
    Surface(
        color = animatedColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = animatedColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ── Add event dialog (simplified: name + date range only) ───────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, startDate: LocalDate, endDate: LocalDate) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    // Date range
    var showDatePicker by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var endDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }

    if (showDatePicker) {
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = startDate
                .atStartOfDay(java.time.ZoneId.of("UTC"))
                .toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = endDate
                .atStartOfDay(java.time.ZoneId.of("UTC"))
                .toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    rangeState.selectedStartDateMillis?.let { s ->
                        startDate = java.time.Instant.ofEpochMilli(s)
                            .atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                    }
                    rangeState.selectedEndDateMillis?.let { e ->
                        endDate = java.time.Instant.ofEpochMilli(e)
                            .atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                    } ?: run { endDate = startDate }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) { DateRangePicker(state = rangeState, modifier = Modifier.weight(1f)) }
    }

    val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1
    val dateLabel = if (startDate == endDate)
        startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    else
        "${startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))} – ${
            endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
        } ($days ${stringResource(R.string.planner_days)})"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.event_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(dateLabel, maxLines = 2)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), startDate, endDate) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ── Add session dialog (location + date + time window) ───────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSessionDialog(
    event: PlannerEvent,
    onDismiss: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double, date: LocalDate, startTime: LocalTime?, endTime: LocalTime?) -> Unit,
    onPickOnMap: (lat: Double, lon: Double) -> Unit = { _, _ -> },
    initialName: String = "",
    initialLat: String = "",
    initialLon: String = "",
    editMode: Boolean = false,
    initialDate: LocalDate? = null,
    initialStartTime: LocalTime? = null,
    initialEndTime: LocalTime? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var latStr by remember { mutableStateOf(initialLat) }
    var lonStr by remember { mutableStateOf(initialLon) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var gpsLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val myLocationStr = stringResource(R.string.planner_my_location)

    // Date (within event range)
    var showDatePicker by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(initialDate ?: event.startDate) }

    // Time window (optional – only set when user explicitly picks)
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(initialStartTime) }
    var endTime by remember { mutableStateOf(initialEndTime) }

    // Debounced city search
    var searchJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(searchQuery) {
        searchJob?.cancel()
        if (searchQuery.length < 2) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        searchJob = scope.launch {
            delay(350)
            searchResults = searchCities(searchQuery)
            searching = false
        }
    }

    val valid = name.isNotBlank()
            && latStr.toDoubleOrNull()?.let { it in -90.0..90.0 } == true
            && lonStr.toDoubleOrNull()?.let { it in -180.0..180.0 } == true

    // Date picker (single date within event range)
    if (showDatePicker) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = date
                .atStartOfDay(java.time.ZoneId.of("UTC"))
                .toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = java.time.Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                    return !d.isBefore(event.startDate) && !d.isAfter(event.endDate)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    dpState.selectedDateMillis?.let { ms ->
                        date = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneId.of("UTC")).toLocalDate()
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) { DatePicker(state = dpState) }
    }

    // Time pickers
    if (showStartTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = startTime?.hour ?: 20, initialMinute = startTime?.minute ?: 0, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showStartTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    startTime = LocalTime.of(tpState.hour, tpState.minute)
                    showStartTimePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
            text = { TimePicker(state = tpState) },
        )
    }
    if (showEndTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = endTime?.hour ?: 6, initialMinute = endTime?.minute ?: 0, is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showEndTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endTime = LocalTime.of(tpState.hour, tpState.minute)
                    showEndTimePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showEndTimePicker = false }) { Text(stringResource(R.string.cancel)) }
            },
            text = { TimePicker(state = tpState) },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editMode) R.string.session_edit_title else R.string.session_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ── Name ─────────────────────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.session_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                // ── City search ──────────────────────────────────────
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.planner_search_city)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Search results dropdown ──────────────────────────
                if (searchResults.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 4.dp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    ) {
                        LazyColumn {
                            items(searchResults) { result ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (name.isBlank()) name = result.name
                                            latStr = String.format(Locale.US, "%.5f", result.latitude)
                                            lonStr = String.format(Locale.US, "%.5f", result.longitude)
                                            searchQuery = ""
                                            searchResults = emptyList()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(result.displayName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }

                // ── GPS button ───────────────────────────────────────
                val gpsUnavailableMsg = stringResource(R.string.planner_gps_unavailable)
                OutlinedButton(
                    onClick = {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) {
                            Toast.makeText(context, gpsUnavailableMsg, Toast.LENGTH_LONG).show()
                            return@OutlinedButton
                        }
                        gpsLoading = true
                        scope.launch {
                            try {
                                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                                val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                if (loc != null) {
                                    latStr = String.format(Locale.US, "%.5f", loc.latitude)
                                    lonStr = String.format(Locale.US, "%.5f", loc.longitude)
                                    try {
                                        @Suppress("DEPRECATION")
                                        val addresses = Geocoder(context, Locale.getDefault())
                                            .getFromLocation(loc.latitude, loc.longitude, 1)
                                        addresses?.firstOrNull()?.let { addr ->
                                            val geoName = listOfNotNull(
                                                addr.locality, addr.adminArea, addr.countryCode
                                            ).joinToString(", ")
                                            if (geoName.isNotEmpty() && name.isBlank()) name = geoName
                                        }
                                    } catch (_: Exception) {
                                        if (name.isBlank()) name = myLocationStr
                                    }
                                } else {
                                    Toast.makeText(context, gpsUnavailableMsg, Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                gpsLoading = false
                            }
                        }
                    },
                    enabled = !gpsLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (gpsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.planner_use_gps))
                }

                // ── Pick on map (requires a starting point) ─────────
                val hasCoords = latStr.toDoubleOrNull()?.let { it in -90.0..90.0 } == true
                        && lonStr.toDoubleOrNull()?.let { it in -180.0..180.0 } == true
                OutlinedButton(
                    onClick = {
                        onPickOnMap(
                            latStr.toDoubleOrNull() ?: 0.0,
                            lonStr.toDoubleOrNull() ?: 0.0,
                        )
                    },
                    enabled = hasCoords,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.planner_pick_on_map))
                }

                // ── Manual lat/lon ───────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = latStr,
                        onValueChange = { latStr = it },
                        label = { Text(stringResource(R.string.planner_lat_short)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonStr,
                        onValueChange = { lonStr = it },
                        label = { Text(stringResource(R.string.planner_lon_short)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider()

                // ── Date ─────────────────────────────────────────────
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }

                // ── Time window (optional) ───────────────────────────
                Text(
                    stringResource(R.string.planner_time_window),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(startTime?.let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) } ?: "--:--")
                    }
                    Text("–", modifier = Modifier.align(Alignment.CenterVertically))
                    OutlinedButton(
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(endTime?.let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) } ?: "--:--")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(), latStr.toDouble(), lonStr.toDouble(),
                        date,
                        startTime,
                        endTime,
                    )
                },
                enabled = valid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ── Import event dialog ──────────────────────────────────────────────────────

@Composable
private fun ImportEventDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Boolean,
) {
    var json by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it; showError = false },
                    label = { Text(stringResource(R.string.event_import_hint)) },
                    isError = showError,
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        stringResource(R.string.planner_import_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (!onImport(json.trim())) {
                        showError = true
                    }
                },
                enabled = json.isNotBlank(),
            ) { Text(stringResource(R.string.event_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
