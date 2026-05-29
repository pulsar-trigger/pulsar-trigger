/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
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
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.ui.components.NumPadDialog
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.theme.LocalCurrentFlowStep
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.ui.theme.StatusOrange
import com.ehrocha.pulsar.ui.theme.StatusRed
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
fun Intervalometer2Screen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    initialPresetId: String? = null,
) {
    // Track which preset (if any) is being edited. null = brand-new config.
    val allModes by vm.userModes.collectAsState()
    val loadedPreset = remember(initialPresetId, allModes) {
        initialPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    var editingPresetId by rememberSaveable { mutableStateOf(initialPresetId) }

    // Local state — defaults to the loaded preset's values, or zero for
    // "start fresh". rememberSaveable's initializer only runs once, so
    // changing presets requires navigating away and back.
    var exposureMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.exposureMs ?: 0L)
    }
    var intervalMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.intervalMs ?: 0L)
    }
    var shotCount by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.shotCount ?: 0)
    }
    var delayMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.delayMs ?: 0L)
    }
    var useAutofocus by rememberSaveable {
        mutableStateOf(loadedPreset?.body?.useAutofocus ?: false)
    }

    // Save dialog state
    var showSaveDialog by remember { mutableStateOf(false) }

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current
    val onCanon = vm.canonCcapiTransport.collectAsState().value != null
    val onPtp = vm.ptpTransport.collectAsState().value != null
    val onCanonBle = vm.canonBleTransport.collectAsState().value != null
    val canControlAf = onCanon || onPtp || onCanonBle

    // Jump to the final tab when a preset is loaded — its values are already
    // valid so the user is one tap away from Start.
    var tabIdx by rememberSaveable {
        mutableIntStateOf(if (loadedPreset != null) IvTab.entries.size - 1 else 0)
    }
    val tab = IvTab.entries[tabIdx]

    val continuous = shotCount == 0
    val totalMs = if (continuous) 0L
                  else delayMs + shotCount.toLong() * (exposureMs + intervalMs) - intervalMs

    // Validation gate: every time-based parameter that drives the firmware
    // needs a non-zero value. Delay and shots can legitimately be zero
    // (no countdown / run-until-stop).
    val configComplete = exposureMs > 0L && intervalMs > 0L
    val isContinuous = configComplete && shotCount == 0
    // Per-tab validity: gates the wizard's Next button. Delay and Shots are
    // always valid (0 means "no countdown" / "continuous", both legitimate).
    val currentTabValid = when (tab) {
        IvTab.EXPOSURE -> exposureMs > 0L
        IvTab.INTERVAL -> intervalMs > 0L
        IvTab.DELAY -> true
        IvTab.SHOTS -> true
    }
    // Sub-second host-timed bulb is unreliable on ANY camera transport (the
    // press/release round-trip — WiFi RTT, BLE toggle, or USB — can't bracket
    // a <1s exposure). The ESP32/wired path can (firmware GPIO timing).
    val subSecondCanon = canControlAf && exposureMs in 1L..999L
    val bottomHint = when {
        tab == IvTab.EXPOSURE && exposureMs == 0L -> stringResource(R.string.iv2_set_exposure)
        tab == IvTab.EXPOSURE && subSecondCanon -> stringResource(R.string.canon_sub_second_warning)
        tab == IvTab.INTERVAL && intervalMs == 0L -> stringResource(R.string.iv2_set_interval)
        tab == IvTab.INTERVAL && intervalMs in 1L..1999L -> stringResource(R.string.interval_short_warning)
        isContinuous && tab == IvTab.SHOTS -> stringResource(R.string.iv2_continuous_warning)
        !configComplete && tab == IvTab.SHOTS -> stringResource(R.string.iv2_set_exposure_and_interval)
        else -> null
    }
    val hintIsContinuous = isContinuous && configComplete

    val editingPreset = remember(editingPresetId, allModes) {
        editingPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    val canSave = exposureMs > 0L && intervalMs > 0L

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_intervalometer),
                onBack = onBack,
                actions = {
                    if (editingPreset != null) {
                        IconButton(onClick = { vm.toggleUserModeBookmark(editingPreset.id) }) {
                            Icon(
                                if (editingPreset.bookmarked)
                                    Icons.Default.Bookmark
                                else
                                    Icons.Default.BookmarkBorder,
                                contentDescription = stringResource(
                                    if (editingPreset.bookmarked)
                                        R.string.preset_picker_unbookmark
                                    else
                                        R.string.preset_picker_bookmark,
                                ),
                                tint = if (editingPreset.bookmarked)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(
                        onClick = { showSaveDialog = true },
                        enabled = canSave && !running,
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.preset_save_action),
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomBar(
                running = running,
                currentTabIdx = tabIdx,
                tabCount = IvTab.entries.size,
                currentTabValid = currentTabValid,
                // Start is clickable as long as we have a device — the action
                // checks config and routes to the first missing-field tab if
                // not ready, so "nothing happens" is impossible.
                canStart = connected && !running,
                hint = if (running) null else bottomHint,
                hintIsAccent = hintIsContinuous,
                onPrev = { if (tabIdx > 0) tabIdx-- },
                onNext = { if (tabIdx < IvTab.entries.size - 1) tabIdx++ },
                onStart = {
                    when {
                        exposureMs == 0L -> tabIdx = IvTab.EXPOSURE.ordinal
                        intervalMs == 0L -> tabIdx = IvTab.INTERVAL.ordinal
                        else -> {
                            vm.saveFlowSteps(
                                listOf(
                                    FlowStep.Intervalometer(
                                        intervalMs = intervalMs,
                                        exposureMs = exposureMs,
                                        shotCount = shotCount,
                                        delayMs = delayMs,
                                        useAutofocus = useAutofocus,
                                    )
                                )
                            )
                            vm.startFlow()
                        }
                    }
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
                if (running) {
                    RunningView(plannedShots = shotCount)
                    return@Box
                }
                when (tab) {
                    IvTab.EXPOSURE -> SegmentedTimeEditor(
                        ms = exposureMs,
                        onChange = { exposureMs = it },
                        rangeMs = 0L..86_400_000L,
                        enabled = !running,
                    )
                    IvTab.INTERVAL -> SegmentedTimeEditor(
                        ms = intervalMs,
                        onChange = { intervalMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    IvTab.DELAY -> SegmentedTimeEditor(
                        ms = delayMs,
                        onChange = { delayMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    IvTab.SHOTS -> Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        ShotsEditor(
                            value = shotCount,
                            onChange = { shotCount = it },
                            enabled = !running,
                        )
                        if (canControlAf) {
                            com.ehrocha.pulsar.ui.components.AutofocusToggle(
                                checked = useAutofocus,
                                onCheckedChange = { useAutofocus = it },
                                enabled = !running,
                            )
                        }
                    }
                }
            }

            SummaryStrip(
                shotCount = shotCount,
                continuous = continuous,
                totalMs = totalMs,
                cameraHintRes = R.string.cam_hint_bulb,
            )
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            initialName = editingPreset?.name ?: "",
            isUpdate = editingPreset != null,
            onConfirm = { name ->
                val body = com.ehrocha.pulsar.model.UserMode.Body(
                    fwMode = com.ehrocha.pulsar.ble.TriggerMode.INTERVALOMETER,
                    intervalMs = intervalMs,
                    exposureMs = exposureMs,
                    shotCount = shotCount,
                    delayMs = delayMs,
                    useAutofocus = useAutofocus,
                )
                val mode = if (editingPreset != null) {
                    editingPreset.copy(name = name.trim(), body = body)
                } else {
                    com.ehrocha.pulsar.model.UserMode(name = name.trim(), body = body)
                }
                vm.upsertUserMode(mode)
                editingPresetId = mode.id
                showSaveDialog = false
            },
            onDismiss = { showSaveDialog = false },
        )
    }
}

// ── Editors ──────────────────────────────────────────────────────────────

@Composable
internal fun SegmentedTimeEditor(
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
internal fun ShotsEditor(
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

/**
 * Live-run dashboard rendered in place of the tab editors while a sequence
 * is running. Shows current state pill, shot counter (taken / planned, or
 * `n / ∞` for continuous), time remaining, and a progress bar.
 *
 * State pulls from [LocalDeviceStatus] (firmware status frame). For continuous
 * runs the progress bar is indeterminate.
 */
@Composable
internal fun RunningView(plannedShots: Int) {
    val status = LocalDeviceStatus.current
    val state = status?.state ?: DeviceState.IDLE
    val shotsTaken = status?.shotsTaken ?: 0
    val statusRemainingMs = status?.timeRemainingMs ?: 0L
    val batteryPct = status?.batteryPct ?: 0
    val currentStep = LocalCurrentFlowStep.current
    val continuous = plannedShots == 0

    // ── Continuous countdown: the run loop only updates timeRemainingMs at
    //    shot boundaries, so interpolate locally between updates — the
    //    remaining-time text and progress bar move smoothly, not in jumps.
    //    Resync to the authoritative value whenever the status changes. ──
    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastRemainingMs by remember { mutableLongStateOf(statusRemainingMs) }
    var liveRemainingMs by remember { mutableLongStateOf(statusRemainingMs) }
    // Total duration = the largest remaining we see (the first update at run
    // start), used to drive a smooth time-based progress bar.
    var totalMs by remember { mutableLongStateOf(statusRemainingMs) }
    LaunchedEffect(state) { if (state == DeviceState.IDLE) totalMs = 0L }
    LaunchedEffect(statusRemainingMs) {
        lastUpdateTime = System.currentTimeMillis()
        lastRemainingMs = statusRemainingMs
        liveRemainingMs = statusRemainingMs
        if (statusRemainingMs > totalMs) totalMs = statusRemainingMs
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100L)
            val elapsed = System.currentTimeMillis() - lastUpdateTime
            liveRemainingMs = (lastRemainingMs - elapsed).coerceAtLeast(0)
        }
    }

    // Human-friendly count: bump to the current shot the instant exposure
    // starts (1-based) instead of waiting for it to finish — people want to
    // feel progress. Holds through the trailing gap; never overshoots.
    val shotsTakenDisplay = when {
        state == DeviceState.RUNNING && continuous -> shotsTaken + 1
        state == DeviceState.RUNNING -> (shotsTaken + 1).coerceAtMost(plannedShots)
        else -> shotsTaken
    }

    val progress = when {
        continuous -> 0f
        totalMs > 0 -> ((totalMs - liveRemainingMs).toFloat() / totalMs).coerceIn(0f, 1f)
        else -> (shotsTakenDisplay.toFloat() / plannedShots.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(state)
            if (batteryPct > 0) {
                Spacer(Modifier.width(12.dp))
                BatteryChip(pct = batteryPct)
            }
        }
        // Settings chip for the current step — only meaningful inside a
        // multi-step flow run (Camera Test, Custom Flow). Single-step
        // wizards already show their config above the run; for them the
        // currentStep flow stays null and this no-ops.
        currentStep?.let { step ->
            Spacer(Modifier.height(12.dp))
            CurrentStepChip(step)
        }
        Spacer(Modifier.height(28.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$shotsTakenDisplay",
                fontSize = 96.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (continuous) " / ∞" else " / $plannedShots",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Text(
            stringResource(R.string.iv2_running_shots),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        if (!continuous && liveRemainingMs > 0) {
            Text(
                iv2FormatHmsPretty(liveRemainingMs),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.iv2_running_remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))

        if (continuous) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        }
    }
}

/** Tiny battery chip surfaced next to the [StatusPill] during a run. Only
 *  shows when a meaningful percentage is available (ESP32 firmware reports
 *  0 — no analog reading; Canon polling fills it in from the body). */
@Composable
private fun BatteryChip(pct: Int) {
    val color = when {
        pct < 20 -> MaterialTheme.colorScheme.error
        pct < 50 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.BatteryFull,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$pct%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

@Composable
private fun StatusPill(state: DeviceState) {
    // Use the LED palette from the top-bar indicator so RUN-vs-WAIT is
    // unambiguous at a glance: red for exposing (shutter open), orange for
    // the inter-shot gap. The full surface fills with the colour during
    // exposure + a slow pulse animation so the user can tell from across
    // the room when the shutter is open.
    val (labelRes, color) = when (state) {
        DeviceState.RUNNING -> R.string.iv2_state_exposing to StatusRed
        DeviceState.WAITING -> R.string.iv2_state_waiting to StatusOrange
        DeviceState.ERROR   -> R.string.iv2_state_error to MaterialTheme.colorScheme.error
        DeviceState.IDLE    -> R.string.iv2_state_starting to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pulsing = state == DeviceState.RUNNING
    val pulseAlpha = if (pulsing) {
        val infinite = rememberInfiniteTransition(label = "exposingPulse")
        val v by infinite.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "alpha",
        )
        v
    } else 1f
    // Fill colour vs background: RUNNING = filled with strong colour (LED on),
    // WAITING = soft halo, others = original soft halo.
    val (fillColor, textColor) = when (state) {
        DeviceState.RUNNING -> color.copy(alpha = pulseAlpha) to androidx.compose.ui.graphics.Color.White
        else -> color.copy(alpha = 0.15f) to color
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = fillColor,
    ) {
        Text(
            stringResource(labelRes),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            letterSpacing = 2.sp,
        )
    }
}

/** One-line summary chip surfaced under the StatusPill during a multi-step
 *  flow (Camera Test, Custom Flow). Single-step wizards have their config
 *  already on-screen so the chip stays null for them — see [LocalCurrentFlowStep]. */
@Composable
private fun CurrentStepChip(step: FlowStep) {
    val summary = stepSummary(step) ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = summary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun stepSummary(step: FlowStep): String? {
    val s = step
    return when (s) {
        is FlowStep.Intervalometer ->
            if (s.exposureMs == AppConfig.TIMELAPSE_PULSE_MS) {
                stringResource(
                    R.string.run_step_timelapse_summary,
                    s.shotCount, formatMsShort(s.intervalMs),
                )
            } else stringResource(
                R.string.run_step_interval_summary,
                s.shotCount, formatMsShort(s.exposureMs), formatMsShort(s.intervalMs),
            )
        is FlowStep.Astro -> stringResource(
            R.string.run_step_astro_summary,
            s.focalLength, s.cropFactor, s.ruleDivisor, s.shotCount,
            formatMsShort(s.gapMs),
        )
        is FlowStep.DarkFrame -> stringResource(
            R.string.run_step_dark_summary,
            s.shotCount, formatMsShort(s.exposureMs), formatMsShort(s.gapMs),
        )
        is FlowStep.Ramp -> stringResource(
            R.string.run_step_ramp_summary,
            s.steps,
            formatMsShort(s.startExposureMs), formatMsShort(s.endExposureMs),
            formatMsShort(s.intervalMs),
        )
        is FlowStep.Pause -> null
    }
}

/** Compact ms formatter: "750ms", "4s", "1m 30s". Used by the per-step
 *  summary chip; intentionally terse since the chip is a one-liner. */
private fun formatMsShort(ms: Long): String = when {
    ms <= 0L -> "0s"
    ms < 1_000L -> "${ms}ms"
    ms < 60_000L -> "${(ms + 500) / 1000}s"
    else -> {
        val totalSec = (ms + 500) / 1000
        val m = totalSec / 60
        val sec = totalSec % 60
        if (sec == 0L) "${m}m" else "${m}m ${sec}s"
    }
}

@Composable
internal fun SummaryStrip(
    shotCount: Int,
    continuous: Boolean,
    totalMs: Long,
    cameraHintRes: Int? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (cameraHintRes != null) {
                Text(
                    stringResource(cameraHintRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
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
                    stringResource(R.string.iv2_summary_total, iv2FormatHmsPretty(totalMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.iv2_summary_ends, iv2FormatEndClock(totalMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        }
    }
}

/**
 * Wizard-style bottom bar. Prev / Next navigate between tabs; on the last
 * tab Next is replaced by Start (gated on the global config-complete check).
 * Per-tab validity gates Next so the user can't skip required fields.
 *
 * During a run the whole nav collapses to a single Stop pill.
 */
@Composable
internal fun BottomBar(
    running: Boolean,
    currentTabIdx: Int,
    tabCount: Int,
    currentTabValid: Boolean,
    canStart: Boolean,
    hint: String?,
    hintIsAccent: Boolean = false,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isLast = currentTabIdx >= tabCount - 1
    val isFirst = currentTabIdx == 0
    Surface(tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hintIsAccent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (hintIsAccent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (running) {
                    Spacer(Modifier.weight(1f))
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
                    Spacer(Modifier.weight(1f))
                } else {
                    OutlinedButton(
                        onClick = onPrev,
                        enabled = !isFirst,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(56.dp).weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.iv2_wizard_prev))
                    }
                    if (isLast) {
                        Button(
                            onClick = onStart,
                            enabled = canStart,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(56.dp).weight(1.4f),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_start), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onNext,
                            enabled = currentTabValid,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(56.dp).weight(1f),
                        ) {
                            Text(stringResource(R.string.iv2_wizard_next), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Save-preset dialog used by both Iv2 and Astro 2 wizards. Asks for a name;
 * empty names are rejected. When [isUpdate], the title reflects "update
 * existing" instead of "save new" so the user knows what's about to happen.
 */
@Composable
internal fun SavePresetDialog(
    initialName: String,
    isUpdate: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(
                if (isUpdate) R.string.preset_save_update_title
                else R.string.preset_save_new_title,
            ))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.preset_save_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

internal fun iv2FormatHmsPretty(ms: Long): String {
    val totalSec = (ms + 500) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
           else String.format(Locale.US, "%02d:%02d", m, s)
}

internal fun iv2FormatEndClock(durationFromNowMs: Long): String {
    val end = Calendar.getInstance().apply {
        timeInMillis = System.currentTimeMillis() + durationFromNowMs
    }
    return String.format(Locale.US, "%02d:%02d",
        end.get(Calendar.HOUR_OF_DAY), end.get(Calendar.MINUTE))
}
