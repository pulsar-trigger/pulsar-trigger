/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.components.NumPadDialog
import com.ehrocha.pulsar.ui.components.ScrubPresets
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

private enum class IvTab(val labelRes: Int) {
    INTERVAL(R.string.iv2_tab_interval),
    EXPOSURE(R.string.iv2_tab_exposure),
    SHOTS(R.string.iv2_tab_shots),
    DELAY(R.string.iv2_tab_delay),
}

/**
 * Intervalometer 2 — single-value-at-a-time editor. Tabs switch which
 * parameter is in focus; the active value is rendered as a huge tap-to-edit
 * number with a vertical-drag scrub on the surrounding area. Preset chips
 * give 1-tap jumps. A sticky summary strip + start/stop pill anchor the
 * bottom.
 *
 * State is the same `vm.intervalMs / exposureMs / shotCount / delayMs` as the
 * legacy Intervalometer panel — switching between screens preserves config.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Intervalometer2Screen(vm: PulsarViewModel, onBack: () -> Unit) {
    val intervalMs by vm.intervalMs.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val shotCount by vm.shotCount.collectAsState()
    val delayMs by vm.delayMs.collectAsState()
    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current

    var tabIdx by rememberSaveable { mutableIntStateOf(0) }
    val tab = IvTab.entries[tabIdx]

    val continuous = shotCount == 0
    val totalMs = if (continuous) 0L
                  else delayMs + shotCount.toLong() * (exposureMs + intervalMs) - intervalMs

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_intervalometer_2),
                onBack = onBack,
            )
        },
        bottomBar = {
            BottomBar(
                running = running,
                canStart = connected && !running,
                onStart = {
                    vm.saveFlowSteps(
                        listOf(
                            FlowStep.Intervalometer(
                                intervalMs = intervalMs,
                                exposureMs = exposureMs,
                                shotCount = shotCount,
                                delayMs = delayMs,
                            )
                        )
                    )
                    vm.startFlow()
                },
                onStop = { vm.stopFlow() },
            )
        },
    ) { pad ->
        Column(modifier = Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tabIdx) {
                IvTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = tabIdx == i,
                        onClick = { tabIdx = i },
                        text = { Text(stringResource(t.labelRes)) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    IvTab.INTERVAL -> BigTimeEditor(
                        ms = intervalMs,
                        onChange = { vm.setIntervalMs(it.coerceAtLeast(AppConfig.MIN_INTERVAL_MS)) },
                        presets = ScrubPresets.INTERVAL,
                        scrubStepMs = 1_000L,
                        rangeMs = AppConfig.MIN_INTERVAL_MS..3_600_000L,
                        formatter = ::formatHmsPretty,
                        enabled = !running,
                    )
                    IvTab.EXPOSURE -> BigTimeEditor(
                        ms = exposureMs,
                        onChange = { vm.setExposureMs(it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)) },
                        presets = ScrubPresets.EXPOSURE,
                        scrubStepMs = 100L,
                        rangeMs = AppConfig.MIN_EXPOSURE_MS..86_400_000L,
                        formatter = ::formatHmsPretty,
                        enabled = !running,
                    )
                    IvTab.SHOTS -> BigIntEditor(
                        value = shotCount,
                        onChange = { vm.setShotCount(it.coerceAtLeast(0)) },
                        presets = listOf(0, 30, 60, 120, 240),
                        presetLabel = { if (it == 0) "∞" else "$it" },
                        zeroLabel = "∞",
                        range = 0..9999,
                        enabled = !running,
                    )
                    IvTab.DELAY -> BigTimeEditor(
                        ms = delayMs,
                        onChange = { vm.setDelayMs(it.coerceAtLeast(0)) },
                        presets = ScrubPresets.DELAY,
                        scrubStepMs = 1_000L,
                        rangeMs = 0L..3_600_000L,
                        formatter = ::formatHmsPretty,
                        enabled = !running,
                    )
                }
            }

            SummaryStrip(
                shotCount = shotCount,
                continuous = continuous,
                totalMs = totalMs,
            )
        }
    }
}

// ── Editors ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigTimeEditor(
    ms: Long,
    onChange: (Long) -> Unit,
    presets: List<Long>,
    scrubStepMs: Long,
    rangeMs: LongRange,
    formatter: (Long) -> String,
    enabled: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerStep = with(density) { 6.dp.toPx() }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragAccumPx != 0f
    val displayed = (ms + (dragAccumPx / pxPerStep).toLong() * scrubStepMs)
        .coerceIn(rangeMs.first, rangeMs.last)

    var showNumPad by remember { mutableStateOf(false) }
    if (showNumPad) {
        NumPadDialog(
            initialValue = (ms / 1000).toString(),
            onConfirm = { entered ->
                val secs = entered.toLongOrNull() ?: (ms / 1000)
                val newMs = (secs * 1000L).coerceIn(rangeMs.first, rangeMs.last)
                if (newMs != ms) onChange(newMs)
                showNumPad = false
            },
            onDismiss = { showNumPad = false },
            maxDigits = 5,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = enabled) { showNumPad = true }
            .pointerInput(enabled, ms) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { dragAccumPx = 0f },
                    onDragEnd = {
                        val committed = (ms + (dragAccumPx / pxPerStep).toLong() * scrubStepMs)
                            .coerceIn(rangeMs.first, rangeMs.last)
                        if (committed != ms) onChange(committed)
                        dragAccumPx = 0f
                    },
                    onDragCancel = { dragAccumPx = 0f },
                ) { _, dy ->
                    // Up = increase. dy positive = down = decrease.
                    val prev = (dragAccumPx / pxPerStep).toLong()
                    dragAccumPx -= dy * velocityAccel(abs(dy))
                    val cur = (dragAccumPx / pxPerStep).toLong()
                    if (cur != prev) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            formatter(displayed),
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = if (isDragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.iv2_tap_to_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        PresetRow(presets, ms, onChange = onChange, format = ::formatPresetShort, enabled = enabled)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BigIntEditor(
    value: Int,
    onChange: (Int) -> Unit,
    presets: List<Int>,
    presetLabel: (Int) -> String,
    zeroLabel: String?,
    range: IntRange,
    enabled: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerStep = with(density) { 8.dp.toPx() }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragAccumPx != 0f
    val displayed = (value + (dragAccumPx / pxPerStep).toInt())
        .coerceIn(range)

    var showNumPad by remember { mutableStateOf(false) }
    if (showNumPad) {
        NumPadDialog(
            initialValue = value.toString(),
            onConfirm = { entered ->
                val v = entered.toIntOrNull()?.coerceIn(range) ?: value
                if (v != value) onChange(v)
                showNumPad = false
            },
            onDismiss = { showNumPad = false },
            maxDigits = 4,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(enabled = enabled) { showNumPad = true }
            .pointerInput(enabled, value) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { dragAccumPx = 0f },
                    onDragEnd = {
                        val committed = (value + (dragAccumPx / pxPerStep).toInt())
                            .coerceIn(range)
                        if (committed != value) onChange(committed)
                        dragAccumPx = 0f
                    },
                    onDragCancel = { dragAccumPx = 0f },
                ) { _, dy ->
                    val prev = (dragAccumPx / pxPerStep).toInt()
                    dragAccumPx -= dy * velocityAccel(abs(dy))
                    val cur = (dragAccumPx / pxPerStep).toInt()
                    if (cur != prev) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val text = if (zeroLabel != null && displayed == 0) zeroLabel else "$displayed"
        Text(
            text,
            fontSize = 96.sp,
            fontWeight = FontWeight.Light,
            color = if (isDragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.iv2_tap_to_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                val selected = p == value
                AssistChip(
                    onClick = { if (enabled && p in range) onChange(p) },
                    label = { Text(presetLabel(p)) },
                    colors = if (selected) AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) else AssistChipDefaults.assistChipColors(),
                    enabled = enabled,
                )
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────

@Composable
private fun PresetRow(
    presetsMs: List<Long>,
    currentMs: Long,
    onChange: (Long) -> Unit,
    format: (Long) -> String,
    enabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presetsMs.forEach { p ->
            val selected = currentMs == p
            AssistChip(
                onClick = { if (enabled) onChange(p) },
                label = { Text(format(p)) },
                colors = if (selected) AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) else AssistChipDefaults.assistChipColors(),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun SummaryStrip(shotCount: Int, continuous: Boolean, totalMs: Long) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (continuous) stringResource(R.string.iv2_summary_continuous)
                else stringResource(R.string.iv2_summary_shots, "$shotCount"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!continuous && totalMs > 0) {
                Text(
                    stringResource(R.string.iv2_summary_total, formatHmsPretty(totalMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.iv2_summary_ends, formatEndClock(totalMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BottomBar(
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = { /* TODO: save preset */ }, enabled = !running) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
            }
            Spacer(Modifier.weight(1f))
            if (running) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(56.dp).fillMaxWidth(0.6f),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_stop), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = canStart,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.height(56.dp).fillMaxWidth(0.6f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.btn_start), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Pixels-per-step velocity acceleration ramp: 1× for slow drags, up to 8×
 *  for fast flicks. Matches the IntScrubField helper for consistency. */
private fun velocityAccel(absDy: Float): Float = when {
    absDy >= 24f -> 8.0f
    absDy >= 12f -> 4.0f
    absDy >= 6f  -> 2.0f
    else         -> 1.0f
}

private fun formatHmsPretty(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
           else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatPresetShort(ms: Long): String = when {
    ms < 1_000 -> {
        val denom = (1_000.0 / ms).toInt()
        if (denom > 1) "1/${denom}" else "${ms}ms"
    }
    ms < 60_000 -> "${ms / 1_000}s"
    ms < 3_600_000 -> "${ms / 60_000}m"
    else -> "${ms / 3_600_000}h"
}

private fun formatEndClock(durationFromNowMs: Long): String {
    val end = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis() + durationFromNowMs
    }
    return String.format(Locale.US, "%02d:%02d",
        end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE))
}
