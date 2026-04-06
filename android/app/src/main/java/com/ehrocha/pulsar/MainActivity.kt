/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.update.AppUpdateState
import com.ehrocha.pulsar.ui.screens.MainMenuScreen
import com.ehrocha.pulsar.ui.screens.ModeScreen
import com.ehrocha.pulsar.ui.screens.ModeSettingsScreen
import com.ehrocha.pulsar.ui.screens.ScanScreen
import com.ehrocha.pulsar.ui.screens.SettingsScreen
import com.ehrocha.pulsar.ui.screens.CustomFlowScreen
import com.ehrocha.pulsar.ui.screens.DashboardScreen
import com.ehrocha.pulsar.ui.screens.PlannerScreen
import com.ehrocha.pulsar.ui.theme.DarkColorScheme
import com.ehrocha.pulsar.ui.theme.RedLightColorScheme
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val nightMode = remember { mutableStateOf(false) }
            val colorScheme = if (nightMode.value) RedLightColorScheme else DarkColorScheme
            CompositionLocalProvider(LocalNightMode provides nightMode) {
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val permissions = rememberMultiplePermissionsState(
                        buildList {
                            add(android.Manifest.permission.BLUETOOTH_SCAN)
                            add(android.Manifest.permission.BLUETOOTH_CONNECT)
                            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )

                    LaunchedEffect(Unit) {
                        if (!permissions.allPermissionsGranted) {
                            permissions.launchMultiplePermissionRequest()
                        }
                    }

                    if (permissions.allPermissionsGranted) {
                        PulsarNavHost()
                    }
                }
            }
            }
        }
    }
}

@Composable
fun PulsarNavHost(vm: PulsarViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Scan) }
    val connected by vm.connected.collectAsState()

    // ── Update-available dialog ──────────────────────────────────────
    val fwState by vm.firmwareManager.state.collectAsState()
    val appState by vm.appUpdateManager.state.collectAsState()
    val fwRelease by vm.firmwareManager.latestRelease.collectAsState()
    val appRelease by vm.appUpdateManager.latestRelease.collectAsState()
    var dismissedUpdateDialog by remember { mutableStateOf(false) }

    // Reset dismissed flag when disconnected so it shows again on next connect
    LaunchedEffect(connected) {
        if (!connected) dismissedUpdateDialog = false
    }

    val hasFwUpdate = fwState == OtaState.AVAILABLE && fwRelease != null
    val hasAppUpdate = appState == AppUpdateState.AVAILABLE && appRelease != null

    if (connected && (hasFwUpdate || hasAppUpdate) && !dismissedUpdateDialog && currentScreen != AppScreen.Settings) {
        AlertDialog(
            onDismissRequest = { dismissedUpdateDialog = true },
            title = { Text(stringResource(R.string.dialog_updates_available)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasFwUpdate) {
                        Text(stringResource(R.string.update_firmware_available, fwRelease!!.version))
                    }
                    if (hasAppUpdate) {
                        Text(stringResource(R.string.update_app_available, appRelease!!.version))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.update_go_to_settings),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissedUpdateDialog = true
                    currentScreen = AppScreen.Settings
                }) { Text(stringResource(R.string.btn_go_to_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { dismissedUpdateDialog = true }) { Text(stringResource(R.string.btn_later)) }
            },
        )
    }

    // Go back to scan if disconnected
    LaunchedEffect(connected) {
        if (!connected) currentScreen = AppScreen.Scan
    }

    // Auto-navigate to running mode when connecting to a busy device
    LaunchedEffect(currentScreen) {
        if (currentScreen == AppScreen.Menu) {
            vm.status.filterNotNull().first().let { s ->
                if (s.state == DeviceState.RUNNING || s.state == DeviceState.WAITING) {
                    // Use the ViewModel's selected mode (avoids ambiguity when
                    // firmware modes like ASTRO share the same byte as INTERVALOMETER)
                    currentScreen = AppScreen.Mode(vm.currentMode.value)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val screen = currentScreen) {
            AppScreen.Scan -> ScanScreen(vm) { currentScreen = AppScreen.Menu }
            AppScreen.Menu -> MainMenuScreen(
                vm = vm,
                onModeSelected = { currentScreen = AppScreen.Mode(it) },
                onModeSettingsSelected = { currentScreen = AppScreen.ModeSettings(it) },
                onSettingsSelected = { currentScreen = AppScreen.Settings },
                onCustomFlowSelected = { currentScreen = AppScreen.CustomFlow },
                onDashboardSelected = { currentScreen = AppScreen.Dashboard },
                onPlannerSelected = { currentScreen = AppScreen.Planner },
            )
            is AppScreen.Mode -> {
                BackHandler { currentScreen = AppScreen.Menu }
                ModeScreen(
                    vm = vm,
                    targetMode = screen.mode,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Settings -> {
                BackHandler { currentScreen = AppScreen.Menu }
                SettingsScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            is AppScreen.ModeSettings -> {
                BackHandler { currentScreen = AppScreen.Menu }
                ModeSettingsScreen(
                    vm = vm,
                    targetMode = screen.mode,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.CustomFlow -> {
                BackHandler { currentScreen = AppScreen.Menu }
                CustomFlowScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Dashboard -> {
                BackHandler { currentScreen = AppScreen.Menu }
                DashboardScreen(
                    dashboardManager = vm.dashboardManager,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Planner -> {
                BackHandler { currentScreen = AppScreen.Menu }
                PlannerScreen(
                    plannerManager = vm.plannerManager,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
        }

        // Global night-mode toggle (visible on every screen)
        val nightMode = LocalNightMode.current
        SmallFloatingActionButton(
            onClick = { nightMode.value = !nightMode.value },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .size(40.dp),
            containerColor = if (nightMode.value) Color(0xFFCC4444) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (nightMode.value) Color.White else MaterialTheme.colorScheme.onSurface,
        ) {
            Icon(
                if (nightMode.value) Icons.Default.LightMode else Icons.Default.Nightlight,
                contentDescription = stringResource(R.string.night_mode_toggle),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private sealed class AppScreen {
    data object Scan : AppScreen()
    data object Menu : AppScreen()
    data class Mode(val mode: TriggerMode) : AppScreen()
    data class ModeSettings(val mode: TriggerMode) : AppScreen()
    data object Settings : AppScreen()
    data object CustomFlow : AppScreen()
    data object Dashboard : AppScreen()
    data object Planner : AppScreen()
}
