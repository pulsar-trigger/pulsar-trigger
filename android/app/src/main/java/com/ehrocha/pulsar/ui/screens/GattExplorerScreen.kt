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
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * GATT Explorer wizard — raw-GATT probing tool for community testers.
 *
 * Sister to Tools → Camera Test, opposite intent: instead of firing
 * shots through Pulsar's known protocols, drive raw GATT operations
 * (read / write / subscribe) so unsupported Canon bodies can be mapped
 * and added to [docs/canon-body-matrix.md]. Output goes into a separate
 * ring buffer for sharing as the artifact that promotes new bodies.
 *
 * **STATUS: scaffold only.** Five wizard steps defined; GATT-call hooks
 * are TODO. See [docs/gatt-explorer-draft.md] for the full design.
 *
 * Gated behind Settings → About → Developer options → Debug mode (off by
 * default). The entry in Settings → Diagnostics is hidden when debug
 * mode is off.
 */
private enum class GattStep {
    INTENT,       // 1. Pick "probe known body" vs "test new body"
    CONNECT,      // 2. Conditional — scan + bond when no existing bond
    TREE,         // 3. Service/characteristic tree with property chips
    CHAR_ACTIONS, // 4. Read / Subscribe / Write panel for a selected char
    SHARE,        // 5. Capture + share the RE report
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GattExplorerScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    var phase by remember { mutableStateOf(GattStep.INTENT) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gatt_explorer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(24.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (phase) {
                GattStep.INTENT -> IntentStep(
                    onProbeKnown = { phase = GattStep.TREE },
                    onProbeUnknown = { phase = GattStep.CONNECT },
                )
                GattStep.CONNECT -> ConnectStep(
                    onConnected = { phase = GattStep.TREE },
                    onBack = { phase = GattStep.INTENT },
                )
                GattStep.TREE -> TreeStep(
                    onCharSelected = { phase = GattStep.CHAR_ACTIONS },
                    onShare = { phase = GattStep.SHARE },
                    onBack = { phase = GattStep.INTENT },
                )
                GattStep.CHAR_ACTIONS -> CharActionsStep(
                    onBackToTree = { phase = GattStep.TREE },
                    onShare = { phase = GattStep.SHARE },
                )
                GattStep.SHARE -> ShareStep(
                    onDone = { phase = GattStep.TREE },
                )
            }
        }
    }
}

// ── Step 1: Intent ──────────────────────────────────────────────────────

@Composable
private fun IntentStep(
    onProbeKnown: () -> Unit,
    onProbeUnknown: () -> Unit,
) {
    // Safety disclaimer — shown only on the entry step so it's
    // unmissable but doesn't clutter the workflow once the user is
    // probing.
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.gatt_explorer_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }

    Text(
        stringResource(R.string.gatt_explorer_intent_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    OptionCard(
        title = stringResource(R.string.gatt_explorer_intent_known_title),
        body = stringResource(R.string.gatt_explorer_intent_known_body),
        onClick = onProbeKnown,
    )
    OptionCard(
        title = stringResource(R.string.gatt_explorer_intent_unknown_title),
        body = stringResource(R.string.gatt_explorer_intent_unknown_body),
        onClick = onProbeUnknown,
    )
}

// ── Step 2: Connect (conditional) ───────────────────────────────────────

@Composable
private fun ConnectStep(
    onConnected: () -> Unit,
    onBack: () -> Unit,
) {
    // TODO: re-use CanonBleDiscovery with relaxed filter (any
    // advertising peer). For now: stub with a Continue button so
    // navigation works.
    Text(
        stringResource(R.string.gatt_explorer_connect_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_connect_body))
    Text(
        "[TODO: relaxed BLE scan list goes here]",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onConnected,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_continue_stub)) }
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.cancel)) }
}

// ── Step 3: Service / characteristic tree ───────────────────────────────

@Composable
private fun TreeStep(
    onCharSelected: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    // TODO: drive BluetoothGatt.discoverServices() via a new
    // canonble/GattExplorerClient.kt and render the service → char
    // tree with property chips. Tap a char → onCharSelected().
    Text(
        stringResource(R.string.gatt_explorer_tree_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_tree_body))
    Text(
        "[TODO: service tree goes here]",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = onCharSelected,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_continue_stub)) }
    OutlinedButton(
        onClick = onShare,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_share_now)) }
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.cancel)) }
}

// ── Step 4: Read / Subscribe / Write for a selected characteristic ─────

@Composable
private fun CharActionsStep(
    onBackToTree: () -> Unit,
    onShare: () -> Unit,
) {
    // TODO: drive read, setCharacteristicNotification + CCCD write,
    // and write/writeNoResponse via GattExplorerClient. Each action
    // appends to GattExplorerLog with a timestamp. Hex input parser
    // accepts whitespace and `0x` prefixes for forgiveness.
    Text(
        stringResource(R.string.gatt_explorer_char_actions_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_char_actions_body))
    Text(
        "[TODO: Read / Subscribe / Write panel goes here]",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onBackToTree,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_back_to_tree)) }
    Button(
        onClick = onShare,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_share_now)) }
}

// ── Step 5: Share the captured RE report ────────────────────────────────

@Composable
private fun ShareStep(onDone: () -> Unit) {
    // TODO: assemble the structured report from GattExplorerLog +
    // service tree + interaction history, drop into a temp file via
    // FileProvider, fire ACTION_SEND. See shareDiagnostics() in
    // TestCameraScreen.kt for the existing pattern.
    Text(
        stringResource(R.string.gatt_explorer_share_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_share_body))
    Button(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.done)) }
}

// ── Tiny option card used by Step 1 ─────────────────────────────────────

@Composable
private fun OptionCard(title: String, body: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

