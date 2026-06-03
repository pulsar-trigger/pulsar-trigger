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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import com.ehrocha.pulsar.model.RunState
import com.ehrocha.pulsar.ui.theme.LocalRunState
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Diagnostic screen that fires a fixed 5-step flow across all modes —
 * Timelapse, Intervalometer (bulb), Astro, Dark Frame, Ramp — with 5 shots
 * per mode. Used to verify a freshly-connected transport (BLE / CCAPI /
 * PTP) end-to-end without manually walking each wizard.
 *
 * Camera-side setup the user is expected to do before tapping Start:
 *  - Body in Manual mode with shutter speed = Bulb (for bulb-mode steps)
 *  - Lens AF/MF switch wherever they want (Pulsar passes `useAutofocus=false`
 *    to every step so AF is suppressed at the protocol level)
 *  - Memory card with space for ~25 shots
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestCameraScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val runState = LocalRunState.current
    val running = runState !is RunState.Idle
    val stepCount by vm.cameraTestStepCount.collectAsState()
    val fullSequence = stepCount >= 5
    val ctx = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    // Probe-run state machine: idle(0) → pending(1) on tap → running(2) once
    // the flow actually starts → back to idle, raising the share prompt.
    // The pending → running transition handles preflight (compat + settings
    // probe before any shot fires); without it the dialog would pop the
    // instant the user tapped because runState is still Idle during preflight.
    var probeState by remember { mutableStateOf(0) }
    var probeMark by remember { mutableStateOf(0L) }
    var sharePromptText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(running, probeState) {
        when {
            probeState == 1 && running -> probeState = 2
            probeState == 2 && !running -> {
                probeState = 0
                sharePromptText = com.ehrocha.pulsar.canonble.CanonBleLog.dumpSince(probeMark)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.test_camera_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        if (running) {
            Column(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize(),
            ) {
                // 1 shot per mode × up to 5 modes when bulb is supported.
                RunningView(plannedShots = if (fullSequence) 5 else 1)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { vm.stopFlow() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {
                    Text(stringResource(R.string.test_camera_stop))
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
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
                    onClick = {
                        probeMark = com.ehrocha.pulsar.canonble.CanonBleLog.mark()
                        probeState = 1
                        vm.runCameraTest()
                    },
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

    sharePromptText?.let { runLog ->
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
