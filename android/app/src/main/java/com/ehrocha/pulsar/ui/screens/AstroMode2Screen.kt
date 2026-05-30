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

/** Map a continuous slider position (in segments — 0 = first preset, n-1 =
 *  last) to an interpolated focal length in mm. Mirrors the old dial's
 *  segment-interpolation behaviour so users can land on in-between values
 *  like 11 / 16 / 28 / 70 / 145 mm without opening the numeric keypad. */
private fun sliderValueToFocal(sliderValue: Float, presets: List<Int>): Int {
    val n = presets.size
    if (n < 2) return presets.firstOrNull() ?: 0
    val clamped = sliderValue.coerceIn(0f, (n - 1).toFloat())
    val segIdx = clamped.toInt().coerceAtMost(n - 2)
    val frac = clamped - segIdx
    val a = presets[segIdx]
    val b = presets[segIdx + 1]
    return (a + (b - a) * frac).roundToInt()
}

/** Inverse — where does [valueMm] sit on the slider in segment coordinates? */
private fun focalToSliderValue(valueMm: Int, presets: List<Int>): Float {
    val n = presets.size
    if (valueMm <= presets.first()) return 0f
    if (valueMm >= presets.last()) return (n - 1).toFloat()
    for (i in 0 until n - 1) {
        if (valueMm in presets[i]..presets[i + 1]) {
            val frac = (valueMm - presets[i]).toFloat() / (presets[i + 1] - presets[i])
            return i + frac
        }
    }
    return 0f
}

/** Which preset is the current value closest to — used by [FocalLengthSlider]
 *  for haptic feedback as the user drags across preset boundaries. */
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

        if (focalLength > 0) {
            Spacer(Modifier.height(12.dp))
            TrailPredictor(focalLength = focalLength, cropFactor = cropFactor)
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** Compact 4-column readout that shows max exposure (host-timed seconds)
 *  before stars trail by 1, 3, 5, or 10 pixels. Pure geometry via
 *  [AppConfig.astroTrailExposureS] — works for any rule selection (it
 *  complements them rather than replacing). Hidden when focal length is
 *  unset; only renders when there's a valid focal value. */
@Composable
private fun TrailPredictor(focalLength: Int, cropFactor: Float) {
    val trailLengths = listOf(1, 3, 5, 10)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.astro2_trail_predictor),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.astro2_trail_predictor_caption),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                trailLengths.forEach { trailPx ->
                    val t = AppConfig.astroTrailExposureS(focalLength, cropFactor, trailPx)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.astro2_trail_px, trailPx),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            formatTrailExposure(t),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Format an exposure in seconds for the trail-predictor cells. Sub-second
 *  values get one decimal; whole-second values from 10 s up are integer-only
 *  to stay narrow inside the 4-column row. */
private fun formatTrailExposure(seconds: Double): String = when {
    seconds <= 0.0 -> "—"
    seconds < 10.0 -> "%.1fs".format(seconds)
    else -> "${seconds.roundToInt()}s"
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
 *  ~120 dp Slider that interpolates between [FOCAL_DIAL_PRESETS], a big
 *  tap-to-edit current-value readout above it, and tick labels under it.
 *
 *  Slider is **continuous, segment-mapped** — each pair of adjacent presets
 *  occupies an equal slice of the track and the slider interpolates linearly
 *  between them. So users can land on intermediate values like 11 / 16 / 28
 *  / 70 / 145 mm just by dragging between the marked positions; the numeric
 *  keypad (tap the big readout) is only needed for values outside the preset
 *  range. Haptic fires each time the thumb crosses into a different preset
 *  neighbourhood, so the preset rhythm of the old dial is preserved. */
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
    val sliderValue = remember(valueMm) {
        focalToSliderValue(valueMm.coerceAtLeast(0), FOCAL_DIAL_PRESETS)
    }
    // Last preset neighbourhood the user was in — fires haptic when crossing
    // into a different one (preserves the dial's preset-snap feel without
    // forcing snap-to-preset values).
    var lastPresetIdx by remember {
        mutableIntStateOf(
            if (valueMm > 0) nearestPresetIndex(valueMm, FOCAL_DIAL_PRESETS) else 0
        )
    }

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

        // Continuous slider — segment-mapped to presets so each preset
        // occupies equal track width while in-between values are reachable.
        Slider(
            value = sliderValue,
            onValueChange = { f ->
                val newFocal = sliderValueToFocal(f, FOCAL_DIAL_PRESETS)
                if (newFocal != valueMm) {
                    val newPresetIdx = nearestPresetIndex(newFocal, FOCAL_DIAL_PRESETS)
                    if (newPresetIdx != lastPresetIdx) {
                        lastPresetIdx = newPresetIdx
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onChange(newFocal)
                }
            },
            valueRange = 0f..(n - 1).toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )

        // Tick labels under the slider, evenly spaced at the same positions
        // the slider's segment-mapping uses. SpaceBetween + matching outer
        // padding aligns each label with its slider-track position.
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
