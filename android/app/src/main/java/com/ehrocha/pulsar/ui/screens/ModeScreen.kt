/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.delay
import com.ehrocha.pulsar.ui.components.BatteryIndicator
import com.ehrocha.pulsar.ui.components.NightModeToggle
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ble.StatusFrame
import androidx.compose.ui.tooling.preview.Preview
import com.ehrocha.pulsar.ui.theme.DarkColorScheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun ModeScreen(
    vm: PulsarViewModel,
    targetMode: TriggerMode,
    onBack: () -> Unit,
) {
    val status = LocalDeviceStatus.current
    val deviceName by vm.deviceName.collectAsState()
    val mode by vm.currentMode.collectAsState()
    val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

    var uiLocked by remember { mutableStateOf(false) }

    // Auto-unlock when sequence finishes
    LaunchedEffect(isRunning) { if (!isRunning) uiLocked = false }

    var showExitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.selectMode(targetMode) }

    // Show confirmation dialog instead of silently swallowing back press
    BackHandler(enabled = isRunning) { showExitDialog = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !isRunning) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            val title = when (targetMode) {
                TriggerMode.INTERVALOMETER -> stringResource(R.string.mode_intervalometer)
                TriggerMode.ASTRO -> stringResource(R.string.mode_astro)
                TriggerMode.PRESS_HOLD -> stringResource(R.string.mode_manual)
                else -> targetMode.name.replace('_', ' ')
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Spacer(Modifier.weight(1f))

            // Lock toggle — only visible when a sequence is running
            if (isRunning && targetMode != TriggerMode.PRESS_HOLD) {
                IconButton(onClick = { uiLocked = !uiLocked }) {
                    Icon(
                        if (uiLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = stringResource(
                            if (uiLocked) R.string.btn_unlock_ui else R.string.btn_lock_ui
                        ),
                        tint = if (uiLocked) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            if (isRunning && targetMode != TriggerMode.PRESS_HOLD) {
                val totalShots = when (targetMode) {
                    TriggerMode.INTERVALOMETER -> vm.shotCount.collectAsState().value
                    TriggerMode.ASTRO -> vm.astroShotCount.collectAsState().value
                    else -> 0
                }
                val exposureMs: Long
                val gapMs: Long
                when (targetMode) {
                    TriggerMode.INTERVALOMETER -> {
                        gapMs = vm.intervalMs.collectAsState().value
                        exposureMs = vm.exposureMs.collectAsState().value
                    }
                    TriggerMode.ASTRO -> {
                        gapMs = vm.astroGapMs.collectAsState().value
                        val fl = vm.astroFocalLength.collectAsState().value
                        val cf = vm.astroCropFactor.collectAsState().value
                        val rd = vm.astroRuleDivisor.collectAsState().value
                        exposureMs = (rd.toDouble() / (fl * cf) * 1000).toLong().coerceAtLeast(AppConfig.MIN_ASTRO_EXPOSURE_MS)
                    }
                    else -> {
                        exposureMs = 1L
                        gapMs = 0L
                    }
                }
                RunningStatusContent(
                    totalShots = totalShots,
                    exposureMs = exposureMs,
                    gapMs = gapMs,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    when (targetMode) {
                        TriggerMode.INTERVALOMETER -> IntervalometerPanel(vm, enabled = !isRunning)
                        TriggerMode.ASTRO -> AstroPanel(vm, enabled = !isRunning)
                        TriggerMode.PRESS_HOLD -> ManualPanel(vm)
                        else -> {}
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        LaunchedEffect(isRunning, status?.shotsTaken) {
            if (isRunning) vm.updateNotification()
        }

        if (uiLocked) {
            SwipeToUnlockBar(onUnlocked = { uiLocked = false })
        } else {
            when (targetMode) {
                TriggerMode.PRESS_HOLD -> ManualActions(vm, mode)
                TriggerMode.ASTRO -> AstroActions(vm, isRunning)
                else -> DefaultActions(vm, isRunning)
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ── Exit confirmation dialog while sequence is running ───────────
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.dialog_exit_running_title)) },
            text = { Text(stringResource(R.string.dialog_exit_running_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    vm.stop()
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_stop_and_exit), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(stringResource(R.string.btn_keep_running))
                }
            },
        )
    }
}

@Composable
fun SettingsScreen(
    vm: PulsarViewModel,
    initialSection: SettingsSection? = null,
    onBack: () -> Unit,
) {
    val deviceName by vm.deviceName.collectAsState()
    val otaState by vm.firmwareManager.state.collectAsState()
    val otaProgress by vm.firmwareManager.progress.collectAsState()
    val otaError by vm.firmwareManager.errorMessage.collectAsState()

    var currentSection by remember { mutableStateOf(initialSection) }

    val otaActive = otaState in listOf(
        OtaState.DOWNLOADING, OtaState.UPLOADING, OtaState.VALIDATING, OtaState.COMPLETE,
    )

    // Block back navigation while OTA is in progress
    BackHandler(enabled = otaActive && otaState != OtaState.COMPLETE) { /* swallow */ }

    // Sub-section back navigation
    BackHandler(enabled = currentSection != null && !(otaActive && otaState != OtaState.COMPLETE)) {
        currentSection = null
    }

    // Post OTA notifications on state changes
    LaunchedEffect(otaState, otaProgress) {
        when (otaState) {
            OtaState.DOWNLOADING -> vm.updateOtaNotification(
                "Downloading firmware…",
                "${(otaProgress * 100).toInt()}%",
                (otaProgress * 100).toInt(),
            )
            OtaState.UPLOADING -> vm.updateOtaNotification(
                "Uploading to device…",
                "${(otaProgress * 100).toInt()}%",
                (otaProgress * 100).toInt(),
            )
            OtaState.VALIDATING -> vm.updateOtaNotification(
                "Validating firmware…",
                "Device is verifying and rebooting",
            )
            OtaState.COMPLETE -> vm.updateOtaNotification(
                "Firmware updated",
                "Update complete",
                done = true,
            )
            OtaState.ERROR -> vm.updateOtaNotification(
                "Firmware update failed",
                otaError ?: "Unknown error",
                done = true,
            )
            else -> {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (currentSection != null) currentSection = null else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (currentSection != null) stringResource(currentSection!!.titleRes)
                else stringResource(R.string.menu_settings),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (currentSection) {
                    null -> SettingsMenu(onSectionSelected = { currentSection = it })
                    SettingsSection.LANGUAGE -> LanguageSectionContent()
                    SettingsSection.DEVICE -> DeviceSectionContent(vm, deviceName)
                    SettingsSection.GPIO_PINS -> GpioPinsSectionContent(vm)
                    SettingsSection.PLANNER -> PlannerSettingsSectionContent(vm)
                    SettingsSection.BACKUP_RESTORE -> BackupRestoreSectionContent(vm)
                    SettingsSection.UPDATES -> UpdatesSectionContent(vm)
                    SettingsSection.DEVICE_INFO -> DeviceInfoSectionContent(vm)
                    SettingsSection.ABOUT -> AboutSectionContent()
                }
            }
        }
    }

        // ── Firmware OTA overlay ─────────────────────────────────────────
        if (otaActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(24.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        when (otaState) {
                            OtaState.DOWNLOADING -> {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.ota_downloading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(
                                    progress = { otaProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${(otaProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.ota_do_not_close),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OtaState.UPLOADING -> {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.ota_uploading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                LinearProgressIndicator(
                                    progress = { otaProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text("${(otaProgress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.ota_do_not_disconnect),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OtaState.VALIDATING -> {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.ota_validating), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.ota_validating_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                            OtaState.COMPLETE -> {
                                Text(stringResource(R.string.ota_complete_check), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.ota_update_complete), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.ota_rebooting_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                OutlinedButton(
                                    onClick = { vm.firmwareManager.reset() },
                                    shape = RoundedCornerShape(12.dp),
                                ) { Text(stringResource(R.string.done)) }
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeSettingsScreen(
    vm: PulsarViewModel,
    targetMode: TriggerMode,
    onBack: () -> Unit,
) {
    val title = when (targetMode) {
        TriggerMode.INTERVALOMETER -> stringResource(R.string.settings_intervalometer)
        TriggerMode.ASTRO -> stringResource(R.string.settings_astro)
        else -> "${targetMode.name.replace('_', ' ')} Settings"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (targetMode) {
                    TriggerMode.INTERVALOMETER -> IntervalometerSettingsPanel(vm)
                    TriggerMode.ASTRO -> AstroSettingsPanel(vm)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun SwipeToUnlockBar(onUnlocked: () -> Unit) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val unlockThreshold = 200f

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffset > unlockThreshold) onUnlocked()
                        dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                    onHorizontalDrag = { _, delta ->
                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    },
                )
            },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.swipe_to_unlock),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RunningStatusContent(
    totalShots: Int,
    exposureMs: Long,
    gapMs: Long,
) {
    val status = LocalDeviceStatus.current ?: return
    val cycleMs = exposureMs + gapMs

    // ── Local countdown: start from firmware's timeRemainingMs and tick down ──
    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastRemainingMs by remember { mutableLongStateOf(status.timeRemainingMs) }
    var liveRemainingMs by remember { mutableLongStateOf(status.timeRemainingMs) }

    // Track phase start so we can compute per-phase countdown
    var phaseStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastState by remember { mutableStateOf(status.state) }

    // When firmware sends a new status, reset the baseline
    LaunchedEffect(status.timeRemainingMs, status.shotsTaken) {
        lastUpdateTime = System.currentTimeMillis()
        lastRemainingMs = status.timeRemainingMs
        liveRemainingMs = status.timeRemainingMs
    }

    // Detect phase transitions (RUNNING ↔ WAITING)
    LaunchedEffect(status.state) {
        if (status.state != lastState) {
            phaseStartTime = System.currentTimeMillis()
            lastState = status.state
        }
    }

    // Tick every 100 ms to update the countdown locally
    LaunchedEffect(lastUpdateTime) {
        while (true) {
            delay(100L)
            val elapsed = System.currentTimeMillis() - lastUpdateTime
            liveRemainingMs = (lastRemainingMs - elapsed).coerceAtLeast(0)
        }
    }

    // ── Per-phase countdown ──
    val phaseDurationMs = when (status.state) {
        DeviceState.RUNNING -> exposureMs
        DeviceState.WAITING -> gapMs
        else -> 0L
    }
    val phaseElapsed = System.currentTimeMillis() - phaseStartTime
    val phaseRemainingMs = (phaseDurationMs - phaseElapsed).coerceAtLeast(0)

    // ── Shot display number: +1 during exposure so user sees "working on shot N" ──
    val displayShots = when (status.state) {
        DeviceState.RUNNING -> status.shotsTaken + 1
        else -> status.shotsTaken
    }

    // ── Smooth continuous progress ──
    // Base progress from completed shots + fractional progress within current cycle
    val cycleElapsedMs = (System.currentTimeMillis() - lastUpdateTime).let { elapsed ->
        (lastRemainingMs - (lastRemainingMs - elapsed).coerceAtLeast(0))
    }
    val fractionalCycle = if (cycleMs > 0) cycleElapsedMs.toFloat() / cycleMs else 0f
    val rawProgress = if (totalShots > 0) {
        (status.shotsTaken.toFloat() + fractionalCycle.coerceIn(0f, 1f)) / totalShots
    } else 0f
    val smoothProgress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300),
        label = "progress",
    )

    val stateColor by animateColorAsState(
        targetValue = when (status.state) {
            DeviceState.RUNNING -> Color(0xFF00E676)
            DeviceState.WAITING -> Color(0xFFFFD600)
            DeviceState.ERROR -> Color(0xFFFF1744)
            else -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // State badge
        Surface(
            color = stateColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(stateColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (status.state) {
                        DeviceState.RUNNING -> stringResource(R.string.state_exposing)
                        DeviceState.WAITING -> stringResource(R.string.state_waiting)
                        else -> status.state.name
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                )
                // Phase countdown next to state badge
                if (phaseDurationMs > 0 && phaseRemainingMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTimeRemaining(phaseRemainingMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = stateColor,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        // Shot counter — starts at 1 during exposure
        Text(
            text = "$displayShots",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.status_of_shots, totalShots),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        // Progress bar
        LinearProgressIndicator(
            progress = { smoothProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )

        Spacer(Modifier.height(24.dp))

        // Total time remaining
        if (liveRemainingMs > 0) {
            Text(
                text = formatTimeRemaining(liveRemainingMs),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.status_remaining),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimeRemaining(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

// ── Running Status Preview ──────────────────────────────────────────────────

@Preview(showBackground = true, widthDp = 380, heightDp = 600, name = "Running Status")
@Composable
private fun RunningStatusPreview() {
    val mockStatus = StatusFrame(
        state = DeviceState.RUNNING,
        mode = 0x01,
        shotsTaken = 3,
        timeRemainingMs = 125_000L,
        batteryPct = 78,
        errorCode = 0,
    )
    MaterialTheme(colorScheme = DarkColorScheme) {
        CompositionLocalProvider(LocalDeviceStatus provides mockStatus) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                RunningStatusContent(
                    totalShots = 50,
                    exposureMs = 2_000L,
                    gapMs = 3_000L,
                )
            }
        }
    }
}
