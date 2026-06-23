/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.ShotLogEntry
import com.ehrocha.pulsar.model.ShotLogStatus
import com.ehrocha.pulsar.model.UserMode
import com.ehrocha.pulsar.ui.components.LocalSnackbarHost
import com.ehrocha.pulsar.ui.components.ModeUsageBar
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.components.SessionStat
import com.ehrocha.pulsar.ui.components.SuccessRing
import com.ehrocha.pulsar.ui.theme.StatusGreen
import com.ehrocha.pulsar.ui.theme.StatusOrange
import com.ehrocha.pulsar.ui.theme.StatusRed
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotLogScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val entries by vm.shotLog.entries.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var grouped by remember { mutableStateOf(false) }
    var savePresetFor by remember { mutableStateOf<ShotLogEntry?>(null) }
    val snackHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val presetSavedMsg = stringResource(R.string.preset_saved)
    val presetLimitMsg = stringResource(R.string.preset_limit, com.ehrocha.pulsar.model.UserMode.MAX_USER_MODES)

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.shot_log_title),
                onBack = onBack,
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = { grouped = !grouped }) {
                            Icon(
                                Icons.Default.Sort,
                                contentDescription = stringResource(R.string.sessions_group_toggle),
                                tint = if (grouped) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.delete))
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (entries.isEmpty()) {
            com.ehrocha.pulsar.ui.components.EmptyState(
                icon = Icons.Default.PhotoCamera,
                text = stringResource(R.string.shot_log_empty),
                modifier = Modifier.padding(pad),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item("hero") { SessionStatsHeader(entries) }
                if (grouped) {
                    entries.groupBy { it.modeLabel }.entries.sortedByDescending { it.value.size }
                        .forEach { (mode, list) ->
                            item(key = "h:$mode") { GroupHeader(mode, list.size) }
                            items(list, key = { it.id }) { entry ->
                                ShotLogRow(entry, if (entry.canMakePreset()) ({ savePresetFor = entry }) else null)
                            }
                        }
                } else {
                    items(entries, key = { it.id }) { entry ->
                        ShotLogRow(entry, if (entry.canMakePreset()) ({ savePresetFor = entry }) else null)
                    }
                }
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

    savePresetFor?.let { entry ->
        SavePresetDialog(
            entry = entry,
            onSave = { name ->
                val msg = entry.presetStep?.let { step ->
                    bodyFromStep(step)?.let { body ->
                        // Don't create a second preset with identical settings —
                        // tell the user which existing one already matches.
                        val dup = vm.userModes.value.firstOrNull { it.body == body }
                        when {
                            dup != null -> context.getString(R.string.preset_exists, dup.name)
                            vm.upsertUserMode(UserMode(name = name, body = body)) -> presetSavedMsg
                            else -> presetLimitMsg
                        }
                    }
                }
                if (msg != null) scope.launch { snackHost.showSnackbar(msg) }
                savePresetFor = null
            },
            onDismiss = { savePresetFor = null },
        )
    }
}

/** Converts a captured run-step into a preset [UserMode.Body]. Null for a Pause
 *  (not a shooting mode). Mirrors the wizard → Body field mapping. */
private fun bodyFromStep(step: FlowStep): UserMode.Body? = when (step) {
    is FlowStep.Intervalometer -> UserMode.Body(
        fwMode = if (step.timelapse) TriggerMode.TIMELAPSE else TriggerMode.INTERVALOMETER,
        intervalMs = step.intervalMs, exposureMs = step.exposureMs, shotCount = step.shotCount,
        delayMs = step.delayMs, useAutofocus = step.useAutofocus,
        iso = step.cameraSettings.iso, aperture = step.cameraSettings.aperture,
        shutterSpeed = step.cameraSettings.shutterSpeed,
    )
    is FlowStep.Astro -> UserMode.Body(
        fwMode = TriggerMode.ASTRO, intervalMs = step.gapMs, shotCount = step.shotCount,
        delayMs = step.delayMs, focalLength = step.focalLength, cropFactor = step.cropFactor,
        ruleDivisor = step.ruleDivisor, useAutofocus = step.useAutofocus,
    )
    is FlowStep.DarkFrame -> UserMode.Body(
        fwMode = TriggerMode.DARK_FRAME, exposureMs = step.exposureMs, shotCount = step.shotCount,
        intervalMs = step.gapMs, delayMs = step.delayMs, useAutofocus = step.useAutofocus,
    )
    is FlowStep.Ramp -> UserMode.Body(
        fwMode = TriggerMode.RAMP, intervalMs = step.intervalMs,
        rampStartExposureMs = step.startExposureMs, rampEndExposureMs = step.endExposureMs,
        rampSteps = step.steps, delayMs = step.delayMs, useAutofocus = step.useAutofocus,
    )
    is FlowStep.Pause -> null
}

@Composable
private fun SavePresetDialog(entry: ShotLogEntry, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(entry.modeLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BookmarkAdd, contentDescription = null) },
        title = { Text(stringResource(R.string.save_as_preset)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.preset_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save_as_preset))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GroupHeader(mode: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mode.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Bold session-stats hero atop the ShotLog list — the same SuccessRing +
 *  Runs/Shots + mode-usage bars as the dashboard card (shared graphics), at a
 *  larger scale so the full screen reads as the detailed view of that card. */
@Composable
private fun SessionStatsHeader(entries: List<ShotLogEntry>) {
    val totalShots = entries.sumOf { it.completedShots }
    val totalRuns = entries.size
    val completedRuns = entries.count { it.status == ShotLogStatus.COMPLETED }
    val successRate = if (totalRuns > 0) (100 * completedRuns) / totalRuns else 0
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SuccessRing(successRate, Modifier.size(84.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SessionStat(stringResource(R.string.shot_log_stat_runs), "$totalRuns")
                    SessionStat(stringResource(R.string.shot_log_stat_shots), "$totalShots")
                }
            }
            val usage = entries.groupingBy { it.modeLabel }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
            if (usage.isNotEmpty()) {
                val maxUse = usage.first().value
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.session_mode_usage).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                usage.forEach { (mode, count) -> ModeUsageBar(mode, count, maxUse) }
            }
        }
    }
}

@Composable
private fun ShotLogRow(entry: ShotLogEntry, onSavePreset: (() -> Unit)? = null) {
    val (icon, tint) = statusIconAndColor(entry.status)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Status accent stripe — green / amber / red down the leading edge.
            Box(Modifier.width(4.dp).fillMaxHeight().background(tint))
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (onSavePreset != null) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onSavePreset, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.BookmarkAdd,
                            contentDescription = stringResource(R.string.save_as_preset),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
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
            entry.conditions?.let { ConditionsRow(it) }
            }
        }
    }
}

/** Compact one-line summary of the conditions at run start, mirroring
 *  the in-app Dashboard verdict chips (small icons + values). Renders
 *  nothing when the snapshot is null (older entries / no snapshot
 *  available at run time). */
@Composable
private fun ConditionsRow(c: com.ehrocha.pulsar.model.ConditionSnapshot) {
    val parts = buildList {
        c.moonIlluminationPct?.let { illum ->
            val emoji = if (c.moonGoodForAstro == true) "🌑" else "🌖"
            add("$emoji ${illum.toInt()}%")
        }
        c.cloudCoverPct?.let { add("☁ ${it}%") }
        c.dewPointC?.let { dew ->
            val risk = c.dewRisk
            val mark = when (risk) {
                "CRITICAL" -> "💧!"
                "WARNING" -> "💧"
                else -> "·"
            }
            add("$mark dew ${dew.toInt()}°C")
        }
        c.bortleClass?.let { add("💡 B${it.toInt()}") }
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString("  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
