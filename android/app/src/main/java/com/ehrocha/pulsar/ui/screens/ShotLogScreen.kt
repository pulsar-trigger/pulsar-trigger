/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.ShotLogEntry
import com.ehrocha.pulsar.model.ShotLogStatus
import com.ehrocha.pulsar.ui.theme.StatusGreen
import com.ehrocha.pulsar.ui.theme.StatusOrange
import com.ehrocha.pulsar.ui.theme.StatusRed
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotLogScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val entries by vm.shotLog.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shot_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.delete))
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.shot_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ShotLogStats(entries)
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 88.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { entry -> ShotLogRow(entry) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.shot_log_clear_title)) },
            text = { Text(stringResource(R.string.shot_log_clear_body)) },
            confirmButton = {
                TextButton(onClick = { vm.shotLog.clear(); confirmClear = false }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ShotLogStats(entries: List<ShotLogEntry>) {
    val totalShots = entries.sumOf { it.completedShots }
    val totalRuns = entries.size
    val completedRuns = entries.count { it.status == ShotLogStatus.COMPLETED }
    val successRate = if (totalRuns > 0) (100 * completedRuns) / totalRuns else 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 72.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCard(stringResource(R.string.shot_log_stat_runs), "$totalRuns", Modifier.weight(1f))
        StatCard(stringResource(R.string.shot_log_stat_shots), "$totalShots", Modifier.weight(1f))
        StatCard(stringResource(R.string.shot_log_stat_success), "${successRate}%", Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ShotLogRow(entry: ShotLogEntry) {
    val (icon, tint) = statusIconAndColor(entry.status)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.modeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDate(entry.startedAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.shot_log_row_shots,
                        entry.completedShots,
                        entry.plannedShots,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatDuration(entry.durationMs()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.exposureMs > 0 || entry.intervalMs > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (entry.exposureMs > 0) {
                        MiniStat(stringResource(R.string.label_exposure), formatDuration(entry.exposureMs))
                    }
                    if (entry.intervalMs > 0) {
                        MiniStat(stringResource(R.string.label_interval), formatDuration(entry.intervalMs))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun statusIconAndColor(status: ShotLogStatus): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    return when (status) {
        ShotLogStatus.COMPLETED -> Icons.Default.CheckCircle to StatusGreen
        ShotLogStatus.STOPPED   -> Icons.Default.StopCircle to StatusOrange
        ShotLogStatus.ERROR     -> Icons.Default.ErrorOutline to StatusRed
    }
}

private fun formatDate(ms: Long): String {
    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return fmt.format(Date(ms))
}
