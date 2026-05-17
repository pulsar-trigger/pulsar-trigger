/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.ui.components.NumPadDialog
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import java.util.Calendar
import java.util.Locale

private enum class IvTab(val labelRes: Int, val isTime: Boolean) {
    EXPOSURE(R.string.iv2_tab_exposure, true),
    INTERVAL(R.string.iv2_tab_interval, true),
    DELAY(R.string.iv2_tab_delay, true),
    SHOTS(R.string.iv2_tab_shots, false),
}

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
                    IvTab.EXPOSURE -> SegmentedTimeEditor(
                        ms = exposureMs,
                        onChange = { vm.setExposureMs(it.coerceAtLeast(AppConfig.MIN_EXPOSURE_MS)) },
                        rangeMs = AppConfig.MIN_EXPOSURE_MS..86_400_000L,
                        enabled = !running,
                    )
                    IvTab.INTERVAL -> SegmentedTimeEditor(
                        ms = intervalMs,
                        onChange = { vm.setIntervalMs(it.coerceAtLeast(AppConfig.MIN_INTERVAL_MS)) },
                        rangeMs = AppConfig.MIN_INTERVAL_MS..3_600_000L,
                        enabled = !running,
                    )
                    IvTab.DELAY -> SegmentedTimeEditor(
                        ms = delayMs,
                        onChange = { vm.setDelayMs(it.coerceAtLeast(0)) },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    IvTab.SHOTS -> ShotsEditor(
                        value = shotCount,
                        onChange = { vm.setShotCount(it.coerceAtLeast(0)) },
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

@Composable
private fun SegmentedTimeEditor(
    ms: Long,
    onChange: (Long) -> Unit,
    rangeMs: LongRange,
    enabled: Boolean,
) {
    val (h, m, s, cs) = decomposeMs(ms)

    fun commit(newH: Int = h, newM: Int = m, newS: Int = s, newCs: Int = cs) {
        val recomposed = recomposeMs(newH, newM, newS, newCs).coerceIn(rangeMs.first, rangeMs.last)
        if (recomposed != ms) onChange(recomposed)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            ScrubSegment(
                value = h, range = 0..23, format = "%02d",
                onChange = { commit(newH = it) },
                enabled = enabled,
                fontSize = 64.sp,
            )
            Separator(":", fontSize = 56.sp)
            ScrubSegment(
                value = m, range = 0..59, format = "%02d",
                onChange = { commit(newM = it) },
                enabled = enabled,
                fontSize = 64.sp,
            )
            Separator(":", fontSize = 56.sp)
            ScrubSegment(
                value = s, range = 0..59, format = "%02d",
                onChange = { commit(newS = it) },
                enabled = enabled,
                fontSize = 64.sp,
            )
            Separator(".", fontSize = 56.sp)
            ScrubSegment(
                value = cs, range = 0..99, format = "%02d",
                onChange = { commit(newCs = it) },
                enabled = enabled,
                fontSize = 44.sp,
                pxPerStep = 8.dp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "hh : mm : ss . ms",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.iv2_tap_to_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ShotsEditor(
    value: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScrubSegment(
            value = value, range = 0..9999,
            format = null,
            zeroLabel = "∞",
            onChange = onChange,
            enabled = enabled,
            fontSize = 96.sp,
            maxDigits = 4,
            pxPerStep = 8.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.iv2_shots_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.iv2_tap_to_edit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun ScrubSegment(
    value: Int,
    range: IntRange,
    format: String?,
    onChange: (Int) -> Unit,
    enabled: Boolean,
    fontSize: TextUnit,
    zeroLabel: String? = null,
    maxDigits: Int = 2,
    pxPerStep: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val stepPx = with(density) { pxPerStep.toPx() }
    var dragAccumPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragAccumPx != 0f
    // Up-drag = decrease dy (negative), should INCREASE value.
    val delta = (-dragAccumPx / stepPx).toInt()
    val displayed = (value + delta).coerceIn(range)

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
            maxDigits = maxDigits,
        )
    }

    val text = when {
        zeroLabel != null && displayed == 0 -> zeroLabel
        format != null -> format.format(displayed)
        else -> "$displayed"
    }

    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = FontWeight.Light,
        color = if (isDragging) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(enabled = enabled) { showNumPad = true }
            .pointerInput(enabled, value) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { dragAccumPx = 0f },
                    onDragEnd = {
                        val committed = (value + (-dragAccumPx / stepPx).toInt()).coerceIn(range)
                        if (committed != value) onChange(committed)
                        dragAccumPx = 0f
                    },
                    onDragCancel = { dragAccumPx = 0f },
                ) { _, dy ->
                    val prev = (-dragAccumPx / stepPx).toInt()
                    dragAccumPx += dy
                    val cur = (-dragAccumPx / stepPx).toInt()
                    if (cur != prev) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
            .padding(horizontal = 4.dp),
    )
}

@Composable
private fun Separator(sym: String, fontSize: TextUnit) {
    Text(
        sym,
        fontSize = fontSize,
        fontWeight = FontWeight.Light,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── Decomposition helpers ────────────────────────────────────────────────

private data class TimeParts(val h: Int, val m: Int, val s: Int, val cs: Int)

private operator fun TimeParts.component1() = h
private operator fun TimeParts.component2() = m
private operator fun TimeParts.component3() = s
private operator fun TimeParts.component4() = cs

private fun decomposeMs(ms: Long): TimeParts {
    val totalCs = ms / 10  // centiseconds
    val h = (totalCs / 360_000).toInt()
    val m = ((totalCs % 360_000) / 6_000).toInt()
    val s = ((totalCs % 6_000) / 100).toInt()
    val cs = (totalCs % 100).toInt()
    return TimeParts(h, m, s, cs)
}

private fun recomposeMs(h: Int, m: Int, s: Int, cs: Int): Long =
    h * 3_600_000L + m * 60_000L + s * 1_000L + cs * 10L

// ── Summary + bottom bar ─────────────────────────────────────────────────

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

private fun formatHmsPretty(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
           else String.format(Locale.US, "%02d:%02d", m, s)
}

private fun formatEndClock(durationFromNowMs: Long): String {
    val end = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis() + durationFromNowMs
    }
    return String.format(Locale.US, "%02d:%02d",
        end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE))
}
