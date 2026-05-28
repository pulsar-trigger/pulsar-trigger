/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import com.ehrocha.pulsar.AppConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@SuppressLint("MissingPermission")
@Composable
fun ScanScreen(vm: PulsarViewModel, onConnected: () -> Unit) {
    val scanning by vm.scanning.collectAsState()
    val devices by vm.devices.collectAsState()
    val canonCcapiCameras by vm.canonCcapiCameras.collectAsState()
    val ptpCameras by vm.ptpCameras.collectAsState()
    val ptpConnecting by vm.ptpConnecting.collectAsState()
    val ptpError by vm.ptpError.collectAsState()
    val ptpErrorPermissionDenied = stringResource(R.string.ptp_err_permission_denied)
    val ptpErrorOpenFailed = stringResource(R.string.ptp_err_open_failed)
    val ptpErrorSessionFailed = stringResource(R.string.ptp_err_session_failed)
    val ptpErrorGeneric = stringResource(R.string.ptp_err_generic)
    val canonBleCameras by vm.canonBleCameras.collectAsState()
    val canonBleConnecting by vm.canonBleConnecting.collectAsState()
    val canonBleError by vm.canonBleError.collectAsState()
    val canonBleErrorGeneric = stringResource(R.string.canon_ble_err_connect_failed)
    val connected by vm.connected.collectAsState()
    val canonCcapiConnecting by vm.canonCcapiConnecting.collectAsState()
    val canonCcapiError by vm.canonCcapiError.collectAsState()
    val canonCcapiAuthPrompt by vm.canonCcapiAuthPrompt.collectAsState()
    val canonCcapiNicknames by vm.canonCcapiNicknames.collectAsState()
    val currentSsid by vm.currentWifiSsid.collectAsState()
    var canonInfo by remember { mutableStateOf<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null) }
    var renameCanon by remember { mutableStateOf<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null) }
    var capabilitiesCanon by remember { mutableStateOf<com.ehrocha.pulsar.transport.ccapi.CanonCamera?>(null) }
    var showCanonSetupHelp by remember { mutableStateOf(false) }
    var showCanonManualAdd by remember { mutableStateOf(false) }
    var showDeviceTypesHelp by remember { mutableStateOf(false) }

    if (connected) {
        LaunchedEffect(Unit) { onConnected() }
    }

    // Surface PTP connection failures as a Toast so the user knows why a
    // tap didn't go through. The viewmodel keeps the error sticky in the
    // flow; we clear it once the toast is in flight.
    val toastCtx = LocalContext.current
    LaunchedEffect(ptpError) {
        val e = ptpError ?: return@LaunchedEffect
        val msg = when (e) {
            "permission_denied" -> ptpErrorPermissionDenied
            "open_failed" -> ptpErrorOpenFailed
            "session_failed" -> ptpErrorSessionFailed
            else -> ptpErrorGeneric.format(e)
        }
        android.widget.Toast.makeText(toastCtx, msg, android.widget.Toast.LENGTH_LONG).show()
        vm.clearPtpError()
    }

    // Same pattern for Canon BLE connect failures.
    LaunchedEffect(canonBleError) {
        if (canonBleError == null) return@LaunchedEffect
        android.widget.Toast.makeText(toastCtx, canonBleErrorGeneric, android.widget.Toast.LENGTH_LONG).show()
        vm.clearCanonBleError()
    }

    // Run a Canon BLE scan while this screen is visible. The Pulsar ESP32
    // scan and the Canon BLE scan use independent ScanCallbacks with
    // different filters — they run concurrently.
    DisposableEffect(Unit) {
        vm.startCanonBleScan()
        onDispose { vm.stopCanonBleScan() }
    }

    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(64.dp))

        // Brand Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = stringResource(R.string.cd_bluetooth), tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
            }
            IconButton(onClick = { showLanguageDialog = true }) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = stringResource(R.string.section_language),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        Text(
            stringResource(R.string.available_devices),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.select_device_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = { showDeviceTypesHelp = true },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Default.HelpOutline,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.scan_compatible_devices_link),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.scan_pull_hint),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        // Animated bouncing chevron
        val bounce = rememberInfiniteTransition(label = "bounce")
        val offsetY by bounce.animateFloat(
            initialValue = 0f,
            targetValue = 8f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "chevronBounce",
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier
                .size(28.dp)
                .offset(y = offsetY.dp),
        )

        Spacer(Modifier.height(8.dp))

        PullToRefreshBox(
            isRefreshing = scanning,
            onRefresh = { vm.stopScan(); vm.startScan() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(devices) { scanned ->
                    DeviceCard(scanned) { vm.connectTo(scanned.device) }
                }
                if (canonCcapiCameras.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                stringResource(R.string.section_canon_cameras),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            currentSsid?.let { ssid ->
                                Text(
                                    stringResource(R.string.canon_on_wifi, ssid),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(canonCcapiCameras) { camera ->
                        CanonCameraCard(
                            camera = camera,
                            nickname = canonCcapiNicknames[camera.udn],
                            onClick = { canonInfo = camera },
                            onRename = { renameCanon = camera },
                            onCapabilities = { capabilitiesCanon = camera },
                        )
                    }
                }
                if (ptpCameras.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                stringResource(R.string.section_usb_cameras),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.usb_cameras_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(ptpCameras) { device ->
                        UsbCameraCard(
                            device = device,
                            connecting = ptpConnecting,
                            onClick = { vm.connectPtp(device) },
                        )
                    }
                }
                if (canonBleCameras.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            Text(
                                stringResource(R.string.section_canon_ble_remotes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.canon_ble_remotes_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(canonBleCameras) { device ->
                        CanonBleCameraCard(
                            device = device,
                            connecting = canonBleConnecting,
                            onClick = { vm.connectCanonBle(device) },
                        )
                    }
                }
                if (devices.isEmpty() && canonCcapiCameras.isEmpty() &&
                    ptpCameras.isEmpty() && canonBleCameras.isEmpty()) {
                    item { PairingProtocolCard() }
                }
                item {
                    Row {
                        TextButton(onClick = { showCanonSetupHelp = true }) {
                            Icon(
                                Icons.Default.HelpOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.canon_setup_button),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        TextButton(onClick = { showCanonManualAdd = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.canon_manual_add_button),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

        // Simulator option
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Surface(
            onClick = { vm.connectSimulator() },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = stringResource(R.string.cd_simulator),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.use_simulator),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.simulator_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(onDismiss = { showLanguageDialog = false })
    }

    if (showCanonSetupHelp) {
        CanonSetupHelpDialog(onDismiss = { showCanonSetupHelp = false })
    }

    if (showDeviceTypesHelp) {
        DeviceTypesHelpDialog(onDismiss = { showDeviceTypesHelp = false })
    }

    if (showCanonManualAdd) {
        CanonManualAddDialog(
            adding = vm.canonCcapiManualAdding.collectAsState().value,
            error = vm.canonCcapiManualError.collectAsState().value,
            onDismiss = {
                showCanonManualAdd = false
                vm.clearCanonCcapiManualError()
            },
            onSubmit = { input ->
                vm.addCanonCcapiByHost(input) { ok ->
                    if (ok) showCanonManualAdd = false
                }
            },
        )
    }

    renameCanon?.let { cam ->
        CanonRenameDialog(
            camera = cam,
            initial = canonCcapiNicknames[cam.udn].orEmpty(),
            onDismiss = { renameCanon = null },
            onConfirm = { newName ->
                vm.setCanonCcapiNickname(cam.udn, newName)
                renameCanon = null
            },
        )
    }

    capabilitiesCanon?.let { cam ->
        CanonCapabilitiesDialog(
            camera = cam,
            probe = { vm.probeCanonCapabilities(cam) },
            onDismiss = { capabilitiesCanon = null },
        )
    }

    canonCcapiAuthPrompt?.let { cam ->
        CanonAuthDialog(
            camera = cam,
            connecting = canonCcapiConnecting,
            onCancel = { vm.cancelCanonCcapiAuth() },
            onSubmit = { u, p -> vm.submitCanonCredentials(cam, u, p) },
        )
    }

    // Dismiss the connect dialog automatically once we successfully connect.
    LaunchedEffect(connected, canonInfo) {
        if (connected && canonInfo != null) canonInfo = null
    }
    // Auth dialog takes over once we know credentials are needed — otherwise
    // both dialogs stack on top of each other.
    LaunchedEffect(canonCcapiAuthPrompt) {
        if (canonCcapiAuthPrompt != null) canonInfo = null
    }

    canonInfo?.let { cam ->
        AlertDialog(
            onDismissRequest = { if (!canonCcapiConnecting) { canonInfo = null; vm.clearCanonCcapiError() } },
            confirmButton = {
                TextButton(
                    onClick = { vm.connectCanonCcapi(cam) },
                    enabled = !canonCcapiConnecting,
                ) {
                    Text(
                        if (canonCcapiConnecting) stringResource(R.string.canon_connecting)
                        else stringResource(R.string.canon_connect)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { canonInfo = null; vm.clearCanonCcapiError() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            icon = {
                Icon(Icons.Default.CameraAlt, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
            },
            title = { Text(cam.nickname ?: cam.friendlyName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(cam.friendlyName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("${cam.ipAddress}:${cam.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(cam.accessUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
}

@Composable
private fun LanguagePickerDialog(onDismiss: () -> Unit) {
    val currentLocale = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    val currentTag = if (currentLocale.isEmpty) "" else currentLocale.toLanguageTags()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.section_language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // System default
                val isSystemDefault = currentTag.isEmpty()
                Surface(
                    onClick = {
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                        )
                        onDismiss()
                    },
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = if (isSystemDefault) 4.dp else 0.dp,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    ) {
                        RadioButton(selected = isSystemDefault, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.lang_system_default),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                AppConfig.SUPPORTED_LOCALES.forEach { (tag, label) ->
                    val selected = currentTag.startsWith(tag)
                    Surface(
                        onClick = {
                            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                                androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                            )
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = if (selected) 4.dp else 0.dp,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (tag != "en") {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "(${java.util.Locale(tag).getDisplayLanguage(java.util.Locale.ENGLISH)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
internal fun CanonAuthDialog(
    camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
    connecting: Boolean,
    onCancel: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var revealPass by remember { mutableStateOf(false) }
    val canSubmit = user.isNotBlank() && pass.isNotEmpty() && !connecting

    AlertDialog(
        onDismissRequest = { if (!connecting) onCancel() },
        icon = {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.canon_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.canon_auth_subtitle, camera.friendlyName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.canon_auth_user)) },
                    singleLine = true,
                    enabled = !connecting,
                )
                val passTransform: androidx.compose.ui.text.input.VisualTransformation =
                    if (revealPass) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation()
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text(stringResource(R.string.canon_auth_pass)) },
                    singleLine = true,
                    enabled = !connecting,
                    visualTransformation = passTransform,
                    trailingIcon = {
                        IconButton(onClick = { revealPass = !revealPass }) {
                            val icon = if (revealPass) Icons.Default.VisibilityOff
                                       else Icons.Default.Visibility
                            val descRes = if (revealPass) R.string.canon_auth_hide_pass
                                          else R.string.canon_auth_show_pass
                            Icon(icon, contentDescription = stringResource(descRes))
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(user.trim(), pass) },
                enabled = canSubmit,
            ) {
                Text(
                    if (connecting) stringResource(R.string.canon_connecting)
                    else stringResource(R.string.canon_connect)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !connecting) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun CanonCapabilitiesDialog(
    camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
    probe: suspend () -> com.ehrocha.pulsar.viewmodel.PulsarViewModel.CanonCapabilities?,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var caps by remember {
        mutableStateOf<com.ehrocha.pulsar.viewmodel.PulsarViewModel.CanonCapabilities?>(null)
    }
    LaunchedEffect(camera.udn) {
        loading = true
        caps = probe()
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.canon_camera_capabilities)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    camera.friendlyName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.canon_capabilities_probing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    caps == null -> Text(
                        stringResource(R.string.canon_capabilities_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        val c = caps!!
                        Text(
                            stringResource(R.string.canon_capabilities_version, c.version, c.endpointCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        CapabilityRow(stringResource(R.string.canon_cap_bulb), c.supportsBulb)
                        CapabilityRow(stringResource(R.string.canon_cap_shooting_mode), c.supportsShootingMode)
                        CapabilityRow(stringResource(R.string.canon_cap_dial_ignore), c.supportsDialIgnore)
                        CapabilityRow(stringResource(R.string.canon_cap_polling), c.supportsPolling)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
internal fun CapabilityRow(label: String, supported: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (supported) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (supported) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CanonManualAddDialog(
    adding: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    val canSubmit = host.trim().isNotEmpty() && !adding
    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        icon = {
            Icon(Icons.Default.Add, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text(stringResource(R.string.canon_manual_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.canon_manual_add_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text(stringResource(R.string.canon_manual_add_label)) },
                    placeholder = { Text("192.168.1.2:8080") },
                    singleLine = true,
                    enabled = !adding,
                )
                if (error != null) {
                    Text(
                        text = when (error) {
                            "not_found" -> stringResource(R.string.canon_manual_add_not_found)
                            "invalid" -> stringResource(R.string.canon_manual_add_invalid)
                            else -> error
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(host) }, enabled = canSubmit) {
                Text(
                    if (adding) stringResource(R.string.canon_manual_add_probing)
                    else stringResource(R.string.canon_manual_add_action)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun CanonRenameDialog(
    camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canon_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.canon_rename_subtitle, camera.friendlyName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.canon_rename_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun CanonSetupHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.canon_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.canon_setup_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.canon_setup_step_1),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.canon_setup_step_2),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.canon_setup_step_3),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.canon_setup_step_4),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.canon_setup_step_5),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.canon_setup_long_runs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.canon_setup_battery_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun PairingProtocolCard() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.pairing_protocol_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            PairingStep("01", stringResource(R.string.pairing_step_1))
            PairingStep("02", stringResource(R.string.pairing_step_2))
            PairingStep("03", stringResource(R.string.pairing_step_3))
        }
    }
}

@Composable
private fun PairingStep(number: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            number,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceCard(scanned: com.ehrocha.pulsar.ble.ScannedDevice, onClick: () -> Unit) {
    val device = scanned.device
    val boardLabel = boardLabel(scanned.boardKind)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Board-specific icon
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        boardIcon(scanned.boardKind),
                        contentDescription = boardLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name ?: stringResource(R.string.unknown_device),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (boardLabel != null) {
                    Text(
                        boardLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun CanonCameraCard(
    camera: com.ehrocha.pulsar.transport.ccapi.CanonCamera,
    nickname: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onCapabilities: () -> Unit,
) {
    val displayName = nickname?.takeIf { it.isNotEmpty() }
        ?: camera.nickname
        ?: camera.friendlyName
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = stringResource(R.string.cd_canon_camera),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                // When the user has set a nickname, surface the body's own
                // friendly name underneath so they can still tell which model
                // it is (e.g. "Astro Cam" / "EOS R10").
                if (!nickname.isNullOrEmpty()) {
                    Text(
                        camera.friendlyName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        stringResource(R.string.canon_camera_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "${camera.ipAddress}:${camera.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.canon_camera_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.canon_camera_rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.canon_camera_capabilities)) },
                        onClick = { menuOpen = false; onCapabilities() },
                    )
                }
            }
        }
    }
}

@Composable
private fun boardIcon(kind: com.ehrocha.pulsar.ble.BoardKind): androidx.compose.ui.graphics.vector.ImageVector =
    when (kind) {
        com.ehrocha.pulsar.ble.BoardKind.M5STICK_S3 ->
            androidx.compose.material.icons.Icons.Default.Smartphone
        com.ehrocha.pulsar.ble.BoardKind.M5CORE2 ->
            androidx.compose.material.icons.Icons.Default.DeveloperBoard
        com.ehrocha.pulsar.ble.BoardKind.GENERIC_ESP32 ->
            androidx.compose.material.icons.Icons.Default.Memory
        com.ehrocha.pulsar.ble.BoardKind.UNKNOWN ->
            androidx.compose.material.icons.Icons.Default.Bluetooth
    }

@Composable
private fun boardLabel(kind: com.ehrocha.pulsar.ble.BoardKind): String? = when (kind) {
    com.ehrocha.pulsar.ble.BoardKind.M5STICK_S3 -> stringResource(R.string.board_m5stick_s3)
    com.ehrocha.pulsar.ble.BoardKind.M5CORE2 -> stringResource(R.string.board_m5core2)
    com.ehrocha.pulsar.ble.BoardKind.GENERIC_ESP32 -> stringResource(R.string.board_esp32_generic)
    com.ehrocha.pulsar.ble.BoardKind.UNKNOWN -> null
}

@Composable
internal fun UsbCameraCard(
    device: android.hardware.usb.UsbDevice,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    val name = device.productName ?: device.manufacturerName ?: "USB camera"
    Surface(
        onClick = if (connecting) ({}) else onClick,
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
                        Icons.Default.Usb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.usb_camera_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "VID 0x%04X · PID 0x%04X".format(device.vendorId, device.productId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun CanonBleCameraCard(
    device: android.bluetooth.BluetoothDevice,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    val name = try {
        @Suppress("MissingPermission") device.name
    } catch (_: SecurityException) { null }
        ?: stringResource(R.string.canon_ble_camera_fallback_name)
    Surface(
        onClick = if (connecting) ({}) else onClick,
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
                        Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.canon_ble_camera_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceTypesHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DeviceTypeHelpRow(
                    icon = Icons.Default.Bluetooth,
                    title = stringResource(R.string.scan_help_esp_title),
                    body = stringResource(R.string.scan_help_esp_body),
                )
                DeviceTypeHelpRow(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.scan_help_ccapi_title),
                    body = stringResource(R.string.scan_help_ccapi_body),
                )
                DeviceTypeHelpRow(
                    icon = Icons.Default.Usb,
                    title = stringResource(R.string.scan_help_ptp_title),
                    body = stringResource(R.string.scan_help_ptp_body),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}

@Composable
private fun DeviceTypeHelpRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(end = 12.dp, top = 2.dp)
                .size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
