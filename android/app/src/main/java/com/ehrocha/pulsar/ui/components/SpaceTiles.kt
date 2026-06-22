/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * Space-style launcher tile — a spaceship cockpit push-button: a backlit keycap
 * (soft top highlight), a status LED, the icon, and a Mono "engraved" label.
 * When [active] (pressed) the LED + border light up on the live gradient, so the
 * tile reads as a tactile control you've just keyed. Stays on surface/outline
 * roles otherwise. Pass a shared [interactionSource] so an outer press/scale
 * animation and the lit state track the same gesture.
 */
@Composable
fun SpaceCockpitTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = PulsarTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val liveBrush = Brush.linearGradient(listOf(colors.liveStart, colors.liveEnd))
    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
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
                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Text(
                    label.uppercase(), fontFamily = Mono, fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp, lineHeight = 12.sp,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
