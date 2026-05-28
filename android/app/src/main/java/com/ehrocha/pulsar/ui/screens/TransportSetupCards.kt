/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/**
 * Cards + dialogs referenced from the per-transport setup branches in
 * [TransportSetupScreen]. Lived in the legacy ScanScreen.kt before it was
 * deleted in Phase 4 of the scan-screen overhaul.
 *
 * Each composable here corresponds to a piece of UI the user touches
 * while picking / inspecting / pairing a camera. They're transport-card
 * fragments — not full screens — so the setup-screen scaffold can compose
 * them into its own layout.
 */

// ── Canon CCAPI ───────────────────────────────────────────────────────────

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
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                Text(displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                // When the user has set a nickname, surface the body's own
                // friendly name underneath so they can still tell which
                // model it is (e.g. "Astro Cam" / "EOS R10").
                if (!nickname.isNullOrEmpty()) {
                    Text(camera.friendlyName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(stringResource(R.string.canon_camera_subtitle),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text("${camera.ipAddress}:${camera.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.canon_camera_menu),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Icon(Icons.Default.Wifi, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text(stringResource(R.string.canon_auth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.canon_auth_subtitle, camera.friendlyName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = { onSubmit(user.trim(), pass) }, enabled = canSubmit) {
                Text(if (connecting) stringResource(R.string.canon_connecting)
                     else stringResource(R.string.canon_connect))
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
            Icon(Icons.Default.Wifi, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text(stringResource(R.string.canon_camera_capabilities)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(camera.friendlyName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.canon_capabilities_probing),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    caps == null -> Text(stringResource(R.string.canon_capabilities_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    else -> {
                        val c = caps!!
                        Text(stringResource(R.string.canon_capabilities_version, c.version, c.endpointCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
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
                Text(stringResource(R.string.canon_manual_add_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(if (adding) stringResource(R.string.canon_manual_add_probing)
                     else stringResource(R.string.canon_manual_add_action))
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
                Text(stringResource(R.string.canon_rename_subtitle, camera.friendlyName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ── USB PTP ───────────────────────────────────────────────────────────────

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
                    Icon(Icons.Default.Usb, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.usb_camera_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text("VID 0x%04X · PID 0x%04X".format(device.vendorId, device.productId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Canon BLE direct ──────────────────────────────────────────────────────

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
                    Icon(Icons.Default.Bluetooth, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.canon_ble_camera_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
