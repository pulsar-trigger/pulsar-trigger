/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.roundToInt

private enum class AstroTab(val labelRes: Int) {
    LENS(R.string.astro2_tab_lens),
    INTERVAL(R.string.iv2_tab_interval),
    DELAY(R.string.iv2_tab_delay),
    SHOTS(R.string.iv2_tab_shots),
}

private data class SensorOpt(val labelShort: String, val crop: Float)

private val SENSOR_OPTS = listOf(
    SensorOpt("FF", 1.0f),
    SensorOpt("APS-C", 1.5f),
    SensorOpt("APS-C", 1.6f),
    SensorOpt("M4/3", 2.0f),
)

private val FOCAL_DIAL_PRESETS = listOf(8, 14, 24, 35, 50, 85, 105, 135, 200)

/** Which preset is the current value closest to — used by [FocalLengthSlider]
 *  to place the thumb when the user's actual value isn't an exact preset
 *  (e.g. 28 lands between 24 and 35). */
private fun nearestPresetIndex(valueMm: Int, presets: List<Int>): Int {
    if (valueMm <= 0) return -1
    var bestIdx = 0
    var bestDelta = Int.MAX_VALUE
    presets.forEachIndexed { i, p ->
        val d = kotlin.math.abs(p - valueMm)
        if (d < bestDelta) { bestDelta = d; bestIdx = i }
    }
    return bestIdx
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstroMode2Screen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    initialPresetId: String? = null,
) {
    val allModes by vm.userModes.collectAsState()
    val loadedPreset = remember(initialPresetId, allModes) {
        initialPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    var editingPresetId by rememberSaveable { mutableStateOf(initialPresetId) }

    var focalLength by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.focalLength ?: 0)
    }
    var cropFactor by rememberSaveable {
        mutableFloatStateOf(loadedPreset?.body?.cropFactor ?: 1.0f)
    }
    var ruleDivisor by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.ruleDivisor ?: AppConfig.NPF_RULE_DIVISOR)
    }
    var intervalMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.intervalMs ?: 0L)
    }
    var delayMs by rememberSaveable {
        mutableLongStateOf(loadedPreset?.body?.delayMs ?: 0L)
    }
    var shotCount by rememberSaveable {
        mutableIntStateOf(loadedPreset?.body?.shotCount ?: 0)
    }
    var useAutofocus by rememberSaveable {
        mutableStateOf(loadedPreset?.body?.useAutofocus ?: false)
    }
    var showSaveDialog by remember { mutableStateOf(false) }

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current
    val canonCcapiTransport = vm.canonCcapiTransport.collectAsState().value
    val ptpTransport = vm.ptpTransport.collectAsState().value
    val canonBleTransport = vm.canonBleTransport.collectAsState().value
    val onCanon = canonCcapiTransport != null
    val onPtp = ptpTransport != null
    val onCanonBle = canonBleTransport != null
    /** All three Canon transports give us a per-shot AF flag. */
    val canControlAf = onCanon || onPtp || onCanonBle

    // Fetch what lens is on the camera from whichever transport is active —
    // CCAPI (Wi-Fi) or USB PTP. For a *fresh* run (no preset loaded) and a
    // prime lens, auto-fill the focal length. For a loaded preset we leave
    // the saved value alone — the user explicitly picked it — but still
    // surface the detection chip so they know what's mounted.
    var lensInfo by remember {
        mutableStateOf<com.ehrocha.pulsar.transport.LensInfo?>(null)
    }
    LaunchedEffect(canonCcapiTransport, ptpTransport) {
        val t: com.ehrocha.pulsar.transport.CameraTransport =
            canonCcapiTransport ?: ptpTransport ?: return@LaunchedEffect
        if (!t.supportsLensInfo) return@LaunchedEffect
        val info = t.getLensInfo() ?: return@LaunchedEffect
        lensInfo = info
        if (loadedPreset == null && focalLength == 0 && info.focalMm != null) {
            focalLength = info.focalMm
        }
    }

    var tabIdx by rememberSaveable {
        mutableIntStateOf(if (loadedPreset != null) AstroTab.entries.size - 1 else 0)
    }
    val tab = AstroTab.entries[tabIdx]

    val maxExpMs = if (focalLength > 0)
        AppConfig.astroExposureMs(focalLength, cropFactor, ruleDivisor) else 0L
    val continuous = shotCount == 0
    val totalMs = if (continuous) 0L
                  else delayMs + shotCount.toLong() * (maxExpMs + intervalMs) - intervalMs

    val configComplete = focalLength > 0 && intervalMs > 0L
    val currentTabValid = when (tab) {
        AstroTab.LENS -> focalLength > 0
        AstroTab.INTERVAL -> intervalMs > 0L
        AstroTab.DELAY -> true
        AstroTab.SHOTS -> true
    }
    val bottomHint = when {
        tab == AstroTab.LENS && focalLength == 0 -> stringResource(R.string.astro2_set_lens)
        tab == AstroTab.INTERVAL && intervalMs == 0L -> stringResource(R.string.iv2_set_interval)
        tab == AstroTab.SHOTS && continuous && configComplete ->
            stringResource(R.string.iv2_continuous_warning)
        tab == AstroTab.SHOTS && !configComplete ->
            stringResource(R.string.astro2_set_lens_and_interval)
        else -> null
    }
    val wizardWarning = when {
        tab == AstroTab.INTERVAL && intervalMs in 1L..3999L ->
            stringResource(R.string.interval_short_warning)
        else -> null
    }

    val editingPreset = remember(editingPresetId, allModes) {
        editingPresetId?.let { id -> allModes.firstOrNull { it.id == id } }
    }
    val canSave = focalLength > 0 && intervalMs > 0L

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_astro),
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
                tabCount = AstroTab.entries.size,
                currentTabValid = currentTabValid,
                // Always clickable when connected — the action routes to
                // the first missing-field tab if config isn't ready.
                canStart = connected && !running,
                hint = if (running) null else bottomHint,
                hintIsAccent = configComplete && continuous,
                onPrev = { if (tabIdx > 0) tabIdx-- },
                onNext = { if (tabIdx < AstroTab.entries.size - 1) tabIdx++ },
                onStart = {
                    when {
                        focalLength == 0 -> tabIdx = AstroTab.LENS.ordinal
                        intervalMs == 0L -> tabIdx = AstroTab.INTERVAL.ordinal
                        else -> {
                            vm.saveFlowSteps(
                                listOf(
                                    FlowStep.Astro(
                                        focalLength = focalLength,
                                        cropFactor = cropFactor,
                                        ruleDivisor = ruleDivisor,
                                        gapMs = intervalMs,
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
                AstroTab.entries.forEachIndexed { i, t ->
                    Tab(
                        selected = tabIdx == i,
                        onClick = { tabIdx = i },
                        text = { Text(stringResource(t.labelRes)) },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (running) {
                    RunningView(
                        plannedShots = shotCount,
                        exposureMs = maxExpMs,
                        gapMs = intervalMs,
                        startDelayMs = delayMs,
                    )
                    return@Box
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    com.ehrocha.pulsar.ui.components.WizardWarning(
                        wizardWarning,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    when (tab) {
                    AstroTab.LENS -> LensTab(
                        focalLength = focalLength,
                        cropFactor = cropFactor,
                        lensInfo = lensInfo,
                        ruleDivisor = ruleDivisor,
                        maxExpMs = maxExpMs,
                        onFocalChange = { focalLength = it },
                        onCropChange = { cropFactor = it },
                        onRuleChange = { ruleDivisor = it },
                        enabled = !running,
                    )
                    AstroTab.INTERVAL -> SegmentedTimeEditor(
                        ms = intervalMs,
                        onChange = { intervalMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    AstroTab.DELAY -> SegmentedTimeEditor(
                        ms = delayMs,
                        onChange = { delayMs = it },
                        rangeMs = 0L..3_600_000L,
                        enabled = !running,
                    )
                    AstroTab.SHOTS -> Column(
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
                    fwMode = com.ehrocha.pulsar.ble.TriggerMode.ASTRO,
                    intervalMs = intervalMs,
                    shotCount = shotCount,
                    delayMs = delayMs,
                    focalLength = focalLength,
                    cropFactor = cropFactor,
                    ruleDivisor = ruleDivisor,
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

// ── Lens tab: rotary + sensor + rule ─────────────────────────────────────

@Composable
private fun LensTab(
    focalLength: Int,
    cropFactor: Float,
    ruleDivisor: Int,
    maxExpMs: Long,
    lensInfo: com.ehrocha.pulsar.transport.LensInfo?,
    onFocalChange: (Int) -> Unit,
    onCropChange: (Float) -> Unit,
    onRuleChange: (Int) -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Computed-exposure readout
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.astro2_max_exposure_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (focalLength == 0) "—"
            else iv2FormatHmsPretty(maxExpMs).replace(":", "·"),
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
            color = if (focalLength == 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(16.dp))

        FocalLengthSlider(
            valueMm = focalLength,
            onChange = onFocalChange,
            enabled = enabled,
        )

        if (lensInfo != null && lensInfo.mounted) {
            Spacer(Modifier.height(8.dp))
            DetectedLensChip(
                lens = lensInfo,
                currentFocalMm = focalLength,
                enabled = enabled,
                onUseFocal = { onFocalChange(it) },
            )
        } else if (lensInfo != null && !lensInfo.mounted) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.astro2_lens_not_mounted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Sensor format
        Text(
            stringResource(R.string.astro2_sensor_format),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SENSOR_OPTS.forEachIndexed { i, opt ->
                SegmentedButton(
                    selected = cropFactor == opt.crop,
                    onClick = { if (enabled) onCropChange(opt.crop) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = SENSOR_OPTS.size),
                ) {
                    Text("${opt.labelShort}\n${opt.crop}×",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center, maxLines = 2)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Rule
        Text(
            stringResource(R.string.astro2_rule),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val rules = listOf(
                AppConfig.DEFAULT_RULE_DIVISOR to R.string.chip_500_rule,
                AppConfig.TIGHT_RULE_DIVISOR to R.string.chip_400_rule,
                AppConfig.NPF_RULE_DIVISOR to R.string.chip_npf_rule,
            )
            rules.forEachIndexed { i, (rule, labelRes) ->
                SegmentedButton(
                    selected = ruleDivisor == rule,
                    onClick = { if (enabled) onRuleChange(rule) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = rules.size),
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

// ── Rotary focal-length dial ─────────────────────────────────────────────

/**
 * Oven-knob style rotary picker for focal length. Eight presets evenly
 * spaced around the rim; drag anywhere on the dial to rotate the indicator
 * to the nearest preset. Tap the centre to enter an arbitrary value.
 */
/** Chip below the focal-length dial that surfaces the lens reported by the
 *  connected camera. For primes (parsed focal length single value) we offer
 *  a one-tap "Use" button. For zooms we just inform — current zoom position
 *  isn't reported by CCAPI, so the user types the value manually. */
@Composable
private fun DetectedLensChip(
    lens: com.ehrocha.pulsar.transport.LensInfo,
    currentFocalMm: Int,
    enabled: Boolean,
    onUseFocal: (Int) -> Unit,
) {
    val matches = lens.focalMm != null && lens.focalMm == currentFocalMm
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    lens.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        lens.isPrime -> stringResource(
                            R.string.astro2_lens_detected_prime, lens.focalMm!!)
                        lens.isZoom -> stringResource(
                            R.string.astro2_lens_detected_zoom,
                            lens.zoomRangeMm!!.first, lens.zoomRangeMm.last)
                        else -> stringResource(R.string.astro2_lens_detected_unknown)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lens.isPrime && lens.focalMm != null && !matches) {
                TextButton(
                    onClick = { onUseFocal(lens.focalMm) },
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.astro2_lens_use_focal, lens.focalMm))
                }
            }
        }
    }
}

/** Compact focal-length picker. Replaces the 220 dp round dial with a
 *  ~120 dp Slider that snaps to [FOCAL_DIAL_PRESETS], a big tap-to-edit
 *  current-value readout above it, and tick labels under it.
 *
 *  Snap behaviour: the slider's discrete steps map 1:1 to the presets, so
 *  dragging hops 8 → 14 → 24 → 35 → 50 → 85 → 105 → 135 → 200 with haptic
 *  feedback at each landing. For arbitrary values (e.g. 28 or 70 mm) the
 *  user taps the big readout to open the numeric keypad — the slider then
 *  sits at the *nearest* preset position so the visual neighbourhood
 *  stays meaningful. */
@Composable
private fun FocalLengthSlider(
    valueMm: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    var showNumPad by remember { mutableStateOf(false) }
    if (showNumPad) {
        NumPadDialog(
            initialValue = valueMm.toString(),
            onConfirm = { entered ->
                val v = entered.toIntOrNull()
                    ?.coerceIn(AppConfig.MIN_FOCAL_LENGTH, AppConfig.MAX_FOCAL_LENGTH) ?: valueMm
                if (v != valueMm) onChange(v)
                showNumPad = false
            },
            onDismiss = { showNumPad = false },
            maxDigits = 3,
        )
    }

    val n = FOCAL_DIAL_PRESETS.size
    // Slider position = index of the preset the slider thumb sits on. When
    // the current value is non-preset, snap visually to the nearest one so
    // the slider still reads as "you're around this focal length."
    val sliderIdx = remember(valueMm) {
        if (valueMm <= 0) 0
        else nearestPresetIndex(valueMm, FOCAL_DIAL_PRESETS)
    }
    var lastEmittedIdx by remember(valueMm) { mutableIntStateOf(sliderIdx) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Big tap-to-edit current value
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled) { showNumPad = true }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (valueMm <= 0) "—" else "$valueMm",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = if (valueMm <= 0) onSurfaceVariant else onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "mm",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        // Snap-to-preset slider
        Slider(
            value = sliderIdx.toFloat(),
            onValueChange = { f ->
                val idx = f.roundToInt().coerceIn(0, n - 1)
                if (idx != lastEmittedIdx) {
                    lastEmittedIdx = idx
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onChange(FOCAL_DIAL_PRESETS[idx])
                }
            },
            valueRange = 0f..(n - 1).toFloat(),
            // steps = (n - 2) means the slider has (n - 1) discrete stops
            // counting the endpoints — i.e. one stop per preset.
            steps = (n - 2).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )

        // Tick labels under the slider, evenly spaced at the same positions
        // as the slider stops. The Row uses SpaceBetween so labels align with
        // the slider's first / last / interior stops on a constant-width track.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FOCAL_DIAL_PRESETS.forEach { focal ->
                val isCurrent = focal == valueMm
                Text(
                    text = "$focal",
                    fontSize = 11.sp,
                    color = if (isCurrent) primary else onSurfaceVariant,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
