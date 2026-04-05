/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Tap-to-step number picker with ▲/▼ buttons instead of scroll wheels.
 * Tap the centre value to type via keyboard.
 * This prevents accidental value changes when the user scrolls the page.
 */
@Composable
fun ScrollPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
    format: (Int) -> String = { "%02d".format(it) },
) {
    val haptic = LocalHapticFeedback.current
    val alpha = if (enabled) 1f else 0.4f

    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun step(delta: Int) {
        if (!enabled) return
        val newVal = (value + delta).coerceIn(range)
        if (newVal != value) {
            onValueChange(newVal)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun commitEdit() {
        val parsed = editText.toIntOrNull()?.coerceIn(range) ?: value
        onValueChange(parsed)
        editing = false
        focusManager.clearFocus()
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
            Spacer(Modifier.height(2.dp))
        }

        // ▲ button
        FilledTonalIconButton(
            onClick = { step(1) },
            enabled = enabled && value < range.last,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(2.dp))

        // Centre value — tap to type
        if (editing) {
            BasicTextField(
                value = editText,
                onValueChange = { editText = it.filter { c -> c.isDigit() } },
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitEdit() }),
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .height(40.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { if (!it.isFocused && editing) commitEdit() },
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                RoundedCornerShape(8.dp),
                            ),
                    ) { inner() }
                },
            )
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .height(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = enabled) {
                        editText = value.toString()
                        editing = true
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = format(value),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // ▼ button
        FilledTonalIconButton(
            onClick = { step(-1) },
            enabled = enabled && value > range.first,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
