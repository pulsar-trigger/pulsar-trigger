/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ehrocha.pulsar.ble.DeviceState
import com.ehrocha.pulsar.ble.OtaState
import com.ehrocha.pulsar.ble.TriggerMode
import com.ehrocha.pulsar.model.FlowStepType
import com.ehrocha.pulsar.update.AppUpdateState
import com.ehrocha.pulsar.ui.components.BatteryIndicator
import com.ehrocha.pulsar.ui.components.LatencyIndicator
import com.ehrocha.pulsar.ui.components.SignalStrengthIndicator
import com.ehrocha.pulsar.ui.components.NightModeToggle
import com.ehrocha.pulsar.ui.screens.MainMenuScreen
import com.ehrocha.pulsar.ui.screens.ModeScreen
import com.ehrocha.pulsar.ui.screens.ScanScreen
import com.ehrocha.pulsar.ui.screens.SettingsScreen
import com.ehrocha.pulsar.ui.screens.SettingsSection
import com.ehrocha.pulsar.ui.screens.CustomFlowScreen
import com.ehrocha.pulsar.ui.screens.AlignmentScreen
import com.ehrocha.pulsar.ui.screens.WhatsUpScreen
import com.ehrocha.pulsar.ui.screens.DashboardScreen
import com.ehrocha.pulsar.ui.screens.PlannerScreen
import com.ehrocha.pulsar.ui.screens.EventSessionsScreen
import com.ehrocha.pulsar.ui.screens.SessionDetailScreen
import com.ehrocha.pulsar.ui.screens.MapLocationPicker
import com.ehrocha.pulsar.ui.screens.MapPickerResult
import com.ehrocha.pulsar.ui.theme.DarkColorScheme
import com.ehrocha.pulsar.ui.theme.LightColorScheme
import com.ehrocha.pulsar.ui.theme.OutdoorColorScheme
import com.ehrocha.pulsar.ui.theme.RedLightColorScheme
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.ui.theme.LocalDeviceConnected
import com.ehrocha.pulsar.ui.theme.LocalDeviceLatency
import com.ehrocha.pulsar.ui.theme.LocalDeviceRssi
import com.ehrocha.pulsar.ui.theme.LocalDeviceStatus
import com.ehrocha.pulsar.ui.theme.LocalRunState
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

    /** Read event JSON from an incoming VIEW/SEND intent (.pulsar file). */
    private fun readImportIntent(): String? {
        val intent = intent ?: return null
        return try {
            val uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                else -> null
            }
            uri?.let { contentResolver.openInputStream(it)?.bufferedReader()?.readText() }
        } catch (_: Exception) { null }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pendingImportJson = readImportIntent()
        enableEdgeToEdge()
        setContent {
            val nightMode = remember { mutableStateOf(ThemeMode.Dark) }
            val nightModeLocked = remember { mutableStateOf(false) }
            val colorScheme = when (nightMode.value) {
                ThemeMode.Light -> LightColorScheme
                ThemeMode.Outdoor -> OutdoorColorScheme
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
                        PulsarNavHost(importJson = pendingImportJson)
                    } else {
                        PermissionsRequiredScreen(
                            onRequestAgain = { permissions.launchMultiplePermissionRequest() },
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun PermissionsRequiredScreen(onRequestAgain: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.BluetoothSearching,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.permissions_required_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.permissions_required_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        androidx.compose.material3.Button(onClick = onRequestAgain) {
            Text(stringResource(R.string.permissions_grant_button))
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        }) {
            Text(stringResource(R.string.permissions_open_settings))
        }
    }
}

@Composable
fun PulsarNavHost(vm: PulsarViewModel = viewModel(), importJson: String? = null) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Scan) }
    var menuTab by remember { mutableIntStateOf(com.ehrocha.pulsar.ui.screens.TAB_TRIGGER) }
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

    // Go back to scan if disconnected (but not if we arrived via file import)
    var importHandled by remember { mutableStateOf(importJson != null) }
    LaunchedEffect(connected) {
        if (!connected && !importHandled) currentScreen = AppScreen.Scan
        if (connected) importHandled = false // once connected, normal disconnect-reset resumes
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

    // ── Auto-import from .pulsar file intent ──────────────────────
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(importJson) {
        if (importJson != null) {
            val event = vm.plannerManager.importEvent(importJson)
            if (event != null) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.planner_import_success, event.name),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                currentScreen = AppScreen.EventSessions(event)
            } else {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.planner_import_failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val deviceStatus by vm.status.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val deviceRssi by vm.rssi.collectAsState()
    val deviceLatency by vm.latencyMs.collectAsState()
    val runState by vm.runState.collectAsState()

    // Keep the screen awake while a sequence is running — long timelapses /
    // astro sessions outlast a phone's default sleep timer, and a sleeping
    // screen means the user can't glance progress without unlocking.
    val activityForWake = LocalContext.current as? android.app.Activity
    val runActive = runState !is com.ehrocha.pulsar.model.RunState.Idle
    DisposableEffect(runActive) {
        if (runActive) {
            activityForWake?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activityForWake?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    CompositionLocalProvider(
        LocalDeviceStatus provides deviceStatus,
        LocalRunState provides runState,
        LocalDeviceConnected provides connected,
        LocalDeviceRssi provides deviceRssi,
        LocalDeviceLatency provides deviceLatency,
    ) {
    Column(Modifier.fillMaxSize()) {
        // ── Persistent top bar (hidden on Scan screen) ───────────────
        if (currentScreen !is AppScreen.Scan) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
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
                    LatencyIndicator()
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        // ── Screen content ───────────────────────────────────────────
        Box(Modifier.weight(1f)) {
        when (val screen = currentScreen) {
            AppScreen.Scan -> ScanScreen(vm) { currentScreen = AppScreen.Menu }
            AppScreen.Menu -> {
                MainMenuScreen(
                    vm = vm,
                    initialTab = menuTab,
                    onTabChanged = { menuTab = it },
                    onQuickFlow = { type ->
                        // Intervalometer and Astro now have their own dedicated
                        // wizard screens (Iv2 / Astro 2). Dark Frame and Ramp
                        // still ride the legacy Mode screen until they get
                        // a similar rework.
                        when (type) {
                            FlowStepType.DARK_FRAME -> currentScreen = AppScreen.Mode(TriggerMode.DARK_FRAME)
                            FlowStepType.RAMP -> currentScreen = AppScreen.Mode(TriggerMode.RAMP)
                            else -> return@MainMenuScreen
                        }
                    },
                    onManualSelected = { currentScreen = AppScreen.Mode(TriggerMode.PRESS_HOLD) },
                    onIntervalometer2Selected = { currentScreen = AppScreen.Intervalometer2 },
                    onAstroMode2Selected = { currentScreen = AppScreen.AstroMode2 },
                    onUserModeRun = { mode ->
                        // Push the preset into global params so the mode panel
                        // reflects what's running, then kick off the flow and
                        // navigate so the user lands on the live progress view.
                        applyUserModeParams(vm, mode)
                        vm.runUserMode(mode)
                        currentScreen = AppScreen.Mode(mode.body.fwMode)
                    },
                    onCustomFlowSelected = { currentScreen = AppScreen.CustomFlow() },
                    onPlannerSelected = { currentScreen = AppScreen.Planner },
                    onAlignmentSelected = { currentScreen = AppScreen.Alignment },
                    onWhatsUpSelected = { currentScreen = AppScreen.WhatsUp },
                    onModesSelected = { currentScreen = AppScreen.Modes },
                    onSettingsSelected = { currentScreen = AppScreen.Settings(SettingsSection.UPDATES) },
                )
            }
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
                                TriggerMode.DARK_FRAME -> FlowStepType.DARK_FRAME
                                TriggerMode.RAMP -> FlowStepType.RAMP
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
            AppScreen.WhatsUp -> {
                BackHandler { currentScreen = AppScreen.Menu }
                WhatsUpScreen(
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Modes -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.ModesScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                    onEdit = { id -> currentScreen = AppScreen.ModeEditor(id) },
                )
            }
            is AppScreen.ModeEditor -> {
                BackHandler { currentScreen = AppScreen.Modes }
                com.ehrocha.pulsar.ui.screens.ModeEditorScreen(
                    vm = vm,
                    editingId = screen.modeId,
                    onBack = { currentScreen = AppScreen.Modes },
                )
            }
            AppScreen.ShotLog -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.ShotLogScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Intervalometer2 -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.Intervalometer2Screen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.AstroMode2 -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.AstroMode2Screen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
        }
    }
        // ── Bottom bar (Menu screen only) ────────────────────────────
        if (currentScreen is AppScreen.Menu) {
            val hasAnyUpdate = hasFwUpdate || hasAppUpdate
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    IconButton(onClick = { vm.disconnect() }) {
                        Icon(
                            Icons.Default.LinkOff,
                            contentDescription = stringResource(R.string.disconnect),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NightModeToggle()
                    IconButton(onClick = { currentScreen = AppScreen.ShotLog }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = stringResource(R.string.shot_log_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { currentScreen = AppScreen.Settings() }) {
                        BadgedBox(
                            badge = {
                                if (hasAnyUpdate) {
                                    Badge(containerColor = MaterialTheme.colorScheme.error)
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
    }
    }
}

/** Push a user-mode's saved params into the viewmodel's global state, so the
 *  mode panel shows the same values the run is using. */
private fun applyUserModeParams(vm: PulsarViewModel, mode: com.ehrocha.pulsar.model.UserMode) {
    val b = mode.body
    when (b.fwMode) {
        TriggerMode.INTERVALOMETER -> {
            vm.setIntervalMs(b.intervalMs)
            vm.setExposureMs(b.exposureMs)
            vm.setShotCount(b.shotCount)
            vm.setDelayMs(b.delayMs)
        }
        TriggerMode.ASTRO -> {
            vm.setAstroFocalLength(b.focalLength)
            vm.setAstroCropFactor(b.cropFactor)
            vm.setAstroRuleDivisor(b.ruleDivisor)
            vm.setAstroGapMs(b.intervalMs)
            vm.setAstroShotCount(b.shotCount)
            vm.setAstroDelayMs(b.delayMs)
        }
        TriggerMode.DARK_FRAME -> {
            vm.setDarkFrameCount(b.shotCount)
            vm.setDarkFrameExposureMs(b.exposureMs)
            vm.setDarkFrameGapMs(b.intervalMs)
        }
        TriggerMode.RAMP -> {
            vm.setRampStartExposureMs(b.rampStartExposureMs)
            vm.setRampEndExposureMs(b.rampEndExposureMs)
            vm.setRampSteps(b.rampSteps)
            vm.setRampIntervalMs(b.intervalMs)
        }
        else -> {}
    }
}

private sealed class AppScreen {
    data object Scan : AppScreen()
    data object Menu : AppScreen()
    data class Mode(val mode: TriggerMode) : AppScreen()
    data class Settings(val initialSection: SettingsSection? = null) : AppScreen()
    data class CustomFlow(val quickLaunch: Boolean = false) : AppScreen()
    data object Planner : AppScreen()
    data class MapPicker(val event: PlannerEvent, val initialLat: Double = 0.0, val initialLon: Double = 0.0) : AppScreen()
    data class EventSessions(val event: PlannerEvent, val mapResult: MapPickerResult? = null) : AppScreen()
    data class SessionDetail(val session: PlannerSession, val event: PlannerEvent) : AppScreen()
    data object Alignment : AppScreen()
    data object WhatsUp : AppScreen()
    data object Modes : AppScreen()
    data class ModeEditor(val modeId: String? = null) : AppScreen()
    data object ShotLog : AppScreen()
    data object Intervalometer2 : AppScreen()
    data object AstroMode2 : AppScreen()
}
