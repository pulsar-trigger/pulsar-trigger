/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.camera.PhoneCameraManager
import com.ehrocha.pulsar.ui.components.IntStepperField
import com.ehrocha.pulsar.ui.components.TimePicker
import com.ehrocha.pulsar.ui.theme.ExposureGreen
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.WaitingYellow
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun CameraIntervalometerScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    onStartFlow: () -> Unit,
) {
    val cameraManager = vm.phoneCameraManager
    val status = LocalDeviceStatus.current
    val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

    val lenses by cameraManager.lenses.collectAsState()
    val selectedLens by cameraManager.selectedLens.collectAsState()
    val isCapturing by cameraManager.isCapturing.collectAsState()
    val photoCount by cameraManager.photoCount.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Initialize camera with preview
    LaunchedEffect(Unit) {
        cameraManager.initialize(lifecycleOwner, previewView)
    }

    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = isRunning) { showExitDialog = true }

    // Intervalometer params
    val intervalMs by vm.intervalMs.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val shotCount by vm.shotCount.collectAsState()
    val delayMs by vm.delayMs.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // ── Top: Camera preview with overlays ────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Live preview
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            // Capture flash effect
            val flashAlpha by animateFloatAsState(
                targetValue = if (isCapturing) 0.4f else 0f,
                animationSpec = tween(if (isCapturing) 50 else 200),
                label = "captureFlash",
            )
            if (flashAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = flashAlpha)),
                )
            }

            // Back button
            IconButton(
                onClick = { if (isRunning) showExitDialog = true else onBack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }

            // Photo counter badge
            if (photoCount > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    Text(
                        "$photoCount",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            // Running status overlay on preview
            if (isRunning) {
                RunningOverlay(
                    status = status!!,
                    totalShots = shotCount,
                    exposureMs = exposureMs,
                    gapMs = intervalMs,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }

            // Lens picker + camera info at bottom of preview
            if (lenses.size > 1) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        lenses.forEachIndexed { index, lens ->
                            val isSelected = index == selectedLens
                            Surface(
                                onClick = {
                                    cameraManager.selectLens(index, lifecycleOwner, previewView)
                                },
                                color = if (isSelected) Color.White else Color.Black.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(
                                    lens.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color.Black else Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Camera info overlay (selected lens metadata)
            val currentLens = lenses.getOrNull(selectedLens)
            if (currentLens != null && (currentLens.aperture > 0 || currentLens.megapixels > 0)) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (currentLens.aperture > 0) {
                            Text(
                                "f/%.1f".format(currentLens.aperture),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                        if (currentLens.focalLength > 0) {
                            Text(
                                "%.1fmm".format(currentLens.focalLength),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        if (currentLens.megapixels > 0) {
                            Text(
                                "%.0f MP".format(currentLens.megapixels),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // ── Bottom: Controls or running progress ─────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (isRunning) {
                RunningControls(
                    status = status!!,
                    totalShots = shotCount,
                    exposureMs = exposureMs,
                    gapMs = intervalMs,
                    onStop = {
                        vm.stop()
                    },
                )
            } else {
                SetupControls(
                    vm = vm,
                    intervalMs = intervalMs,
                    exposureMs = exposureMs,
                    shotCount = shotCount,
                    delayMs = delayMs,
                    onStart = onStartFlow,
                )
            }
        }
    }

    // ── Exit confirmation dialog while running ──────────────────────
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
private fun SetupControls(
    vm: PulsarViewModel,
    intervalMs: Long,
    exposureMs: Long,
    shotCount: Int,
    delayMs: Long,
    onStart: () -> Unit,
) {
    val totalMs = delayMs + shotCount.toLong() * (exposureMs + intervalMs) - intervalMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Compact sequence summary
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$shotCount",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.label_shots),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatDuration(totalMs),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.label_total_duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Parameter controls
        TimePicker(
            totalMs = exposureMs,
            onChanged = { vm.setExposureMs(it) },
            label = stringResource(R.string.label_exposure) + " (hh:mm:ss)",
        )

        TimePicker(
            totalMs = intervalMs,
            onChanged = { vm.setIntervalMs(it) },
            label = stringResource(R.string.label_interval) + " (hh:mm:ss)",
        )

        TimePicker(
            totalMs = delayMs,
            onChanged = { vm.setDelayMs(it) },
            label = stringResource(R.string.label_start_delay) + " (hh:mm:ss)",
        )

        IntStepperField(
            label = stringResource(R.string.label_number_of_shots),
            value = shotCount,
            onValueChange = { vm.setShotCount(it.coerceAtLeast(AppConfig.MIN_SHOT_COUNT)) },
            min = AppConfig.MIN_SHOT_COUNT,
            max = 999,
        )

        // Start button
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                stringResource(R.string.btn_start),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RunningControls(
    status: com.ehrocha.pulsar.ble.StatusFrame,
    totalShots: Int,
    exposureMs: Long,
    gapMs: Long,
    onStop: () -> Unit,
) {
    // ── Local countdown ──
    var lastUpdateTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastRemainingMs by remember { mutableStateOf(status.timeRemainingMs) }
    var liveRemainingMs by remember { mutableStateOf(status.timeRemainingMs) }
    var phaseStartTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var lastState by remember { mutableStateOf(status.state) }

    LaunchedEffect(status.timeRemainingMs, status.shotsTaken) {
        lastUpdateTime = System.currentTimeMillis()
        lastRemainingMs = status.timeRemainingMs
        liveRemainingMs = status.timeRemainingMs
    }

    LaunchedEffect(status.state) {
        if (status.state != lastState) {
            phaseStartTime = System.currentTimeMillis()
            lastState = status.state
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(100L)
            val elapsed = System.currentTimeMillis() - lastUpdateTime
            liveRemainingMs = (lastRemainingMs - elapsed).coerceAtLeast(0)
        }
    }

    val phaseDurationMs = when (status.state) {
        DeviceState.RUNNING -> exposureMs
        DeviceState.WAITING -> gapMs
        else -> 0L
    }
    val phaseElapsed = System.currentTimeMillis() - phaseStartTime
    val phaseRemainingMs = (phaseDurationMs - phaseElapsed).coerceAtLeast(0)

    val displayShots = when (status.state) {
        DeviceState.RUNNING, DeviceState.WAITING -> status.shotsTaken + 1
        else -> status.shotsTaken
    }

    val exposureProgress = when (status.state) {
        DeviceState.RUNNING -> if (exposureMs > 0) (phaseElapsed.toFloat() / exposureMs).coerceIn(0f, 1f) else 0f
        else -> 0f
    }
    val waitProgress = when (status.state) {
        DeviceState.WAITING -> if (gapMs > 0) (phaseElapsed.toFloat() / gapMs).coerceIn(0f, 1f) else 0f
        else -> 0f
    }
    val smoothExposureProgress by animateFloatAsState(
        targetValue = exposureProgress,
        animationSpec = tween(300),
        label = "expProg",
    )
    val smoothWaitProgress by animateFloatAsState(
        targetValue = waitProgress,
        animationSpec = tween(300),
        label = "waitProg",
    )

    val stateColor by animateColorAsState(
        targetValue = when (status.state) {
            DeviceState.RUNNING -> ExposureGreen
            DeviceState.WAITING -> WaitingYellow
            else -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // State + phase countdown
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
                if (phaseDurationMs > 0 && phaseRemainingMs > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatTimeRemaining(phaseRemainingMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = stateColor,
                    )
                }
            }
        }

        // Shot counter
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$displayShots",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.status_of_shots, totalShots),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Progress bars
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.state_exposing),
                    style = MaterialTheme.typography.labelSmall,
                    color = ExposureGreen,
                    modifier = Modifier.width(64.dp),
                )
                LinearProgressIndicator(
                    progress = { smoothExposureProgress },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = ExposureGreen,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.state_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = WaitingYellow,
                    modifier = Modifier.width(64.dp),
                )
                LinearProgressIndicator(
                    progress = { smoothWaitProgress },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = WaitingYellow,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }

        // Time remaining + stop button
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (liveRemainingMs > 0) {
                Text(
                    formatTimeRemaining(liveRemainingMs),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                val finishTime = remember(liveRemainingMs) {
                    DateFormat.getTimeInstance(DateFormat.SHORT)
                        .format(Date(System.currentTimeMillis() + liveRemainingMs))
                }
                Text(
                    stringResource(R.string.finishes_at, finishTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.btn_stop),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun RunningOverlay(
    status: com.ehrocha.pulsar.ble.StatusFrame,
    totalShots: Int,
    exposureMs: Long,
    gapMs: Long,
    modifier: Modifier = Modifier,
) {
    val displayShots = when (status.state) {
        DeviceState.RUNNING, DeviceState.WAITING -> status.shotsTaken + 1
        else -> status.shotsTaken
    }

    val stateColor = when (status.state) {
        DeviceState.RUNNING -> ExposureGreen
        DeviceState.WAITING -> WaitingYellow
        else -> Color.White
    }

    // Semi-transparent shot counter on the preview
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                "$displayShots / $totalShots",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = when (status.state) {
                    DeviceState.RUNNING -> stringResource(R.string.state_exposing)
                    DeviceState.WAITING -> stringResource(R.string.state_waiting)
                    else -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = stateColor,
            )
        }
    }
}

private fun formatTimeRemaining(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
