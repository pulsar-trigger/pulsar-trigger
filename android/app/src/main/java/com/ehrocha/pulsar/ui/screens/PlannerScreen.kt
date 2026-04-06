/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import android.location.Geocoder
import android.location.LocationManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.planner.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    plannerManager: PlannerManager,
    onBack: () -> Unit,
) {
    val state by plannerManager.state.collectAsState()
    var showAddLocation by remember { mutableStateOf(false) }
    var showAddEntry by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    // ── Add location dialog ──────────────────────────────────────────
    if (showAddLocation) {
        AddLocationDialog(
            onDismiss = { showAddLocation = false },
            onConfirm = { name, lat, lon ->
                plannerManager.addLocation(name, lat, lon)
                showAddLocation = false
            },
        )
    }

    // ── Add entry dialog ─────────────────────────────────────────────
    if (showAddEntry && state.locations.isNotEmpty()) {
        AddEntryDialog(
            locations = state.locations,
            onDismiss = { showAddEntry = false },
            onConfirm = { location, date ->
                plannerManager.addEntry(location, date)
                showAddEntry = false
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
            IconButton(onClick = {
                if (selectedTab == 0) showAddLocation = true
                else showAddEntry = true
            }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.planner_add))
            }
        }

        // ── Tabs ─────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.planner_tab_locations)) },
                icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.planner_tab_sessions)) },
                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
            )
        }

        Spacer(Modifier.height(12.dp))

        when (selectedTab) {
            0 -> LocationsTab(state.locations, plannerManager)
            1 -> SessionsTab(state, plannerManager, onAddEntry = {
                if (state.locations.isEmpty()) showAddLocation = true
                else showAddEntry = true
            })
        }
    }
}

// ── Locations tab ────────────────────────────────────────────────────────────

@Composable
private fun LocationsTab(
    locations: List<SavedLocation>,
    manager: PlannerManager,
) {
    if (locations.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.AddLocationAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.planner_no_locations),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(locations, key = { it.id }) { loc ->
            LocationCard(loc) { manager.removeLocation(loc.id) }
        }
    }
}

@Composable
private fun LocationCard(
    location: SavedLocation,
    onDelete: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.planner_delete_location_title)) },
            text = { Text(stringResource(R.string.planner_delete_location_msg, location.name)) },
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
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    location.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    String.format("%.4f, %.4f", location.latitude, location.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ── Sessions tab ─────────────────────────────────────────────────────────────

@Composable
private fun SessionsTab(
    state: PlannerState,
    manager: PlannerManager,
    onAddEntry: () -> Unit,
) {
    if (state.entries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.EventNote,
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
                OutlinedButton(onClick = onAddEntry) {
                    Text(stringResource(R.string.planner_add_session))
                }
            }
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            state.entries.sortedBy { it.date },
            key = { it.id },
        ) { entry ->
            EntryCard(entry) { manager.removeEntry(entry.id) }
        }
    }
}

@Composable
private fun EntryCard(
    entry: PlannerEntry,
    onDelete: () -> Unit,
) {
    val isPast = entry.date.isBefore(LocalDate.now())

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isPast) Modifier.background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            ) else Modifier),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.location.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        entry.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VerdictChip(entry.verdict)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.delete),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (entry.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (entry.lastChecked > 0) {
                val ago = (System.currentTimeMillis() - entry.lastChecked) / 3_600_000
                Text(
                    stringResource(R.string.planner_last_checked, ago),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

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

// ── Add location dialog ──────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
@Composable
private fun AddLocationDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double) -> Unit,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planner_add_location_title)) },
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
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
                                    Text(
                                        result.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Use GPS button ───────────────────────────────────
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
                                    // Reverse geocode for a friendly name
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

                HorizontalDivider()

                // ── Manual fields (auto-filled by search / GPS) ──────
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), latStr.toDouble(), lonStr.toDouble()) },
                enabled = valid,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ── Add entry dialog ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEntryDialog(
    locations: List<SavedLocation>,
    onDismiss: () -> Unit,
    onConfirm: (SavedLocation, LocalDate) -> Unit,
) {
    var selectedLoc by remember { mutableStateOf(locations.first()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var expanded by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = pickedDate
                .atStartOfDay(java.time.ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    datePickerState.selectedDateMillis?.let { millis ->
                        pickedDate = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.of("UTC"))
                            .toLocalDate()
                    }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planner_add_session_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Location selector
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                ) {
                    OutlinedTextField(
                        value = selectedLoc.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.planner_location)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        locations.forEach { loc ->
                            DropdownMenuItem(
                                text = { Text(loc.name) },
                                onClick = {
                                    selectedLoc = loc
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                // Date selector
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(pickedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedLoc, pickedDate) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
