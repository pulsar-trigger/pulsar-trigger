/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Big-numeral scrub field. The whole card is the touch target — drag horizontally
 * to scrub through [presetValues]; release snaps to the nearest preset.
 *
 * Right drag → larger value, left drag → smaller. ~40dp of travel per step.
 */
@Composable
fun ScrubField(
    label: String,
    value: Long,
    presetValues: List<Long>,
    onValueChange: (Long) -> Unit,
    formatValue: (Long) -> Pair<String, String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    require(presetValues.isNotEmpty()) { "presetValues must not be empty" }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val pxPerStep = with(density) { 40.dp.toPx() }

    // Anchor: index of the closest preset to the committed [value]
    val anchorIndex = remember(value, presetValues) {
        presetValues.indexOfMinDistance(value)
    }

    var dragPx by remember { mutableFloatStateOf(0f) }

    val displayedIndex = (anchorIndex + (dragPx / pxPerStep).roundToInt())
        .coerceIn(0, presetValues.lastIndex)
    val displayedValue = presetValues[displayedIndex]

    val (numberText, unitText) = formatValue(displayedValue)

    val isDragging = dragPx != 0f

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(enabled, anchorIndex, presetValues) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { dragPx = 0f },
                    onDragEnd = {
                        if (presetValues[displayedIndex] != value) {
                            onValueChange(presetValues[displayedIndex])
                        }
                        dragPx = 0f
                    },
                    onDragCancel = { dragPx = 0f },
                    onHorizontalDrag = { _, delta ->
                        val before = displayedIndex
                        dragPx += delta
                        val after = (anchorIndex + (dragPx / pxPerStep).roundToInt())
                            .coerceIn(0, presetValues.lastIndex)
                        if (after != before) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                )
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            // Big numeral + unit
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    numberText,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    unitText,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            TickRuler(
                pixelOffset = if (isDragging) dragPx else 0f,
                pxPerStep = pxPerStep,
                anchorActive = isDragging,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
            )
        }
    }
}

@Composable
private fun TickRuler(
    pixelOffset: Float,
    pxPerStep: Float,
    anchorActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val anchorColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val tickSpacingPx = with(density) { 8.dp.toPx() }
    val tickHeightPx = with(density) { 8.dp.toPx() }
    val majorTickHeightPx = with(density) { 16.dp.toPx() }
    val tickStrokePx = with(density) { 1.5.dp.toPx() }
    val anchorStrokePx = with(density) { 3.dp.toPx() }

    // 5 minor ticks per step → major tick every 5th
    val ticksPerStep = (pxPerStep / tickSpacingPx).roundToInt().coerceAtLeast(1)

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val baseY = size.height / 2f

        // Continuous tick offset; modulo so they appear infinite
        val rawOffset = pixelOffset
        val nHalf = (centerX / tickSpacingPx).toInt() + 2

        for (i in -nHalf..nHalf) {
            val rawX = centerX + i * tickSpacingPx + (rawOffset % tickSpacingPx)
            if (rawX < 0f || rawX > size.width) continue

            // Effective tick index (used to pick majors)
            val majorIdx = i + (rawOffset / tickSpacingPx).toInt()
            val isMajor = (majorIdx % ticksPerStep) == 0
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

        // Center anchor
        drawLine(
            color = if (anchorActive) anchorColor else anchorColor.copy(alpha = 0.7f),
            start = Offset(centerX, baseY - majorTickHeightPx),
            end = Offset(centerX, baseY + majorTickHeightPx),
            strokeWidth = anchorStrokePx,
            cap = StrokeCap.Round,
        )
    }
}

/** Index of the preset value closest to [target]. */
private fun List<Long>.indexOfMinDistance(target: Long): Int {
    if (isEmpty()) return -1
    var bestIndex = 0
    var bestDist = Long.MAX_VALUE
    forEachIndexed { i, v ->
        val d = abs(v - target)
        if (d < bestDist) {
            bestDist = d
            bestIndex = i
        }
    }
    return bestIndex
}
