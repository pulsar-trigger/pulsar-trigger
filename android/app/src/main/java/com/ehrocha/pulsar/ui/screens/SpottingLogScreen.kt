/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-curated log of aircraft they've spotted. Persisted as a JSON file
 *  in app filesDir; reads on entry, exposes Share-as-text and Clear-all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpottingLogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LoggedSighting>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        entries = SpottingLogStore.load(ctx).sortedByDescending { it.whenMs }
    }
    var pendingClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.spotting_log_title),
                onBack = onBack,
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = {
                            shareLog(ctx, entries)
                        }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.spotting_log_share),
                            )
                        }
                        IconButton(onClick = { pendingClear = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.spotting_log_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (entries.isEmpty()) {
            com.ehrocha.pulsar.ui.components.EmptyState(
                icon = Icons.Default.FlightTakeoff,
                text = stringResource(R.string.spotting_log_empty),
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { "${it.whenMs}_${it.icaoHex}" }) { e ->
                    LogRow(
                        entry = e,
                        onDelete = {
                            scope.launch {
                                SpottingLogStore.delete(ctx, e.whenMs, e.icaoHex)
                                refresh += 1
                            }
                        },
                    )
                }
            }
        }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text(stringResource(R.string.spotting_log_clear_confirm_title)) },
            text = { Text(stringResource(R.string.spotting_log_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = false
                    scope.launch {
                        SpottingLogStore.clear(ctx)
                        refresh += 1
                    }
                }) {
                    Text(stringResource(R.string.spotting_log_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun LogRow(entry: LoggedSighting, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                entry.callsign ?: entry.icaoHex.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            val sub = listOfNotNull(entry.model, entry.operator, entry.registration)
                .joinToString(" · ")
                .ifEmpty { entry.icaoHex.uppercase() }
            Text(
                sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatLogTime(entry.whenMs) +
                    "  ·  " + String.format(Locale.US, "%.1f km", entry.distanceKm),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(onClick = onDelete, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.spotting_log_delete))
            }
        }
    }
}

private fun formatLogTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).format(t)
}

private fun shareLog(ctx: android.content.Context, entries: List<LoggedSighting>) {
    val body = buildString {
        appendLine("Pulsar Trigger — Spotting Log")
        appendLine()
        entries.forEach { e ->
            appendLine("${formatLogTime(e.whenMs)}  ${(e.callsign ?: e.icaoHex.uppercase())}")
            val parts = listOfNotNull(e.model, e.operator, e.registration)
            if (parts.isNotEmpty()) appendLine("  ${parts.joinToString(" · ")}")
            appendLine("  ICAO ${e.icaoHex.uppercase()} · ${String.format(Locale.US, "%.1f km", e.distanceKm)}")
            appendLine()
        }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Pulsar Spotting Log")
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    ctx.startActivity(android.content.Intent.createChooser(intent, null))
}
