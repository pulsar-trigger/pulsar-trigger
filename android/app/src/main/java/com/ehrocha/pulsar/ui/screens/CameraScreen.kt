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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
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
import kotlin.math.roundToInt

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

    // Grid overlay
    var gridMode by remember { mutableStateOf(GridMode.OFF) }

    // Device orientation for celestial pole overlay
    val deviceOrientation = remember { DeviceOrientation(context) }
    val azimuth by deviceOrientation.azimuth.collectAsState()
    val pitch by deviceOrientation.pitch.collectAsState()
    val sensorAccuracy by deviceOrientation.accuracy.collectAsState()

    DisposableEffect(gridMode) {
        if (gridMode == GridMode.CELESTIAL) deviceOrientation.start()
        else deviceOrientation.stop()
        onDispose { deviceOrientation.stop() }
    }

    // Live location for celestial grid — updates as GPS locks improve
    var latitude by remember { mutableFloatStateOf(0f) }
    var longitude by remember { mutableFloatStateOf(0f) }
    var locationAccuracy by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var hasLocation by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager

        // Seed from cached location immediately
        try {
            @Suppress("MissingPermission")
            val cached = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (cached != null) {
                latitude = cached.latitude.toFloat()
                longitude = cached.longitude.toFloat()
                locationAccuracy = cached.accuracy
                hasLocation = true
            }
        } catch (_: Exception) {}

        // Listen for live updates
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                latitude = loc.latitude.toFloat()
                longitude = loc.longitude.toFloat()
                locationAccuracy = loc.accuracy
                hasLocation = true
            }
        }

        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 10f, listener)
        } catch (_: Exception) {}

        onDispose {
            lm.removeUpdates(listener)
        }
    }

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

    // Current lens capabilities
    val currentLens = lenses.getOrNull(selectedLens)
    val caps = currentLens?.capabilities ?: LensCapabilities()

    // Dialogs
    var showExitDialog by remember { mutableStateOf(false) }
    var showCameraDebug by remember { mutableStateOf(false) }
    BackHandler(enabled = isRunning) { showExitDialog = true }

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
                    azimuth = azimuth,
                    pitch = pitch,
                    latitude = latitude,
                    longitude = longitude,
                    currentLens = currentLens,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Calibration warning for celestial grid
            if (gridMode == GridMode.CELESTIAL) {
                val compassLow = sensorAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
                val noGps = !hasLocation
                val gpsInaccurate = hasLocation && locationAccuracy > 100f

                if (compassLow || noGps || gpsInaccurate) {
                    val warning = buildString {
                        if (compassLow) append(stringResource(R.string.warning_compass))
                        if (noGps) {
                            if (isNotEmpty()) append(" \u2022 ")
                            append(stringResource(R.string.warning_no_gps))
                        } else if (gpsInaccurate) {
                            if (isNotEmpty()) append(" \u2022 ")
                            append(stringResource(R.string.warning_gps_inaccurate, locationAccuracy.toInt()))
                        }
                    }
                    Surface(
                        color = Color(0xCC331100),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                    ) {
                        Text(
                            warning,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF9800),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
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
                val expSimEnabled by cameraManager.expSimEnabled.collectAsState()

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
                    expSimEnabled = expSimEnabled,
                    onExpSimToggle = { cameraManager.setExpSimEnabled(it) },
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
                        // Auto modes don't use the param panel — close it on switch.
                        if (mode != CaptureMode.MANUAL) activePanel = null
                    },
                    onPanelToggle = { panel ->
                        activePanel = if (activePanel == panel) null else panel
                    },
                    onStart = {
                        activePanel = null
                        when (selectedCaptureMode) {
                            CaptureMode.MANUAL -> {
                                vm.selectMode(TriggerMode.INTERVALOMETER)
                                vm.loadQuickMode(FlowStepType.INTERVALOMETER)
                                vm.startFlow()
                            }
                            CaptureMode.AUTO -> {
                                keepAwake = true
                                vm.startAutoAstro()
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
    CELESTIAL("Sky"),
}

@Composable
private fun GridOverlay(
    gridMode: GridMode,
    azimuth: Float,
    pitch: Float,
    latitude: Float,
    longitude: Float,
    currentLens: com.ehrocha.pulsar.camera.PhoneLens?,
    modifier: Modifier = Modifier,
) {
    val gridColor = Color.White.copy(alpha = 0.4f)
    val poleColor = Color.Red.copy(alpha = 0.8f)
    val mwColor = Color(0xFFFF9800) // Orange for MW core
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        when (gridMode) {
            GridMode.THIRDS -> drawGrid(3, 3, gridColor)
            GridMode.GRID_4X4 -> drawGrid(4, 4, gridColor)
            GridMode.CELESTIAL -> drawCelestialGrid(
                azimuth = azimuth,
                pitch = pitch,
                latitude = latitude,
                longitude = longitude,
                lens = currentLens,
                poleColor = poleColor,
                mwColor = mwColor,
                gridColor = gridColor,
                textMeasurer = textMeasurer,
            )
            GridMode.OFF -> {}
        }
    }
}

private fun DrawScope.drawGrid(cols: Int, rows: Int, color: Color) {
    val strokeWidth = 1f
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

/**
 * Unified celestial overlay: spherical declination grid centered on the pole
 * (like PhotoPills) plus a Milky Way core marker.
 *
 * Draws concentric circles at 10° declination intervals from the pole.
 * Small circles near the pole, growing toward the equator (90° away).
 */
private fun DrawScope.drawCelestialGrid(
    azimuth: Float,
    pitch: Float,
    latitude: Float,
    longitude: Float,
    lens: com.ehrocha.pulsar.camera.PhoneLens?,
    poleColor: Color,
    mwColor: Color,
    gridColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    if (lens == null || lens.focalLength <= 0 || lens.sensorWidth <= 0) return

    val hFovDeg = 2.0 * Math.toDegrees(
        kotlin.math.atan((lens.sensorWidth / 2.0) / lens.focalLength)
    )
    val vFovDeg = 2.0 * Math.toDegrees(
        kotlin.math.atan((lens.sensorHeight / 2.0) / lens.focalLength)
    )
    if (hFovDeg <= 0 || vFovDeg <= 0) return

    val pixPerDeg = ((size.width / hFovDeg + size.height / vFovDeg) / 2.0)
    val pixPerDegH = size.width / hFovDeg
    val pixPerDegV = size.height / vFovDeg

    val camAz = azimuth.toDouble()
    val camAlt = -pitch.toDouble()

    // ── Celestial pole position ──
    val isNorth = latitude >= 0
    val poleAz = if (isNorth) 0.0 else 180.0
    val poleAlt = kotlin.math.abs(latitude).toDouble()

    var deltaAz = poleAz - camAz
    while (deltaAz > 180) deltaAz -= 360
    while (deltaAz < -180) deltaAz += 360
    val deltaAlt = poleAlt - camAlt

    val poleX = (size.width / 2.0 + deltaAz * pixPerDegH).toFloat()
    val poleY = (size.height / 2.0 - deltaAlt * pixPerDegV).toFloat()

    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

    // ── Spherical declination rings at 10° intervals from the pole ──
    if (latitude != 0f) {
        for (degFromPole in 10..90 step 10) {
            val radiusPx = (degFromPole.toDouble() * pixPerDeg).toFloat()
            val isEquator = degFromPole == 90
            val strokeW = if (isEquator) 1.5f else 0.8f
            val ringAlpha = if (isEquator) 0.4f else 0.2f

            drawCircle(
                color = gridColor.copy(alpha = ringAlpha),
                radius = radiusPx,
                center = Offset(poleX, poleY),
                style = Stroke(strokeW, pathEffect = if (!isEquator) dashEffect else null),
            )
        }

        // ── Pole crosshair ──
        val crossSize = 25f
        drawLine(poleColor, Offset(poleX - crossSize, poleY), Offset(poleX + crossSize, poleY), 2f)
        drawLine(poleColor, Offset(poleX, poleY - crossSize), Offset(poleX, poleY + crossSize), 2f)

        // Inner 1° circle
        val r1 = pixPerDeg.toFloat()
        drawCircle(poleColor, r1, Offset(poleX, poleY), style = Stroke(1.5f))

        // Pole label
        val poleLabel = if (isNorth) "NCP" else "SCP"
        val poleLabelResult = textMeasurer.measure(
            poleLabel,
            style = TextStyle(color = poleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
        )
        drawText(poleLabelResult, topLeft = Offset(poleX + crossSize + 4f, poleY - poleLabelResult.size.height / 2f))
    }

    // ── Milky Way core ──
    if (latitude != 0f || longitude != 0f) {
        val lst = localSiderealTimeDeg(longitude.toDouble())
        val (gcAlt, gcAz) = raDecToAltAz(GC_RA_DEG, GC_DEC_DEG, latitude.toDouble(), lst)

        var mwDeltaAz = gcAz - camAz
        while (mwDeltaAz > 180) mwDeltaAz -= 360
        while (mwDeltaAz < -180) mwDeltaAz += 360
        val mwDeltaAlt = gcAlt - camAlt

        val mwX = (size.width / 2.0 + mwDeltaAz * pixPerDegH).toFloat()
        val mwY = (size.height / 2.0 - mwDeltaAlt * pixPerDegV).toFloat()

        val belowHorizon = gcAlt < 0
        val drawColor = if (belowHorizon) mwColor.copy(alpha = 0.3f) else mwColor

        val margin = 40f
        val onScreen = mwX in -margin..size.width + margin &&
            mwY in -margin..size.height + margin

        if (onScreen) {
            // Draw crosshair + circle when visible
            val mwCross = 20f
            drawLine(drawColor, Offset(mwX - mwCross, mwY), Offset(mwX + mwCross, mwY), 2f)
            drawLine(drawColor, Offset(mwX, mwY - mwCross), Offset(mwX, mwY + mwCross), 2f)

            val mwR = (2.0 * pixPerDeg).toFloat()
            drawCircle(drawColor, mwR, Offset(mwX, mwY), style = Stroke(1.5f))

            val mwLabel = if (belowHorizon) "MW \u2193" else "MW"
            val mwLabelResult = textMeasurer.measure(
                mwLabel,
                style = TextStyle(color = drawColor, fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            drawText(mwLabelResult, topLeft = Offset(mwX + mwCross + 4f, mwY - mwLabelResult.size.height / 2f))
        } else {
            // Off-screen: draw an arrow at the edge pointing toward the MW core
            val edgePadding = 50f
            val cx = size.width / 2f
            val cy = size.height / 2f

            // Direction from center to MW position
            val dx = mwX - cx
            val dy = mwY - cy
            val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())

            // Clamp to screen edge with padding
            val edgeX = (cx + (size.width / 2f - edgePadding) * kotlin.math.cos(angle)).toFloat()
                .coerceIn(edgePadding, size.width - edgePadding)
            val edgeY = (cy + (size.height / 2f - edgePadding) * kotlin.math.sin(angle)).toFloat()
                .coerceIn(edgePadding, size.height - edgePadding)

            // Arrow triangle pointing in the direction of the MW core
            val arrowSize = 14f
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()

            val tip = Offset(edgeX + arrowSize * cos, edgeY + arrowSize * sin)
            val left = Offset(
                edgeX - arrowSize * cos + arrowSize * 0.6f * -sin,
                edgeY - arrowSize * sin + arrowSize * 0.6f * cos,
            )
            val right = Offset(
                edgeX - arrowSize * cos - arrowSize * 0.6f * -sin,
                edgeY - arrowSize * sin - arrowSize * 0.6f * cos,
            )

            val arrowPath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(right.x, right.y)
                close()
            }
            drawPath(arrowPath, color = drawColor)

            // Label next to arrow
            val mwLabel = if (belowHorizon) "MW \u2193" else "MW"
            val mwLabelResult = textMeasurer.measure(
                mwLabel,
                style = TextStyle(color = drawColor, fontSize = 11.sp, fontWeight = FontWeight.Bold),
            )
            // Position label offset from arrow, away from center
            val labelOffsetX = if (edgeX > cx) -mwLabelResult.size.width - arrowSize else arrowSize
            drawText(
                mwLabelResult,
                topLeft = Offset(edgeX + labelOffsetX, edgeY - mwLabelResult.size.height / 2f),
            )
        }
    }

    // ── Pole off-screen arrow ──
    if (latitude != 0f) {
        val poleMargin = 40f
        val poleOnScreen = poleX in -poleMargin..size.width + poleMargin &&
            poleY in -poleMargin..size.height + poleMargin

        if (!poleOnScreen) {
            val edgePadding = 50f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val dx = poleX - cx
            val dy = poleY - cy
            val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())

            val edgeX = (cx + (size.width / 2f - edgePadding) * kotlin.math.cos(angle)).toFloat()
                .coerceIn(edgePadding, size.width - edgePadding)
            val edgeY = (cy + (size.height / 2f - edgePadding) * kotlin.math.sin(angle)).toFloat()
                .coerceIn(edgePadding, size.height - edgePadding)

            val arrowSize = 12f
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()

            val tip = Offset(edgeX + arrowSize * cos, edgeY + arrowSize * sin)
            val left = Offset(
                edgeX - arrowSize * cos + arrowSize * 0.6f * -sin,
                edgeY - arrowSize * sin + arrowSize * 0.6f * cos,
            )
            val right = Offset(
                edgeX - arrowSize * cos - arrowSize * 0.6f * -sin,
                edgeY - arrowSize * sin - arrowSize * 0.6f * cos,
            )

            val arrowPath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(left.x, left.y)
                lineTo(right.x, right.y)
                close()
            }
            drawPath(arrowPath, color = poleColor)

            val poleLabel = if (isNorth) "NCP" else "SCP"
            val poleLabelResult = textMeasurer.measure(
                poleLabel,
                style = TextStyle(color = poleColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            )
            val labelOffsetX = if (edgeX > cx) -poleLabelResult.size.width - arrowSize else arrowSize
            drawText(
                poleLabelResult,
                topLeft = Offset(edgeX + labelOffsetX, edgeY - poleLabelResult.size.height / 2f),
            )
        }
    }
}

// ── Celestial helpers ─────────────────────────────────────────────────

/** Galactic center: RA 17h 45m 40s = 266.417°, Dec -29.008° */
private const val GC_RA_DEG = 266.417
private const val GC_DEC_DEG = -29.008

private fun localSiderealTimeDeg(longitudeDeg: Double): Double {
    val now = System.currentTimeMillis()
    val j2000Ms = 946728000000L
    val daysSinceJ2000 = (now - j2000Ms) / 86400000.0
    val gmst = (280.46061837 + 360.98564736629 * daysSinceJ2000) % 360.0
    val lst = (gmst + longitudeDeg) % 360.0
    return if (lst < 0) lst + 360.0 else lst
}

private fun raDecToAltAz(raDeg: Double, decDeg: Double, latDeg: Double, lstDeg: Double): Pair<Double, Double> {
    val ha = Math.toRadians(lstDeg - raDeg)
    val dec = Math.toRadians(decDeg)
    val lat = Math.toRadians(latDeg)
    val sinAlt = kotlin.math.sin(dec) * kotlin.math.sin(lat) +
        kotlin.math.cos(dec) * kotlin.math.cos(lat) * kotlin.math.cos(ha)
    val alt = Math.toDegrees(kotlin.math.asin(sinAlt.coerceIn(-1.0, 1.0)))
    val cosA = (kotlin.math.sin(dec) - kotlin.math.sin(lat) * sinAlt) /
        (kotlin.math.cos(lat) * kotlin.math.cos(Math.toRadians(alt)))
    var az = Math.toDegrees(kotlin.math.acos(cosA.coerceIn(-1.0, 1.0)))
    if (kotlin.math.sin(ha) > 0) az = 360.0 - az
    return Pair(alt, az)
}

// ── Camera panel enum ───────────────────────────────────────────────────

private enum class CameraPanel { LENS, ISO, FOCUS, INTERVALOMETER }

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
    expSimEnabled: Boolean = true,
    onExpSimToggle: (Boolean) -> Unit = {},
    onOpenGallery: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val manualIso by cameraManager.manualIso.collectAsState()
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
                if (caps.supportsManualExposure) {
                    ControlIconButton(
                        icon = Icons.Default.Iso,
                        label = if (manualIso != null) "$manualIso" else "Auto",
                        active = activePanel == CameraPanel.ISO,
                        onClick = { onPanelToggle(CameraPanel.ISO) },
                        tooltip = stringResource(R.string.tooltip_iso),
                    )
                }
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

                // Exposure Simulation toggle (only when manual exposure is available)
                if (caps.supportsManualExposure) {
                    ControlIconButton(
                        icon = Icons.Default.Visibility,
                        label = if (expSimEnabled) "ExpSim" else "Off",
                        active = expSimEnabled,
                        onClick = { onExpSimToggle(!expSimEnabled) },
                        tooltip = stringResource(R.string.tooltip_expsim),
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
                    CameraPanel.ISO -> IsoPanel(cameraManager, caps)
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
        // Expanded panel (left of icons) — only for Manual mode
        AnimatedVisibility(visible = intervalPanel != null && selectedMode == CaptureMode.MANUAL) {
            ExpandedPanel(activePanel = intervalPanel) {
                when (intervalPanel) {
                    CameraPanel.INTERVALOMETER -> IntervalometerPanel(vm, currentLens)
                    else -> {}
                }
            }
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
                    active = selectedMode == CaptureMode.AUTO,
                    onClick = { onModeSelected(CaptureMode.AUTO) },
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
                // ─── Group divider ───
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    thickness = 1.dp,
                    color = Color.White.copy(alpha = 0.2f),
                )
                Spacer(Modifier.height(6.dp))
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
                Spacer(Modifier.height(8.dp))
                StartIconButton(onClick = onStart)
            }
        }
    }
}

// Panel group membership
private val CAMERA_PANELS = setOf(CameraPanel.LENS, CameraPanel.ISO, CameraPanel.FOCUS)
private val INTERVALOMETER_PANELS = setOf(CameraPanel.INTERVALOMETER)

private enum class CaptureMode { MANUAL, AUTO, STORM, TRAILS, FIREWORKS }

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
                CameraPanel.ISO -> "ISO"
                CameraPanel.FOCUS -> stringResource(R.string.label_focus_mode)
                CameraPanel.INTERVALOMETER -> stringResource(R.string.mode_intervalometer)
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
    return when {
        ms < 1000 -> "${ms}ms"
        ms % 1000 == 0L -> "${ms / 1000}s"
        else -> "%.1fs".format(ms / 1000.0)
    }
}

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
private fun IsoPanel(cameraManager: PhoneCameraManager, caps: LensCapabilities) {
    val manualIso by cameraManager.manualIso.collectAsState()
    val isoRange = caps.isoRange ?: return
    val isManual = manualIso != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("ISO", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f))
        Surface(
            onClick = {
                if (isManual) {
                    cameraManager.setManualIso(null)
                    cameraManager.setManualExposureNs(null)
                } else {
                    cameraManager.setManualIso((isoRange.lower + isoRange.upper) / 2)
                    cameraManager.setManualExposureNs(1_000_000_000L)
                }
            },
            color = if (isManual) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                if (isManual) "Manual" else "Auto",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    if (isManual) {
        Text(
            "ISO ${manualIso ?: isoRange.lower}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = (manualIso ?: isoRange.lower).toFloat(),
            onValueChange = { cameraManager.setManualIso(it.roundToInt()) },
            valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${isoRange.lower}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
            Text("${isoRange.upper}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun FocusPanel(cameraManager: PhoneCameraManager, caps: LensCapabilities) {
    val manualFocus by cameraManager.manualFocusDist.collectAsState()
    val isManualFocus = manualFocus != null
    val starFocusRunning by cameraManager.starFocusRunning.collectAsState()
    val starFocusProgress by cameraManager.starFocusProgress.collectAsState()
    val scope = rememberCoroutineScope()

    // Three focus mode buttons: Auto, Manual, Star
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
            color = if (isManualFocus && !starFocusRunning) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
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

        // Star auto-focus
        if (caps.minFocusDistance > 0f) {
            Surface(
                onClick = {
                    if (!starFocusRunning) {
                        if (!isManualFocus) cameraManager.setManualFocusDist(0f)
                        scope.launch { cameraManager.starAutoFocus() }
                    }
                },
                color = if (starFocusRunning) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (starFocusRunning) stringResource(R.string.star_focus_running)
                        else stringResource(R.string.star_focus),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
            if (starFocusRunning) {
                LinearProgressIndicator(
                    progress = { starFocusProgress },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Text(
                    stringResource(R.string.star_focus_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
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
private val EXPOSURE_STEPS_MS = longArrayOf(
    100, 250, 500, 1000, 2000, 3000, 4000, 5000,
    8000, 10000, 13000, 15000, 20000, 25000, 30000,
)
private val GAP_STEPS_MS = longArrayOf(
    0, 500, 1000, 2000, 3000, 5000, 8000, 10000, 15000, 20000, 30000,
)

@Composable
private fun IntervalometerPanel(vm: PulsarViewModel, currentLens: com.ehrocha.pulsar.camera.PhoneLens?) {
    val shotCount by vm.shotCount.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val intervalMs by vm.intervalMs.collectAsState()
    val scrollState = rememberScrollState()

    // NPF auto calculation
    val npfMs = if (currentLens != null && currentLens.focalLength > 0 && currentLens.sensorWidth > 0) {
        val cropFactor = 36f / currentLens.sensorWidth
        val pixelArray = currentLens.megapixels * 1_000_000f
        val sensorWidthUm = currentLens.sensorWidth * 1000f
        val approxPixelsWide = kotlin.math.sqrt(pixelArray.toDouble() * 4.0 / 3.0)
        val pixelPitchUm = sensorWidthUm / approxPixelsWide
        val aperture = if (currentLens.aperture > 0) currentLens.aperture.toDouble() else 2.8
        val exposureS = (35.0 * aperture + 30.0 * pixelPitchUm) / (currentLens.focalLength * cropFactor)
        (exposureS * 1000).toLong().coerceAtLeast(1000)
    } else null

    val isNpfAuto = npfMs != null && exposureMs == npfMs

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
        if (npfMs != null) {
            Surface(
                onClick = { vm.setExposureMs(npfMs) },
                color = if (isNpfAuto) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("NPF Auto", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.weight(1f))
                    Text(formatExposureLabel(npfMs), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        val expIdx = EXPOSURE_STEPS_MS.indices.minBy { kotlin.math.abs(EXPOSURE_STEPS_MS[it] - exposureMs) }
        Slider(
            value = expIdx.toFloat(),
            onValueChange = { vm.setExposureMs(EXPOSURE_STEPS_MS[it.roundToInt().coerceIn(0, EXPOSURE_STEPS_MS.lastIndex)]) },
            valueRange = 0f..(EXPOSURE_STEPS_MS.lastIndex).toFloat(),
            steps = (EXPOSURE_STEPS_MS.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Gap ──
        val gapAlpha = if (isNpfAuto) 0.4f else 1f
        Text(
            stringResource(R.string.camera_gap_fmt, if (intervalMs == 0L) "0s" else formatExposureLabel(intervalMs)),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = gapAlpha),
            fontWeight = FontWeight.Medium,
        )
        val gapIdx = GAP_STEPS_MS.indices.minBy { kotlin.math.abs(GAP_STEPS_MS[it] - intervalMs) }
        Slider(
            value = gapIdx.toFloat(),
            onValueChange = { vm.setIntervalMs(GAP_STEPS_MS[it.roundToInt().coerceIn(0, GAP_STEPS_MS.lastIndex)]) },
            enabled = !isNpfAuto,
            valueRange = 0f..(GAP_STEPS_MS.lastIndex).toFloat(),
            steps = (GAP_STEPS_MS.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth(),
        )

        // ── Shots ──
        val shotIdx = SHOT_STEPS.indices.minBy { kotlin.math.abs(SHOT_STEPS[it] - shotCount) }
        Text(
            stringResource(R.string.camera_shots_fmt, shotCount),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = shotIdx.toFloat(),
            onValueChange = { vm.setShotCount(SHOT_STEPS[it.roundToInt().coerceIn(0, SHOT_STEPS.lastIndex)]) },
            valueRange = 0f..(SHOT_STEPS.lastIndex).toFloat(),
            steps = (SHOT_STEPS.size - 2).coerceAtLeast(0),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
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

