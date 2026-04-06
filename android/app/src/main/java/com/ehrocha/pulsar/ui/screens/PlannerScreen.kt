/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Geocoder
import android.location.LocationManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    plannerManager: PlannerManager,
    onBack: () -> Unit,
    onEventSessions: (PlannerEvent) -> Unit = {},
    onPickOnMap: () -> Unit = {},
) {
    val state by plannerManager.state.collectAsState()
    var showAddEvent by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    if (showAddEvent) {
        AddEventDialog(
            onDismiss = { showAddEvent = false },
            onConfirm = { name, lat, lon, startDate, endDate, startTime, endTime ->
                plannerManager.addEvent(name, lat, lon, startDate, endDate, startTime, endTime)
                showAddEvent = false
            },
            onPickOnMap = {
                showAddEvent = false
                onPickOnMap()
            },
        )
    }

    if (showImportDialog) {
        ImportEventDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                plannerManager.importEvent(json)
                showImportDialog = false
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

        // ── Events list ──────────────────────────────────────────────
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.events.sortedBy { it.startDate }, key = { it.id }) { event ->
                    val sessions = state.sessions.filter { it.eventId == event.id }
                    EventCard(
                        event = event,
                        sessionCount = sessions.size,
                        bestVerdict = sessions.maxByOrNull { it.verdict.ordinal }?.verdict
                            ?: PlannerVerdict.UNKNOWN,
                        onClick = { onEventSessions(event) },
                        onShare = {
                            plannerManager.exportEvent(event.id)?.let { json ->
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                it.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                        onDelete = { plannerManager.removeEvent(event.id) },
                    )
                }
            }
        }
    }
}

// ── Event sessions screen (shown when clicking an event) ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSessionsScreen(
    event: PlannerEvent,
    plannerManager: PlannerManager,
    onBack: () -> Unit,
    onSessionDetail: (PlannerSession, PlannerEvent) -> Unit = { _, _ -> },
) {
    val state by plannerManager.state.collectAsState()
    val sessions = state.sessions
        .filter { it.eventId == event.id }
        .sortedBy { it.date }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    event.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
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

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.planner_no_sessions),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        event = event,
                        onClick = { onSessionDetail(session, event) },
                        onDelete = { plannerManager.removeSession(session.id) },
                    )
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
                    Text(
                        String.format(Locale.US, "%.4f, %.4f", event.latitude, event.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // Optional time window
            if (event.startTime != null || event.endTime != null) {
                val timeStr = buildString {
                    append("🕐 ")
                    event.startTime?.let { append(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) }
                        ?: append("--:--")
                    append(" – ")
                    event.endTime?.let { append(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) }
                        ?: append("--:--")
                }
                Text(
                    timeStr,
                    style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun SessionCard(
    session: PlannerSession,
    event: PlannerEvent,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val isPast = session.date.isBefore(LocalDate.now())

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (event.startTime != null || event.endTime != null) {
                        val timeStr = buildString {
                            append("🕐 ")
                            event.startTime?.let { append(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) }
                                ?: append("--:--")
                            append(" – ")
                            event.endTime?.let { append(String.format(Locale.US, "%02d:%02d", it.hour, it.minute)) }
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
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(20.dp),
                    )
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

// ── Add event dialog ─────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double, startDate: LocalDate, endDate: LocalDate, startTime: LocalTime?, endTime: LocalTime?) -> Unit,
    onPickOnMap: () -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    var latStr by remember { mutableStateOf("") }
    var lonStr by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var gpsLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val myLocationStr = stringResource(R.string.planner_my_location)

    // Date range
    var showDatePicker by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var endDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }

    // Optional time window
    var useTimeWindow by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(LocalTime.of(20, 0)) }
    var endTime by remember { mutableStateOf(LocalTime.of(6, 0)) }

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

    // Date range picker dialog
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

    // Time pickers
    if (showStartTimePicker) {
        val tpState = rememberTimePickerState(
            initialHour = startTime.hour, initialMinute = startTime.minute, is24Hour = true,
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
            initialHour = endTime.hour, initialMinute = endTime.minute, is24Hour = true,
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
                                            name = result.displayName
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
                OutlinedButton(
                    onClick = {
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
                                            if (geoName.isNotEmpty()) name = geoName
                                        }
                                    } catch (_: Exception) {
                                        if (name.isBlank()) name = myLocationStr
                                    }
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

                // ── Pick on map ──────────────────────────────────────
                OutlinedButton(
                    onClick = onPickOnMap,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.planner_pick_on_map))
                }

                HorizontalDivider()

                // ── Manual fields ────────────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.planner_location_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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

                // ── Date range ───────────────────────────────────────
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(dateLabel, maxLines = 2)
                }

                // ── Optional time window ─────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.planner_time_window),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = useTimeWindow, onCheckedChange = { useTimeWindow = it })
                }

                if (useTimeWindow) {
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
                            Text(String.format(Locale.US, "%02d:%02d", startTime.hour, startTime.minute))
                        }
                        Text("–", modifier = Modifier.align(Alignment.CenterVertically))
                        OutlinedButton(
                            onClick = { showEndTimePicker = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(String.format(Locale.US, "%02d:%02d", endTime.hour, endTime.minute))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name.trim(), latStr.toDouble(), lonStr.toDouble(),
                        startDate, endDate,
                        if (useTimeWindow) startTime else null,
                        if (useTimeWindow) endTime else null,
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
    onImport: (String) -> Unit,
) {
    var json by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_import_title)) },
        text = {
            OutlinedTextField(
                value = json,
                onValueChange = { json = it },
                label = { Text(stringResource(R.string.event_import_hint)) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(json.trim()) },
                enabled = json.isNotBlank(),
            ) { Text(stringResource(R.string.event_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
