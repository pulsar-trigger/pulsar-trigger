/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import kotlin.math.roundToInt

/** Standard shutter speeds (label, seconds), slowest-last so a higher
 *  slider index = slower base exposure. */
private val ND_SHUTTER_SPEEDS: List<Pair<String, Double>> = listOf(
    "1/8000" to 1.0 / 8000, "1/4000" to 1.0 / 4000, "1/2000" to 1.0 / 2000,
    "1/1000" to 1.0 / 1000, "1/500" to 1.0 / 500, "1/250" to 1.0 / 250,
    "1/125" to 1.0 / 125, "1/60" to 1.0 / 60, "1/30" to 1.0 / 30,
    "1/15" to 1.0 / 15, "1/8" to 1.0 / 8, "1/4" to 1.0 / 4, "1/2" to 0.5,
    "1\"" to 1.0, "2\"" to 2.0, "4\"" to 4.0, "8\"" to 8.0,
    "15\"" to 15.0, "30\"" to 30.0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NdCalculatorScreen(onBack: () -> Unit) {
    // Default to 1/125 s base + ND1000 (10 stops) — the canonical daylight
    // long-exposure starting point.
    var baseIdx by remember { mutableIntStateOf(6) }
    var stops by remember { mutableIntStateOf(10) }

    val (baseLabel, baseSec) = ND_SHUTTER_SPEEDS[baseIdx]
    val resultSec = baseSec * Math.pow(2.0, stops.toDouble())
    val ndNumber = (Math.pow(2.0, stops.toDouble())).roundToInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.nd_calc_title), fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Result card.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.nd_calc_result_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        formatExposure(resultSec),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.nd_calc_base, baseLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = baseIdx.toFloat(),
                onValueChange = { baseIdx = it.roundToInt() },
                valueRange = 0f..(ND_SHUTTER_SPEEDS.size - 1).toFloat(),
                steps = ND_SHUTTER_SPEEDS.size - 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.nd_calc_filter, ndNumber, stops),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = stops.toFloat(),
                onValueChange = { stops = it.roundToInt() },
                valueRange = 1f..20f,
                steps = 18,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.nd_calc_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// formatExposure lives in FormatUtils.kt (shared with Star Trails).
