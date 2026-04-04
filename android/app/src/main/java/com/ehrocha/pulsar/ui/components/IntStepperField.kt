/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
@OptIn(ExperimentalLayoutApi::class)
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
    val focusManager = LocalFocusManager.current
    var text by remember(value) { mutableStateOf(value.toString()) }

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

            OutlinedTextField(
                value = text,
                onValueChange = { raw -> text = raw.filter { it.isDigit() } },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    val parsed = text.toIntOrNull()?.coerceIn(min, max) ?: value
                    onValueChange(parsed)
                    text = parsed.toString()
                    focusManager.clearFocus()
                }),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            )

            FilledTonalIconButton(
                onClick = { onValueChange((value + step).coerceIn(min, max)) },
                enabled = enabled && value < max,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_increase))
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            resolvedPresets.forEach { preset ->
                SuggestionChip(
                    onClick = { if (enabled) onValueChange(preset) },
                    label = { Text(presetLabel(preset), style = MaterialTheme.typography.labelSmall) },
                    enabled = enabled,
                )
            }
        }
    }
}
