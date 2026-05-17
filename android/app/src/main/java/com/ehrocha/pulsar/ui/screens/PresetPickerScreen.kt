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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.UserMode
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Lists saved presets (UserModes filtered by [fwMode]) and a "Start fresh"
 * option that takes the user into the wizard with empty state. Tapping a
 * preset opens the wizard pre-filled. Long-press / trailing icon allows
 * delete + bookmark toggle.
 */
@Composable
fun PresetPickerScreen(
    vm: PulsarViewModel,
    fwMode: TriggerMode,
    onBack: () -> Unit,
    onStartFresh: () -> Unit,
    onPresetSelected: (UserMode) -> Unit,
) {
    val allModes by vm.userModes.collectAsState()
    val presets = remember(allModes, fwMode) { allModes.filter { it.body.fwMode == fwMode } }
    val title = stringResource(titleResFor(fwMode))
    var confirmDelete by remember { mutableStateOf<UserMode?>(null) }

    Scaffold(
        topBar = { PulsarTopBar(title = title, onBack = onBack) },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                StartFreshCard(onClick = onStartFresh)
            }
            if (presets.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.preset_picker_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                    )
                }
                items(presets, key = { it.id }) { preset ->
                    PresetRow(
                        preset = preset,
                        onClick = { onPresetSelected(preset) },
                        onToggleBookmark = { vm.toggleUserModeBookmark(preset.id) },
                        onDelete = { confirmDelete = preset },
                    )
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.preset_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                    )
                }
            }
        }
    }

    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.preset_picker_delete_title)) },
            text = { Text(stringResource(R.string.preset_picker_delete_body, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeUserMode(preset.id)
                    confirmDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun StartFreshCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.preset_picker_start_fresh),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.preset_picker_start_fresh_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: UserMode,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    presetSummary(preset),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (preset.bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = stringResource(
                        if (preset.bookmarked) R.string.preset_picker_unbookmark
                        else R.string.preset_picker_bookmark,
                    ),
                    tint = if (preset.bookmarked) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun titleResFor(fwMode: TriggerMode): Int = when (fwMode) {
    TriggerMode.INTERVALOMETER -> R.string.mode_intervalometer
    TriggerMode.ASTRO -> R.string.mode_astro
    TriggerMode.DARK_FRAME -> R.string.mode_dark_frame
    TriggerMode.RAMP -> R.string.mode_ramp
    TriggerMode.TIMELAPSE -> R.string.mode_timelapse
    else -> R.string.mode_intervalometer
}

private fun presetSummary(preset: UserMode): String {
    val b = preset.body
    fun timeFmt(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1_000}s"
        else -> "${ms / 60_000}m${(ms % 60_000) / 1_000}s".removeSuffix("0s")
    }
    return when (b.fwMode) {
        TriggerMode.INTERVALOMETER ->
            "${timeFmt(b.exposureMs)} · ${timeFmt(b.intervalMs)} gap · ${if (b.shotCount == 0) "∞" else "${b.shotCount}"} shots"
        TriggerMode.TIMELAPSE ->
            "${timeFmt(b.intervalMs)} interval · ${if (b.shotCount == 0) "∞" else "${b.shotCount}"} shots"
        TriggerMode.ASTRO -> {
            val maxExp = if (b.focalLength > 0)
                AppConfig.astroExposureMs(b.focalLength, b.cropFactor, b.ruleDivisor)
            else 0L
            "${b.focalLength}mm · ${timeFmt(maxExp)} · ${if (b.shotCount == 0) "∞" else "${b.shotCount}"} shots"
        }
        TriggerMode.DARK_FRAME ->
            "${timeFmt(b.exposureMs)} · ${b.shotCount} frames"
        TriggerMode.RAMP ->
            "${timeFmt(b.rampStartExposureMs)} → ${timeFmt(b.rampEndExposureMs)} · ${b.rampSteps} steps"
        else -> ""
    }
}
