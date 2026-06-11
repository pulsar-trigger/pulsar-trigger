/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R

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
    hint: String? = null,
    hintIsAccent: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val isLast = currentTabIdx >= tabCount - 1
    val isFirst = currentTabIdx == 0
    Surface(tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
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
                            modifier = Modifier.height(56.dp).fillMaxWidth(0.75f),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.btn_start), fontWeight = FontWeight.Bold)
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
                                modifier = Modifier.height(56.dp).weight(1.4f),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.btn_start), fontWeight = FontWeight.Bold)
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
