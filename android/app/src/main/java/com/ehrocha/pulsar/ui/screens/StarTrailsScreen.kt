/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.ehrocha.pulsar.ui.theme.Mono
import com.ehrocha.pulsar.ui.theme.PulsarTheme
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StarTrailsScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    var totalMin by rememberSaveable { mutableIntStateOf(60) }   // session length
    var subSec by rememberSaveable { mutableIntStateOf(30) }     // per-frame exposure
    var gapSec by rememberSaveable { mutableIntStateOf(2) }      // write gap
    var useAutofocus by rememberSaveable { mutableStateOf(false) }
    // Lens + sensor: the sky arc is focal-length independent (15°/h), but
    // what the arc means IN FRAME isn't — 30° through 16mm is a sweep,
    // through 200mm it exits the frame.
    var focalMm by rememberSaveable { mutableIntStateOf(24) }
    var sensorIdx by rememberSaveable { mutableIntStateOf(0) }

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
    // Horizontal FOV for the chosen glass; trail share computed at the
    // celestial equator (worst case — stars near the pole trail less).
    val sensorWidthMm = listOf(36.0, 23.5, 17.3)[sensorIdx]
    val hFovDeg = Math.toDegrees(
        2.0 * Math.atan(sensorWidthMm / (2.0 * focalMm)),
    )
    val framePct = (arcDeg / hFovDeg * 100.0)

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            PulsarTopBar(title = stringResource(R.string.mode_star_trails), onBack = onBack)
        },
        bottomBar = {
            com.ehrocha.pulsar.ui.components.StartStopBar(
                running = running,
                canStart = connected,
                hint = if (!connected && !running) {
                    stringResource(R.string.star_trails_not_connected)
                } else null,
                onStart = {
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
                onStop = { vm.stopFlow() },
            )
        },
    ) { pad ->
        if (running) {
            // Same instrument as every other wizard (Eduardo's #4): status
            // pill, mono counters, CyclePhaseTrace, PulseScope — instead of
            // a column of disabled sliders.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                RunningView(
                    plannedShots = frames,
                    exposureMs = subSec * 1000L,
                    gapMs = gapSec * 1000L,
                )
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Sweep scope: live preview of the star-trail arc ──────────────
            // The one beautiful, intuitive truth of the mode — stars trail
            // 15°/h around the pole — drawn live as you tune the session.
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(1.7f)) {
                    StarTrailScope(arcDeg = arcDeg.toFloat(), modifier = Modifier.fillMaxSize())
                    Column(modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
                        Text(
                            stringResource(R.string.star_trails_arc).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${arcDeg.roundToInt()}°",
                            style = MaterialTheme.typography.headlineLarge.copy(fontFamily = Mono),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Summary card.
            com.ehrocha.pulsar.ui.components.StatPanel {
                Column(modifier = Modifier.fillMaxWidth()) {
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.star_trails_frames),
                        "$frames",
                        emphasise = true,
                    )
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.star_trails_total),
                        formatExposure(actualTotalSec.toDouble()),
                    )
                    com.ehrocha.pulsar.ui.components.StatRow(
                        stringResource(R.string.star_trails_frame_row),
                        if (framePct >= 100.0) {
                            stringResource(R.string.star_trails_frame_full)
                        } else {
                            stringResource(
                                R.string.star_trails_frame_value,
                                framePct.roundToInt(),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            com.ehrocha.pulsar.ui.components.SignalSlider(
                text = stringResource(R.string.star_trails_duration, totalMin),
                value = totalMin.toFloat(),
                onChange = { totalMin = it.roundToInt() },
                range = 10f..240f,
            )

            com.ehrocha.pulsar.ui.components.SignalSlider(
                text = stringResource(R.string.star_trails_sub, subSec),
                value = subSec.toFloat(),
                onChange = { subSec = it.roundToInt() },
                range = 10f..120f,
            )

            com.ehrocha.pulsar.ui.components.SignalSlider(
                text = stringResource(R.string.star_trails_gap, gapSec),
                value = gapSec.toFloat(),
                onChange = { gapSec = it.roundToInt().coerceAtLeast(minGapSec) },
                range = minGapSec.toFloat()..8f,
                steps = (8 - minGapSec - 1).coerceAtLeast(0),
            )
            if (onCanonBle) {
                Text(
                    stringResource(R.string.star_trails_ble_gap_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            com.ehrocha.pulsar.ui.components.SignalSlider(
                text = stringResource(
                    R.string.star_trails_focal,
                    focalMm,
                    listOf("FF", "APS-C", "MFT")[sensorIdx],
                ),
                value = focalMm.toFloat(),
                onChange = { focalMm = it.roundToInt() },
                range = 8f..200f,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf("Full-frame", "APS-C", "MFT").forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = sensorIdx == i,
                        onClick = { sensorIdx = i },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = i, count = 3,
                        ),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
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
        }
    }
}

/**
 * Live preview of the predicted star-trail sweep — concentric arcs around the
 * celestial pole, each star trailing [arcDeg]° (15°/h × session length). The
 * arc grows as the session lengthens. Transparent, so the themed backdrop
 * (starfield / board) shows through and it reads in every visual style.
 */
@Composable
private fun StarTrailScope(arcDeg: Float, modifier: Modifier) {
    val trail = MaterialTheme.colorScheme.primary
    val starColor = MaterialTheme.colorScheme.onSurface
    val poleColor = PulsarTheme.colors.liveEnd
    val sweep by animateFloatAsState(arcDeg, tween(450), label = "sweep")
    Canvas(modifier) {
        val pole = Offset(size.width * 0.15f, size.height * 0.18f)
        val maxR = hypot(size.width, size.height) * 0.9f
        // radius fraction → start angle (deg) for a handful of stars
        val stars = listOf(
            0.30f to 20f, 0.44f to 65f, 0.57f to 110f,
            0.70f to 45f, 0.83f to 92f, 0.95f to 135f,
        )
        stars.forEach { (rf, startA) ->
            val r = maxR * rf
            val tl = Offset(pole.x - r, pole.y - r)
            val sz = Size(2 * r, 2 * r)
            // faint full circle = the star's whole nightly path
            drawArc(starColor.copy(alpha = 0.05f), 0f, 360f, false, tl, sz, style = Stroke(1f))
            // the trail it will actually draw this session
            drawArc(
                trail.copy(alpha = 0.85f), startA, sweep, false, tl, sz,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
            // bright head at the leading end (the star's current position)
            val a = Math.toRadians((startA + sweep).toDouble())
            val head = Offset(pole.x + (r * cos(a)).toFloat(), pole.y + (r * sin(a)).toFloat())
            drawCircle(starColor.copy(alpha = 0.25f), 4.dp.toPx(), head)
            drawCircle(starColor, 2.dp.toPx(), head)
        }
        // celestial pole
        drawCircle(poleColor.copy(alpha = 0.6f), 3.dp.toPx(), pole)
    }
}

