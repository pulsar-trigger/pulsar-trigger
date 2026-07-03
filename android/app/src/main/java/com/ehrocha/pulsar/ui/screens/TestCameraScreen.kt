/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Camera-test wizard. The Canon BLE / CCAPI / PTP shutter protocols use
 * different press/release semantics in M (single-shot) vs Bulb. Firing the
 * full sequence with the wrong dial position causes continuous shooting or
 * silent failures, so the test pauses between phases to let the user swap
 * the dial.
 *
 * Phase 1 (Manual dial): 1 Timelapse shot. Verifies the press/release
 * single-shot path. Preceded by the read-only compat-report + settings-probe
 * preflight on Canon transports.
 *
 * Phase 2 (Bulb dial): 4 shots (Intervalometer-bulb, Astro, Dark Frame,
 * Ramp). Verifies the press-and-hold bulb path. Skipped on transports that
 * don't advertise bulb.
 */
private enum class TestPhase {
    IDLE,
    AWAIT_MANUAL,
    RUNNING_MANUAL,
    AWAIT_BULB,
    RUNNING_BULB,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestCameraScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    // flowRunning (not the status-derived RunState) is authoritative across a
    // multi-step flow: runCanonBulb sets status IDLE at the end of EACH bulb
    // step, so RunState flickers Idle between the bulb phase's steps — keying
    // the phase-done transition on that fired the share after step 1 while the
    // rest kept shooting. flowRunning stays true until the whole flow ends.
    val running by vm.flowRunning.collectAsState()
    val cumulativeShots by vm.flowShotsCompleted.collectAsState()
    val stepCount by vm.cameraTestStepCount.collectAsState()
    val fullSequence = stepCount >= 5  // i.e. bulb supported
    val ctx = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(TestPhase.IDLE) }
    LaunchedEffect(phase) { com.ehrocha.pulsar.canonble.CanonBleLog.i("Nav", "◆ Camera Test phase → $phase") }
    var probeMark by remember { mutableLongStateOf(0L) }
    var sharePromptText by remember { mutableStateOf<String?>(null) }

    // Phase transitions driven by the running flag. CRITICAL: the manual phase
    // runs the compatibility report + settings probe BEFORE startFlow(), so
    // `running` stays false for several seconds after we enter RUNNING_MANUAL.
    // Keying the "phase done" transition on `!running` alone fired it during
    // that preflight window — raising the share dialog (and resetting the shot
    // count) before a single shot. So we only advance once we've actually SEEN
    // the phase run (running went true), then back to false.
    var sawRunning by remember { mutableStateOf(false) }
    LaunchedEffect(running, phase) {
        if (running) {
            sawRunning = true
            return@LaunchedEffect
        }
        when {
            phase == TestPhase.RUNNING_MANUAL && sawRunning -> {
                sawRunning = false
                phase = if (fullSequence) TestPhase.AWAIT_BULB else TestPhase.IDLE
                if (!fullSequence) {
                    // No bulb phase → the entire test is done, raise share.
                    sharePromptText = com.ehrocha.pulsar.canonble.CanonBleLog.dumpSince(probeMark)
                }
            }
            phase == TestPhase.RUNNING_BULB && sawRunning -> {
                sawRunning = false
                phase = TestPhase.IDLE
                sharePromptText = com.ehrocha.pulsar.canonble.CanonBleLog.dumpSince(probeMark)
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.test_camera_title),
                onBack = onBack,
            )
        },
    ) { pad ->
        when {
            running -> {
                Column(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize(),
                ) {
                    // Phase 1 = 1 timelapse shot. Phase 2 = the bulb sequence
                    // (Intervalometer + Astro + DarkFrame + Ramp + endurance
                    // marathon); the total is derived from the actual step list
                    // so it never drifts when a step is added/removed.
                    val plannedShots = if (phase == TestPhase.RUNNING_MANUAL) 1
                                       else vm.cameraTestBulbShots
                    // Cumulative across the bulb phase's steps so the count
                    // climbs 1→5 instead of resetting per mode.
                    RunningView(plannedShots = plannedShots, shotsOverride = cumulativeShots)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { vm.stopFlow(); phase = TestPhase.IDLE },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    ) {
                        Text(stringResource(R.string.test_camera_stop))
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            phase == TestPhase.AWAIT_MANUAL -> {
                DialPrompt(
                    pad = pad,
                    stepLabel = stringResource(
                        if (fullSequence) R.string.test_camera_step_label_1_of_2
                        else R.string.test_camera_step_label_only
                    ),
                    title = stringResource(R.string.test_camera_dial_manual_title),
                    body = stringResource(R.string.test_camera_dial_manual_body),
                    onContinue = {
                        probeMark = com.ehrocha.pulsar.canonble.CanonBleLog.mark()
                        phase = TestPhase.RUNNING_MANUAL
                        vm.runCameraTestManualPhase()
                    },
                    onCancel = { phase = TestPhase.IDLE },
                )
            }
            phase == TestPhase.AWAIT_BULB -> {
                DialPrompt(
                    pad = pad,
                    stepLabel = stringResource(R.string.test_camera_step_label_2_of_2),
                    title = stringResource(R.string.test_camera_dial_bulb_title),
                    body = stringResource(R.string.test_camera_dial_bulb_body),
                    onContinue = {
                        phase = TestPhase.RUNNING_BULB
                        vm.runCameraTestBulbPhase()
                    },
                    onCancel = {
                        phase = TestPhase.IDLE
                        // Still offer to share Phase 1's results.
                        sharePromptText = com.ehrocha.pulsar.canonble.CanonBleLog.dumpSince(probeMark)
                    },
                )
            }
            else -> {
                // IDLE
                Column(
                    modifier = Modifier
                        .padding(pad)
                        .padding(24.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(
                            if (fullSequence) R.string.test_camera_blurb
                            else R.string.test_camera_blurb_timelapse_only,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.test_camera_sequence_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(stringResource(R.string.test_camera_step_timelapse))
                            if (fullSequence) {
                                Text(stringResource(R.string.test_camera_step_intervalometer))
                                Text(stringResource(R.string.test_camera_step_astro))
                                Text(stringResource(R.string.test_camera_step_dark))
                                Text(stringResource(R.string.test_camera_step_ramp))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.test_camera_prereqs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { phase = TestPhase.AWAIT_MANUAL },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.test_camera_start))
                    }
                    OutlinedButton(
                        onClick = { showLogs = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.tools_view_logs))
                    }
                    OutlinedButton(
                        onClick = { shareDiagnostics(ctx, vm.canonDiagnosticsText()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.tools_collect_diagnostics))
                    }
                    Text(
                        stringResource(R.string.tools_collect_diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    sharePromptText?.let { runLog ->
        LaunchedEffect(Unit) {
            com.ehrocha.pulsar.canonble.CanonBleLog.i("Nav", "◆ Dialog: Camera Test — share results")
        }
        AlertDialog(
            onDismissRequest = { sharePromptText = null },
            title = { Text(stringResource(R.string.test_camera_share_title)) },
            text = { Text(stringResource(R.string.test_camera_share_body)) },
            confirmButton = {
                TextButton(onClick = {
                    shareDiagnostics(ctx, runLog)
                    sharePromptText = null
                }) { Text(stringResource(R.string.test_camera_share_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { sharePromptText = null }) {
                    Text(stringResource(R.string.test_camera_share_no))
                }
            },
        )
    }

    if (showLogs) {
        val logText = remember { vm.canonDiagnosticsText() }
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text(stringResource(R.string.tools_logs_title)) },
            text = {
                SelectionContainer {
                    Text(
                        logText,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { shareDiagnostics(ctx, logText) }) {
                    Text(stringResource(R.string.event_share))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(logText)) }) {
                        Text(stringResource(R.string.tools_logs_copy))
                    }
                    TextButton(onClick = { showLogs = false }) {
                        Text(stringResource(R.string.tools_logs_close))
                    }
                }
            },
        )
    }
}

@Composable
private fun DialPrompt(
    pad: androidx.compose.foundation.layout.PaddingValues,
    stepLabel: String,
    title: String,
    body: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(pad)
            .padding(24.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stepLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.test_camera_continue))
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/** Write the diagnostics text to a cache file and open the share sheet. Reuses
 *  the app's existing FileProvider (`shared/` cache path is whitelisted). */
internal fun shareDiagnostics(ctx: android.content.Context, text: String) {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val file = File(dir, "pulsar-diagnostics-$ts.txt")
    file.writeText(text)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Pulsar diagnostics")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(intent, null))
}
