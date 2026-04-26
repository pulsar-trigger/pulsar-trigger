/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
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
import kotlin.math.roundToInt

/**
 * Time field shown as `hh : mm : ss`. Each pair (hours, minutes, seconds) is
 * its own horizontal scrub zone — drag left/right to decrement/increment that
 * segment. Release commits the value. Haptic tick on each step crossed.
 *
 * Replaces the old +/- button + numpad TimePicker idiom.
 */
@Composable
fun ScrubField(
    label: String,
    totalMs: Long,
    onChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxHours: Int = 23,
) {
    val hours = ((totalMs / 3_600_000) % (maxHours + 1)).toInt()
    val minutes = ((totalMs % 3_600_000) / 60_000).toInt()
    val seconds = ((totalMs % 60_000) / 1_000).toInt()
    val millis = (totalMs % 1_000).toInt()

    fun recompose(h: Int = hours, m: Int = minutes, s: Int = seconds): Long =
        h * 3_600_000L + m * 60_000L + s * 1_000L + millis

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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ScrubDigit(
                    value = hours,
                    range = 0..maxHours,
                    unitLabel = "h",
                    onChange = { onChanged(recompose(h = it)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Separator()
                ScrubDigit(
                    value = minutes,
                    range = 0..59,
                    unitLabel = "m",
                    onChange = { onChanged(recompose(m = it)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Separator()
                ScrubDigit(
                    value = seconds,
                    range = 0..59,
                    unitLabel = "s",
                    onChange = { onChanged(recompose(s = it)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Integer scrub field. Big number with horizontal drag to change value.
 * Optional preset chips render below for quick jumps.
 *
 * Replaces +/- stepper buttons + numpad popup.
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
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerStep = with(density) { 12.dp.toPx() }

    var dragPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragPx != 0f
    val displayedValue = (value + (dragPx / pxPerStep).roundToInt()).coerceIn(range)

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
                    .pointerInput(enabled, value, range) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { dragPx = 0f },
                            onDragEnd = {
                                if (displayedValue != value) onValueChange(displayedValue)
                                dragPx = 0f
                            },
                            onDragCancel = { dragPx = 0f },
                            onHorizontalDrag = { _, delta ->
                                val before = displayedValue
                                dragPx += delta
                                val after = (value + (dragPx / pxPerStep).roundToInt())
                                    .coerceIn(range)
                                if (after != before) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                        )
                    },
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$displayedValue",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isDragging) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    if (unit != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            unit,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
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
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pxPerStep = with(density) { 16.dp.toPx() }

    var dragPx by remember { mutableFloatStateOf(0f) }
    val isDragging = dragPx != 0f

    val displayedValue = (value + (dragPx / pxPerStep).roundToInt()).coerceIn(range)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .pointerInput(enabled, value, range) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragPx = 0f },
                    onDragEnd = {
                        if (displayedValue != value) onChange(displayedValue)
                        dragPx = 0f
                    },
                    onDragCancel = { dragPx = 0f },
                    onHorizontalDrag = { _, delta ->
                        val before = displayedValue
                        dragPx += delta
                        val after = (value + (dragPx / pxPerStep).roundToInt()).coerceIn(range)
                        if (after != before) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                )
            },
    ) {
        Text(
            "%02d".format(displayedValue),
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
        Spacer(Modifier.height(6.dp))
        TickRuler(
            pixelOffset = if (isDragging) dragPx else 0f,
            anchorActive = isDragging,
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
        )
    }
}

@Composable
private fun Separator() {
    Text(
        ":",
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
