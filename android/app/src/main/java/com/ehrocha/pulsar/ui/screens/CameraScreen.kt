/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.Manifest
import android.location.LocationManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
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
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.WaitingYellow
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
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

    DisposableEffect(gridMode) {
        if (gridMode == GridMode.CELESTIAL) deviceOrientation.start()
        else deviceOrientation.stop()
        onDispose { deviceOrientation.stop() }
    }

    // Get latitude for celestial pole calculation
    val latitude = remember {
        try {
            val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.latitude?.toFloat() ?: 0f
        } catch (_: Exception) { 0f }
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

            // Grid overlay
            if (gridMode != GridMode.OFF) {
                GridOverlay(
                    gridMode = gridMode,
                    azimuth = azimuth,
                    pitch = pitch,
                    latitude = latitude,
                    currentLens = currentLens,
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
            if (isRunning && status != null) {
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

            // ── Camera controls strip (right side) ────────────────
            CameraControlsStrip(
                vm = vm,
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
                onStart = {
                    activePanel = null
                    vm.selectMode(TriggerMode.INTERVALOMETER)
                    vm.loadQuickMode(FlowStepType.INTERVALOMETER)
                    vm.startFlow()
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
            )
        }

        // ── Bottom bar (only when running) ─────────────────────────
        if (isRunning && status != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RunningBar(
                    status = status,
                    totalShots = shotCount,
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

/** Compact bottom bar when running — state + shot counter + stop button. */
@Composable
private fun RunningBar(
    status: com.ehrocha.pulsar.ble.StatusFrame,
    totalShots: Int,
    onStop: () -> Unit,
) {
    val displayShots = when (status.state) {
        DeviceState.RUNNING, DeviceState.WAITING -> status.shotsTaken + 1
        else -> status.shotsTaken
    }
    val stateColor = when (status.state) {
        DeviceState.RUNNING -> ExposureGreen
        DeviceState.WAITING -> WaitingYellow
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // State badge
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
            }
        }

        // Shot counter
        Text(
            "$displayShots / $totalShots",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )

        // Stop button
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
}

// ── Grid overlay ────────────────────────────────────────────────────────

private enum class GridMode(val label: String) {
    OFF("Off"),
    THIRDS("3x3"),
    GRID_4X4("4x4"),
    CELESTIAL("Pole"),
}

@Composable
private fun GridOverlay(
    gridMode: GridMode,
    azimuth: Float,
    pitch: Float,
    latitude: Float,
    currentLens: com.ehrocha.pulsar.camera.PhoneLens?,
    modifier: Modifier = Modifier,
) {
    val gridColor = Color.White.copy(alpha = 0.4f)
    val poleColor = Color.Red.copy(alpha = 0.8f)
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        when (gridMode) {
            GridMode.THIRDS -> drawGrid(3, 3, gridColor)
            GridMode.GRID_4X4 -> drawGrid(4, 4, gridColor)
            GridMode.CELESTIAL -> drawCelestialPole(
                azimuth = azimuth,
                pitch = pitch,
                latitude = latitude,
                lens = currentLens,
                poleColor = poleColor,
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

private fun DrawScope.drawCelestialPole(
    azimuth: Float,
    pitch: Float,
    latitude: Float,
    lens: com.ehrocha.pulsar.camera.PhoneLens?,
    poleColor: Color,
    gridColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    // Draw a subtle grid for reference
    drawGrid(3, 3, gridColor.copy(alpha = 0.2f))

    if (lens == null || lens.focalLength <= 0 || lens.sensorWidth <= 0 || latitude == 0f) return

    // Camera field of view
    val hFovDeg = 2.0 * Math.toDegrees(
        kotlin.math.atan((lens.sensorWidth / 2.0) / lens.focalLength)
    )
    val vFovDeg = 2.0 * Math.toDegrees(
        kotlin.math.atan((lens.sensorHeight / 2.0) / lens.focalLength)
    )
    if (hFovDeg <= 0 || vFovDeg <= 0) return

    // Celestial pole position:
    // NCP: azimuth = 0° (north), altitude = +latitude
    // SCP: azimuth = 180° (south), altitude = -latitude (visible when latitude < 0)
    val isNorth = latitude >= 0
    val poleAz = if (isNorth) 0.0 else 180.0
    val poleAlt = kotlin.math.abs(latitude).toDouble()

    // Camera is pointing at (azimuth, -pitch) in alt-az coordinates
    // (pitch from sensor: 0 = horizon, negative = looking up when phone held upright in landscape)
    val camAz = azimuth.toDouble()
    val camAlt = -pitch.toDouble() // Negate: sensor pitch is negative when looking up

    // Angular offset from camera center to pole
    var deltaAz = poleAz - camAz
    // Normalize to -180..180
    while (deltaAz > 180) deltaAz -= 360
    while (deltaAz < -180) deltaAz += 360
    val deltaAlt = poleAlt - camAlt

    // Convert to screen pixels
    val pixPerDegH = size.width / hFovDeg
    val pixPerDegV = size.height / vFovDeg

    val screenX = (size.width / 2.0 + deltaAz * pixPerDegH).toFloat()
    val screenY = (size.height / 2.0 - deltaAlt * pixPerDegV).toFloat() // Y inverted

    // Draw crosshair at pole position (even if off-screen, partial lines may show)
    val crossSize = 30f
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))

    // Crosshair
    drawLine(poleColor, Offset(screenX - crossSize, screenY), Offset(screenX + crossSize, screenY), 2f)
    drawLine(poleColor, Offset(screenX, screenY - crossSize), Offset(screenX, screenY + crossSize), 2f)

    // Concentric circles (1° and 3° radius)
    val r1 = (1.0 * pixPerDegH).toFloat()
    val r3 = (3.0 * pixPerDegH).toFloat()
    drawCircle(poleColor, r1, Offset(screenX, screenY), style = Stroke(1.5f))
    drawCircle(poleColor.copy(alpha = 0.5f), r3, Offset(screenX, screenY), style = Stroke(1f, pathEffect = dashEffect))

    // Label
    val label = if (isNorth) "NCP" else "SCP"
    val textResult = textMeasurer.measure(
        label,
        style = TextStyle(color = poleColor, fontSize = 12.sp, fontWeight = FontWeight.Bold),
    )
    drawText(textResult, topLeft = Offset(screenX + crossSize + 4f, screenY - textResult.size.height / 2f))
}

// ── Camera panel enum ───────────────────────────────────────────────────

private enum class CameraPanel { LENS, ISO, SHUTTER, FOCUS, SHOTS, EXPOSURE, GAP }

// ── Camera controls strip (right-side icons + expandable panels) ────────

@Composable
private fun CameraControlsStrip(
    vm: PulsarViewModel,
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
    onStart: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val manualIso by cameraManager.manualIso.collectAsState()
    val manualExpNs by cameraManager.manualExposureNs.collectAsState()
    val manualFocus by cameraManager.manualFocusDist.collectAsState()
    val currentLens = lenses.getOrNull(selectedLens)

    val shotCount by vm.shotCount.collectAsState()
    val exposureMs by vm.exposureMs.collectAsState()
    val intervalMs by vm.intervalMs.collectAsState()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── Expanded panel (appears to the left of the icon strip) ──
        AnimatedVisibility(visible = activePanel != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .padding(end = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (activePanel) {
                        CameraPanel.LENS -> LensPanel(
                            lenses = lenses,
                            selectedLens = selectedLens,
                            onLensSelected = onLensSelected,
                            onShowDebug = onShowDebug,
                        )
                        CameraPanel.ISO -> IsoPanel(cameraManager, caps)
                        CameraPanel.SHUTTER -> ShutterPanel(cameraManager, caps)
                        CameraPanel.FOCUS -> FocusPanel(cameraManager, caps)
                        CameraPanel.SHOTS -> ShotsPanel(vm)
                        CameraPanel.EXPOSURE -> ExposurePanel(vm, currentLens)
                        CameraPanel.GAP -> GapPanel(vm)
                        null -> {}
                    }
                }
            }
        }

        // ── Icon strip ──────────────────────────────────────────────
        Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                // ── Camera controls ─────────────────────────────────
                // Lens
                if (lenses.size > 1) {
                    ControlIconButton(
                        icon = Icons.Default.Lens,
                        label = currentLens?.label?.removePrefix("Back ") ?: "",
                        active = activePanel == CameraPanel.LENS,
                        onClick = { onPanelToggle(CameraPanel.LENS) },
                    )
                }

                // ISO
                if (caps.supportsManualExposure) {
                    ControlIconButton(
                        icon = Icons.Default.Iso,
                        label = if (manualIso != null) "${manualIso}" else "Auto",
                        active = activePanel == CameraPanel.ISO,
                        onClick = { onPanelToggle(CameraPanel.ISO) },
                    )
                }

                // Shutter speed
                if (caps.supportsManualExposure) {
                    ControlIconButton(
                        icon = Icons.Default.ShutterSpeed,
                        label = if (manualExpNs != null) formatShutterSpeed(manualExpNs!!) else "Auto",
                        active = activePanel == CameraPanel.SHUTTER,
                        onClick = { onPanelToggle(CameraPanel.SHUTTER) },
                    )
                }

                // Focus
                if (caps.supportsManualFocus) {
                    ControlIconButton(
                        icon = Icons.Default.CenterFocusStrong,
                        label = if (manualFocus != null) formatFocusDistance(manualFocus!!) else "AF",
                        active = activePanel == CameraPanel.FOCUS,
                        onClick = { onPanelToggle(CameraPanel.FOCUS) },
                    )
                }

                // ── Divider ─────────────────────────────────────────
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.3f)),
                )
                Spacer(Modifier.height(4.dp))

                // ── Intervalometer controls ──────────────────────────
                // Shots
                ControlIconButton(
                    icon = Icons.Default.PhotoLibrary,
                    label = "$shotCount",
                    active = activePanel == CameraPanel.SHOTS,
                    onClick = { onPanelToggle(CameraPanel.SHOTS) },
                )

                // Exposure
                ControlIconButton(
                    icon = Icons.Default.Timer,
                    label = formatExposureLabel(exposureMs),
                    active = activePanel == CameraPanel.EXPOSURE,
                    onClick = { onPanelToggle(CameraPanel.EXPOSURE) },
                )

                // Gap
                ControlIconButton(
                    icon = Icons.Default.AvTimer,
                    label = formatExposureLabel(intervalMs),
                    active = activePanel == CameraPanel.GAP,
                    onClick = { onPanelToggle(CameraPanel.GAP) },
                )

                // Grid overlay
                ControlIconButton(
                    icon = Icons.Default.GridOn,
                    label = gridMode.label,
                    active = gridMode != GridMode.OFF,
                    onClick = onGridCycle,
                )

                // Keep screen awake
                ControlIconButton(
                    icon = Icons.Default.LightMode,
                    label = if (keepAwake) "On" else "Off",
                    active = keepAwake,
                    onClick = { onKeepAwakeToggle(!keepAwake) },
                )

                // ── Start button ────────────────────────────────────
                Spacer(Modifier.height(4.dp))
                StartIconButton(onClick = onStart)
            }
        }
    }
}

@Composable
private fun ControlIconButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (active) Color.White.copy(alpha = 0.3f) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Color.White else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(22.dp),
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

@Composable
private fun StartIconButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.btn_start),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(22.dp),
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
private fun ShutterPanel(cameraManager: PhoneCameraManager, caps: LensCapabilities) {
    val manualExpNs by cameraManager.manualExposureNs.collectAsState()
    val manualIso by cameraManager.manualIso.collectAsState()
    val expRange = caps.exposureTimeRange ?: return
    val isManual = manualIso != null

    if (!isManual) {
        Text(
            stringResource(R.string.exposure_auto),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
        )
        Text(
            "Enable manual ISO first",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.4f),
        )
        return
    }

    val validSteps = SHUTTER_STEPS_NS.filter { it in expRange.lower..expRange.upper }
    if (validSteps.isEmpty()) return

    val currentNs = manualExpNs ?: 1_000_000_000L
    val nearestIdx = validSteps.indices.minBy { idx ->
        kotlin.math.abs(validSteps[idx] - currentNs)
    }

    Text(
        formatShutterSpeed(validSteps[nearestIdx]),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
    Slider(
        value = nearestIdx.toFloat(),
        onValueChange = { idx ->
            val step = validSteps[idx.roundToInt().coerceIn(0, validSteps.lastIndex)]
            cameraManager.setManualExposureNs(step)
        },
        valueRange = 0f..(validSteps.lastIndex).toFloat(),
        steps = (validSteps.size - 2).coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatShutterSpeed(validSteps.first()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        Text(formatShutterSpeed(validSteps.last()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
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

private val SHUTTER_STEPS_NS = longArrayOf(
    1_000_000_000L / 8000,
    1_000_000_000L / 4000,
    1_000_000_000L / 2000,
    1_000_000_000L / 1000,
    1_000_000_000L / 500,
    1_000_000_000L / 250,
    1_000_000_000L / 125,
    1_000_000_000L / 60,
    1_000_000_000L / 30,
    1_000_000_000L / 15,
    1_000_000_000L / 8,
    1_000_000_000L / 4,
    1_000_000_000L / 2,
    1_000_000_000L,
    2_000_000_000L,
    4_000_000_000L,
    8_000_000_000L,
    15_000_000_000L,
    30_000_000_000L,
)

private fun formatShutterSpeed(ns: Long): String {
    return if (ns >= 1_000_000_000L) {
        val sec = ns / 1_000_000_000.0
        if (sec == sec.toLong().toDouble()) "${sec.toLong()}s" else "%.1fs".format(sec)
    } else {
        val denom = (1_000_000_000.0 / ns).roundToInt()
        "1/${denom}"
    }
}

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
private fun ShotsPanel(vm: PulsarViewModel) {
    val shotCount by vm.shotCount.collectAsState()
    val nearestIdx = SHOT_STEPS.indices.minBy { idx ->
        kotlin.math.abs(SHOT_STEPS[idx] - shotCount)
    }

    Text(
        "$shotCount shots",
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
    Slider(
        value = nearestIdx.toFloat(),
        onValueChange = { idx ->
            val value = SHOT_STEPS[idx.roundToInt().coerceIn(0, SHOT_STEPS.lastIndex)]
            vm.setShotCount(value)
        },
        valueRange = 0f..(SHOT_STEPS.lastIndex).toFloat(),
        steps = (SHOT_STEPS.size - 2).coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${SHOT_STEPS.first()}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        Text("${SHOT_STEPS.last()}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun ExposurePanel(vm: PulsarViewModel, currentLens: com.ehrocha.pulsar.camera.PhoneLens?) {
    val exposureMs by vm.exposureMs.collectAsState()

    // Compute NPF-based max exposure for this lens
    val npfMs = if (currentLens != null && currentLens.focalLength > 0 && currentLens.sensorWidth > 0) {
        val cropFactor = 36f / currentLens.sensorWidth
        val pixelArray = currentLens.megapixels * 1_000_000f
        // Estimate pixel pitch: sensorWidth (mm) * 1000 (μm) / sqrt(pixelCount * aspect)
        // Simplified: use sensor width in mm / sqrt(megapixels * 4/3) * 1000
        val sensorWidthUm = currentLens.sensorWidth * 1000f
        val approxPixelsWide = kotlin.math.sqrt(pixelArray.toDouble() * 4.0 / 3.0)
        val pixelPitchUm = sensorWidthUm / approxPixelsWide
        val aperture = if (currentLens.aperture > 0) currentLens.aperture.toDouble() else 2.8
        val exposureS = (35.0 * aperture + 30.0 * pixelPitchUm) / (currentLens.focalLength * cropFactor)
        (exposureS * 1000).toLong().coerceAtLeast(1000)
    } else null

    // Auto (NPF) button
    if (npfMs != null) {
        Surface(
            onClick = { vm.setExposureMs(npfMs) },
            color = if (exposureMs == npfMs) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("NPF Auto", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f))
                Text(formatExposureLabel(npfMs), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }

    // Manual slider
    val nearestIdx = EXPOSURE_STEPS_MS.indices.minBy { idx ->
        kotlin.math.abs(EXPOSURE_STEPS_MS[idx] - exposureMs)
    }

    Text(
        formatExposureLabel(exposureMs),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
    Slider(
        value = nearestIdx.toFloat(),
        onValueChange = { idx ->
            val value = EXPOSURE_STEPS_MS[idx.roundToInt().coerceIn(0, EXPOSURE_STEPS_MS.lastIndex)]
            vm.setExposureMs(value)
        },
        valueRange = 0f..(EXPOSURE_STEPS_MS.lastIndex).toFloat(),
        steps = (EXPOSURE_STEPS_MS.size - 2).coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatExposureLabel(EXPOSURE_STEPS_MS.first()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        Text(formatExposureLabel(EXPOSURE_STEPS_MS.last()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun GapPanel(vm: PulsarViewModel) {
    val intervalMs by vm.intervalMs.collectAsState()
    val nearestIdx = GAP_STEPS_MS.indices.minBy { idx ->
        kotlin.math.abs(GAP_STEPS_MS[idx] - intervalMs)
    }

    Text(
        if (intervalMs == 0L) "No gap" else formatExposureLabel(intervalMs),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
    )
    Slider(
        value = nearestIdx.toFloat(),
        onValueChange = { idx ->
            val value = GAP_STEPS_MS[idx.roundToInt().coerceIn(0, GAP_STEPS_MS.lastIndex)]
            vm.setIntervalMs(value)
        },
        valueRange = 0f..(GAP_STEPS_MS.lastIndex).toFloat(),
        steps = (GAP_STEPS_MS.size - 2).coerceAtLeast(0),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0s", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
        Text(formatExposureLabel(GAP_STEPS_MS.last()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f))
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

