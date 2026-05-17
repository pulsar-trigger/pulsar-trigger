/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Time field. Default layout is `hh : mm : ss` (max 23:59:59). When
 * [subSecond] is true the hours segment is dropped in favour of a tenths
 * column — `mm : ss . t` — for sub-second exposures (firmware accepts down
 * to 10 ms; 100 ms tenths via scrub, finer precision via tap-to-type).
 *
 * Each digit is its own horizontal scrub zone. Tap a digit to open an
 * in-app numpad for exact entry. A subtle range hint under each unit
 * label tells the user the per-segment cap.
 *
 * Velocity-aware: a quick flick accelerates the step rate so the user can
 * traverse large value ranges (e.g. 0 → 47 minutes) in one motion.
 * Hitting a min/max bound delivers a heavier haptic than a tick.
 */
@Composable
fun ScrubField(
    label: String,
    totalMs: Long,
    onChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxHours: Int = 23,
    subSecond: Boolean = false,
    presetsMs: List<Long> = emptyList(),
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (subSecond) SubSecondRow(totalMs, onChanged, enabled)
            else HoursMinutesSecondsRow(totalMs, onChanged, enabled, maxHours)

            if (presetsMs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    presetsMs.forEach { preset ->
                        val selected = totalMs == preset
                        AssistChip(
                            onClick = { if (enabled) onChanged(preset) },
                            label = {
                                Text(
                                    formatPresetMs(preset),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            colors = if (selected) AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) else AssistChipDefaults.assistChipColors(),
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

/** Compact label for a time preset chip: "1/250", "1s", "30s", "2m", "1h". */
private fun formatPresetMs(ms: Long): String = when {
    ms < 1_000 -> {
        // Sub-second: photographic denominator like 1/250
        val denom = (1_000.0 / ms).toInt()
        if (denom > 1) "1/${denom}" else "${ms}ms"
    }
    ms < 60_000 -> "${ms / 1_000}s"
    ms < 3_600_000 -> "${ms / 60_000}m"
    else -> "${ms / 3_600_000}h"
}

/** Common preset sets ready to drop into ScrubField. Tuned for field use. */
object ScrubPresets {
    /** Exposure presets — covers 1/250s up to 30s, the photographer's home turf. */
    val EXPOSURE = listOf(4L, 250L, 1_000L, 5_000L, 30_000L)
    /** Interval / gap presets — what most intervalometer recipes call for. */
    val INTERVAL = listOf(2_000L, 5_000L, 30_000L, 60_000L)
    /** Start-delay presets — defaults the firmware and astro flows tend to want. */
    val DELAY    = listOf(0L, 5_000L, 30_000L, 60_000L)
}

@Composable
private fun HoursMinutesSecondsRow(
    totalMs: Long,
    onChanged: (Long) -> Unit,
    enabled: Boolean,
    maxHours: Int,
) {
    val hours = ((totalMs / 3_600_000) % (maxHours + 1)).toInt()
    val minutes = ((totalMs % 3_600_000) / 60_000).toInt()
    val seconds = ((totalMs % 60_000) / 1_000).toInt()
    val millis = (totalMs % 1_000).toInt()

    fun recompose(h: Int = hours, m: Int = minutes, s: Int = seconds): Long =
        h * 3_600_000L + m * 60_000L + s * 1_000L + millis

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ScrubDigit(
            value = hours, range = 0..maxHours, unitLabel = "h",
            onChange = { onChanged(recompose(h = it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Separator(":")
        ScrubDigit(
            value = minutes, range = 0..59, unitLabel = "m",
            onChange = { onChanged(recompose(m = it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Separator(":")
        ScrubDigit(
            value = seconds, range = 0..59, unitLabel = "s",
            onChange = { onChanged(recompose(s = it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SubSecondRow(
    totalMs: Long,
    onChanged: (Long) -> Unit,
    enabled: Boolean,
) {
    // Min:Sec.tenths layout — caps naturally at 59m 59.9s (firmware MAX is 1 h).
    val minutes = ((totalMs / 60_000) % 60).toInt()
    val seconds = ((totalMs % 60_000) / 1_000).toInt()
    val tenths = (((totalMs % 1_000) + 50) / 100).toInt().coerceIn(0, 9)

    fun recompose(m: Int = minutes, s: Int = seconds, t: Int = tenths): Long =
        m * 60_000L + s * 1_000L + t * 100L

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ScrubDigit(
            value = minutes, range = 0..59, unitLabel = "m",
            onChange = { onChanged(recompose(m = it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Separator(":")
        ScrubDigit(
            value = seconds, range = 0..59, unitLabel = "s",
            onChange = { onChanged(recompose(s = it)) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Separator(".")
        ScrubDigit(
            value = tenths, range = 0..9, unitLabel = "·100ms",
            onChange = { onChanged(recompose(t = it)) },
            enabled = enabled,
            digits = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Integer scrub field. Big number with horizontal drag to change value;
 * tap to type. Optional preset chips render below for quick jumps.
 *
 * Velocity-aware and bound-aware (see ScrubField doc).
 */
@Composable
fun IntScrubField(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    unit: String? = null,
    presets: List<Int> = emptyList(),
    presetLabel: (Int) -> String = { it.toString() },
    /** When non-null, the value 0 is rendered as this label (e.g. "∞").
     *  Must be paired with `range.first == 0` for scrub-to-0 to work. */
    zeroLabel: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerStep = with(density) { 12.dp.toPx() }

    var dragPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragPx != 0f
    val displayedValue = (value + (dragPx / pxPerStep).toInt()).coerceIn(range)

    var showNumPad by remember { mutableStateOf(false) }
    if (showNumPad) {
        NumPadDialog(
            initialValue = value.toString(),
            onConfirm = { entered ->
                val v = entered.toIntOrNull()?.coerceIn(range) ?: value
                if (v != value) onValueChange(v)
                showNumPad = false
            },
            onDismiss = { showNumPad = false },
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { showNumPad = true }
                    .pointerInput(enabled, value, range) {
                        if (!enabled) return@pointerInput
                        velocityScrub(
                            stepPx = pxPerStep,
                            valueProvider = { value },
                            range = range,
                            onDragPxChange = { dragPx = it },
                            onCommit = { committed ->
                                if (committed != value) onValueChange(committed)
                            },
                            onTickCrossed = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                            onBoundHit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        )
                    },
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val isZeroSpecial = zeroLabel != null && displayedValue == 0
                    Text(
                        if (isZeroSpecial) zeroLabel!! else "$displayedValue",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isDragging) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    if (unit != null && !isZeroSpecial) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            unit,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                val rangeFirstLabel = if (zeroLabel != null && range.first == 0) zeroLabel
                                      else "${range.first}"
                Text(
                    "$rangeFirstLabel–${range.last}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TickRuler(
                    pixelOffset = if (isDragging) dragPx else 0f,
                    anchorActive = isDragging,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                )
            }

            if (presets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    presets.forEach { preset ->
                        val selected = preset == value
                        AssistChip(
                            onClick = {
                                if (enabled && preset in range) onValueChange(preset)
                            },
                            label = { Text(presetLabel(preset), style = MaterialTheme.typography.labelMedium) },
                            colors = if (selected) AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) else AssistChipDefaults.assistChipColors(),
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrubDigit(
    value: Int,
    range: IntRange,
    unitLabel: String,
    onChange: (Int) -> Unit,
    enabled: Boolean,
    digits: Int = 2,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerStep = with(density) { 16.dp.toPx() }

    var dragPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragPx != 0f
    val displayedValue = (value + (dragPx / pxPerStep).toInt()).coerceIn(range)

    var showNumPad by remember { mutableStateOf(false) }
    if (showNumPad) {
        NumPadDialog(
            initialValue = value.toString(),
            onConfirm = { entered ->
                val v = entered.toIntOrNull()?.coerceIn(range) ?: value
                if (v != value) onChange(v)
                showNumPad = false
            },
            onDismiss = { showNumPad = false },
            maxDigits = digits.coerceAtLeast(1),
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(enabled = enabled) { showNumPad = true }
            .pointerInput(enabled, value, range) {
                if (!enabled) return@pointerInput
                velocityScrub(
                    stepPx = pxPerStep,
                    valueProvider = { value },
                    range = range,
                    onDragPxChange = { dragPx = it },
                    onCommit = { committed ->
                        if (committed != value) onChange(committed)
                    },
                    onTickCrossed = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    onBoundHit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                )
            },
    ) {
        Text(
            "%0${digits}d".format(displayedValue),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (isDragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            unitLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${range.first}–${range.last}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(4.dp))
        TickRuler(
            pixelOffset = if (isDragging) dragPx else 0f,
            anchorActive = isDragging,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        )
    }
}

/**
 * Shared velocity-aware drag handler. Pixel-distance scaled by recent
 * velocity (fast flick = up to 8× the base step rate), so the user can
 * traverse a 0–999 range in one motion when they fling but still hit
 * single-unit precision on a slow drag. Bound-hit fires onBoundHit
 * exactly once per arrival at min or max.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.velocityScrub(
    stepPx: Float,
    valueProvider: () -> Int,
    range: IntRange,
    onDragPxChange: (Float) -> Unit,
    onCommit: (Int) -> Unit,
    onTickCrossed: () -> Unit,
    onBoundHit: () -> Unit,
) {
    var dragPx = 0f
    var lastTickValue = valueProvider()
    var lastDragMs = 0L
    var atBound = false

    fun currentValue(): Int {
        val raw = valueProvider() + (dragPx / stepPx).toInt()
        return raw.coerceIn(range)
    }

    detectHorizontalDragGestures(
        onDragStart = {
            dragPx = 0f
            onDragPxChange(0f)
            lastTickValue = valueProvider()
            lastDragMs = System.currentTimeMillis()
            atBound = false
        },
        onDragEnd = {
            onCommit(currentValue())
            dragPx = 0f
            onDragPxChange(0f)
        },
        onDragCancel = {
            dragPx = 0f
            onDragPxChange(0f)
        },
        onHorizontalDrag = { _, delta ->
            // Velocity-aware: pixels per millisecond determines a multiplier
            // in [1, 8]. A leisurely 1 dp/ms drag stays 1×; a fling of
            // 4+ dp/ms reaches the cap. This is cooperative with the
            // existing pxPerStep — accelerated motion just covers more
            // ticks per millimetre of finger travel.
            val now = System.currentTimeMillis()
            val dtMs = (now - lastDragMs).coerceAtLeast(1).toInt()
            lastDragMs = now
            val pxPerMs = abs(delta) / dtMs
            val accel = when {
                pxPerMs >= 4.0f -> 8.0f
                pxPerMs >= 2.0f -> 4.0f
                pxPerMs >= 1.0f -> 2.0f
                else -> 1.0f
            }
            dragPx += delta * accel
            onDragPxChange(dragPx)

            val v = currentValue()
            if (v != lastTickValue) {
                onTickCrossed()
                lastTickValue = v
            }
            val hitBound = v == range.first || v == range.last
            if (hitBound && !atBound) {
                onBoundHit()
                atBound = true
            } else if (!hitBound) {
                atBound = false
            }
        },
    )
}

@Composable
private fun Separator(symbol: String) {
    Text(
        symbol,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp),
    )
}

@Composable
private fun TickRuler(
    pixelOffset: Float,
    anchorActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val anchorColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val tickSpacingPx = with(density) { 6.dp.toPx() }
    val tickHeightPx = with(density) { 6.dp.toPx() }
    val majorTickHeightPx = with(density) { 12.dp.toPx() }
    val tickStrokePx = with(density) { 1.2.dp.toPx() }
    val anchorStrokePx = with(density) { 2.4.dp.toPx() }

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val baseY = size.height / 2f
        val nHalf = (centerX / tickSpacingPx).toInt() + 2

        for (i in -nHalf..nHalf) {
            val rawX = centerX + i * tickSpacingPx + (pixelOffset % tickSpacingPx)
            if (rawX < 0f || rawX > size.width) continue

            val majorIdx = i + (pixelOffset / tickSpacingPx).toInt()
            val isMajor = (majorIdx % 5) == 0
            val h = if (isMajor) majorTickHeightPx else tickHeightPx
            val distFromCenter = abs(rawX - centerX) / centerX
            val alpha = (1f - distFromCenter * 0.85f).coerceAtLeast(0.15f)

            drawLine(
                color = tickColor.copy(alpha = alpha),
                start = Offset(rawX, baseY - h / 2f),
                end = Offset(rawX, baseY + h / 2f),
                strokeWidth = tickStrokePx,
                cap = StrokeCap.Round,
            )
        }

        drawLine(
            color = if (anchorActive) anchorColor else anchorColor.copy(alpha = 0.6f),
            start = Offset(centerX, baseY - majorTickHeightPx),
            end = Offset(centerX, baseY + majorTickHeightPx),
            strokeWidth = anchorStrokePx,
            cap = StrokeCap.Round,
        )
    }
}
