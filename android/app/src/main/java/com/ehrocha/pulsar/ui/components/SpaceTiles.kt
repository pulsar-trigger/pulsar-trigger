/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PulsarTheme

// Space-style launcher tiles — spaceship-console takes on the menu chips.
// [active] lights the tile on the live gradient (the one destination you're on).
// Each keeps to surface/outline roles + a Mono "engraved" label.

/** HUD glass module — flat holographic panel: translucent so the starfield
 *  shows through, reticle corner brackets, a faint scanline, Mono label. */
@Composable
fun SpaceHudTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = PulsarTheme.colors
    val edge = if (active) colors.liveEnd else MaterialTheme.colorScheme.primary
    val bracket = edge.copy(alpha = if (active) 0.9f else 0.55f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, edge.copy(alpha = if (active) 0.8f else 0.35f)),
        modifier = modifier.height(110.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val gap = 6.dp.toPx()
                var y = gap
                while (y < size.height) {
                    drawLine(edge.copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += gap
                }
                val len = 12.dp.toPx()
                val ins = 6.dp.toPx()
                val sw = 1.5.dp.toPx()
                val w = size.width
                val h = size.height
                drawLine(bracket, Offset(ins, ins), Offset(ins + len, ins), sw, StrokeCap.Round)
                drawLine(bracket, Offset(ins, ins), Offset(ins, ins + len), sw, StrokeCap.Round)
                drawLine(bracket, Offset(w - ins, ins), Offset(w - ins - len, ins), sw, StrokeCap.Round)
                drawLine(bracket, Offset(w - ins, ins), Offset(w - ins, ins + len), sw, StrokeCap.Round)
                drawLine(bracket, Offset(ins, h - ins), Offset(ins + len, h - ins), sw, StrokeCap.Round)
                drawLine(bracket, Offset(ins, h - ins), Offset(ins, h - ins - len), sw, StrokeCap.Round)
                drawLine(bracket, Offset(w - ins, h - ins), Offset(w - ins - len, h - ins), sw, StrokeCap.Round)
                drawLine(bracket, Offset(w - ins, h - ins), Offset(w - ins, h - ins - len), sw, StrokeCap.Round)
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (active) colors.liveEnd else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    label.uppercase(), fontFamily = Mono, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Cockpit push-button — tactile backlit key: keycap top highlight, a status
 *  LED, icon + Mono label. Active = LED + border on the live gradient. */
@Composable
fun SpaceCockpitTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = PulsarTheme.colors
    val liveBrush = Brush.linearGradient(listOf(colors.liveStart, colors.liveEnd))
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (active) BorderStroke(1.5.dp, liveBrush)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier.height(110.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(46.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent))),
            )
            Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).size(7.dp).clip(CircleShape)
                    .background(if (active) colors.liveStart else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (active) colors.liveEnd else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    label.uppercase(), fontFamily = Mono, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Console switch key — mechanical switch-bank: a recessed icon slot with an
 *  indicator bar and a Mono label. Active lights the indicator. */
@Composable
fun SpaceSwitchTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = PulsarTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier.height(110.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label.uppercase(), fontFamily = Mono, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(3) { i ->
                        Box(
                            Modifier.size(4.dp, 9.dp).clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (active && i < 2) colors.liveStart
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (active) colors.liveEnd else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** TEMPORARY — side-by-side sample of the three console-tile candidates so the
 *  style can be picked on-device. Remove once chosen. */
@Composable
fun SpaceTileSampler(modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            "TILE STYLES  ·  A cockpit  ·  B hud  ·  C switch",
            fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpaceCockpitTile("Trigger", Icons.Default.Camera, {}, Modifier.weight(1f), active = true)
            SpaceHudTile("Tools", Icons.Default.Camera, {}, Modifier.weight(1f), active = false)
            SpaceSwitchTile("Astro", Icons.Default.Camera, {}, Modifier.weight(1f), active = false)
        }
    }
}
