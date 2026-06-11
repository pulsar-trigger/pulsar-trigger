/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.PulsarTopBar
import com.ehrocha.pulsar.transport.aircraft.AircraftSighting
import com.ehrocha.pulsar.transport.aircraft.AircraftSize
import com.ehrocha.pulsar.transport.aircraft.aircraftSizeFor
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Tool screen — "what aircraft are overhead right now". Pulls the lat/lon
 * from the Astro Dashboard (so the user only configures location in one
 * place) and polls the configured [com.ehrocha.pulsar.transport.aircraft.AircraftFeed]
 * at its recommended cadence. Auto-starts on entry, stops on dispose.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AircraftWatchScreen(
    vm: PulsarViewModel,
    onBack: () -> Unit,
    onSpottingLog: () -> Unit,
) {
    var selectedIcao by remember { mutableStateOf<String?>(null) }
    var showPhotoTips by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }
    val rawSightings by vm.aircraftSightings.collectAsState()
    val showSunMoon by vm.aircraftWatchShowSunMoon.collectAsState()
    val mapHeadingLock by vm.aircraftWatchMapHeadingLock.collectAsState()
    val watching by vm.aircraftWatching.collectAsState()
    val error by vm.aircraftWatchError.collectAsState()
    val lastUpdateMs by vm.aircraftWatchLastUpdateMs.collectAsState()
    val radiusKm by vm.aircraftWatchRadiusKm.collectAsState()
    val maxAltFt by vm.aircraftWatchMaxAltitudeFt.collectAsState()
    val intervalSec by vm.aircraftWatchIntervalSec.collectAsState()
    val alertNotable by vm.aircraftWatchAlertNotable.collectAsState()
    val keepScreenOn by vm.aircraftWatchKeepScreenOn.collectAsState()
    val mapHybrid by vm.aircraftWatchMapHybrid.collectAsState()
    // Resolved ONCE at entry (LocationManager lookup is a binder IPC —
    // calling it in the composable body ran it on every recomposition:
    // each poll, each live tick, each 5° compass bucket). Refreshed
    // explicitly by the GPS banner's refresh action.
    var location by remember { mutableStateOf(vm.aircraftWatchLocation()) }
    // Sun direction for the lighting hint. Recomputed each poll (and on
    // location change) — cheap trig, no network.
    val sunDir = androidx.compose.runtime.remember(location, lastUpdateMs) {
        location?.let { currentSunDir(it.first, it.second) }
    }
    // Compass — must know user lat/lon to apply true-vs-magnetic declination.
    val deviceCompass = rememberDeviceCompass(
        userLat = location?.first,
        userLon = location?.second,
    )
    // Altitude filter is applied locally so the slider feels instant —
    // changing it doesn't wait for the next poll. Aircraft with no altitude
    // (typically ground traffic or transponders not reporting) pass through:
    // they're the most interesting subjects for spotters anyway.
    val sightings = androidx.compose.runtime.remember(rawSightings, maxAltFt) {
        rawSightings.filter { it.altitudeFt == null || it.altitudeFt <= maxAltFt }
    }

    // Auto-deselect when the selected aircraft drops out of range — keeps
    // a stale highlight ring + trails from sticking around pointing at
    // nothing on the map.
    LaunchedEffect(sightings, selectedIcao) {
        val sel = selectedIcao ?: return@LaunchedEffect
        if (sightings.none { it.icaoHex == sel }) selectedIcao = null
    }

    // Notable-aircraft alert. Beep + vibrate when a military / emergency /
    // vintage aircraft FIRST appears. `alerted` tracks already-announced
    // ICAOs so we don't re-beep every poll; pruned to in-range so a body
    // that leaves and returns later re-alerts.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val alerted = androidx.compose.runtime.remember { mutableSetOf<String>() }
    LaunchedEffect(sightings, alertNotable) {
        if (!alertNotable) {
            alerted.clear()
            return@LaunchedEffect
        }
        val inRange = sightings.map { it.icaoHex }.toSet()
        alerted.retainAll(inRange)
        val fresh = sightings.filter { isAlertWorthy(it) && it.icaoHex !in alerted }
        if (fresh.isNotEmpty()) {
            fresh.forEach { alerted += it.icaoHex }
            playNotableAlert(ctx)
        }
    }

    // Auto-start on entry, stop on dispose. Mirrors how the Canon BLE setup
    // screen owns its scan lifecycle.
    DisposableEffect(Unit) {
        if (location != null) vm.startAircraftWatch()
        onDispose { vm.stopAircraftWatch() }
    }

    // Keep-screen-on: set the window flag while the toggle is on and this
    // screen is composed; always cleared on dispose so we never leak the
    // flag back to the rest of the app. The activity is found by unwrapping
    // the ContextWrapper chain (Compose's LocalContext may be a wrapper).
    DisposableEffect(keepScreenOn) {
        val activity = run {
            var c: android.content.Context? = ctx
            while (c is android.content.ContextWrapper) {
                if (c is android.app.Activity) return@run c
                c = c.baseContext
            }
            null
        }
        val window = activity?.window
        if (keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // BottomSheet — collapsed (peek): just the status row sits above the
    // map+list. Drag up or tap the handle to reveal the three sliders.
    // Frees ~280dp of vertical space in the collapsed state.
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        // Peek shows roughly: drag handle + status row + small bottom padding.
        // Peek shows a text line — scale with the user's font size so
        // large-text accessibility settings don't clip it (audit P0-4).
        sheetPeekHeight = (76 * androidx.compose.ui.platform.LocalDensity.current.fontScale).dp,
        topBar = {
            PulsarTopBar(
                title = stringResource(R.string.aircraft_watch_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showPhotoTips = true }) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.aircraft_photo_tips_title),
                        )
                    }
                    IconButton(onClick = onSpottingLog) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = stringResource(R.string.spotting_log_title),
                        )
                    }
                    IconButton(
                        onClick = { vm.refreshAircraftWatch() },
                        enabled = watching,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.aircraft_watch_refresh),
                        )
                    }
                },
            )
        },
        sheetContent = {
            val loc = location
            if (loc != null) {
                SettingsPanel(
                    lat = loc.first,
                    lon = loc.second,
                    radiusKm = radiusKm,
                    onRadiusChange = vm::setAircraftWatchRadiusKm,
                    intervalSec = intervalSec,
                    onIntervalChange = vm::setAircraftWatchIntervalSec,
                    onShowDisplaySettings = { showDisplaySettings = true },
                    watching = watching,
                    lastUpdateMs = lastUpdateMs,
                    providerName = vm.aircraftFeedName,
                )
            }
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val loc = location
            if (loc == null) {
                NoLocationCard()
                return@BottomSheetScaffold
            }

            error?.let { ErrorBanner(it) }

            // Compass calibration nudge — visible only when the rotation-
            // vector sensor reports LOW or UNRELIABLE accuracy. Dismissible
            // for this session; reappears on a fresh entry if still bad.
            var calibrationDismissed by remember { mutableStateOf(false) }
            val needsCalibration = deviceCompass.accuracy ==
                android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
                deviceCompass.accuracy ==
                android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE
            if (needsCalibration && !calibrationDismissed) {
                CompassCalibrationBanner(onDismiss = { calibrationDismissed = true })
            }

            // GPS-accuracy nudge — surfaces when the OS fix is older than
            // 15 min OR less accurate than 200 m. Both are common signs
            // of stale/cached location (indoors, on a long-running session,
            // bad satellite lock). Refresh button forces a fresh GPS read.
            var gpsBannerDismissed by remember { mutableStateOf(false) }
            var gpsRefreshing by remember { mutableStateOf(false) }
            val gpsQuality = remember(lastUpdateMs) { vm.aircraftWatchLocationQuality() }
            val gpsBad = (gpsQuality.accuracyM ?: 0f) > 200f ||
                (gpsQuality.ageMs ?: 0L) > 15 * 60_000L
            if (gpsBad && !gpsBannerDismissed) {
                GpsAccuracyBanner(
                    accuracyM = gpsQuality.accuracyM,
                    ageMs = gpsQuality.ageMs,
                    refreshing = gpsRefreshing,
                    onRefresh = {
                        gpsRefreshing = true
                        vm.refreshAircraftWatchGps {
                            gpsRefreshing = false
                            // Pull the fresh fix into the screen's cached
                            // location so the map / compass / sun re-anchor.
                            // The polling loop re-reads location on its own
                            // each cycle.
                            location = vm.aircraftWatchLocation()
                        }
                    },
                    onDismiss = { gpsBannerDismissed = true },
                )
            }

            // Map — fixed height so the list still gets meaningful space
            // on phone-sized devices. Lives above the (now-collapsible)
            // settings sheet.
            AircraftMap(
                centreLat = loc.first,
                centreLon = loc.second,
                radiusKm = radiusKm,
                sightings = sightings,
                liveMode = intervalSec == 0,
                selectedIcao = selectedIcao,
                showSunMoon = showSunMoon,
                deviceAzimuth = deviceCompass.azimuth,
                mapHeadingLock = mapHeadingLock,
                mapHybrid = mapHybrid,
                onMarkerSelect = { icao ->
                    selectedIcao = if (selectedIcao == icao) null else icao
                },
            )

            // List — takes the remaining vertical space. The bottom-sheet
            // peek height (76dp) is subtracted from the scaffold body
            // automatically via `pad`, so we don't have to reserve room.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (sightings.isEmpty() && watching && error == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (lastUpdateMs == 0L) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            Text(
                                stringResource(R.string.aircraft_watch_no_aircraft),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sightings, key = { it.icaoHex }) { s ->
                            AircraftRow(
                                s = s,
                                radiusKm = radiusKm,
                                userLat = loc.first,
                                userLon = loc.second,
                                sunDir = sunDir,
                                selected = selectedIcao == s.icaoHex,
                                onSelectOnMap = {
                                    // Toggle: tapping the already-selected
                                    // row deselects (gets the ring off the
                                    // map without an extra control).
                                    selectedIcao = if (selectedIcao == s.icaoHex) null
                                                   else s.icaoHex
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showPhotoTips) {
        PhotoTipsDialog(onDismiss = { showPhotoTips = false })
    }
    if (showDisplaySettings) {
        DisplaySettingsSheet(
            onDismiss = { showDisplaySettings = false },
            maxAltFt = maxAltFt,
            onMaxAltChange = vm::setAircraftWatchMaxAltitudeFt,
            showSunMoon = showSunMoon,
            onShowSunMoonChange = vm::setAircraftWatchShowSunMoon,
            mapHeadingLock = mapHeadingLock,
            onMapHeadingLockChange = vm::setAircraftWatchMapHeadingLock,
            mapHybrid = mapHybrid,
            onMapHybridChange = vm::setAircraftWatchMapHybrid,
            alertNotable = alertNotable,
            onAlertNotableChange = vm::setAircraftWatchAlertNotable,
            keepScreenOn = keepScreenOn,
            onKeepScreenOnChange = vm::setAircraftWatchKeepScreenOn,
            compassAccuracy = deviceCompass.accuracy,
            onShowCalibrate = { showCalibrationDialog = true },
        )
    }
    if (showCalibrationDialog) {
        CompassCalibrationDialog(onDismiss = { showCalibrationDialog = false })
    }
}

@Composable
internal fun compassAccuracyLabel(acc: Int): String = when (acc) {
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
        stringResource(R.string.aircraft_compass_acc_high)
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
        stringResource(R.string.aircraft_compass_acc_medium)
    android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
        stringResource(R.string.aircraft_compass_acc_low)
    android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE ->
        stringResource(R.string.aircraft_compass_acc_unreliable)
    else -> stringResource(R.string.aircraft_compass_acc_unknown)
}

@Composable
internal fun CompassCalibrationDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        },
        title = {
            Text(
                stringResource(R.string.aircraft_compass_calibrate),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                stringResource(R.string.aircraft_compass_calibrate_body),
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

/** Aviation-photography cheat sheet. Lives in one long localised string
 *  per locale (`aircraft_photo_tips_body`) — keeps localisation manageable
 *  and lets the writer arrange the prose to read naturally per language. */
@Composable
internal fun PhotoTipsDialog(onDismiss: () -> Unit) {
    com.ehrocha.pulsar.ui.components.DetailSheet(
        onDismiss = onDismiss,
        title = {
            Text(
                stringResource(R.string.aircraft_photo_tips_title),
                fontWeight = FontWeight.Bold,
            )
        },
    ) {
        // The sheet scrolls its content itself — no nested scroll column.
        Text(
            stringResource(R.string.aircraft_photo_tips_body),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
