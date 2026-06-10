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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.model.FlowStep
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlin.math.roundToInt

/**
 * Star Trails wizard — guided front end that produces a stacked
 * Intervalometer flow: many back-to-back sub-exposures with a minimal gap,
 * later stacked in software into continuous trails. Stacked-only on
 * purpose — a single multi-minute exposure cooks the sensor (heat noise,
 * amp glow) far worse than N short subs.
 *
 * The wizard owns only the maths + guidance; the actual shooting reuses the
 * proven `FlowStep.Intervalometer` bulb path across all transports.
 */
@Composable
fun StarTrailsScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    var totalMin by rememberSaveable { mutableIntStateOf(60) }   // session length
    var subSec by rememberSaveable { mutableIntStateOf(30) }     // per-frame exposure
    var gapSec by rememberSaveable { mutableIntStateOf(2) }      // write gap
    var useAutofocus by rememberSaveable { mutableStateOf(false) }

    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val connected = LocalDeviceConnected.current
    val canControlAf = vm.activeTransportSupportsAf.collectAsState().value

    // Canon BLE's shutter state machine misses rapid release→press cycles
    // (the R6 "every-other-shot" bug, v0.357–v0.385). Hundreds of frames at
    // a 1–2 s gap is exactly that regime, so floor the gap at 4 s on that
    // transport. Other transports ACK the release synchronously and are
    // fine down to 1 s.
    val onCanonBle = vm.canonBleTransport.collectAsState().value != null
    val minGapSec = if (onCanonBle) 4 else 1
    LaunchedEffect(minGapSec) {
        if (gapSec < minGapSec) gapSec = minGapSec
    }

    val cycleSec = subSec + gapSec
    val frames = ((totalMin * 60) / cycleSec).coerceAtLeast(1)
    val actualTotalSec = frames.toLong() * cycleSec - gapSec
    // Stars sweep 15°/hour (Earth's rotation). Arc is independent of where
    // you point — it's the trail length you'll get.
    val arcDeg = actualTotalSec / 3600.0 * 15.0

    Scaffold(
        topBar = {
            PulsarTopBar(title = stringResource(R.string.mode_star_trails), onBack = onBack)
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
            // Summary card.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    StarTrailSummaryRow(
                        stringResource(R.string.star_trails_frames),
                        "$frames",
                        emphasise = true,
                    )
                    StarTrailSummaryRow(
                        stringResource(R.string.star_trails_total),
                        formatExposure(actualTotalSec.toDouble()),
                    )
                    StarTrailSummaryRow(
                        stringResource(R.string.star_trails_arc),
                        String.format(java.util.Locale.US, "%.1f°", arcDeg),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.star_trails_duration, totalMin),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = totalMin.toFloat(),
                onValueChange = { totalMin = it.roundToInt() },
                valueRange = 10f..240f,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.star_trails_sub, subSec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = subSec.toFloat(),
                onValueChange = { subSec = it.roundToInt() },
                valueRange = 10f..120f,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                stringResource(R.string.star_trails_gap, gapSec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = gapSec.toFloat(),
                onValueChange = { gapSec = it.roundToInt().coerceAtLeast(minGapSec) },
                valueRange = minGapSec.toFloat()..8f,
                steps = (8 - minGapSec - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
            if (onCanonBle) {
                Text(
                    stringResource(R.string.star_trails_ble_gap_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (canControlAf) {
                com.ehrocha.pulsar.ui.components.AutofocusToggle(
                    checked = useAutofocus,
                    onCheckedChange = { useAutofocus = it },
                    enabled = !running,
                )
            }

            Text(
                stringResource(R.string.star_trails_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(8.dp))
            if (running) {
                OutlinedButton(
                    onClick = { vm.stopFlow() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_stop))
                }
            } else {
                Button(
                    onClick = {
                        vm.saveFlowSteps(
                            listOf(
                                FlowStep.Intervalometer(
                                    intervalMs = gapSec * 1000L,
                                    exposureMs = subSec * 1000L,
                                    shotCount = frames,
                                    delayMs = 0L,
                                    useAutofocus = useAutofocus,
                                )
                            )
                        )
                        vm.startFlow()
                    },
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_start))
                }
            }
            if (!connected && !running) {
                Text(
                    stringResource(R.string.star_trails_not_connected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StarTrailSummaryRow(label: String, value: String, emphasise: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            value,
            style = if (emphasise) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasise) FontWeight.Bold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
