/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ble.BoardKind
import com.ehrocha.pulsar.ble.ScannedDevice
import com.ehrocha.pulsar.transport.TransportKind
import com.ehrocha.pulsar.viewmodel.PulsarViewModel

/**
 * Per-transport setup screen. The Scan landing routes here when the user
 * taps a transport tile. Same scaffold for every transport (top bar with
 * back, instructions, scan/refresh control, discovered-devices list); the
 * `when (kind)` branches in [SetupContent] supply the transport-specific
 * pieces:
 *  - instruction copy (body-specific)
 *  - scan lifecycle (which discovery to start/stop)
 *  - discovered list (which card composable + which view-model action on tap)
 *
 * Each transport's scan starts on screen-visible (`DisposableEffect`) and
 * stops on dispose. This is the cleanest battery profile — concurrent
 * Pulsar-BLE + Canon-BLE scans only run when the user is in the
 * relevant tile, not all the time.
 *
 * Phase 3 of the scan-screen overhaul: implements transports one at a time
 * (one commit per kind). Branches not yet implemented fall through to the
 * legacy [ScanScreen]; the landing routes accordingly. Phase 4 deletes the
 * legacy screen.
 */
@Composable
fun TransportSetupScreen(
    vm: PulsarViewModel,
    kind: TransportKind,
    onBack: () -> Unit,
    onConnected: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    if (connected) {
        LaunchedEffect(Unit) { onConnected() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopBar(kind = kind, onBack = onBack)
        SetupContent(vm = vm, kind = kind)
    }
}

@Composable
private fun TopBar(kind: TransportKind, onBack: () -> Unit) {
    val titleRes = when (kind) {
        TransportKind.BLE_ESP -> R.string.transport_tile_pulsar_ble_title
        TransportKind.CCAPI -> R.string.transport_tile_ccapi_title
        TransportKind.PTP_USB -> R.string.transport_tile_ptp_title
        TransportKind.CANON_BLE -> R.string.transport_tile_canon_ble_title
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SetupContent(vm: PulsarViewModel, kind: TransportKind) {
    when (kind) {
        TransportKind.BLE_ESP -> PulsarBleSetup(vm)
        TransportKind.CCAPI -> CcapiSetup(vm)
        TransportKind.PTP_USB -> PtpSetup(vm)
        TransportKind.CANON_BLE -> CanonBleSetup(vm)
    }
}

// ── Pulsar BLE (ESP32 module) ─────────────────────────────────────────────

@Composable
private fun PulsarBleSetup(vm: PulsarViewModel) {
    val scanning by vm.bleController.scanning.collectAsState()
    val devices by vm.bleController.devices.collectAsState()
    val scrollState = rememberScrollState()

    // Owned scan lifecycle: start on screen-visible, stop on dispose.
    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        InstructionCard(
            iconRes = Icons.Default.Bluetooth,
            lines = listOf(
                stringResource(R.string.pulsar_ble_setup_step1),
                stringResource(R.string.pulsar_ble_setup_step2),
                stringResource(R.string.pulsar_ble_setup_step3),
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.pulsar_ble_setup_devices_header),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (scanning) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.pulsar_ble_setup_scanning),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = {
                    vm.stopScan(); vm.startScan()
                }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.pulsar_ble_setup_rescan))
                }
            }
        }

        if (devices.isEmpty() && !scanning) {
            EmptyState(stringResource(R.string.pulsar_ble_setup_empty))
        } else {
            // Compact column instead of LazyColumn since we're already in a
            // verticalScroll — nesting scrollables would break behaviour.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                devices.forEach { scanned ->
                    PulsarBleDeviceCard(scanned) { vm.connectTo(scanned.device) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Shared building blocks ────────────────────────────────────────────────

@Composable
private fun InstructionCard(iconRes: ImageVector, lines: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconRes,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transport_setup_instructions_header),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            lines.forEachIndexed { idx, line ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }
}

// ── Canon CCAPI (Wi-Fi) ───────────────────────────────────────────────────

@Composable
private fun CcapiSetup(vm: PulsarViewModel) {
    val cameras by vm.canonCcapiCameras.collectAsState()
    val connecting by vm.canonCcapiConnecting.collectAsState()
    val authPrompt by vm.canonCcapiAuthPrompt.collectAsState()
    val nicknames by vm.canonCcapiNicknames.collectAsState()
    val ssid by vm.currentWifiSsid.collectAsState()
    val scrollState = rememberScrollState()

    // Per-screen scan lifecycle. We still call the aggregate startScan() for
    // now — Phase 4 will split it into start/stopBleEspScan + start/stopCcapiScan
    // so a CCAPI scan doesn't also kick off a (wasteful) Pulsar-BLE radio
    // scan and vice versa.
    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    var connectingTo by remember { mutableStateOf<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null) }
    var renaming by remember { mutableStateOf<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null) }
    var showingCapabilities by remember { mutableStateOf<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null) }
    var showAddByIp by remember { mutableStateOf(false) }
    val canonCcapiError by vm.canonCcapiError.collectAsState()

    // Dismiss the connect dialog automatically once we successfully connect,
    // and clear it when the auth prompt takes over to avoid stacked dialogs.
    val connectedState by vm.connected.collectAsState()
    LaunchedEffect(connectedState, connectingTo) {
        if (connectedState && connectingTo != null) connectingTo = null
    }
    LaunchedEffect(authPrompt) {
        if (authPrompt != null) connectingTo = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        InstructionCard(
            iconRes = Icons.Default.Wifi,
            lines = listOf(
                stringResource(R.string.ccapi_setup_step1),
                stringResource(R.string.ccapi_setup_step2),
                stringResource(R.string.ccapi_setup_step3),
                stringResource(R.string.ccapi_setup_step4),
            ),
        )

        // Wi-Fi network indicator: confirms phone is on the camera's net.
        WifiNetworkRow(ssid = ssid)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ccapi_setup_cameras_header),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                vm.stopScan(); vm.startScan()
            }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pulsar_ble_setup_rescan))
            }
        }

        if (cameras.isEmpty()) {
            EmptyState(stringResource(R.string.ccapi_setup_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cameras.forEach { camera ->
                    CanonCameraCard(
                        camera = camera,
                        nickname = nicknames[camera.udn],
                        onClick = { connectingTo = camera },
                        onRename = { renaming = camera },
                        onCapabilities = { showingCapabilities = camera },
                    )
                }
            }
        }

        // Add-by-IP fallback — when SSDP discovery is blocked (camera AP
        // suppresses multicast, or the body skips UPnP). Same UI as before;
        // moved here from the legacy ScanScreen footer.
        TextButton(onClick = { showAddByIp = true }) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.canon_manual_add_button))
        }

        Spacer(Modifier.height(24.dp))
    }

    // ── CCAPI dialogs ────────────────────────────────────────────────────
    // All four dialogs reference helpers in the legacy ScanScreen.kt
    // (made `internal` in this commit). Phase 4 will relocate them to
    // their final home as ScanScreen.kt goes away.

    connectingTo?.let { cam ->
        // The original "Connect to <camera>" confirmation dialog from the
        // legacy ScanScreen — shows the camera's friendly name, IP:port,
        // and CCAPI accessUrl, with a Connect / Cancel pair of buttons.
        AlertDialog(
            onDismissRequest = {
                if (!connecting) {
                    connectingTo = null
                    vm.clearCanonCcapiError()
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.connectCanonCcapi(cam) },
                    enabled = !connecting,
                ) {
                    Text(
                        if (connecting) stringResource(R.string.canon_connecting)
                        else stringResource(R.string.canon_connect)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    connectingTo = null
                    vm.clearCanonCcapiError()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            icon = {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text(cam.nickname ?: cam.friendlyName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        cam.friendlyName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${cam.ipAddress}:${cam.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        cam.accessUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.canon_camera_join_wifi_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    canonCcapiError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when (err) {
                                "auth_required" -> stringResource(R.string.canon_err_auth)
                                "network" -> stringResource(R.string.canon_err_network)
                                else -> stringResource(R.string.canon_err_generic, err)
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }

    if (showAddByIp) {
        CanonManualAddDialog(
            adding = vm.canonCcapiManualAdding.collectAsState().value,
            error = vm.canonCcapiManualError.collectAsState().value,
            onDismiss = {
                showAddByIp = false
                vm.clearCanonCcapiManualError()
            },
            onSubmit = { input ->
                vm.addCanonCcapiByHost(input) { ok ->
                    if (ok) showAddByIp = false
                }
            },
        )
    }

    renaming?.let { cam ->
        CanonRenameDialog(
            camera = cam,
            initial = nicknames[cam.udn].orEmpty(),
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                vm.setCanonCcapiNickname(cam.udn, newName)
                renaming = null
            },
        )
    }

    showingCapabilities?.let { cam ->
        CanonCapabilitiesDialog(
            camera = cam,
            probe = { vm.probeCanonCapabilities(cam) },
            onDismiss = { showingCapabilities = null },
        )
    }

    authPrompt?.let { cam ->
        CanonAuthDialog(
            camera = cam,
            connecting = connecting,
            onCancel = { vm.cancelCanonCcapiAuth() },
            onSubmit = { user, pass ->
                vm.submitCanonCredentials(cam, user, pass)
            },
        )
    }
}

@Composable
private fun WifiNetworkRow(ssid: String?) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ssid != null) Icons.Default.Wifi else Icons.Default.Warning,
                contentDescription = null,
                tint = if (ssid != null) onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = ssid ?: stringResource(R.string.ccapi_setup_no_wifi_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (ssid != null) onSurface else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = if (ssid != null) stringResource(R.string.ccapi_setup_wifi_hint)
                           else stringResource(R.string.ccapi_setup_no_wifi_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }
    }
}

// ── USB PTP (Canon over USB-C) ────────────────────────────────────────────

@Composable
private fun PtpSetup(vm: PulsarViewModel) {
    val cameras by vm.ptpCameras.collectAsState()
    val connecting by vm.ptpConnecting.collectAsState()
    val ptpError by vm.ptpError.collectAsState()
    val ptpErrPermDenied = stringResource(R.string.ptp_err_permission_denied)
    val ptpErrOpenFailed = stringResource(R.string.ptp_err_open_failed)
    val ptpErrSessionFailed = stringResource(R.string.ptp_err_session_failed)
    val ptpErrGeneric = stringResource(R.string.ptp_err_generic)
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()

    // Same Toast-the-failure pattern as the legacy screen — surfaces
    // permission_denied / open_failed / session_failed so the user knows
    // why a tap didn't take effect.
    LaunchedEffect(ptpError) {
        val e = ptpError ?: return@LaunchedEffect
        val msg = when (e) {
            "permission_denied" -> ptpErrPermDenied
            "open_failed" -> ptpErrOpenFailed
            "session_failed" -> ptpErrSessionFailed
            else -> ptpErrGeneric.format(e)
        }
        android.widget.Toast.makeText(toastCtx, msg, android.widget.Toast.LENGTH_LONG).show()
        vm.clearPtpError()
    }
    // PTP discovery is push-based (UsbManager broadcasts on attach/detach)
    // and starts at viewmodel init time — no per-screen scan lifecycle to
    // manage. The cameras flow updates the moment a USB device is plugged
    // or unplugged.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        InstructionCard(
            iconRes = Icons.Default.Usb,
            lines = listOf(
                stringResource(R.string.ptp_setup_step1),
                stringResource(R.string.ptp_setup_step2),
                stringResource(R.string.ptp_setup_step3),
            ),
        )

        Text(
            stringResource(R.string.ptp_setup_cameras_header),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        if (cameras.isEmpty()) {
            EmptyState(stringResource(R.string.ptp_setup_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cameras.forEach { device ->
                    UsbCameraCard(
                        device = device,
                        connecting = connecting,
                        onClick = { vm.connectPtp(device) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Canon BLE (BR-E1) ─────────────────────────────────────────────────────

@Composable
private fun CanonBleSetup(vm: PulsarViewModel) {
    val cameras by vm.canonBleCameras.collectAsState()
    val connecting by vm.canonBleConnecting.collectAsState()
    val awaitingConfirm by vm.canonBleAwaitingConfirm.collectAsState()
    val canonBleError by vm.canonBleError.collectAsState()
    val canonBleErrGeneric = stringResource(R.string.canon_ble_err_connect_failed)
    val canonBleErrNoShutter = stringResource(R.string.canon_ble_err_no_ble_shutter)
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    val scrollState = rememberScrollState()

    // Surface connect failures. "no_ble_shutter" = a smartphone-mode body with
    // no BLE shutter (2018 EOS R) — steer to USB/Wi-Fi rather than "failed".
    LaunchedEffect(canonBleError) {
        val err = canonBleError ?: return@LaunchedEffect
        val msg = if (err == "no_ble_shutter") canonBleErrNoShutter else canonBleErrGeneric
        android.widget.Toast.makeText(toastCtx, msg, android.widget.Toast.LENGTH_LONG).show()
        vm.clearCanonBleError()
    }

    // Owned scan lifecycle: only scan while this screen is visible. After
    // a successful connect Pulsar stops the scan internally; on a
    // spontaneous link drop the viewmodel re-arms it.
    DisposableEffect(Unit) {
        vm.startCanonBleScan()
        onDispose { vm.stopCanonBleScan() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        InstructionCard(
            iconRes = Icons.Default.Bluetooth,
            lines = listOf(
                stringResource(R.string.canon_ble_setup_step1),
                stringResource(R.string.canon_ble_setup_step2),
                stringResource(R.string.canon_ble_setup_step3),
                stringResource(R.string.canon_ble_setup_step4),
            ),
        )

        // Smartphone-mode pairing: the camera shows its own confirm prompt and
        // we wait for the user to accept it on the body.
        if (awaitingConfirm) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.canon_ble_awaiting_confirm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.canon_ble_setup_cameras_header),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                vm.stopCanonBleScan(); vm.startCanonBleScan()
            }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pulsar_ble_setup_rescan))
            }
        }

        if (cameras.isEmpty()) {
            EmptyState(stringResource(R.string.canon_ble_setup_empty))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cameras.forEach { device ->
                    CanonBleCameraCard(
                        device = device,
                        connecting = connecting,
                        onClick = { vm.connectCanonBle(device) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Pulsar BLE — discovered-device card ───────────────────────────────────
// Inline copy of ScanScreen's DeviceCard so the legacy file stays
// untouched until Phase 4 deletes it.

@Composable
private fun PulsarBleDeviceCard(scanned: ScannedDevice, onClick: () -> Unit) {
    val icon = when (scanned.boardKind) {
        BoardKind.M5STICK_S3, BoardKind.M5CORE2 -> Icons.Default.Bluetooth
        else -> Icons.Default.Bluetooth
    }
    val boardLabelText = when (scanned.boardKind) {
        BoardKind.M5STICK_S3 -> stringResource(R.string.board_m5stick_s3)
        BoardKind.M5CORE2 -> stringResource(R.string.board_m5core2)
        BoardKind.GENERIC_ESP32 -> stringResource(R.string.board_esp32_generic)
        BoardKind.UNKNOWN -> null
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    @Suppress("MissingPermission")
                    scanned.device.name ?: stringResource(R.string.pulsar_ble_setup_unknown_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (boardLabelText != null) {
                    Text(
                        boardLabelText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    @Suppress("MissingPermission") scanned.device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
