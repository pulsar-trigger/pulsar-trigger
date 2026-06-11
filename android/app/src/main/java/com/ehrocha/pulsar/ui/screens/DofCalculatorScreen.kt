/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import kotlin.math.roundToInt

private val DOF_FSTOPS = listOf(1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0, 16.0, 22.0)

/** (label, circle-of-confusion mm) per sensor format. CoC values are the
 *  widely-used "Zeiss formula" approximations (≈ diagonal / 1500). */
private val DOF_SENSORS = listOf(
    Triple("Full-frame", 0.029, "FF"),
    Triple("APS-C", 0.020, "APS-C"),
    Triple("MFT", 0.015, "MFT"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DofCalculatorScreen(onBack: () -> Unit) {
    var focalMm by remember { mutableFloatStateOf(50f) }
    var fstopIdx by remember { mutableIntStateOf(4) }      // f/5.6
    var distanceM by remember { mutableFloatStateOf(5f) }
    var sensorIdx by remember { mutableIntStateOf(0) }      // Full-frame

    val n = DOF_FSTOPS[fstopIdx]
    val c = DOF_SENSORS[sensorIdx].second
    val f = focalMm.toDouble()
    val s = distanceM.toDouble() * 1000.0  // m → mm

    // Hyperfocal + near/far in mm, then back to m.
    val hMm = (f * f) / (n * c) + f
    val nearMm = s * (hMm - f) / (hMm + s - 2 * f)
    val farInfinite = (hMm - s) <= 0
    val farMm = if (farInfinite) Double.POSITIVE_INFINITY else s * (hMm - f) / (hMm - s)
    val dofMm = if (farInfinite) Double.POSITIVE_INFINITY else farMm - nearMm

    Scaffold(
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.dof_calc_title),
                onBack = onBack,
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
            // Result card — DoF range + total + hyperfocal.
            com.ehrocha.pulsar.ui.components.StatPanel {
                Column(modifier = Modifier.fillMaxWidth()) {
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.dof_calc_near),
                        formatDistance(nearMm),
                    )
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.dof_calc_far),
                        if (farInfinite) stringResource(R.string.dof_calc_infinity)
                        else formatDistance(farMm),
                    )
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.dof_calc_total),
                        if (dofMm.isInfinite()) stringResource(R.string.dof_calc_infinity)
                        else formatDistance(dofMm),
                        emphasise = true,
                    )
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.dof_calc_hyperfocal),
                        formatDistance(hMm),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            // Sensor format chips.
            Text(
                stringResource(R.string.dof_calc_sensor),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DOF_SENSORS.forEachIndexed { i, (label, _, _) ->
                    FilterChip(
                        selected = sensorIdx == i,
                        onClick = { sensorIdx = i },
                        label = { Text(label) },
                    )
                }
            }

            Text(
                stringResource(R.string.dof_calc_focal, focalMm.roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = focalMm,
                onValueChange = { focalMm = it },
                valueRange = 8f..600f,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.dof_calc_aperture, formatFstop(n)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = fstopIdx.toFloat(),
                onValueChange = { fstopIdx = it.roundToInt() },
                valueRange = 0f..(DOF_FSTOPS.size - 1).toFloat(),
                steps = DOF_FSTOPS.size - 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.dof_calc_distance,
                    String.format(java.util.Locale.US, "%.1f", distanceM)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = distanceM,
                onValueChange = { distanceM = it },
                valueRange = 0.5f..100f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}


private fun formatFstop(n: Double): String =
    if (n == n.toInt().toDouble()) "f/${n.toInt()}" else "f/$n"

/** mm → human distance: < 1 m in cm, else metres with one decimal. */
private fun formatDistance(mm: Double): String {
    if (mm.isInfinite()) return "∞"
    val m = mm / 1000.0
    return if (m < 1.0) "${(m * 100).roundToInt()} cm"
    else String.format(java.util.Locale.US, "%.1f m", m)
}
