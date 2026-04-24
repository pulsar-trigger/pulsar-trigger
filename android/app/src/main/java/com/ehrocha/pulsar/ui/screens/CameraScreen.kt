/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

    // Mode selection state
    var selectedMode by remember { mutableStateOf(TriggerMode.INTERVALOMETER) }

    // Exit confirmation while running
    var showExitDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = isRunning) { showExitDialog = true }

    Column(Modifier.fillMaxSize()) {
        // ── Camera preview (~80% of screen) ─────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(4f),  // 80% ratio with bottom weight of 1f
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
                    totalShots = getTotalShots(vm, selectedMode),
                    exposureMs = getExposureMs(vm, selectedMode),
                    gapMs = getGapMs(vm, selectedMode),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            }

            // Camera info + lens picker at bottom of preview
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            ) {
                // Camera info overlay
                val currentLens = lenses.getOrNull(selectedLens)
                if (currentLens != null && (currentLens.aperture > 0 || currentLens.megapixels > 0)) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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

                // Lens picker pills
                if (lenses.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }

        // ── Bottom controls ─────────────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (isRunning && status != null) {
                RunningControls(
                    status = status,
                    totalShots = getTotalShots(vm, selectedMode),
                    exposureMs = getExposureMs(vm, selectedMode),
                    gapMs = getGapMs(vm, selectedMode),
                    onStop = { vm.stop() },
                )
            } else {
                SetupControls(
                    vm = vm,
                    cameraManager = cameraManager,
                    selectedMode = selectedMode,
                    onModeChanged = { selectedMode = it },
                    onStart = {
                        val flowType = when (selectedMode) {
                            TriggerMode.INTERVALOMETER -> FlowStepType.INTERVALOMETER
                            TriggerMode.ASTRO -> FlowStepType.ASTRO
                            TriggerMode.DARK_FRAME -> FlowStepType.DARK_FRAME
                            TriggerMode.RAMP -> FlowStepType.RAMP
                            else -> return@SetupControls
                        }
                        vm.selectMode(selectedMode)
                        vm.loadQuickMode(flowType)
                        vm.startFlow()
                    },
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
}

// ── Setup controls (bottom panel) ───────────────────────────────────────

@Composable
private fun SetupControls(
    vm: PulsarViewModel,
    cameraManager: PhoneCameraManager,
    selectedMode: TriggerMode,
    onModeChanged: (TriggerMode) -> Unit,
    onStart: () -> Unit,
) {
    val lenses by cameraManager.lenses.collectAsState()
    val selectedLens by cameraManager.selectedLens.collectAsState()
    val currentLens = lenses.getOrNull(selectedLens)
    val caps = currentLens?.capabilities ?: LensCapabilities()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Camera controls (ISO, shutter, focus) ───────────────
        CameraControlsSection(cameraManager, caps)

        // ── Mode selector chips ─────────────────────────────────
        ModeChips(selectedMode = selectedMode, onModeChanged = onModeChanged)

        // ── Mode-specific parameters ────────────────────────────
        ModeParametersSection(vm = vm, selectedMode = selectedMode)

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

// ── Mode selector ───────────────────────────────────────────────────────

private data class ModeOption(
    val mode: TriggerMode,
    val labelRes: Int,
)

private val CAMERA_MODES = listOf(
    ModeOption(TriggerMode.INTERVALOMETER, R.string.mode_intervalometer),
    ModeOption(TriggerMode.ASTRO, R.string.mode_astro),
    ModeOption(TriggerMode.DARK_FRAME, R.string.mode_dark_frame),
    ModeOption(TriggerMode.RAMP, R.string.mode_ramp),
)

@Composable
private fun ModeChips(selectedMode: TriggerMode, onModeChanged: (TriggerMode) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CAMERA_MODES.forEach { option ->
            FilterChip(
                selected = selectedMode == option.mode,
                onClick = { onModeChanged(option.mode) },
                label = {
                    Text(
                        stringResource(option.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Mode parameters (collapsible) ───────────────────────────────────────

@Composable
private fun ModeParametersSection(vm: PulsarViewModel, selectedMode: TriggerMode) {
    var expanded by remember { mutableStateOf(true) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.camera_parameters),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (expanded) stringResource(R.string.btn_collapse)
                        else stringResource(R.string.btn_expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    when (selectedMode) {
                        TriggerMode.INTERVALOMETER -> IntervalometerPanel(vm, enabled = true)
                        TriggerMode.ASTRO -> AstroPanel(vm, enabled = true)
                        TriggerMode.DARK_FRAME -> DarkFramePanel(vm, enabled = true)
                        TriggerMode.RAMP -> RampPanel(vm, enabled = true)
                        else -> {}
                    }
                }
            }
        }
    }
}

// ── Camera controls (ISO, shutter speed, focus) ─────────────────────────

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

@Composable
private fun CameraControlsSection(
    cameraManager: PhoneCameraManager,
    caps: LensCapabilities,
) {
    val hasAnyControl = caps.supportsManualExposure || caps.supportsManualFocus
    if (!hasAnyControl) return

    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.camera_controls),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (expanded) stringResource(R.string.btn_collapse)
                        else stringResource(R.string.btn_expand),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (caps.supportsManualExposure) {
                        ExposureControls(cameraManager, caps)
                    }
                    if (caps.supportsManualFocus) {
                        FocusControls(cameraManager, caps)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun ExposureControls(
    cameraManager: PhoneCameraManager,
    caps: LensCapabilities,
) {
    val manualIso by cameraManager.manualIso.collectAsState()
    val manualExpNs by cameraManager.manualExposureNs.collectAsState()
    val isManual = manualIso != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.label_exposure_mode),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !isManual,
                onClick = {
                    cameraManager.setManualIso(null)
                    cameraManager.setManualExposureNs(null)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.exposure_auto))
            }
            SegmentedButton(
                selected = isManual,
                onClick = {
                    val isoRange = caps.isoRange ?: return@SegmentedButton
                    cameraManager.setManualIso((isoRange.lower + isoRange.upper) / 2)
                    cameraManager.setManualExposureNs(1_000_000_000L)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.exposure_manual))
            }
        }
    }

    if (isManual && caps.isoRange != null && caps.exposureTimeRange != null) {
        val isoRange = caps.isoRange!!
        Text(
            stringResource(R.string.label_iso, manualIso ?: isoRange.lower),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = (manualIso ?: isoRange.lower).toFloat(),
            onValueChange = { cameraManager.setManualIso(it.roundToInt()) },
            valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${isoRange.lower}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${isoRange.upper}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(4.dp))

        val expRange = caps.exposureTimeRange!!
        val validSteps = SHUTTER_STEPS_NS.filter { it in expRange.lower..expRange.upper }
        if (validSteps.isNotEmpty()) {
            val currentNs = manualExpNs ?: 1_000_000_000L
            val nearestIdx = validSteps.indices.minBy { idx ->
                kotlin.math.abs(validSteps[idx] - currentNs)
            }

            Text(
                stringResource(R.string.label_shutter_speed, formatShutterSpeed(validSteps[nearestIdx])),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = nearestIdx.toFloat(),
                onValueChange = { idx ->
                    val step = validSteps[idx.roundToInt().coerceIn(0, validSteps.lastIndex)]
                    cameraManager.setManualExposureNs(step)
                },
                valueRange = 0f..(validSteps.lastIndex).toFloat(),
                steps = (validSteps.size - 2).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatShutterSpeed(validSteps.first()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatShutterSpeed(validSteps.last()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FocusControls(
    cameraManager: PhoneCameraManager,
    caps: LensCapabilities,
) {
    val manualFocus by cameraManager.manualFocusDist.collectAsState()
    val isManualFocus = manualFocus != null
    val starFocusRunning by cameraManager.starFocusRunning.collectAsState()
    val starFocusProgress by cameraManager.starFocusProgress.collectAsState()
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(R.string.label_focus_mode),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = !isManualFocus,
                onClick = { cameraManager.setManualFocusDist(null) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.focus_auto))
            }
            SegmentedButton(
                selected = isManualFocus,
                onClick = { cameraManager.setManualFocusDist(0f) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.focus_manual))
            }
        }
    }

    if (isManualFocus && caps.minFocusDistance > 0f) {
        val currentDist = manualFocus ?: 0f
        Text(
            stringResource(R.string.label_focus_distance, formatFocusDistance(currentDist)),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = currentDist,
            onValueChange = { cameraManager.setManualFocusDist(it) },
            valueRange = 0f..caps.minFocusDistance,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("\u221E", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatFocusDistance(caps.minFocusDistance), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = { scope.launch { cameraManager.starAutoFocus() } },
            enabled = !starFocusRunning,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (starFocusRunning) stringResource(R.string.star_focus_running)
                else stringResource(R.string.star_focus),
            )
        }

        if (starFocusRunning) {
            LinearProgressIndicator(
                progress = { starFocusProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

// ── Running controls ────────────────────────────────────────────────────

@Composable
private fun RunningControls(
    status: com.ehrocha.pulsar.ble.StatusFrame,
    totalShots: Int,
    exposureMs: Long,
    gapMs: Long,
    onStop: () -> Unit,
) {
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
    val smoothExposureProgress by animateFloatAsState(exposureProgress, tween(300), label = "expProg")
    val smoothWaitProgress by animateFloatAsState(waitProgress, tween(300), label = "waitProg")

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
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // State badge + shot counter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = stateColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (status.state) {
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

            Text(
                "$displayShots / $totalShots",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Progress bars
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.state_exposing),
                    style = MaterialTheme.typography.labelSmall,
                    color = ExposureGreen,
                    modifier = Modifier.width(56.dp),
                )
                LinearProgressIndicator(
                    progress = { smoothExposureProgress },
                    modifier = Modifier.weight(1f).height(4.dp),
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
                    modifier = Modifier.width(56.dp),
                )
                LinearProgressIndicator(
                    progress = { smoothWaitProgress },
                    modifier = Modifier.weight(1f).height(4.dp),
                    color = WaitingYellow,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }

        // Time remaining + stop
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (liveRemainingMs > 0) {
                Text(
                    formatTimeRemaining(liveRemainingMs),
                    style = MaterialTheme.typography.titleMedium,
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

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
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

// ── Helpers ─────────────────────────────────────────────────────────────

/** Get total shots for the currently selected mode. */
@Composable
private fun getTotalShots(vm: PulsarViewModel, mode: TriggerMode): Int {
    return when (mode) {
        TriggerMode.INTERVALOMETER -> vm.shotCount.collectAsState().value
        TriggerMode.ASTRO -> vm.astroShotCount.collectAsState().value
        TriggerMode.DARK_FRAME -> vm.darkFrameCount.collectAsState().value
        TriggerMode.RAMP -> vm.rampSteps.collectAsState().value
        else -> 0
    }
}

/** Get exposure duration (ms) for the currently selected mode. */
@Composable
private fun getExposureMs(vm: PulsarViewModel, mode: TriggerMode): Long {
    return when (mode) {
        TriggerMode.INTERVALOMETER -> vm.exposureMs.collectAsState().value
        TriggerMode.ASTRO -> vm.exposureMs.collectAsState().value
        TriggerMode.DARK_FRAME -> vm.darkFrameExposureMs.collectAsState().value
        TriggerMode.RAMP -> vm.rampStartExposureMs.collectAsState().value
        else -> 0L
    }
}

/** Get gap/interval duration (ms) for the currently selected mode. */
@Composable
private fun getGapMs(vm: PulsarViewModel, mode: TriggerMode): Long {
    return when (mode) {
        TriggerMode.INTERVALOMETER -> vm.intervalMs.collectAsState().value
        TriggerMode.ASTRO -> vm.astroGapMs.collectAsState().value
        TriggerMode.DARK_FRAME -> vm.darkFrameGapMs.collectAsState().value
        TriggerMode.RAMP -> vm.rampIntervalMs.collectAsState().value
        else -> 0L
    }
}

private fun formatTimeRemaining(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
