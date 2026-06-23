/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.canonble.GattExplorerClient
import com.ehrocha.pulsar.canonble.GattExplorerLog
import com.ehrocha.pulsar.canonble.KnownGattUuids
import com.ehrocha.pulsar.canonble.parseHexOrNull
import com.ehrocha.pulsar.canonble.toHex
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * GATT Explorer wizard — raw-GATT probing for unsupported Canon bodies.
 *
 * Sister to Tools → Camera Test, opposite intent: instead of firing
 * shots through Pulsar's known protocols, drive raw GATT operations on
 * a Canon body so unsupported bodies can be mapped and added to
 * [docs/canon-body-matrix.md]. Output goes into [GattExplorerLog] for
 * sharing as the artifact that promotes new bodies into the supported
 * set.
 *
 * Gated behind Settings → About → Developer options → Debug mode.
 *
 * See [docs/gatt-explorer-draft.md] for the full design.
 */
private enum class GattStep {
    INTENT,
    CONNECT,
    TREE,
    CHAR_ACTIONS,
    SHARE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GattExplorerScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { GattExplorerClient(ctx) }
    // Disconnect on screen exit so we don't leak the GATT connection
    // when the user backs out.
    DisposableEffect(Unit) {
        onDispose { client.disconnect() }
    }

    var phase by remember { mutableStateOf(GattStep.INTENT) }
    var selectedCharRef by remember { mutableStateOf<Pair<BluetoothGattService, BluetoothGattCharacteristic>?>(null) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.gatt_explorer_title),
                onBack = onBack,
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
                    onProbeKnown = { phase = GattStep.CONNECT },
                    onProbeUnknown = { phase = GattStep.CONNECT },
                )
                GattStep.CONNECT -> ConnectStep(
                    client = client,
                    onConnected = { phase = GattStep.TREE },
                    onBack = { phase = GattStep.INTENT },
                )
                GattStep.TREE -> TreeStep(
                    client = client,
                    onCharSelected = { svc, ch ->
                        selectedCharRef = svc to ch
                        phase = GattStep.CHAR_ACTIONS
                    },
                    onShare = { phase = GattStep.SHARE },
                    onBack = { phase = GattStep.INTENT },
                )
                GattStep.CHAR_ACTIONS -> {
                    val sel = selectedCharRef
                    if (sel == null) {
                        phase = GattStep.TREE
                    } else {
                        CharActionsStep(
                            client = client,
                            service = sel.first,
                            char = sel.second,
                            onBackToTree = { phase = GattStep.TREE },
                            onShare = { phase = GattStep.SHARE },
                        )
                    }
                }
                GattStep.SHARE -> ShareStep(
                    ctx = ctx,
                    client = client,
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

// ── Step 2: Connect (bonded device picker) ──────────────────────────────

@Composable
private fun ConnectStep(
    client: GattExplorerClient,
    onConnected: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val connected by client.connected.collectAsState()
    val services by client.services.collectAsState()
    // Once discover completes we have a non-empty service list — that's
    // our cue to advance to the tree view.
    if (connected && services.isNotEmpty()) {
        onConnected()
        return
    }
    val bonded = remember { bondedBleDevices(ctx) }
    Text(
        stringResource(R.string.gatt_explorer_connect_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_connect_body))
    if (bonded.isEmpty()) {
        Text(
            stringResource(R.string.gatt_explorer_no_bonded),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (d in bonded) {
            DeviceRow(
                name = d.deviceLabel(),
                address = d.address ?: "?",
                onClick = { client.connect(d) },
            )
        }
    }
    if (connected) {
        Text(
            stringResource(R.string.gatt_explorer_discovering),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    OutlinedButton(
        onClick = onBack,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.cancel)) }
}

private fun bondedBleDevices(ctx: Context): List<BluetoothDevice> {
    val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? = mgr?.adapter
    return runCatching {
        adapter?.bondedDevices?.toList().orEmpty()
    }.getOrDefault(emptyList())
}

@Suppress("MissingPermission")
private fun BluetoothDevice.deviceLabel(): String =
    runCatching { name ?: address ?: "?" }.getOrDefault(address ?: "?")

@Composable
private fun DeviceRow(name: String, address: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ── Step 3: Service / characteristic tree ───────────────────────────────

@Composable
private fun TreeStep(
    client: GattExplorerClient,
    onCharSelected: (BluetoothGattService, BluetoothGattCharacteristic) -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
) {
    val services by client.services.collectAsState()
    Text(
        stringResource(R.string.gatt_explorer_tree_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_tree_body))

    if (services.isEmpty()) {
        Text(
            stringResource(R.string.gatt_explorer_discovering),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        for (svc in services) {
            val svcHint = KnownGattUuids.lookup(svc.uuid)?.nickname
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        svcHint ?: stringResource(R.string.gatt_explorer_service_unknown),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        svc.uuid.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (c in svc.characteristics) {
                        CharRow(
                            char = c,
                            onClick = { onCharSelected(svc, c) },
                        )
                    }
                }
            }
        }
    }
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

@Composable
private fun CharRow(char: BluetoothGattCharacteristic, onClick: () -> Unit) {
    val hint = KnownGattUuids.lookup(char.uuid)?.nickname
    val props = propsToString(char.properties)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hint ?: stringResource(R.string.gatt_explorer_char_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    props,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                char.uuid.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun propsToString(props: Int): String = buildList {
    if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WNR")
    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
    if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
}.joinToString(",")

// ── Step 4: Read / Subscribe / Write panel ──────────────────────────────

@Composable
private fun CharActionsStep(
    client: GattExplorerClient,
    service: BluetoothGattService,
    char: BluetoothGattCharacteristic,
    onBackToTree: () -> Unit,
    onShare: () -> Unit,
) {
    val notifications by client.notifications.collectAsState()
    val canRead = (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
    val canWrite = (char.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE
        or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
    val canNotify = (char.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY
        or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
    val supportsWriteResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
    val supportsWriteNoResp = (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    val knownHint = KnownGattUuids.lookup(char.uuid)
    var hexInput by remember { mutableStateOf("") }
    var hexError by remember { mutableStateOf<String?>(null) }
    var useResponse by remember(char.uuid) { mutableStateOf(supportsWriteResp && !supportsWriteNoResp) }
    val subscribedMap = remember { mutableStateMapOf<String, Boolean>() }
    val subscribed = subscribedMap[char.uuid.toString()] == true

    Text(
        knownHint?.nickname ?: stringResource(R.string.gatt_explorer_char_unknown),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        char.uuid.toString(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
    )
    if (knownHint?.hint != null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                knownHint.hint,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    if (canRead) {
        Button(
            onClick = { client.readChar(service.uuid, char.uuid) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.gatt_explorer_action_read)) }
    }

    if (canNotify) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.gatt_explorer_action_subscribe),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = subscribed,
                    onCheckedChange = {
                        client.setNotify(service.uuid, char.uuid, it)
                        subscribedMap[char.uuid.toString()] = it
                    },
                )
            }
        }
        notifications[char.uuid]?.let { lastBytes ->
            Text(
                stringResource(R.string.gatt_explorer_last_notify, lastBytes.toHex()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    if (canWrite) {
        OutlinedTextField(
            value = hexInput,
            onValueChange = {
                hexInput = it
                hexError = null
            },
            label = { Text(stringResource(R.string.gatt_explorer_write_hex_label)) },
            placeholder = { Text("00 01") },
            isError = hexError != null,
            supportingText = hexError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (supportsWriteResp && supportsWriteNoResp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.gatt_explorer_write_response_label),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = useResponse, onCheckedChange = { useResponse = it })
            }
        }
        Button(
            onClick = {
                val bytes = hexInput.parseHexOrNull()
                if (bytes == null) {
                    hexError = "Hex must be even-length pairs (e.g. \"00 01\")"
                } else {
                    client.writeChar(service.uuid, char.uuid, bytes, withResponse = useResponse)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text(stringResource(R.string.gatt_explorer_action_write)) }
    }

    OutlinedButton(
        onClick = onBackToTree,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_back_to_tree)) }
    OutlinedButton(
        onClick = onShare,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_share_now)) }
}

// ── Step 5: Share the captured RE report ────────────────────────────────

@Composable
private fun ShareStep(
    ctx: Context,
    client: GattExplorerClient,
    onDone: () -> Unit,
) {
    val services by client.services.collectAsState()
    val report = remember(services) { buildReport(client.bondAddress, services) }
    Text(
        stringResource(R.string.gatt_explorer_share_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(stringResource(R.string.gatt_explorer_share_body))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                report,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
    Button(
        onClick = {
            // Reuses the existing FileProvider helper from TestCameraScreen.
            shareDiagnostics(ctx, report)
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.gatt_explorer_share_now)) }
    OutlinedButton(
        onClick = onDone,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) { Text(stringResource(R.string.done)) }
}

private fun buildReport(
    bondAddress: String,
    services: List<BluetoothGattService>,
): String = buildString {
    appendLine("=== Pulsar GATT Explorer Report ===")
    appendLine("Pulsar v${com.ehrocha.pulsar.BuildConfig.VERSION_NAME}")
    appendLine("Body: $bondAddress")
    appendLine()
    appendLine("--- Service tree ---")
    for (svc in services) {
        val svcHint = KnownGattUuids.lookup(svc.uuid)?.nickname ?: "unknown"
        appendLine("Service ${svc.uuid} [$svcHint]")
        for (c in svc.characteristics) {
            val chint = KnownGattUuids.lookup(c.uuid)?.nickname ?: "unknown"
            appendLine("  Char ${c.uuid}  props=${propsToString(c.properties)}  [$chint]")
        }
    }
    appendLine()
    appendLine("--- Interactions ---")
    appendLine(GattExplorerLog.dump())
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
