/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch

/**
 * Single-shot remote — the camera owns the exposure (its own shutter-speed
 * setting), Pulsar just sends a press-then-release event over the active
 * transport. Distinct from Manual (Bulb), which holds the shutter open for
 * a phone-timed duration.
 *
 * Visual structure mirrors [ModeScreen]: inline Row top bar (back + title +
 * help tooltip), main info panel filling the middle, and a single
 * full-width action button at the bottom — same layout family as the Manual
 * panel so they read as siblings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CableReleaseScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val connected = LocalDeviceConnected.current
    val title = stringResource(R.string.mode_cable_release)
    val helpText = stringResource(R.string.mode_cable_release_help)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // ── Top bar (matches ModeScreen) ─────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))

            val tooltipState = rememberTooltipState(isPersistent = true)
            val scope = rememberCoroutineScope()
            TooltipBox(
                positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                tooltip = {
                    RichTooltip(
                        title = { Text(title) },
                        action = {
                            TextButton(onClick = { scope.launch { tooltipState.dismiss() } }) {
                                Text(stringResource(R.string.action_dismiss))
                            }
                        },
                    ) { Text(helpText) }
                },
                state = tooltipState,
            ) {
                IconButton(onClick = { scope.launch { tooltipState.show() } }) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = stringResource(R.string.cd_help),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Main panel (matches ManualPanel surface) ─────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.mode_cable_release_dial_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.mode_cable_release_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.camera_drive_single_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Action button (matches Manual hold/lock button) ──────────────
        val armedBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(
                com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.liveStart,
                com.ehrocha.pulsar.ui.theme.PulsarTheme.colors.liveEnd,
            ),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (connected) androidx.compose.ui.graphics.Color.Transparent
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = if (connected) 0.dp else 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .then(
                    if (connected) Modifier.background(
                        armedBrush, RoundedCornerShape(20.dp),
                    ) else Modifier,
                )
                .pointerInput(connected) {
                    if (!connected) return@pointerInput
                    detectTapGestures(onTap = { vm.fireSingle() })
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.btn_single_shot),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (connected) androidx.compose.ui.graphics.Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
