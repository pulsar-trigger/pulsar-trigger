/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

private val FOCAL_DIAL_PRESETS = listOf(14, 24, 35, 50, 85, 105, 135, 200)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AstroMode2Screen(vm: PulsarViewModel, onBack: () -> Unit) {
    var focalLength by rememberSaveable { mutableIntStateOf(0) }
    var cropFactor by rememberSaveable { mutableFloatStateOf(1.0f) }
    var ruleDivisor by rememberSaveable { mutableIntStateOf(AppConfig.NPF_RULE_DIVISOR) }
    var intervalMs by rememberSaveable { mutableLongStateOf(0L) }
    var delayMs by rememberSaveable { mutableLongStateOf(0L) }
    var shotCount by rememberSaveable { mutableIntStateOf(0) }

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current

    var tabIdx by rememberSaveable { mutableIntStateOf(0) }
    val tab = AstroTab.entries[tabIdx]

    val maxExpMs = if (focalLength > 0)
        AppConfig.astroExposureMs(focalLength, cropFactor, ruleDivisor) else 0L
    val continuous = shotCount == 0
    val totalMs = if (continuous) 0L
                  else delayMs + shotCount.toLong() * (maxExpMs + intervalMs) - intervalMs

    val configComplete = focalLength > 0 && intervalMs > 0L
    val bottomHint = when {
        !configComplete && focalLength == 0 && intervalMs == 0L ->
            stringResource(R.string.astro2_set_lens_and_interval)
        !configComplete && focalLength == 0 -> stringResource(R.string.astro2_set_lens)
        !configComplete && intervalMs == 0L -> stringResource(R.string.iv2_set_interval)
        configComplete && continuous -> stringResource(R.string.iv2_continuous_warning)
        else -> null
    }

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.mode_astro_2),
                onBack = onBack,
            )
        },
        bottomBar = {
            BottomBar(
                running = running,
                canStart = connected && !running && configComplete,
                hint = if (running) null else bottomHint,
                hintIsAccent = configComplete && continuous,
                onStart = {
                    vm.saveFlowSteps(
                        listOf(
                            FlowStep.Astro(
                                focalLength = focalLength,
                                cropFactor = cropFactor,
                                ruleDivisor = ruleDivisor,
                                gapMs = intervalMs,
                                shotCount = shotCount,
                                delayMs = delayMs,
                            )
                        )
                    )
                    vm.startFlow()
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
                when (tab) {
                    AstroTab.LENS -> LensTab(
                        focalLength = focalLength,
                        cropFactor = cropFactor,
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
                    AstroTab.SHOTS -> ShotsEditor(
                        value = shotCount,
                        onChange = { shotCount = it },
                        enabled = !running,
                    )
                }
            }

            SummaryStrip(
                shotCount = shotCount,
                continuous = continuous,
                totalMs = totalMs,
            )
        }
    }
}

// ── Lens tab: rotary + sensor + rule ─────────────────────────────────────

@Composable
private fun LensTab(
    focalLength: Int,
    cropFactor: Float,
    ruleDivisor: Int,
    maxExpMs: Long,
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

        FocalLengthDial(
            valueMm = focalLength,
            onChange = onFocalChange,
            enabled = enabled,
        )

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
@Composable
private fun FocalLengthDial(
    valueMm: Int,
    onChange: (Int) -> Unit,
    enabled: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val sizeDp = 220.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val centerPx = sizePx / 2f
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val container = MaterialTheme.colorScheme.surfaceContainerHigh

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

    var lastSelected by remember(valueMm) { mutableIntStateOf(valueMm) }
    val n = FOCAL_DIAL_PRESETS.size
    val stepDeg = 360.0 / n

    Box(
        modifier = Modifier
            .size(sizeDp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { /* no-op */ },
                    onDrag = { change, _ ->
                        val dx = change.position.x - centerPx
                        val dy = change.position.y - centerPx
                        var angleDeg = Math.toDegrees(
                            atan2(dy.toDouble(), dx.toDouble()) + PI / 2
                        )
                        if (angleDeg < 0) angleDeg += 360.0
                        val idx = (((angleDeg + stepDeg / 2) / stepDeg).toInt()) % n
                        val newVal = FOCAL_DIAL_PRESETS[idx]
                        if (newVal != lastSelected) {
                            lastSelected = newVal
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChange(newVal)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val ringR = r - with(density) { 18.dp.toPx() }

            // Background ring
            drawCircle(
                color = container,
                radius = ringR,
                style = Stroke(width = with(density) { 28.dp.toPx() }),
            )

            val tickInset = with(density) { 12.dp.toPx() }
            val tickOutset = with(density) { 14.dp.toPx() }
            val selectedIdx = FOCAL_DIAL_PRESETS.indexOf(valueMm)

            for (i in 0 until n) {
                val rad = -PI / 2 + i * (2 * PI / n)
                val isSelected = i == selectedIdx
                drawLine(
                    color = if (isSelected) primary else outline,
                    start = Offset(
                        center.x + (ringR - tickInset) * cos(rad).toFloat(),
                        center.y + (ringR - tickInset) * sin(rad).toFloat(),
                    ),
                    end = Offset(
                        center.x + (ringR + tickOutset) * cos(rad).toFloat(),
                        center.y + (ringR + tickOutset) * sin(rad).toFloat(),
                    ),
                    strokeWidth = if (isSelected)
                        with(density) { 6.dp.toPx() } else with(density) { 2.dp.toPx() },
                )
            }

            // Indicator dot at selected
            if (selectedIdx >= 0) {
                val rad = -PI / 2 + selectedIdx * (2 * PI / n)
                drawCircle(
                    color = primary,
                    radius = with(density) { 8.dp.toPx() },
                    center = Offset(
                        center.x + ringR * cos(rad).toFloat(),
                        center.y + ringR * sin(rad).toFloat(),
                    ),
                )
            }
        }

        // Preset labels around the rim
        val density2 = LocalDensity.current
        FOCAL_DIAL_PRESETS.forEachIndexed { i, focal ->
            val rad = -PI / 2 + i * (2 * PI / n)
            // Place label OUTSIDE the ring
            val labelRadiusPx = with(density2) { (sizeDp.toPx() / 2f) + 4.dp.toPx() }
            val xPx = labelRadiusPx * cos(rad).toFloat()
            val yPx = labelRadiusPx * sin(rad).toFloat()
            Text(
                text = "${focal}",
                fontSize = 12.sp,
                color = if (focal == valueMm) primary else onSurfaceVariant,
                fontWeight = if (focal == valueMm) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = with(density2) { xPx.toDp() },
                        y = with(density2) { yPx.toDp() },
                    ),
            )
        }

        // Centre: current value, tap to enter arbitrary
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled) { showNumPad = true },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (valueMm == 0) "—" else "$valueMm",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    color = if (valueMm == 0) onSurfaceVariant else onSurface,
                )
                Text(
                    "mm",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant,
                )
            }
        }
    }
}
