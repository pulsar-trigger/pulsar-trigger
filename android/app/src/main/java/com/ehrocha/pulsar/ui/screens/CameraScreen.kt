/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.Manifest
import android.content.Intent
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.camera.DeviceOrientation
import com.ehrocha.pulsar.camera.LensCapabilities
import com.ehrocha.pulsar.camera.PhoneCameraManager
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.ui.theme.ExposureGreen
import com.ehrocha.pulsar.ui.theme.StatusRed
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.WaitingYellow
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    if (!cameraPermission.status.isGranted) {
        CameraPermissionRequest(
            onBack = onBack,
            onGrant = { cameraPermission.launchPermissionRequest() },
        )
        return
    }

    CameraContent(vm = vm, onBack = onBack)
}

@Composable
private fun CameraPermissionRequest(onBack: () -> Unit, onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.camera_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.camera_permission_needed),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.camera_grant_permission))
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraContent(vm: PulsarViewModel, onBack: () -> Unit) {
    val cameraManager = vm.phoneCameraManager
    val status = LocalDeviceStatus.current
    val isRunning = status?.state == DeviceState.RUNNING || status?.state == DeviceState.WAITING

    val lenses by cameraManager.lenses.collectAsState()
    val selectedLens by cameraManager.selectedLens.collectAsState()
    val isCapturing by cameraManager.isCapturing.collectAsState()
    val photoCount by cameraManager.photoCount.collectAsState()
    val cameraDebugLog by cameraManager.cameraDebugLog.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? android.app.Activity

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Grid overlay — celestial sky overlay was dropped: phone compass is too
    // unreliable across devices to align a sky grid usefully.
    var gridMode by remember { mutableStateOf(GridMode.OFF) }

    // Keep screen awake
    var keepAwake by remember { mutableStateOf(false) }
    DisposableEffect(keepAwake) {
        if (keepAwake) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Initialize camera with preview; release on exit
    LaunchedEffect(Unit) {
        cameraManager.initialize(lifecycleOwner, previewView)
    }
    DisposableEffect(Unit) {
        onDispose { cameraManager.release() }
    }

    // Intervalometer params
    val intervalMs by vm.intervalMs.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val shotCount by vm.shotCount.collectAsState()

    // Camera overlay panel state (only one open at a time)
    var activePanel by remember { mutableStateOf<CameraPanel?>(null) }

    // Selected capture mode — Start fires whichever is selected
    var selectedCaptureMode by remember { mutableStateOf(CaptureMode.MANUAL) }
    var selectedTimelapseStyle by remember {
        mutableStateOf(PulsarViewModel.TimelapseStyle.DEFAULT)
    }
    var selectedAutoAstroStyle by remember {
        mutableStateOf(PulsarViewModel.AutoAstroStyle.NPF)
    }
    var autoAstroForeground by remember { mutableStateOf(false) }

    // Current lens capabilities
    val currentLens = lenses.getOrNull(selectedLens)
    val caps = currentLens?.capabilities ?: LensCapabilities()

    // Dialogs
    var showExitDialog by remember { mutableStateOf(false) }
    var showCameraDebug by remember { mutableStateOf(false) }
    BackHandler(enabled = isRunning) { showExitDialog = true }

    // One-time "values are bounded by what the camera advertises" dialog.
    val prefsForLimits = remember { context.getSharedPreferences("camera_intro", android.content.Context.MODE_PRIVATE) }
    var showLimitsDialog by remember {
        mutableStateOf(!prefsForLimits.getBoolean("limits_dismissed", false))
    }
    var limitsDontShowAgain by remember { mutableStateOf(false) }
    if (showLimitsDialog) {
        AlertDialog(
            onDismissRequest = { /* require explicit OK */ },
            title = { Text(stringResource(R.string.camera_limits_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.camera_limits_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = limitsDontShowAgain,
                            onCheckedChange = { limitsDontShowAgain = it },
                        )
                        Text(
                            stringResource(R.string.camera_limits_dont_show_again),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (limitsDontShowAgain) {
                        prefsForLimits.edit().putBoolean("limits_dismissed", true).apply()
                    }
                    showLimitsDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        // ── Camera preview (takes all remaining space) ──────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val zoomRatio by cameraManager.zoomRatio.collectAsState()

            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            val newZoom = cameraManager.zoomRatio.value * zoom
                            cameraManager.setZoomRatio(newZoom)
                        }
                    }
                    .pointerInput(Unit) {
                        // Tap on the preview (no chip target) — dismiss any open panel.
                        detectTapGestures(onTap = { activePanel = null })
                    },
            )

            // Zoom indicator (shown when zoomed in)
            if (zoomRatio > 1.05f) {
                // Optical zoom limit: CameraX total max / Camera2 digital max
                val maxDigital = caps.maxDigitalZoom.coerceAtLeast(1f)
                val totalMax = cameraManager.getMaxZoomRatio()
                val opticalMax = if (maxDigital > 1f) (totalMax / maxDigital).coerceAtLeast(1f) else 1f
                val isDigital = zoomRatio > opticalMax * 1.05f
                val zoomColor = if (isDigital) Color(0xFFFF9800) else Color.White

                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "%.1fx".format(zoomRatio),
                            style = MaterialTheme.typography.labelMedium,
                            color = zoomColor,
                        )
                        if (isDigital) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "digital",
                                style = MaterialTheme.typography.labelSmall,
                                color = zoomColor.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }

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

            // Grid overlay
            if (gridMode != GridMode.OFF) {
                GridOverlay(
                    gridMode = gridMode,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Back button
            IconButton(
                onClick = { if (isRunning) showExitDialog = true else onBack() },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp),
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
                        .statusBarsPadding()
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

            // Running overlay on preview
            if (isRunning) {
                RunningOverlay(
                    status = status,
                    totalShots = shotCount,
                    exposureMs = exposureMs,
                    gapMs = intervalMs,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }

            // ── Camera settings strip (bottom-left, hidden while shooting) ──
            if (!isRunning) {
                val saveAsRaw by cameraManager.saveAsRaw.collectAsState()
                val oisEnabled by cameraManager.oisEnabled.collectAsState()

                CameraSettingsStrip(
                    cameraManager = cameraManager,
                    lenses = lenses,
                    selectedLens = selectedLens,
                    caps = caps,
                    activePanel = activePanel,
                    onPanelToggle = { panel ->
                        activePanel = if (activePanel == panel) null else panel
                    },
                    onLensSelected = { index ->
                        cameraManager.selectLens(index, lifecycleOwner, previewView)
                    },
                    onShowDebug = { showCameraDebug = true },
                    gridMode = gridMode,
                    onGridCycle = {
                        gridMode = GridMode.entries[(gridMode.ordinal + 1) % GridMode.entries.size]
                    },
                    keepAwake = keepAwake,
                    onKeepAwakeToggle = { keepAwake = it },
                    supportsRaw = caps.supportsRaw,
                    saveAsRaw = saveAsRaw,
                    onRawToggle = { cameraManager.setSaveAsRaw(it) },
                    supportsOis = caps.supportsOis,
                    oisEnabled = oisEnabled,
                    onOisToggle = { cameraManager.setOisEnabled(it) },
                    onOpenGallery = {
                        val savedUri = cameraManager.lastSavedUri.value
                        val intent = if (savedUri != null) {
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(savedUri, "image/*")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        } else {
                            Intent(Intent.ACTION_VIEW).apply { type = "image/*" }
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.toast_no_gallery_app, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 8.dp, start = 8.dp),
                )

                // ── Intervalometer strip (bottom-right, hidden while shooting) ──
                IntervalometerStrip(
                    vm = vm,
                    currentLens = lenses.getOrNull(selectedLens),
                    activePanel = activePanel,
                    selectedMode = selectedCaptureMode,
                    onModeSelected = { mode ->
                        selectedCaptureMode = mode
                        // Selecting a non-Manual mode opens its info panel and closes
                        // any other panel (left side or intervalometer params).
                        // Selecting Manual clears panels entirely.
                        activePanel = if (mode == CaptureMode.MANUAL) null else CameraPanel.MODE_INFO
                    },
                    onPanelToggle = { panel ->
                        activePanel = if (activePanel == panel) null else panel
                    },
                    timelapseStyle = selectedTimelapseStyle,
                    onTimelapseStyleChange = { selectedTimelapseStyle = it },
                    autoAstroStyle = selectedAutoAstroStyle,
                    onAutoAstroStyleChange = { selectedAutoAstroStyle = it },
                    autoAstroForeground = autoAstroForeground,
                    onAutoAstroForegroundChange = { autoAstroForeground = it },
                    onStart = {
                        activePanel = null
                        when (selectedCaptureMode) {
                            CaptureMode.MANUAL -> {
                                vm.selectMode(TriggerMode.INTERVALOMETER)
                                vm.loadQuickMode(FlowStepType.INTERVALOMETER)
                                vm.startFlow()
                            }
                            CaptureMode.AUTO_ASTRO -> {
                                keepAwake = true
                                vm.startAutoAstro(
                                    style = selectedAutoAstroStyle,
                                    includeForeground = autoAstroForeground,
                                )
                            }
                            CaptureMode.STORM -> {
                                keepAwake = true
                                vm.startStormCapture()
                            }
                            CaptureMode.TRAILS -> {
                                keepAwake = true
                                vm.startStarTrails()
                            }
                            CaptureMode.FIREWORKS -> {
                                keepAwake = true
                                vm.startFireworks()
                            }
                            CaptureMode.TIMELAPSE -> {
                                keepAwake = true
                                vm.startTimelapse(selectedTimelapseStyle)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp, end = 8.dp),
                )
            }
        }

        // ── Bottom bar (only when running) ─────────────────────────
        if (isRunning) {
            val flowSteps by vm.flowSteps.collectAsState()
            val flowCurrentStep by vm.flowCurrentStep.collectAsState()
            val activeStep = flowSteps.getOrNull(flowCurrentStep)
            val activeShotCount = activeStep?.shotCount ?: shotCount
            val activeExpMs = activeStep?.exposureMs ?: vm.exposureMs.collectAsState().value
            val activeGapMs = activeStep?.intervalMs ?: vm.intervalMs.collectAsState().value
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RunningStatusPanel(
                    status = status,
                    totalShots = activeShotCount,
                    exposureMs = activeExpMs,
                    gapMs = activeGapMs,
                    onStop = { vm.stop() },
                )
            }
        }
    }

    // Exit confirmation dialog
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

    // Camera debug info dialog
    if (showCameraDebug) {
        AlertDialog(
            onDismissRequest = { showCameraDebug = false },
            title = { Text("Camera Info") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    cameraDebugLog.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCameraDebug = false }) {
                    Text("OK")
                }
            },
        )
    }
}

/**
 * Bottom bar shown while a sequence is running. Live phase countdown, per-phase
 * progress bars, total time remaining, and a finishes-at timestamp — same idea as
 * the ESP32 mode panels' RunningStatusContent but laid out compactly so the camera
 * preview stays visible above it.
 */
@Composable
private fun RunningStatusPanel(
    status: com.ehrocha.pulsar.ble.StatusFrame,
    totalShots: Int,
    exposureMs: Long,
    gapMs: Long,
    onStop: () -> Unit,
) {
    // ── Live tick / phase timing (ported from ModeScreen.RunningStatusContent) ──
    var lastUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastRemainingMs by remember { mutableLongStateOf(status.timeRemainingMs) }
    var liveRemainingMs by remember { mutableLongStateOf(status.timeRemainingMs) }
    var phaseStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
        DeviceState.RUNNING ->
            if (exposureMs > 0) (phaseElapsed.toFloat() / exposureMs).coerceIn(0f, 1f) else 0f
        else -> 0f
    }
    val waitProgress = when (status.state) {
        DeviceState.WAITING ->
            if (gapMs > 0) (phaseElapsed.toFloat() / gapMs).coerceIn(0f, 1f) else 0f
        else -> 0f
    }
    val smoothExp by animateFloatAsState(
        targetValue = exposureProgress,
        animationSpec = tween(durationMillis = 300),
        label = "expProgress",
    )
    val smoothWait by animateFloatAsState(
        targetValue = waitProgress,
        animationSpec = tween(durationMillis = 300),
        label = "waitProgress",
    )

    val stateColor by animateColorAsState(
        targetValue = when (status.state) {
            DeviceState.RUNNING -> ExposureGreen
            DeviceState.WAITING -> WaitingYellow
            DeviceState.ERROR -> StatusRed
            else -> MaterialTheme.colorScheme.primary
        },
        label = "stateColor",
    )

    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: state badge with phase countdown · shot counter · stop
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = stateColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (status.state) {
                            DeviceState.RUNNING -> stringResource(R.string.state_exposing)
                            DeviceState.WAITING -> stringResource(R.string.state_waiting)
                            else -> status.state.name
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = stateColor,
                    )
                    if (phaseDurationMs > 0 && phaseRemainingMs > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            formatTimeShort(phaseRemainingMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = stateColor,
                        )
                    }
                }
            }
            Text(
                "$displayShots / $totalShots",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.btn_stop), fontWeight = FontWeight.Bold)
            }
        }

        // Row 2: per-phase progress bars (only when both phases have non-zero duration)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.state_exposing),
                style = MaterialTheme.typography.labelSmall,
                color = ExposureGreen,
                modifier = Modifier.width(56.dp),
            )
            LinearProgressIndicator(
                progress = { smoothExp },
                modifier = Modifier.weight(1f).height(6.dp),
                color = ExposureGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        if (gapMs > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.state_waiting),
                    style = MaterialTheme.typography.labelSmall,
                    color = WaitingYellow,
                    modifier = Modifier.width(56.dp),
                )
                LinearProgressIndicator(
                    progress = { smoothWait },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = WaitingYellow,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }

        // Row 3: total time remaining + finishes-at
        if (liveRemainingMs > 0) {
            val finishTime = remember(liveRemainingMs / 1000) {
                DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(System.currentTimeMillis() + liveRemainingMs))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    formatTimeShort(liveRemainingMs),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "·",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.finishes_at, finishTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTimeShort(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

// ── Grid overlay ────────────────────────────────────────────────────────

private enum class GridMode(val label: String) {
    OFF("Off"),
    THIRDS("3x3"),
    GRID_4X4("4x4"),
}

@Composable
private fun GridOverlay(
    gridMode: GridMode,
    modifier: Modifier = Modifier,
) {
    val gridColor = Color.White.copy(alpha = 0.55f)

    Canvas(modifier = modifier) {
        when (gridMode) {
            GridMode.THIRDS -> drawGrid(3, 3, gridColor)
            GridMode.GRID_4X4 -> drawGrid(4, 4, gridColor)
            GridMode.OFF -> {}
        }
    }
}

private fun DrawScope.drawGrid(cols: Int, rows: Int, color: Color) {
    val strokeWidth = 2.5f
    // Vertical lines
    for (i in 1 until cols) {
        val x = size.width * i / cols
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth)
    }
    // Horizontal lines
    for (i in 1 until rows) {
        val y = size.height * i / rows
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth)
    }
}


// ── Camera panel enum ───────────────────────────────────────────────────

private enum class CameraPanel { LENS, FOCUS, INTERVALOMETER, MODE_INFO }

// ── Camera settings strip (bottom-left: lens, ISO, focus, grid, awake, RAW) ──

@Composable
private fun CameraSettingsStrip(
    cameraManager: PhoneCameraManager,
    lenses: List<com.ehrocha.pulsar.camera.PhoneLens>,
    selectedLens: Int,
    caps: LensCapabilities,
    activePanel: CameraPanel?,
    onPanelToggle: (CameraPanel) -> Unit,
    onLensSelected: (Int) -> Unit,
    onShowDebug: () -> Unit,
    gridMode: GridMode = GridMode.OFF,
    onGridCycle: () -> Unit = {},
    keepAwake: Boolean = false,
    onKeepAwakeToggle: (Boolean) -> Unit = {},
    supportsRaw: Boolean = false,
    saveAsRaw: Boolean = false,
    onRawToggle: (Boolean) -> Unit = {},
    supportsOis: Boolean = false,
    oisEnabled: Boolean = true,
    onOisToggle: (Boolean) -> Unit = {},
    onOpenGallery: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val manualFocus by cameraManager.manualFocusDist.collectAsState()

    // Only show panel if it belongs to this strip
    val cameraPanel = activePanel?.takeIf { it in CAMERA_PANELS }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon strip
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                if (lenses.size > 1) {
                    ControlIconButton(
                        icon = Icons.Default.Lens,
                        label = lenses.getOrNull(selectedLens)?.label?.removePrefix("Back ") ?: "",
                        active = activePanel == CameraPanel.LENS,
                        onClick = { onPanelToggle(CameraPanel.LENS) },
                        tooltip = stringResource(R.string.tooltip_lens),
                    )
                }
                // ISO moved into Manual mode's params panel — set it alongside
                // exposure / interval / shots there, not as a separate sub-menu.
                if (caps.supportsManualFocus) {
                    ControlIconButton(
                        icon = Icons.Default.CenterFocusStrong,
                        label = if (manualFocus != null) formatFocusDistance(manualFocus!!) else "AF",
                        active = activePanel == CameraPanel.FOCUS,
                        onClick = { onPanelToggle(CameraPanel.FOCUS) },
                        tooltip = stringResource(R.string.tooltip_focus),
                    )
                }

                // Grid overlay
                ControlIconButton(
                    icon = Icons.Default.GridOn,
                    label = gridMode.label,
                    active = gridMode != GridMode.OFF,
                    onClick = onGridCycle,
                    tooltip = stringResource(R.string.tooltip_grid),
                )

                // Keep screen awake
                ControlIconButton(
                    icon = Icons.Default.LightMode,
                    label = if (keepAwake) "On" else "Off",
                    active = keepAwake,
                    onClick = { onKeepAwakeToggle(!keepAwake) },
                    tooltip = stringResource(R.string.tooltip_keep_awake),
                )

                // RAW toggle
                if (supportsRaw) {
                    ControlIconButton(
                        icon = Icons.Default.PhotoLibrary,
                        label = if (saveAsRaw) "RAW" else "JPG",
                        active = saveAsRaw,
                        onClick = { onRawToggle(!saveAsRaw) },
                        tooltip = stringResource(R.string.tooltip_raw),
                    )
                }

                // OIS toggle
                if (supportsOis) {
                    ControlIconButton(
                        icon = Icons.Default.Vibration,
                        label = if (oisEnabled) "OIS" else "Off",
                        active = !oisEnabled,  // highlight when OFF (tripod mode)
                        onClick = { onOisToggle(!oisEnabled) },
                        tooltip = stringResource(R.string.tooltip_ois),
                    )
                }

                // Gallery shortcut — opens last saved photo or system gallery
                ControlIconButton(
                    icon = Icons.Default.Collections,
                    label = "Gallery",
                    active = false,
                    onClick = onOpenGallery,
                    tooltip = stringResource(R.string.tooltip_gallery),
                )
            }
        }

        // Expanded panel (right of icons, since strip is on the left)
        AnimatedVisibility(visible = cameraPanel != null) {
            ExpandedPanel(activePanel = cameraPanel, paddingStart = true) {
                when (cameraPanel) {
                    CameraPanel.LENS -> LensPanel(lenses, selectedLens, onLensSelected, onShowDebug)
                    CameraPanel.FOCUS -> FocusPanel(cameraManager, caps)
                    else -> {}
                }
            }
        }
    }
}

// ── Intervalometer strip (bottom-right: timer + start) ──────────────

@Composable
private fun IntervalometerStrip(
    vm: PulsarViewModel,
    currentLens: com.ehrocha.pulsar.camera.PhoneLens?,
    activePanel: CameraPanel?,
    selectedMode: CaptureMode,
    onModeSelected: (CaptureMode) -> Unit,
    onPanelToggle: (CameraPanel) -> Unit,
    timelapseStyle: PulsarViewModel.TimelapseStyle,
    onTimelapseStyleChange: (PulsarViewModel.TimelapseStyle) -> Unit,
    autoAstroStyle: PulsarViewModel.AutoAstroStyle,
    onAutoAstroStyleChange: (PulsarViewModel.AutoAstroStyle) -> Unit,
    autoAstroForeground: Boolean,
    onAutoAstroForegroundChange: (Boolean) -> Unit,
    onStart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val shotCount by vm.shotCount.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()

    // Only show panel if it belongs to this strip and Manual mode is selected
    val intervalPanel = activePanel?.takeIf { it in INTERVALOMETER_PANELS }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Expanded panel (left of icons) — Manual gets its params; auto modes get
        // a brief explanation of what the mode captures and how to process it.
        AnimatedVisibility(visible = intervalPanel != null && selectedMode == CaptureMode.MANUAL) {
            ExpandedPanel(activePanel = intervalPanel) {
                when (intervalPanel) {
                    CameraPanel.INTERVALOMETER -> IntervalometerPanel(vm, currentLens)
                    else -> {}
                }
            }
        }
        AnimatedVisibility(visible = activePanel == CameraPanel.MODE_INFO && selectedMode != CaptureMode.MANUAL) {
            ModeInfoPanel(
                mode = selectedMode,
                timelapseStyle = timelapseStyle,
                onTimelapseStyleChange = onTimelapseStyleChange,
                autoAstroStyle = autoAstroStyle,
                onAutoAstroStyleChange = onAutoAstroStyleChange,
                autoAstroForeground = autoAstroForeground,
                onAutoAstroForegroundChange = onAutoAstroForegroundChange,
            )
        }

        // Icon strip
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
            ) {
                ControlIconButton(
                    icon = Icons.Default.Timer,
                    label = if (selectedMode == CaptureMode.MANUAL)
                        "$shotCount\u00D7${formatExposureLabel(exposureMs)}"
                    else "Manual",
                    active = selectedMode == CaptureMode.MANUAL,
                    onClick = {
                        if (selectedMode == CaptureMode.MANUAL) {
                            onPanelToggle(CameraPanel.INTERVALOMETER)
                        } else {
                            onModeSelected(CaptureMode.MANUAL)
                        }
                    },
                    tooltip = stringResource(R.string.tooltip_intervalometer),
                )
                Spacer(Modifier.height(4.dp))
                // Astro group ───────
                ControlIconButton(
                    icon = Icons.Default.AutoAwesome,
                    label = "Auto",
                    active = selectedMode == CaptureMode.AUTO_ASTRO,
                    onClick = { onModeSelected(CaptureMode.AUTO_ASTRO) },
                    tooltip = stringResource(R.string.tooltip_auto_astro),
                )
                Spacer(Modifier.height(2.dp))
                ControlIconButton(
                    icon = Icons.Default.Loop,
                    label = "Trails",
                    active = selectedMode == CaptureMode.TRAILS,
                    onClick = { onModeSelected(CaptureMode.TRAILS) },
                    tooltip = stringResource(R.string.tooltip_trails),
                )
                Spacer(Modifier.height(2.dp))
                // Weather/Events group ───────
                ControlIconButton(
                    icon = Icons.Default.Bolt,
                    label = "Storm",
                    active = selectedMode == CaptureMode.STORM,
                    onClick = { onModeSelected(CaptureMode.STORM) },
                    tooltip = stringResource(R.string.tooltip_storm),
                )
                Spacer(Modifier.height(2.dp))
                ControlIconButton(
                    icon = Icons.Default.Celebration,
                    label = "Fireworks",
                    active = selectedMode == CaptureMode.FIREWORKS,
                    onClick = { onModeSelected(CaptureMode.FIREWORKS) },
                    tooltip = stringResource(R.string.tooltip_fireworks),
                )
                Spacer(Modifier.height(2.dp))
                ControlIconButton(
                    icon = Icons.Default.Timelapse,
                    label = "Timelapse",
                    active = selectedMode == CaptureMode.TIMELAPSE,
                    onClick = { onModeSelected(CaptureMode.TIMELAPSE) },
                    tooltip = stringResource(R.string.tooltip_timelapse),
                )
                Spacer(Modifier.height(8.dp))
                StartIconButton(onClick = onStart)
            }
        }
    }
}

// Panel group membership
private val CAMERA_PANELS = setOf(CameraPanel.LENS, CameraPanel.FOCUS)
private val INTERVALOMETER_PANELS = setOf(CameraPanel.INTERVALOMETER)

// Capture mode is shared with the stacking package — same enum drives both the
// camera UI and the post-capture composite filtering.
private typealias CaptureMode = com.ehrocha.pulsar.stacking.CaptureMode

/** Brief explanation panel for a non-Manual capture mode. Shows what the mode
 *  captures (e.g. Auto Astro's bonus foreground frame) and which composite to
 *  run on it afterwards, so the user knows what to expect before tapping Start.
 *  For Timelapse, also shows three sub-style chips and switches the body text. */
@Composable
private fun ModeInfoPanel(
    mode: CaptureMode,
    timelapseStyle: PulsarViewModel.TimelapseStyle,
    onTimelapseStyleChange: (PulsarViewModel.TimelapseStyle) -> Unit,
    autoAstroStyle: PulsarViewModel.AutoAstroStyle,
    onAutoAstroStyleChange: (PulsarViewModel.AutoAstroStyle) -> Unit,
    autoAstroForeground: Boolean,
    onAutoAstroForegroundChange: (Boolean) -> Unit,
) {
    val (titleRes, bodyRes) = when (mode) {
        CaptureMode.AUTO_ASTRO -> R.string.mode_info_auto_title to R.string.mode_info_auto_body
        CaptureMode.STORM -> R.string.mode_info_storm_title to R.string.mode_info_storm_body
        CaptureMode.TRAILS -> R.string.mode_info_trails_title to R.string.mode_info_trails_body
        CaptureMode.FIREWORKS -> R.string.mode_info_fireworks_title to R.string.mode_info_fireworks_body
        CaptureMode.TIMELAPSE -> R.string.mode_info_timelapse_title to when (timelapseStyle) {
            PulsarViewModel.TimelapseStyle.DEFAULT -> R.string.mode_info_timelapse_body
            PulsarViewModel.TimelapseStyle.ACTION_BURST -> R.string.mode_info_timelapse_burst_body
            PulsarViewModel.TimelapseStyle.CLOUDSCAPE -> R.string.mode_info_timelapse_cloud_body
        }
        CaptureMode.MANUAL -> return
    }
    Surface(
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .widthIn(max = 240.dp)
            .padding(end = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            if (mode == CaptureMode.TIMELAPSE) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TimelapseStyleChip(
                        label = stringResource(R.string.timelapse_style_default),
                        selected = timelapseStyle == PulsarViewModel.TimelapseStyle.DEFAULT,
                        onClick = { onTimelapseStyleChange(PulsarViewModel.TimelapseStyle.DEFAULT) },
                        modifier = Modifier.weight(1f),
                    )
                    TimelapseStyleChip(
                        label = stringResource(R.string.timelapse_style_burst),
                        selected = timelapseStyle == PulsarViewModel.TimelapseStyle.ACTION_BURST,
                        onClick = { onTimelapseStyleChange(PulsarViewModel.TimelapseStyle.ACTION_BURST) },
                        modifier = Modifier.weight(1f),
                    )
                    TimelapseStyleChip(
                        label = stringResource(R.string.timelapse_style_cloudscape),
                        selected = timelapseStyle == PulsarViewModel.TimelapseStyle.CLOUDSCAPE,
                        onClick = { onTimelapseStyleChange(PulsarViewModel.TimelapseStyle.CLOUDSCAPE) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (mode == CaptureMode.AUTO_ASTRO) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TimelapseStyleChip(
                        label = stringResource(R.string.auto_astro_rule_npf),
                        selected = autoAstroStyle == PulsarViewModel.AutoAstroStyle.NPF,
                        onClick = { onAutoAstroStyleChange(PulsarViewModel.AutoAstroStyle.NPF) },
                        modifier = Modifier.weight(1f),
                    )
                    TimelapseStyleChip(
                        label = stringResource(R.string.auto_astro_rule_400),
                        selected = autoAstroStyle == PulsarViewModel.AutoAstroStyle.RULE_400,
                        onClick = { onAutoAstroStyleChange(PulsarViewModel.AutoAstroStyle.RULE_400) },
                        modifier = Modifier.weight(1f),
                    )
                    TimelapseStyleChip(
                        label = stringResource(R.string.auto_astro_rule_500),
                        selected = autoAstroStyle == PulsarViewModel.AutoAstroStyle.RULE_500,
                        onClick = { onAutoAstroStyleChange(PulsarViewModel.AutoAstroStyle.RULE_500) },
                        modifier = Modifier.weight(1f),
                    )
                }
                TimelapseStyleChip(
                    label = stringResource(
                        if (autoAstroForeground) R.string.auto_astro_foreground_on
                        else R.string.auto_astro_foreground_off
                    ),
                    selected = autoAstroForeground,
                    onClick = { onAutoAstroForegroundChange(!autoAstroForeground) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/** Two-line chip used by Manual mode's astro rule picker (NPF / 400 / 500).
 *  Top line is the rule name, bottom line shows the resulting exposure. */
@Composable
private fun AstroRuleChip(
    label: String,
    sub: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (!enabled) Color.White.copy(alpha = 0.06f)
        else if (selected) Color.White.copy(alpha = 0.3f)
        else Color.White.copy(alpha = 0.15f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = tint,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Text(
                sub ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TimelapseStyleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = if (selected) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

/** Shared expanded panel container with title. */
@Composable
private fun ExpandedPanel(
    activePanel: CameraPanel?,
    paddingStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .widthIn(max = 260.dp)
            .then(if (paddingStart) Modifier.padding(start = 8.dp) else Modifier.padding(end = 8.dp)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val panelTitle = when (activePanel) {
                CameraPanel.LENS -> stringResource(R.string.label_lens)
                CameraPanel.FOCUS -> stringResource(R.string.label_focus_mode)
                CameraPanel.INTERVALOMETER -> stringResource(R.string.mode_intervalometer)
                CameraPanel.MODE_INFO -> ""
                null -> ""
            }
            if (panelTitle.isNotEmpty()) {
                Text(
                    panelTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlIconButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tooltip: String? = null,
) {
    val content = @Composable {
        Surface(
            onClick = onClick,
            color = if (active) Color.White.copy(alpha = 0.3f) else Color.Transparent,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = tooltip ?: label,
                    tint = if (active) Color.White else Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }

    if (tooltip != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun StartIconButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.btn_start),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.btn_start),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
            )
        }
    }
}

private fun formatExposureLabel(ms: Long): String {
    if (ms <= 0L) return "—"
    if (ms >= 1000L) {
        return if (ms % 1000L == 0L) "${ms / 1000}s" else "%.1fs".format(ms / 1000.0)
    }
    // Sub-second values shown as classic shutter denominators (1/15, 1/250, etc.)
    val denom = (1000.0 / ms).toLong()
    return "1/$denom"
}

/**
 * Anchor shutter speeds — quick-jump chips that the user can tap to skip across
 * the slider. The slider provides full precision between these; chips just speed
 * up navigation between distant values.
 */
private data class Shutter(val ms: Long, val label: String)

private val SHUTTER_ANCHORS = listOf(
    Shutter(30_000L, "30s"),
    Shutter(8_000L, "8s"),
    Shutter(2_000L, "2s"),
    Shutter(1_000L, "1s"),
    Shutter(125L, "1/8"),
    Shutter(33L, "1/30"),
    Shutter(8L, "1/125"),
    Shutter(2L, "1/500"),
    Shutter(1L, "1/1000"),
)

/** Anchor ISO chips. Slider covers the continuous range; chips skip between stops. */
private val ISO_ANCHORS = intArrayOf(100, 400, 1600, 6400, 12800)

// ── Expandable panels ──────────────────────────────────────────────────

@Composable
private fun LensPanel(
    lenses: List<com.ehrocha.pulsar.camera.PhoneLens>,
    selectedLens: Int,
    onLensSelected: (Int) -> Unit,
    onShowDebug: () -> Unit,
) {
    lenses.forEachIndexed { index, lens ->
        val isSelected = index == selectedLens
        Surface(
            onClick = { onLensSelected(index) },
            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lens.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.Black else Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (lens.aperture > 0) {
                    Text(
                        "f/%.1f".format(lens.aperture),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
                    )
                }
                if (lens.megapixels > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "%.0fMP".format(lens.megapixels),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
    // Debug info link
    Surface(
        onClick = onShowDebug,
        color = Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Camera info...",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun FocusPanel(cameraManager: PhoneCameraManager, caps: LensCapabilities) {
    val manualFocus by cameraManager.manualFocusDist.collectAsState()
    val isManualFocus = manualFocus != null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Auto focus
        Surface(
            onClick = { cameraManager.setManualFocusDist(null) },
            color = if (!isManualFocus) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.focus_auto),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        // Manual focus
        Surface(
            onClick = { if (!isManualFocus) cameraManager.setManualFocusDist(0f) },
            color = if (isManualFocus) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.focus_manual),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }

    // Manual focus slider (when manual is active)
    if (isManualFocus && caps.minFocusDistance > 0f) {
        Spacer(Modifier.height(4.dp))
        val currentDist = manualFocus ?: 0f
        Text(
            formatFocusDistance(currentDist),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = currentDist,
            onValueChange = { cameraManager.setManualFocusDist(it) },
            valueRange = 0f..caps.minFocusDistance,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("\u221E", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            Text(formatFocusDistance(caps.minFocusDistance), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

// ── Camera controls helpers ────────────────────────────────────────────

private fun formatFocusDistance(diopters: Float): String {
    if (diopters <= 0.01f) return "\u221E"
    val meters = 1f / diopters
    return if (meters >= 1f) "%.1fm".format(meters) else "%.0fcm".format(meters * 100)
}

// ── Intervalometer panels ───────────────────────────────────────────────

private val SHOT_STEPS = intArrayOf(1, 2, 5, 10, 20, 30, 50, 100, 200, 300, 500)
// DSLR-style discrete interval presets — mirrors the menus on common Canon/Nikon
// intervalometer remotes. 2s minimum gives the camera time to write each file
// and keeps the sensor from overheating across a long session.
private val GAP_PRESETS_MS = longArrayOf(2000, 5000, 10000, 30000)

@Composable
private fun IntervalometerPanel(vm: PulsarViewModel, currentLens: com.ehrocha.pulsar.camera.PhoneLens?) {
    val shotCount by vm.shotCount.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val intervalMs by vm.intervalMs.collectAsState()
    val manualIso by vm.phoneCameraManager.manualIso.collectAsState()
    val scrollState = rememberScrollState()

    // Slider range is generous (1 ms … 30 s) regardless of sensor caps — the
    // user can request any value, the capture pipeline clamps internally on the
    // way to the sensor. Filtering the UI by sensor caps was hiding values
    // people legitimately want to set (especially long exposures for stacking).
    val expRange = currentLens?.capabilities?.exposureTimeRange
    val sensorMaxMs = expRange?.upper?.div(1_000_000L) ?: 30_000L
    val expMinMs = 1L
    val expMaxMs = 30_000L

    val expLogMin = kotlin.math.ln(expMinMs.toDouble())
    val expLogMax = kotlin.math.ln(expMaxMs.toDouble())
    fun positionToExpMs(pos: Float): Long =
        kotlin.math.exp(expLogMin + pos * (expLogMax - expLogMin)).toLong().coerceIn(expMinMs, expMaxMs)
    fun expMsToPosition(ms: Long): Float {
        val clamped = ms.coerceIn(expMinMs, expMaxMs).toDouble()
        return ((kotlin.math.ln(clamped) - expLogMin) / (expLogMax - expLogMin)).toFloat()
    }

    // Sky-rule presets (raw + sensor-clamped). NPF accounts for pixel pitch;
    // 400 and 500 rules are the classic focal-length-only divisors.
    val effectiveCropFactor =
        if (currentLens != null && currentLens.sensorWidth > 0) 36f / currentLens.sensorWidth else null
    val npfRawMs = if (currentLens != null && currentLens.focalLength > 0 && effectiveCropFactor != null) {
        val sensorWidthUm = currentLens.sensorWidth * 1000f
        val approxPixelsWide = kotlin.math.sqrt(currentLens.megapixels.toDouble() * 1_000_000.0 * 4.0 / 3.0)
        val pixelPitchUm = sensorWidthUm / approxPixelsWide
        val aperture = if (currentLens.aperture > 0) currentLens.aperture.toDouble() else 2.8
        val exposureS = (35.0 * aperture + 30.0 * pixelPitchUm) / (currentLens.focalLength * effectiveCropFactor)
        (exposureS * 1000).toLong().coerceAtLeast(1000)
    } else null
    fun ruleExposureMs(divisor: Int): Long? {
        if (currentLens == null || currentLens.focalLength <= 0 || effectiveCropFactor == null) return null
        val expS = divisor.toDouble() / (currentLens.focalLength * effectiveCropFactor)
        return (expS * 1000).toLong().coerceAtLeast(1000)
    }
    val rule400RawMs = ruleExposureMs(400)
    val rule500RawMs = ruleExposureMs(500)

    val isoRange = currentLens?.capabilities?.isoRange
    val isoMin = isoRange?.lower ?: 100
    val isoMax = isoRange?.upper ?: 6400
    val isoLogMin = kotlin.math.ln(isoMin.coerceAtLeast(1).toDouble())
    val isoLogMax = kotlin.math.ln(isoMax.coerceAtLeast(2).toDouble())
    fun positionToIso(pos: Float): Int =
        kotlin.math.exp(isoLogMin + pos * (isoLogMax - isoLogMin)).toInt().coerceIn(isoMin, isoMax)
    fun isoToPosition(iso: Int): Float =
        ((kotlin.math.ln(iso.coerceIn(isoMin, isoMax).toDouble()) - isoLogMin) / (isoLogMax - isoLogMin)).toFloat()

    Column(
        modifier = Modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ── Exposure ──
        Text(
            stringResource(R.string.camera_exposure_fmt, formatExposureLabel(exposureMs)),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        if (exposureMs > sensorMaxMs) {
            Text(
                stringResource(R.string.exposure_exceeds_sensor, formatExposureLabel(sensorMaxMs)),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFB300),
            )
        }
        // Astro auto chips — pick a sky rule (NPF / 400 / 500). Tapping any one
        // sets exposure (clamped to slider range) and ISO 1600 in a single tap.
        if (npfRawMs != null || rule400RawMs != null || rule500RawMs != null) {
            fun applyRule(rawMs: Long) {
                vm.setExposureMs(rawMs.coerceIn(expMinMs, expMaxMs))
                isoRange?.let {
                    vm.phoneCameraManager.setManualIso(1600.coerceIn(it.lower, it.upper))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AstroRuleChip(
                    label = stringResource(R.string.auto_astro_rule_npf),
                    sub = npfRawMs?.let { formatExposureLabel(it.coerceIn(expMinMs, expMaxMs)) },
                    selected = npfRawMs != null && exposureMs == npfRawMs.coerceIn(expMinMs, expMaxMs),
                    enabled = npfRawMs != null,
                    onClick = { npfRawMs?.let(::applyRule) },
                    modifier = Modifier.weight(1f),
                )
                AstroRuleChip(
                    label = stringResource(R.string.auto_astro_rule_400),
                    sub = rule400RawMs?.let { formatExposureLabel(it.coerceIn(expMinMs, expMaxMs)) },
                    selected = rule400RawMs != null && exposureMs == rule400RawMs.coerceIn(expMinMs, expMaxMs),
                    enabled = rule400RawMs != null,
                    onClick = { rule400RawMs?.let(::applyRule) },
                    modifier = Modifier.weight(1f),
                )
                AstroRuleChip(
                    label = stringResource(R.string.auto_astro_rule_500),
                    sub = rule500RawMs?.let { formatExposureLabel(it.coerceIn(expMinMs, expMaxMs)) },
                    selected = rule500RawMs != null && exposureMs == rule500RawMs.coerceIn(expMinMs, expMaxMs),
                    enabled = rule500RawMs != null,
                    onClick = { rule500RawMs?.let(::applyRule) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Continuous log-scale slider — sensor min .. sensor max.
        Slider(
            value = expMsToPosition(exposureMs),
            onValueChange = { vm.setExposureMs(positionToExpMs(it)) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        // Quick-jump anchor chips — only ones the sensor can actually do.
        val shutterScroll = rememberScrollState()
        // Show every standard anchor — internal capture pipeline handles values
        // beyond what the sensor advertises (clamps + ISO compensates).
        val supportedAnchors = SHUTTER_ANCHORS
        if (supportedAnchors.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(shutterScroll),
            ) {
                supportedAnchors.forEach { stop ->
                    val selected = exposureMs == stop.ms
                    Surface(
                        onClick = { vm.setExposureMs(stop.ms) },
                        color = if (selected) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            stop.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // ── ISO ──
        if (isoRange != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                if (manualIso != null) "ISO ${manualIso}" else "ISO Auto",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            // ISO Auto tile (matches the rule chips above the exposure slider so
            // both sliders end up the same width). Tap to restore auto-ISO.
            val autoSelected = manualIso == null
            Surface(
                onClick = { vm.phoneCameraManager.setManualIso(null) },
                color = if (autoSelected) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.iso_auto),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Slider(
                value = isoToPosition(manualIso ?: ((isoMin + isoMax) / 2)),
                onValueChange = { vm.phoneCameraManager.setManualIso(positionToIso(it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            val isoScroll = rememberScrollState()
            val supportedIsoAnchors = ISO_ANCHORS.filter { it in isoMin..isoMax }
            if (supportedIsoAnchors.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(isoScroll),
                ) {
                    supportedIsoAnchors.forEach { iso ->
                        val selected = manualIso == iso
                        Surface(
                            onClick = { vm.phoneCameraManager.setManualIso(iso) },
                            color = if (selected) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                "$iso",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Interval (DSLR-style preset chips) ──
        Text(
            stringResource(R.string.camera_gap_label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            GAP_PRESETS_MS.forEach { ms ->
                val selected = intervalMs == ms
                Surface(
                    onClick = { vm.setIntervalMs(ms) },
                    color = if (selected) Color.White.copy(alpha = 0.3f)
                            else Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "${ms / 1000}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        // ── Shots ──
        // Continuous log-scale matches the exposure / ISO sliders. SHOT_STEPS
        // still drives the anchor chips below for quick jumps.
        val shotsMin = SHOT_STEPS.first()
        val shotsMax = SHOT_STEPS.last()
        val shotsLogMin = kotlin.math.ln(shotsMin.toDouble())
        val shotsLogMax = kotlin.math.ln(shotsMax.toDouble())
        fun positionToShots(pos: Float): Int =
            kotlin.math.exp(shotsLogMin + pos * (shotsLogMax - shotsLogMin)).toInt().coerceIn(shotsMin, shotsMax)
        fun shotsToPosition(n: Int): Float =
            ((kotlin.math.ln(n.coerceIn(shotsMin, shotsMax).toDouble()) - shotsLogMin) / (shotsLogMax - shotsLogMin)).toFloat()
        Text(
            stringResource(R.string.camera_shots_fmt, shotCount),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = shotsToPosition(shotCount),
            onValueChange = { vm.setShotCount(positionToShots(it)) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Running overlay (on preview) ────────────────────────────────────────

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

