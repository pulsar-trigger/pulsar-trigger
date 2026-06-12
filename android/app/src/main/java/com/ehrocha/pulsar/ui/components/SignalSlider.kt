/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ehrocha.pulsar.ui.theme.Mono

/**
 * The app's one labelled-slider molecule (Eduardo's #2: ad-hoc Text+Slider
 * pairs read inconsistently next to the wizards' scrub instruments). The
 * label line carries the live value, so it renders in the telemetry voice —
 * mono digits don't jitter while the thumb drags.
 */
@Composable
fun SignalSlider(
    text: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
