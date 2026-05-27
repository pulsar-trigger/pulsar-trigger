/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
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
                // Total planned shots across all 5 steps = 5*5 = 25.
                RunningView(plannedShots = 25)
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
                    onClick = { vm.runCameraTest() },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Text(stringResource(R.string.test_camera_start))
                }
            }
        }
    }
}
