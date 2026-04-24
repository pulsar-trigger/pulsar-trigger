/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.ShutterSpeed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.TriggerMode
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

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Initialize camera with preview; release on exit
    LaunchedEffect(Unit) {
        cameraManager.initialize(lifecycleOwner, previewView)
    }
    DisposableEffect(Unit) {
        onDispose { cameraManager.release() }
    }

    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by remember { mutableStateOf(false) }

    // Intervalometer params (for running overlay)
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
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
            )
        }

        // ── Bottom bar (non-overlapping) ────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isRunning && status != null) {
                RunningBar(
                    status = status,
                    totalShots = shotCount,
                    onStop = { vm.stop() },
                )
            } else {
                SetupBar(
                    onOpenSheet = { showSheet = true },
                    onStart = {
                        vm.selectMode(TriggerMode.INTERVALOMETER)
                        vm.loadQuickMode(FlowStepType.INTERVALOMETER)
                        vm.startFlow()
                    },
                )
            }
        }
    }

    // ── Pull-up bottom sheet for full controls ──────────────────────
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            SetupControls(
                vm = vm,
                onStart = {
                    showSheet = false
                    vm.selectMode(TriggerMode.INTERVALOMETER)
                    vm.loadQuickMode(FlowStepType.INTERVALOMETER)
                    vm.startFlow()
                },
            )
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

/** Compact bottom bar when idle — settings button + start button. */
@Composable
private fun SetupBar(
    onOpenSheet: () -> Unit,
    onStart: () -> Unit,
) {
    Row(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onOpenSheet,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.camera_parameters))
        }
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                stringResource(R.string.btn_start),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
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

// ── Setup controls (bottom panel) ───────────────────────────────────────

@Composable
private fun SetupControls(
    vm: PulsarViewModel,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Intervalometer parameters ───────────────────────────
        IntervalometerPanel(vm, enabled = true)

        // ── Start button ────────────────────────────────────────
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

// ── Camera panel enum ───────────────────────────────────────────────────

private enum class CameraPanel { LENS, ISO, SHUTTER, FOCUS }

// ── Camera controls strip (right-side icons + expandable panels) ────────

@Composable
private fun CameraControlsStrip(
    cameraManager: PhoneCameraManager,
    lenses: List<com.ehrocha.pulsar.camera.PhoneLens>,
    selectedLens: Int,
    caps: LensCapabilities,
    activePanel: CameraPanel?,
    onPanelToggle: (CameraPanel) -> Unit,
    onLensSelected: (Int) -> Unit,
    onShowDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manualIso by cameraManager.manualIso.collectAsState()
    val manualExpNs by cameraManager.manualExposureNs.collectAsState()
    val manualFocus by cameraManager.manualFocusDist.collectAsState()
    val currentLens = lenses.getOrNull(selectedLens)

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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Focus", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.weight(1f))
        Surface(
            onClick = {
                if (isManualFocus) cameraManager.setManualFocusDist(null)
                else cameraManager.setManualFocusDist(0f)
            },
            color = if (isManualFocus) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                if (isManualFocus) "Manual" else "Auto",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }

    if (isManualFocus && caps.minFocusDistance > 0f) {
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

        // Star auto-focus button
        Surface(
            onClick = { scope.launch { cameraManager.starAutoFocus() } },
            color = if (starFocusRunning) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp),
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

