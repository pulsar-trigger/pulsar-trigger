/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import kotlin.math.roundToInt

/**
 * Numeric stepper: label, text field with −/+ buttons, and 5 preset chips
 * computed from the allowed range.
 *
 * @param presets optional explicit preset list; when null, 5 evenly-spaced values
 *               are computed from [min]..[max].
 * @param presetLabel optional transform for preset chip text (e.g. adding "mm" suffix).
 */
@Composable
fun IntStepperField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    min: Int,
    max: Int,
    step: Int = 1,
    enabled: Boolean = true,
    presets: List<Int>? = null,
    presetLabel: (Int) -> String = { it.toString() },
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var showNumPad by remember { mutableStateOf(false) }

    val resolvedPresets = presets ?: remember(min, max) {
        if (max <= min) listOf(min)
        else {
            val count = 5
            (0 until count).map { i ->
                val raw = min + (max - min).toDouble() * i / (count - 1)
                raw.roundToInt().coerceIn(min, max)
            }.distinct()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                onClick = { onValueChange((value - step).coerceIn(min, max)) },
                enabled = enabled && value > min,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.cd_decrease))
            }

            OutlinedCard(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clickable(enabled = enabled) { showNumPad = true },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleLarge.copy(
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        ),
                    )
                }
            }

            FilledTonalIconButton(
                onClick = { onValueChange((value + step).coerceIn(min, max)) },
                enabled = enabled && value < max,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_increase))
            }
        }

        if (resolvedPresets.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(resolvedPresets) { preset ->
                    FilterChip(
                        selected = value == preset,
                        onClick = { if (enabled) onValueChange(preset) },
                        enabled = enabled,
                        label = { Text(presetLabel(preset), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }

    if (showNumPad) {
        NumPadDialog(
            initialValue = value.toString(),
            onConfirm = { raw ->
                val parsed = raw.toIntOrNull()?.coerceIn(min, max) ?: value
                onValueChange(parsed)
                showNumPad = false
            },
            onDismiss = { showNumPad = false },
        )
    }
}
