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
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.togetherWith
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.ehrocha.pulsar.ui.components.GridField
import com.ehrocha.pulsar.ui.components.LatencyIndicator
import com.ehrocha.pulsar.ui.components.PcbField
import com.ehrocha.pulsar.ui.components.SignalStrengthIndicator
import com.ehrocha.pulsar.ui.components.SpaceField
import com.ehrocha.pulsar.ui.theme.LocalVisualStyle
import com.ehrocha.pulsar.ui.theme.VisualStyle
import com.ehrocha.pulsar.ui.components.ThemePicker
import com.ehrocha.pulsar.ui.screens.AircraftWatchScreen
import com.ehrocha.pulsar.ui.screens.SpottingLogScreen
import com.ehrocha.pulsar.ui.screens.NdCalculatorScreen
import com.ehrocha.pulsar.ui.screens.DofCalculatorScreen
import com.ehrocha.pulsar.ui.screens.StarTrailsScreen
import com.ehrocha.pulsar.ui.screens.MainMenuScreen
import com.ehrocha.pulsar.ui.screens.ManageDevicesScreen
import com.ehrocha.pulsar.ui.screens.ModeScreen
import com.ehrocha.pulsar.ui.screens.ScanLandingScreen
import com.ehrocha.pulsar.ui.screens.TransportSetupScreen
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

    companion object {
        /** Intent extra: integer destination index to select on launch. Used
         *  by the home-screen [com.ehrocha.pulsar.widget.DashboardWidget]. */
        const val EXTRA_OPEN_DEST = "com.ehrocha.pulsar.OPEN_DEST"

        // Mirror the consts in MainMenuScreen.kt so callers outside that file
        // (the widget receiver lives in a different package) don't have to
        // import the screen module.
        const val DEST_DASHBOARD = 0
        const val DEST_TRIGGER = 1
        const val DEST_TOOLS = 2

        /** Maximum byte size of an imported .pulsar event file. The
         *  largest event file we'd reasonably expect (a fully-populated
         *  shot log with months of entries) clocks in well under 1 MB. */
        private const val IMPORT_MAX_BYTES = 1_048_576
    }

    /** Read event JSON from an incoming VIEW/SEND intent (.pulsar file).
     *  Caps the read at [IMPORT_MAX_BYTES] so a malicious or oversized
     *  attachment can't OOM the app — the activity is exported with a
     *  .pulsar mime-type intent filter, so any installed app can hand us
     *  arbitrary URIs. */
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
            uri?.let { contentResolver.openInputStream(it)?.use { stream ->
                val buf = ByteArray(IMPORT_MAX_BYTES + 1)
                var total = 0
                while (total <= IMPORT_MAX_BYTES) {
                    val n = stream.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                if (total > IMPORT_MAX_BYTES) {
                    android.util.Log.w("MainActivity",
                        "Refusing import: file exceeds ${IMPORT_MAX_BYTES} bytes")
                    null
                } else {
                    String(buf, 0, total)
                }
            } }
        } catch (_: Exception) { null }
    }

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // SIGNAL splash (carbon + self-drawing pulse); must run before
        // super.onCreate so the compat splash can take the first frame.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val pendingImportJson = readImportIntent()
        enableEdgeToEdge()
        setContent {
            val nightMode = remember { mutableStateOf(ThemeMode.Dark) }
            val nightModeLocked = remember { mutableStateOf(false) }
            // Visual style is a persisted user choice (unlike night mode, which
            // is session-only). Loaded from prefs, saved on every change.
            val uiPrefs = remember {
                this@MainActivity.getSharedPreferences("pulsar_ui", android.content.Context.MODE_PRIVATE)
            }
            val visualStyle = remember {
                mutableStateOf(
                    runCatching {
                        com.ehrocha.pulsar.ui.theme.VisualStyle.valueOf(
                            uiPrefs.getString("visual_style", null) ?: "SPACE")
                    }.getOrDefault(com.ehrocha.pulsar.ui.theme.VisualStyle.SPACE)
                )
            }
            LaunchedEffect(visualStyle.value) {
                uiPrefs.edit().putString("visual_style", visualStyle.value.name).apply()
            }
            // Keep the live style in sync when a settings RESTORE writes the pref
            // directly (import bypasses the picker). The same-value write from the
            // LaunchedEffect above is a no-op, so this can't loop.
            androidx.compose.runtime.DisposableEffect(uiPrefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                    if (key == "visual_style") {
                        visualStyle.value = runCatching {
                            com.ehrocha.pulsar.ui.theme.VisualStyle.valueOf(
                                p.getString("visual_style", null) ?: "SPACE")
                        }.getOrDefault(com.ehrocha.pulsar.ui.theme.VisualStyle.SPACE)
                    }
                }
                uiPrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { uiPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val colorScheme = when (nightMode.value) {
                ThemeMode.Light -> LightColorScheme
                ThemeMode.Outdoor -> OutdoorColorScheme
                ThemeMode.Dark -> DarkColorScheme
                ThemeMode.RedLight -> RedLightColorScheme
            }
            val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
            CompositionLocalProvider(
                LocalNightMode provides nightMode,
                LocalNightModeLocked provides nightModeLocked,
                com.ehrocha.pulsar.ui.theme.LocalVisualStyle provides visualStyle,
                com.ehrocha.pulsar.ui.components.LocalSnackbarHost provides snackbarHost,
                // Semantic roles resolved per mode — under RedLight every
                // role collapses to a red/grey luminance ramp so the user's
                // night-vision choice is honoured by all surfaces.
                com.ehrocha.pulsar.ui.theme.LocalPulsarColors provides
                    com.ehrocha.pulsar.ui.theme.pulsarColorsFor(nightMode.value),
            ) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = com.ehrocha.pulsar.ui.theme.PulsarTypography,
            ) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    // Permission list has to match the manifest's <uses-permission>
                    // SDK gates. Asking for a permission that isn't in the merged
                    // manifest is a silent no-op — the user sees no system dialog
                    // and `allPermissionsGranted` stays false forever, stranding
                    // them on the gate screen.
                    //   - BLUETOOTH_SCAN / BLUETOOTH_CONNECT: Android 12+ only.
                    //   - ACCESS_*_LOCATION: needed on every SDK because the
                    //     astro dashboard / polar-align / What's Up Tonight read
                    //     GPS for Bortle / moon / Polaris hour-angle. On 12+ the
                    //     BLE scanner uses `neverForLocation` so BLE doesn't
                    //     drive this any more — astro features do.
                    val permissions = rememberMultiplePermissionsState(
                        buildList {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                add(android.Manifest.permission.BLUETOOTH_SCAN)
                                add(android.Manifest.permission.BLUETOOTH_CONNECT)
                            }
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
                        PulsarNavHost(
                            importJson = pendingImportJson,
                            initialMenuDest = intent?.getIntExtra(EXTRA_OPEN_DEST, -1)
                                ?.takeIf { it >= 0 },
                        )
                    } else {
                        PermissionsRequiredScreen(
                            onRequestAgain = { permissions.launchMultiplePermissionRequest() },
                        )
                    }
                    androidx.compose.material3.SnackbarHost(
                        hostState = snackbarHost,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
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
            imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
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
fun PulsarNavHost(
    vm: PulsarViewModel = viewModel(),
    importJson: String? = null,
    initialMenuDest: Int? = null,
) {
    // Widget tap → launch straight into the main menu on the requested tab.
    var currentScreen by remember {
        mutableStateOf<AppScreen>(
            if (initialMenuDest != null) AppScreen.Menu else AppScreen.ScanLanding
        )
    }
    var menuDest by remember {
        mutableIntStateOf(initialMenuDest ?: com.ehrocha.pulsar.ui.screens.DEST_TRIGGER)
    }
    val connected by vm.connected.collectAsState()

    // Navigation + dialog trace in the diagnostics wire log (Eduardo's ask,
    // 2026-07-03) — so screen changes and dialogs can be correlated with the
    // shutter events when chasing UI-timing bugs (e.g. the Camera Test share).
    LaunchedEffect(currentScreen, menuDest) {
        com.ehrocha.pulsar.canonble.CanonBleLog.i("Nav", "→ screen=$currentScreen menuTab=$menuDest")
    }

    // Camera-transport link dropped mid-session (phone-driven run loop can't
    // continue, and a bulb may be left exposing) — warn the user prominently.
    val sessionInterrupted by vm.sessionInterrupted.collectAsState()
    if (sessionInterrupted) {
        LaunchedEffect(Unit) { com.ehrocha.pulsar.canonble.CanonBleLog.i("Nav", "◆ Dialog: session interrupted") }
        AlertDialog(
            onDismissRequest = { vm.clearSessionInterrupted() },
            confirmButton = {
                TextButton(onClick = { vm.clearSessionInterrupted() }) {
                    Text(stringResource(R.string.ok))
                }
            },
            title = { Text(stringResource(R.string.session_interrupted_title)) },
            text = { Text(stringResource(R.string.session_interrupted_body)) },
        )
    }

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
        if (!connected && !importHandled) currentScreen = AppScreen.ScanLanding
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
    val snackbarHostLocal = com.ehrocha.pulsar.ui.components.LocalSnackbarHost.current
    // SIGNAL success moment: a finished run lands as one clean pulse — a
    // crisp double-tick you can FEEL at the tripod without looking, plus
    // the snackbar naming the frame count.
    LaunchedEffect(Unit) {
        vm.runCompleted.collect { frames ->
            runCatching {
                val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                        as android.os.Vibrator
                }
                vibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(
                        longArrayOf(0, 35, 110, 35), -1,
                    ),
                )
            }
            snackbarHostLocal.showSnackbar(
                context.getString(R.string.run_complete_snack, frames),
            )
        }
    }
    // Canon BLE safety clamp: a sub-4 s interval was raised at run start (the
    // EOS R drops presses without ~4 s of quiet between exposures). Tell the
    // user their preset isn't running verbatim.
    LaunchedEffect(Unit) {
        vm.canonBleIntervalRaised.collect {
            snackbarHostLocal.showSnackbar(
                context.getString(R.string.canon_ble_interval_raised),
            )
        }
    }
    LaunchedEffect(importJson) {
        if (importJson != null) {
            val event = vm.plannerManager.importEvent(importJson)
            if (event != null) {
                snackbarHostLocal.showSnackbar(
                    context.getString(R.string.planner_import_success, event.name),
                )
                currentScreen = AppScreen.EventSessions(event)
            } else {
                snackbarHostLocal.showSnackbar(
                    context.getString(R.string.planner_import_failed),
                )
            }
        }
    }

    val deviceStatus by vm.status.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val deviceRssi by vm.rssi.collectAsState()
    val deviceLatency by vm.latencyMs.collectAsState()
    val runState by vm.runState.collectAsState()
    val currentFlowStep by vm.currentFlowStep.collectAsState()
    val preparing by vm.canonBlePreparing.collectAsState()

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
        com.ehrocha.pulsar.ui.theme.LocalPreparing provides preparing,
        com.ehrocha.pulsar.ui.theme.LocalCurrentFlowStep provides currentFlowStep,
        LocalDeviceConnected provides connected,
        LocalDeviceRssi provides deviceRssi,
        LocalDeviceLatency provides deviceLatency,
    ) {
    Column(Modifier.fillMaxSize()) {
        // ── Persistent top bar (hidden on Scan + Menu) ───────────────
        // Hidden on ScanLanding / TransportSetup (own brand header) and on the
        // Menu, which now carries a single unified bar: its destination title
        // plus a ConnectionPill that folds in this bar's device/battery/signal.
        if (currentScreen !is AppScreen.ScanLanding && currentScreen !is AppScreen.TransportSetup &&
            currentScreen !is AppScreen.Menu) {
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
        // ── Themed backdrop ──────────────────────────────────────────
        // One starfield / circuit-board sits behind every sub-screen, whose
        // Scaffolds are transparent so it shows through in the gaps. Scan +
        // Menu paint their own opaque background over this (they carry their
        // own decoration). Static (animated = false) to stay calm behind forms.
        when (LocalVisualStyle.current.value) {
            VisualStyle.CIRCUIT -> PcbField(Modifier.matchParentSize(), animated = false)
            VisualStyle.SPACE -> SpaceField(Modifier.matchParentSize(), animated = false)
            VisualStyle.GRID -> GridField(Modifier.matchParentSize(), animated = false)
            VisualStyle.CLASSIC -> Unit
        }
        // Legibility scrim: mute the field so text on the transparent sub-screens
        // stays readable while the backdrop still shows through.
        if (LocalVisualStyle.current.value != VisualStyle.CLASSIC) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f)))
        }
        // SIGNAL motion: screens enter with a fast fade + 1/24-height rise,
        // exit with a quicker fade. For GRID, a Tron "derez" instead — the old
        // surface scales up + fades fast (dissolving into the grid) while the new
        // one resolves in from slightly enlarged. State (currentScreen) unchanged.
        val derezNav = LocalVisualStyle.current.value == VisualStyle.GRID
        androidx.compose.animation.AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                if (derezNav) {
                    (androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(200),
                    ) + androidx.compose.animation.scaleIn(
                        androidx.compose.animation.core.tween(220), initialScale = 1.06f,
                    )).togetherWith(
                        androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(130),
                        ) + androidx.compose.animation.scaleOut(
                            androidx.compose.animation.core.tween(150), targetScale = 1.12f,
                        ),
                    )
                } else {
                    (androidx.compose.animation.fadeIn(
                        androidx.compose.animation.core.tween(180),
                    ) + androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.tween(220),
                    ) { it / 24 }).togetherWith(
                        androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(110),
                        ),
                    )
                }
            },
            label = "screenTransition",
        ) { screen ->
        when (screen) {
            AppScreen.ScanLanding -> ScanLandingScreen(
                vm = vm,
                onTransportSelected = { kind ->
                    // Phase 3: route to the per-transport setup screen for
                    // transports that have one implemented; everything else
                    // still falls through to the legacy combined Scan
                    // until its commit lands.
                    // Phase 3 complete: every transport has its own setup
                    // screen now. The legacy combined Scan no longer routes
                    // from the landing — kept only as a fallback / for
                    // Phase 4's deletion.
                    currentScreen = AppScreen.TransportSetup(kind)
                },
                onSimulatorSelected = {
                    vm.connectSimulator()
                    currentScreen = AppScreen.Menu
                },
                onConnected = { currentScreen = AppScreen.Menu },
                onManageDevicesSelected = { currentScreen = AppScreen.ManageDevices },
            )
            AppScreen.ManageDevices -> {
                BackHandler { currentScreen = AppScreen.ScanLanding }
                ManageDevicesScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.ScanLanding },
                )
            }
            is AppScreen.TransportSetup -> {
                // Without this, system-back on a transport-setup panel finishes
                // the activity (closes the app) instead of returning to the
                // landing — every other sub-screen has its own BackHandler.
                BackHandler { currentScreen = AppScreen.ScanLanding }
                TransportSetupScreen(
                    vm = vm,
                    kind = screen.kind,
                    onBack = { currentScreen = AppScreen.ScanLanding },
                    onConnected = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Menu -> {
                MainMenuScreen(
                    vm = vm,
                    initialDest = menuDest,
                    onDestChanged = { menuDest = it },
                    onQuickFlow = { type ->
                        // All capture modes are now wizard-driven. Anything
                        // arriving here is a fallback path that should route
                        // to the preset picker for the matching fwMode.
                        when (type) {
                            FlowStepType.DARK_FRAME ->
                                currentScreen = AppScreen.PresetPicker(TriggerMode.DARK_FRAME)
                            FlowStepType.RAMP ->
                                currentScreen = AppScreen.PresetPicker(TriggerMode.RAMP)
                            else -> return@MainMenuScreen
                        }
                    },
                    onManualSelected = { currentScreen = AppScreen.Mode(TriggerMode.PRESS_HOLD) },
                    onCableReleaseSelected = { currentScreen = AppScreen.CableRelease },
                    onIntervalometer2Selected = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.INTERVALOMETER)
                    },
                    onAstroMode2Selected = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.ASTRO)
                    },
                    onTimelapseSelected = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.TIMELAPSE)
                    },
                    onStarTrailsSelected = { currentScreen = AppScreen.PresetPicker(TriggerMode.STAR_TRAILS) },
                    onCustomFlowSelected = { currentScreen = AppScreen.CustomFlow() },
                    onPlannerSelected = { currentScreen = AppScreen.Planner },
                    onAlignmentSelected = { currentScreen = AppScreen.Alignment },
                    onWhatsUpSelected = { currentScreen = AppScreen.WhatsUp },
                    onMeteorCalendarSelected = { currentScreen = AppScreen.MeteorCalendar },
                    onStarFocusSelected = { currentScreen = AppScreen.StarFocus },
                    onPhotoTransferSelected = { currentScreen = AppScreen.PhotoTransfer },
                    onCatalogSelected = { currentScreen = AppScreen.Catalog },
                    onTestCameraSelected = { currentScreen = AppScreen.TestCamera },
                    onAircraftWatchSelected = { currentScreen = AppScreen.AircraftWatch },
                    onNdCalcSelected = { currentScreen = AppScreen.NdCalculator },
                    onDofCalcSelected = { currentScreen = AppScreen.DofCalculator },
                    onDiagnosticsSelected = { currentScreen = AppScreen.Diagnostics },
                    // Top-bar Settings icon lands on the menu (no
                    // pre-selected section). The update banner has its own
                    // callback that lands on UPDATES.
                    onSettingsSelected = { currentScreen = AppScreen.Settings() },
                    onUpdatesSelected = { currentScreen = AppScreen.Settings(SettingsSection.UPDATES) },
                    deviceName = deviceName,
                    onDisconnect = { vm.disconnect() },
                    onShotLogSelected = { currentScreen = AppScreen.ShotLog },
                    nightModeToggle = { ThemePicker() },
                )
            }
            is AppScreen.Mode -> {
                BackHandler { currentScreen = AppScreen.Menu }
                ModeScreen(
                    vm = vm,
                    targetMode = screen.mode,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            is AppScreen.Settings -> {
                BackHandler { currentScreen = AppScreen.Menu }
                SettingsScreen(
                    vm = vm,
                    initialSection = screen.initialSection,
                    onBack = { currentScreen = AppScreen.Menu },
                    onTestCameraSelected = { currentScreen = AppScreen.TestCamera },
                    onDiagnosticsSelected = { currentScreen = AppScreen.Diagnostics },
                    onGattExplorerSelected = { currentScreen = AppScreen.GattExplorer },
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
            AppScreen.MeteorCalendar -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.MeteorCalendarScreen(
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.ShotLog -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.ShotLogScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.StarFocus -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.StarFocusScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.PhotoTransfer -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.PhotoTransferScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.Catalog -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.CatalogScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.TestCamera -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.TestCameraScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.AircraftWatch -> {
                BackHandler { currentScreen = AppScreen.Menu }
                AircraftWatchScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                    onSpottingLog = { currentScreen = AppScreen.SpottingLog },
                )
            }
            AppScreen.SpottingLog -> {
                BackHandler { currentScreen = AppScreen.AircraftWatch }
                SpottingLogScreen(onBack = { currentScreen = AppScreen.AircraftWatch })
            }
            AppScreen.NdCalculator -> {
                BackHandler { currentScreen = AppScreen.Menu }
                NdCalculatorScreen(onBack = { currentScreen = AppScreen.Menu })
            }
            AppScreen.DofCalculator -> {
                BackHandler { currentScreen = AppScreen.Menu }
                DofCalculatorScreen(onBack = { currentScreen = AppScreen.Menu })
            }
            is AppScreen.StarTrails -> {
                BackHandler { currentScreen = AppScreen.PresetPicker(TriggerMode.STAR_TRAILS) }
                StarTrailsScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.PresetPicker(TriggerMode.STAR_TRAILS) },
                    initialPresetId = screen.presetId,
                )
            }
            AppScreen.Diagnostics -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.DiagnosticsScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            AppScreen.GattExplorer -> {
                BackHandler { currentScreen = AppScreen.Settings(SettingsSection.DIAGNOSTICS) }
                com.ehrocha.pulsar.ui.screens.GattExplorerScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Settings(SettingsSection.DIAGNOSTICS) },
                )
            }
            AppScreen.CableRelease -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.CableReleaseScreen(
                    vm = vm,
                    onBack = { currentScreen = AppScreen.Menu },
                )
            }
            is AppScreen.PresetPicker -> {
                BackHandler { currentScreen = AppScreen.Menu }
                com.ehrocha.pulsar.ui.screens.PresetPickerScreen(
                    vm = vm,
                    fwMode = screen.fwMode,
                    onBack = { currentScreen = AppScreen.Menu },
                    onStartFresh = {
                        currentScreen = when (screen.fwMode) {
                            TriggerMode.INTERVALOMETER -> AppScreen.Intervalometer2()
                            TriggerMode.ASTRO -> AppScreen.AstroMode2()
                            TriggerMode.TIMELAPSE -> AppScreen.Timelapse()
                            TriggerMode.DARK_FRAME -> AppScreen.DarkFrame2()
                            TriggerMode.RAMP -> AppScreen.Ramp2()
                            TriggerMode.STAR_TRAILS -> AppScreen.StarTrails()
                            else -> AppScreen.Menu
                        }
                    },
                    onPresetSelected = { preset ->
                        currentScreen = when (screen.fwMode) {
                            TriggerMode.INTERVALOMETER -> AppScreen.Intervalometer2(preset.id)
                            TriggerMode.ASTRO -> AppScreen.AstroMode2(preset.id)
                            TriggerMode.TIMELAPSE -> AppScreen.Timelapse(preset.id)
                            TriggerMode.DARK_FRAME -> AppScreen.DarkFrame2(preset.id)
                            TriggerMode.RAMP -> AppScreen.Ramp2(preset.id)
                            TriggerMode.STAR_TRAILS -> AppScreen.StarTrails(preset.id)
                            else -> AppScreen.Menu
                        }
                    },
                    onBrowseCatalog = { currentScreen = AppScreen.Catalog },
                )
            }
            is AppScreen.Timelapse -> {
                BackHandler {
                    currentScreen = AppScreen.PresetPicker(TriggerMode.TIMELAPSE)
                }
                com.ehrocha.pulsar.ui.screens.TimelapseScreen(
                    vm = vm,
                    onBack = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.TIMELAPSE)
                    },
                    initialPresetId = screen.presetId,
                )
            }
            is AppScreen.DarkFrame2 -> {
                BackHandler {
                    currentScreen = AppScreen.PresetPicker(TriggerMode.DARK_FRAME)
                }
                com.ehrocha.pulsar.ui.screens.DarkFrame2Screen(
                    vm = vm,
                    onBack = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.DARK_FRAME)
                    },
                    initialPresetId = screen.presetId,
                )
            }
            is AppScreen.Ramp2 -> {
                BackHandler {
                    currentScreen = AppScreen.PresetPicker(TriggerMode.RAMP)
                }
                com.ehrocha.pulsar.ui.screens.Ramp2Screen(
                    vm = vm,
                    onBack = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.RAMP)
                    },
                    initialPresetId = screen.presetId,
                )
            }
            is AppScreen.Intervalometer2 -> {
                BackHandler {
                    currentScreen = AppScreen.PresetPicker(TriggerMode.INTERVALOMETER)
                }
                com.ehrocha.pulsar.ui.screens.Intervalometer2Screen(
                    vm = vm,
                    onBack = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.INTERVALOMETER)
                    },
                    initialPresetId = screen.presetId,
                )
            }
            is AppScreen.AstroMode2 -> {
                BackHandler {
                    currentScreen = AppScreen.PresetPicker(TriggerMode.ASTRO)
                }
                com.ehrocha.pulsar.ui.screens.AstroMode2Screen(
                    vm = vm,
                    onBack = {
                        currentScreen = AppScreen.PresetPicker(TriggerMode.ASTRO)
                    },
                    initialPresetId = screen.presetId,
                )
            }
        }
        }
    }
    }
    }
}

private sealed class AppScreen {
    /** Transport-picker landing screen (post-permissions entry point). */
    data object ScanLanding : AppScreen()
    /** Per-transport setup screen. Each transport (Pulsar BLE, CCAPI,
     *  PTP, Canon BLE) renders its own instructions + scan flow inside
     *  a shared scaffold in [TransportSetupScreen]. */
    data class TransportSetup(val kind: com.ehrocha.pulsar.transport.TransportKind) : AppScreen()
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
    data object MeteorCalendar : AppScreen()
    data object ShotLog : AppScreen()
    data object StarFocus : AppScreen()
    data object PhotoTransfer : AppScreen()
    data object Catalog : AppScreen()
    data object TestCamera : AppScreen()
    data object AircraftWatch : AppScreen()
    data object SpottingLog : AppScreen()
    data object NdCalculator : AppScreen()
    data object DofCalculator : AppScreen()
    data class StarTrails(val presetId: String? = null) : AppScreen()
    data object Diagnostics : AppScreen()
    /** Manage Devices — reachable only from the Scan landing (pre-connect)
     *  so the user can't accidentally forget the body they're currently
     *  driving. Lists every BLE bond + CCAPI credential and offers Forget. */
    data object ManageDevices : AppScreen()
    /** Raw GATT explorer for unsupported bodies. Settings → Diagnostics
     *  → GATT Explorer (debug mode only). Scaffold; see
     *  docs/gatt-explorer-draft.md. */
    data object GattExplorer : AppScreen()
    data object CableRelease : AppScreen()
    data class Intervalometer2(val presetId: String? = null) : AppScreen()
    data class AstroMode2(val presetId: String? = null) : AppScreen()
    data class Timelapse(val presetId: String? = null) : AppScreen()
    data class DarkFrame2(val presetId: String? = null) : AppScreen()
    data class Ramp2(val presetId: String? = null) : AppScreen()
    data class PresetPicker(val fwMode: TriggerMode) : AppScreen()
}
