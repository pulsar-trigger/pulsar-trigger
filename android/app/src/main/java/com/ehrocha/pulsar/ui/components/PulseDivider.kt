/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * SIGNAL's divider: where other apps draw a hairline, Pulsar draws a flat
 * trace with one pulse in it — the CP 1919 mark, the app's namesake.
 * The pulse sits off-centre (golden-ratio-ish) like a real chart event.
 */
@Composable
fun PulseDivider(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val pulseColor = PulsarTheme.colors.liveStart.copy(alpha = 0.9f)
    Canvas(modifier = modifier.fillMaxWidth().height(12.dp)) {
        val midY = size.height / 2f
        val w = size.width
        val stroke = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
        // pulse occupies ~14% of the width, centred at 38%
        val pc = w * 0.38f
        val ph = w * 0.07f
        val left = pc - ph
        val right = pc + ph
        // flat trace either side of the pulse
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, midY),
            end = androidx.compose.ui.geometry.Offset(left, midY), strokeWidth = stroke.width, cap = stroke.cap)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(right, midY),
            end = androidx.compose.ui.geometry.Offset(w, midY), strokeWidth = stroke.width, cap = stroke.cap)
        // the pulse: sharp rise, rounded peak, undershoot — a heartbeat of
        // the CP 1919 trace, drawn with a cubic for the organic top
        val amp = size.height * 0.42f
        val path = Path().apply {
            moveTo(left, midY)
            cubicTo(
                pc - ph * 0.35f, midY,
                pc - ph * 0.30f, midY - amp * 2f,
                pc, midY - amp * 2f,
            )
            cubicTo(
                pc + ph * 0.30f, midY - amp * 2f,
                pc + ph * 0.25f, midY + amp * 0.7f,
                pc + ph * 0.55f, midY + amp * 0.5f,
            )
            cubicTo(
                pc + ph * 0.75f, midY + amp * 0.35f,
                pc + ph * 0.85f, midY,
                right, midY,
            )
        }
        drawPath(path, pulseColor, style = stroke)
    }
}
