/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.update.AppUpdateState
import com.ehrocha.pulsar.ui.components.BatteryIndicator
import com.ehrocha.pulsar.ui.components.SignalStrengthIndicator
import com.ehrocha.pulsar.ui.components.NightModeToggle
import com.ehrocha.pulsar.ui.screens.MainMenuScreen
import com.ehrocha.pulsar.ui.screens.ModeScreen
import com.ehrocha.pulsar.ui.screens.ScanScreen
import com.ehrocha.pulsar.ui.screens.SettingsScreen
import com.ehrocha.pulsar.ui.screens.SettingsSection
import com.ehrocha.pulsar.ui.screens.CustomFlowScreen
import com.ehrocha.pulsar.ui.screens.AlignmentScreen
import com.ehrocha.pulsar.ui.screens.DashboardScreen
import com.ehrocha.pulsar.ui.screens.PlannerScreen
import com.ehrocha.pulsar.ui.screens.EventSessionsScreen
import com.ehrocha.pulsar.ui.screens.SessionDetailScreen
import com.ehrocha.pulsar.ui.screens.MapLocationPicker
import com.ehrocha.pulsar.ui.screens.MapPickerResult
import com.ehrocha.pulsar.ui.theme.DarkColorScheme
import com.ehrocha.pulsar.ui.theme.LightColorScheme
import com.ehrocha.pulsar.ui.theme.RedLightColorScheme
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceRssi
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.LocalNightModeLocked
import com.ehrocha.pulsar.planner.PlannerEvent
import com.ehrocha.pulsar.planner.PlannerSession
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.google.accompanist.permissions.rememberMultiplePermissionsState

class MainActivity : AppCompatActivity() {
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val nightMode = remember { mutableStateOf(ThemeMode.Dark) }
            val nightModeLocked = remember { mutableStateOf(false) }
            val colorScheme = when (nightMode.value) {
                ThemeMode.Light -> LightColorScheme
                ThemeMode.Dark -> DarkColorScheme
                ThemeMode.RedLight -> RedLightColorScheme
            }
            CompositionLocalProvider(
                LocalNightMode provides nightMode,
                LocalNightModeLocked provides nightModeLocked,
            ) {
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

    // Auto-navigate should only fire once per connection (on initial connect
    // to an already-running device), not every time Menu is visited.
    var autoNavDone by remember { mutableStateOf(false) }

    // Reset dismissed flag when disconnected so it shows again on next connect
    LaunchedEffect(connected) {
        if (!connected) {
            dismissedUpdateDialog = false
            autoNavDone = false
        }
    }

    val hasFwUpdate = fwState == OtaState.AVAILABLE && fwRelease != null
    val hasAppUpdate = appState == AppUpdateState.AVAILABLE && appRelease != null

    if (connected && (hasFwUpdate || hasAppUpdate) && !dismissedUpdateDialog && currentScreen is AppScreen.Menu) {
        // Non-blocking: handled by the banner in MainMenuScreen and badge on Settings gear
    }

    // Go back to scan if disconnected
    LaunchedEffect(connected) {
        if (!connected) currentScreen = AppScreen.Scan
    }

    // Auto-navigate to running mode when connecting to a busy device.
    // Only fires once per connection to avoid bouncing the user back after
    // they press Stop (firmware may still report RUNNING briefly).
    LaunchedEffect(currentScreen) {
        if (currentScreen == AppScreen.Menu && !autoNavDone) {
            autoNavDone = true
            vm.status.filterNotNull().first().let { s ->
                if (s.state == DeviceState.RUNNING || s.state == DeviceState.WAITING) {
                    val mode = vm.currentMode.value
                    when (mode) {
                        TriggerMode.TRACKER -> { /* stay on menu */ }
                        TriggerMode.PRESS_HOLD, TriggerMode.PRESS_LOCK ->
                            currentScreen = AppScreen.Mode(mode)
                        else -> currentScreen = AppScreen.CustomFlow()
                    }
                }
            }
        }
    }

    val deviceStatus by vm.status.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val deviceRssi by vm.rssi.collectAsState()
    CompositionLocalProvider(
        LocalDeviceStatus provides deviceStatus,
        LocalDeviceConnected provides connected,
        LocalDeviceRssi provides deviceRssi,
    ) {
    Column(Modifier.fillMaxSize()) {
        // ── Persistent top bar (hidden on Scan screen) ───────────────
        if (currentScreen !is AppScreen.Scan) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = deviceName.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.weight(1f),
                    )
                    BatteryIndicator()
                    SignalStrengthIndicator()
                    NightModeToggle()
                    Spacer(Modifier.width(4.dp))
                    val hasAnyUpdate = hasFwUpdate || hasAppUpdate
                    IconButton(onClick = { currentScreen = AppScreen.Settings() }) {
                        BadgedBox(
                            badge = {
                                if (hasAnyUpdate) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.menu_settings),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // ── Screen content ───────────────────────────────────────────
        Box(Modifier.fillMaxSize()) {
        when (val screen = currentScreen) {
            AppScreen.Scan -> ScanScreen(vm) { currentScreen = AppScreen.Menu }
            AppScreen.Menu -> MainMenuScreen(
                vm = vm,
                onQuickFlow = { type ->
                    val mode = when (type) {
                        FlowStepType.INTERVALOMETER -> TriggerMode.INTERVALOMETER
                        FlowStepType.ASTRO -> TriggerMode.ASTRO
                        else -> return@MainMenuScreen
                    }
                    currentScreen = AppScreen.Mode(mode)
                },
                onManualSelected = { currentScreen = AppScreen.Mode(TriggerMode.PRESS_HOLD) },
                onCustomFlowSelected = { currentScreen = AppScreen.CustomFlow() },
                onDashboardSelected = { currentScreen = AppScreen.Dashboard },
                onPlannerSelected = { currentScreen = AppScreen.Planner },
                onAlignmentSelected = { currentScreen = AppScreen.Alignment },
                onSettingsSelected = { currentScreen = AppScreen.Settings(SettingsSection.UPDATES) },
            )
            is AppScreen.Mode -> {
                BackHandler { currentScreen = AppScreen.Menu }
                ModeScreen(
                    vm = vm,
                    targetMode = screen.mode,
                    onBack = { currentScreen = AppScreen.Menu },
                    onStartFlow = {
                        vm.loadQuickMode(
                            when (screen.mode) {
                                TriggerMode.INTERVALOMETER -> FlowStepType.INTERVALOMETER
                                TriggerMode.ASTRO -> FlowStepType.ASTRO
                                else -> return@ModeScreen
                            }
                        )
                        vm.startFlow()
                        currentScreen = AppScreen.CustomFlow(quickLaunch = true)
                    },
                )
            }
            is AppScreen.Settings -> {
                BackHandler { currentScreen = AppScreen.Menu }
                SettingsScreen(
                    vm = vm,
                    initialSection = screen.initialSection,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            is AppScreen.CustomFlow -> {
                BackHandler { currentScreen = AppScreen.Menu }
                CustomFlowScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                    quickLaunch = screen.quickLaunch,
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
                    onEventSessions = { event -> currentScreen = AppScreen.EventSessions(event) },
                )
            }
            is AppScreen.MapPicker -> {
                BackHandler { currentScreen = AppScreen.EventSessions(screen.event) }
                MapLocationPicker(
                    onBack = { currentScreen = AppScreen.EventSessions(screen.event) },
                    onConfirm = { name, lat, lon ->
                        currentScreen = AppScreen.EventSessions(
                            screen.event,
                            mapResult = MapPickerResult(name, lat, lon),
                        )
                    },
                    initialLat = screen.initialLat,
                    initialLon = screen.initialLon,
                )
            }
            is AppScreen.EventSessions -> {
                BackHandler { currentScreen = AppScreen.Planner }
                EventSessionsScreen(
                    event = screen.event,
                    plannerManager = vm.plannerManager,
                    onBack = { currentScreen = AppScreen.Planner },
                    onSessionDetail = { session, event ->
                        currentScreen = AppScreen.SessionDetail(session, event)
                    },
                    onPickOnMap = { lat, lon ->
                        currentScreen = AppScreen.MapPicker(screen.event, lat, lon)
                    },
                    mapResult = screen.mapResult,
                )
            }
            is AppScreen.SessionDetail -> {
                BackHandler { currentScreen = AppScreen.EventSessions(screen.event) }
                SessionDetailScreen(
                    session = screen.session,
                    event = screen.event,
                    plannerManager = vm.plannerManager,
                    onBack = { currentScreen = AppScreen.EventSessions(screen.event) },
                )
            }
            AppScreen.Alignment -> {
                BackHandler { currentScreen = AppScreen.Menu }
                AlignmentScreen(
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
        }
    }
    }
    }
}

private sealed class AppScreen {
    data object Scan : AppScreen()
    data object Menu : AppScreen()
    data class Mode(val mode: TriggerMode) : AppScreen()
    data class Settings(val initialSection: SettingsSection? = null) : AppScreen()
    data class CustomFlow(val quickLaunch: Boolean = false) : AppScreen()
    data object Dashboard : AppScreen()
    data object Planner : AppScreen()
    data class MapPicker(val event: PlannerEvent, val initialLat: Double = 0.0, val initialLon: Double = 0.0) : AppScreen()
    data class EventSessions(val event: PlannerEvent, val mapResult: MapPickerResult? = null) : AppScreen()
    data class SessionDetail(val session: PlannerSession, val event: PlannerEvent) : AppScreen()
    data object Alignment : AppScreen()
}
