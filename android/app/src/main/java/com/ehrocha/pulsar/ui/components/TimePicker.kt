/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Time picker with scrollable hh : mm : ss : ms columns.
 * Converts to/from total milliseconds.
 */
@Composable
fun TimePicker(
    totalMs: Long,
    onChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    maxHours: Int = 23,
    showMs: Boolean = false,
    enabled: Boolean = true,
) {
    val hours = ((totalMs / 3_600_000) % (maxHours + 1)).toInt()
    val minutes = ((totalMs % 3_600_000) / 60_000).toInt()
    val seconds = ((totalMs % 60_000) / 1_000).toInt()
    val millis = ((totalMs % 1_000) / 10).toInt()  // 10ms steps → 0..99

    fun recompose(h: Int = hours, m: Int = minutes, s: Int = seconds, ms: Int = millis): Long {
        return h * 3_600_000L + m * 60_000L + s * 1_000L + ms * 10L
    }

    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ScrollPicker(
                value = hours,
                range = 0..maxHours,
                onValueChange = { onChanged(recompose(h = it)) },
                label = "h",
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Separator()
            ScrollPicker(
                value = minutes,
                range = 0..59,
                onValueChange = { onChanged(recompose(m = it)) },
                label = "m",
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Separator()
            ScrollPicker(
                value = seconds,
                range = 0..59,
                onValueChange = { onChanged(recompose(s = it)) },
                label = "s",
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            if (showMs) {
                Separator()
                ScrollPicker(
                    value = millis,
                    range = 0..99,
                    onValueChange = { onChanged(recompose(ms = it)) },
                    label = "ms",
                    enabled = enabled,
                    format = { "%02d".format(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Separator() {
    Text(
        text = ":",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp).padding(top = 14.dp),
    )
}
