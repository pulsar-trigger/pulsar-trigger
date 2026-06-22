/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ui.theme.PulsarTheme

// Shared session-stats graphics — used by both the dashboard SessionHistoryCard
// and the full ShotLog screen so the two can't visually drift.

/** A labelled stat (big value over a small caption). */
@Composable
internal fun SessionStat(label: String, value: String) {
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One mode-usage row — name, run count, and a bar scaled to the most-used mode. */
@Composable
internal fun ModeUsageBar(name: String, count: Int, maxCount: Int) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$count",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((count.toFloat() / maxCount).coerceIn(0.06f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Success-rate gauge — a ring coloured by quality (green/amber/red, the same
 *  honest model as the Sky Dial) with the % in the centre. Stroke scales with
 *  the ring so it reads at both the card (small) and the screen header (large). */
@Composable
internal fun SuccessRing(pct: Int, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = when {
        pct >= 80 -> PulsarTheme.colors.positive
        pct >= 50 -> PulsarTheme.colors.caution
        else -> PulsarTheme.colors.negative
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = size.minDimension * 0.10f
            val inset = sw / 2f
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(track, 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(
                color, -90f, 360f * pct / 100f, false,
                topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        Text(
            "$pct%",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
