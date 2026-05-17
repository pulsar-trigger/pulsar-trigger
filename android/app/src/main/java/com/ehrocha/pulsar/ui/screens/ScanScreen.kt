/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val connected by vm.connected.collectAsState()

    if (connected) {
        LaunchedEffect(Unit) { onConnected() }
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
                if (devices.isEmpty()) {
                    item { PairingProtocolCard() }
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
