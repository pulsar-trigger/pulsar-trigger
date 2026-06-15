/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.theme.PulsarTheme

/**
 * The app's one flow-launching affordance (audit P1-6): every screen that
 * starts the camera pins this bar to the bottom — multi-tab wizards get
 * Prev/Next paging, single-page wizards get just the Start button, and a
 * running flow always swaps to the same red Stop. Promoted from the
 * Intervalometer 2 wizard, where the five tabbed wizards already shared it.
 */
@Composable
fun StartStopBar(
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    currentTabIdx: Int = 0,
    tabCount: Int = 1,
    currentTabValid: Boolean = true,
    /** When set, the terminal button reads this (e.g. "Save") with a check
     *  icon instead of the play-arrow "Start" — used when a wizard is reused
     *  as a Custom Flow step editor: same screen, the action just saves the
     *  step instead of firing the camera. */
    startLabel: String? = null,
    hint: String? = null,
    hintIsAccent: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val isLast = currentTabIdx >= tabCount - 1
    val isFirst = currentTabIdx == 0
    val live = PulsarTheme.colors
    val liveBrush = Brush.horizontalGradient(listOf(live.liveStart, live.liveEnd))
    Surface(tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (running) {
                // The live strip: a thin breathing gradient across the top
                // edge — the SIGNAL convention that something is happening.
                val breathe by rememberInfiniteTransition(label = "live")
                    .animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1000),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "liveAlpha",
                    )
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(liveBrush, alpha = breathe),
                )
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hintIsAccent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (hintIsAccent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    running -> {
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(56.dp).fillMaxWidth(0.6f),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_stop), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    tabCount <= 1 -> {
                        // Single-page wizard — no paging, just Start.
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = onStart,
                            enabled = canStart,
                            shape = RoundedCornerShape(20.dp),
                            colors = if (canStart) ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = Color.White,
                            ) else ButtonDefaults.buttonColors(),
                            modifier = Modifier
                                .height(56.dp)
                                .fillMaxWidth(0.75f)
                                .then(
                                    if (canStart) Modifier.background(
                                        liveBrush, RoundedCornerShape(20.dp),
                                    ) else Modifier,
                                ),
                        ) {
                            Icon(
                                if (startLabel != null) Icons.Default.Check
                                else Icons.Default.PlayArrow,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                startLabel ?: stringResource(R.string.btn_start),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onPrev,
                            enabled = !isFirst,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(56.dp).weight(1f),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.iv2_wizard_prev))
                        }
                        if (isLast) {
                            Button(
                                onClick = onStart,
                                enabled = canStart,
                                shape = RoundedCornerShape(20.dp),
                                colors = if (canStart) ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = Color.White,
                                ) else ButtonDefaults.buttonColors(),
                                modifier = Modifier
                                    .height(56.dp)
                                    .weight(1.4f)
                                    .then(
                                        if (canStart) Modifier.background(
                                            liveBrush, RoundedCornerShape(20.dp),
                                        ) else Modifier,
                                    ),
                            ) {
                                Icon(
                                    if (startLabel != null) Icons.Default.Check
                                    else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    startLabel ?: stringResource(R.string.btn_start),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        } else {
                            Button(
                                onClick = onNext,
                                enabled = currentTabValid,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.height(56.dp).weight(1f),
                            ) {
                                Text(stringResource(R.string.iv2_wizard_next), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}
