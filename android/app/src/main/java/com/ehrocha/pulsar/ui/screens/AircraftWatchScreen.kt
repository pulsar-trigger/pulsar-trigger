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
import com.ehrocha.pulsar.transport.aircraft.AircraftSighting
import com.ehrocha.pulsar.transport.aircraft.AircraftSize
import com.ehrocha.pulsar.transport.aircraft.aircraftSizeFor
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.ThemeMode
import com.ehrocha.pulsar.viewmodel.PulsarViewModel
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
    val rawSightings by vm.aircraftSightings.collectAsState()
    val showSunMoon by vm.aircraftWatchShowSunMoon.collectAsState()
    val mapHeadingLock by vm.aircraftWatchMapHeadingLock.collectAsState()
    val watching by vm.aircraftWatching.collectAsState()
    val error by vm.aircraftWatchError.collectAsState()
    val lastUpdateMs by vm.aircraftWatchLastUpdateMs.collectAsState()
    val radiusKm by vm.aircraftWatchRadiusKm.collectAsState()
    val maxAltFt by vm.aircraftWatchMaxAltitudeFt.collectAsState()
    val intervalSec by vm.aircraftWatchIntervalSec.collectAsState()
    val location = vm.aircraftWatchLocation()
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

    // Auto-start on entry, stop on dispose. Mirrors how the Canon BLE setup
    // screen owns its scan lifecycle.
    DisposableEffect(Unit) {
        if (location != null) vm.startAircraftWatch()
        onDispose { vm.stopAircraftWatch() }
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
        sheetPeekHeight = 76.dp,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.aircraft_watch_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
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
            if (location != null) {
                SettingsPanel(
                    lat = location.first,
                    lon = location.second,
                    radiusKm = radiusKm,
                    onRadiusChange = vm::setAircraftWatchRadiusKm,
                    maxAltFt = maxAltFt,
                    onMaxAltChange = vm::setAircraftWatchMaxAltitudeFt,
                    intervalSec = intervalSec,
                    onIntervalChange = vm::setAircraftWatchIntervalSec,
                    showSunMoon = showSunMoon,
                    onShowSunMoonChange = vm::setAircraftWatchShowSunMoon,
                    mapHeadingLock = mapHeadingLock,
                    onMapHeadingLockChange = vm::setAircraftWatchMapHeadingLock,
                    compassAccuracy = deviceCompass.accuracy,
                    onShowCalibrate = { showCalibrationDialog = true },
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
            if (location == null) {
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
                            // Force a recompute by bumping a dependency-
                            // adjacent key; the LaunchedEffect on lastUpdateMs
                            // is sufficient because the dashboard refresh
                            // also pokes other observers.
                        }
                    },
                    onDismiss = { gpsBannerDismissed = true },
                )
            }

            // Map — fixed height so the list still gets meaningful space
            // on phone-sized devices. Lives above the (now-collapsible)
            // settings sheet.
            AircraftMap(
                centreLat = location.first,
                centreLon = location.second,
                radiusKm = radiusKm,
                sightings = sightings,
                liveMode = intervalSec == 0,
                selectedIcao = selectedIcao,
                showSunMoon = showSunMoon,
                deviceAzimuth = deviceCompass.azimuth,
                mapHeadingLock = mapHeadingLock,
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
                                userLat = location.first,
                                userLon = location.second,
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
    if (showCalibrationDialog) {
        CompassCalibrationDialog(onDismiss = { showCalibrationDialog = false })
    }
}

@Composable
private fun compassAccuracyLabel(acc: Int): String = when (acc) {
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
private fun CompassCalibrationDialog(onDismiss: () -> Unit) {
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
private fun PhotoTipsDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        },
        title = {
            Text(
                stringResource(R.string.aircraft_photo_tips_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.aircraft_photo_tips_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

/** Embedded MapLibre map showing the user's centre plus a marker per
 *  aircraft. Refreshes its marker layer every time [sightings] changes.
 *  Camera centres once on the user location and respects user pan/zoom
 *  thereafter — we don't reset the camera on each poll because that
 *  would fight the user's manual exploration. */
@Composable
private fun AircraftMap(
    centreLat: Double,
    centreLon: Double,
    radiusKm: Int,
    sightings: List<AircraftSighting>,
    liveMode: Boolean = false,
    selectedIcao: String? = null,
    showSunMoon: Boolean = false,
    deviceAzimuth: Int = 0,
    mapHeadingLock: Boolean = false,
    onMarkerSelect: (String) -> Unit = {},
) {
    val isDark = when (LocalNightMode.current.value) {
        ThemeMode.Dark, ThemeMode.RedLight -> true
        ThemeMode.Light, ThemeMode.Outdoor -> false
    }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val markers = remember { mutableListOf<Marker>() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapViewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef.value?.onDestroy()
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val iconFactory = remember(ctx) { IconFactory.getInstance(ctx) }
    // Rotated plane icons are expensive to build (drawable → bitmap → matrix
    // rotate). Cache key = (heading-bucket, size-class, proximity-band):
    // 15° heading buckets × 4 sizes × 3 proximity colours = up to 288
    // cache entries, built lazily as they're seen.
    val iconCache = remember { mutableMapOf<Triple<Int, AircraftSize, Int>, Icon>() }

    // deviceAzimuth comes in as a parameter from the screen so the
    // calibration banner above the map can read accuracy from the same
    // sensor subscription. Single source, no duplicate listener.

    // Heading-lock: rotate the map so the device-pointing direction is
    // always "up". moveCamera is instant (no interpolation) — back-to-
    // back azimuth updates can't stack incomplete animations and produce
    // the over-rotation reported in the wild. When the toggle goes off
    // we animate the map back to north-up in one smooth motion.
    LaunchedEffect(map, mapHeadingLock, deviceAzimuth) {
        val m = map ?: return@LaunchedEffect
        if (mapHeadingLock) {
            val targetBearing = deviceAzimuth.toDouble()
            val before = m.cameraPosition.bearing
            // Set bearing directly on the CameraPosition rather than via
            // CameraUpdateFactory.bearingTo — explicit, no factory side
            // effects, no chance of an animation queue. Builder seeded
            // from current cameraPosition so target / zoom / tilt stay.
            m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder(m.cameraPosition)
                .bearing(targetBearing)
                .build()
            val after = m.cameraPosition.bearing
            android.util.Log.d(
                "AircraftWatch",
                "heading-lock: device=${deviceAzimuth}° " +
                    "before=${"%.1f".format(before)}° → target=${"%.1f".format(targetBearing)}° " +
                    "after=${"%.1f".format(after)}°",
            )
        } else if (m.cameraPosition.bearing != 0.0) {
            m.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.bearingTo(0.0),
                400,
            )
        }
        // While the map's auto-following heading, swallowing user-driven
        // rotate gestures avoids fight-the-compass behaviour. Pan and
        // zoom stay on.
        m.uiSettings.isRotateGesturesEnabled = !mapHeadingLock
    }

    // Directional cone + user pin — Google-Maps-style stack: cone goes on
    // first, pin on top so the cone fans out from BEHIND the pin rather
    // than covering it. Both are re-added on every azimuth change because
    // MapLibre draws markers in insertion order (no z-index API).
    var headingMarker by remember { mutableStateOf<Marker?>(null) }
    var userMarker by remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(map, deviceAzimuth) {
        val m = map ?: return@LaunchedEffect
        headingMarker?.let { m.removeMarker(it) }
        userMarker?.let { m.removeMarker(it) }
        // 1) heading cone first → renders below
        val coneBmp = rotatedAircraftBitmap(
            ctx,
            headingDeg = deviceAzimuth.toFloat(),
            sizeScale = 1.5f,  // user asked for a bigger cone — easier to see
            drawableRes = R.drawable.ic_user_heading,
        )
        headingMarker = m.addMarker(
            MarkerOptions()
                .position(LatLng(centreLat, centreLon))
                .icon(iconFactory.fromBitmap(coneBmp)),
        )
        // 2) user pin on top → always visible regardless of fan direction.
        // sizeScale 0.65 keeps it visibly smaller than aircraft markers so
        // it doesn't dominate the map.
        val userBmp = rotatedAircraftBitmap(
            ctx,
            headingDeg = 0f,
            sizeScale = 0.65f,
            drawableRes = R.drawable.ic_user_marker,
        )
        userMarker = m.addMarker(
            MarkerOptions()
                .position(LatLng(centreLat, centreLon))
                .title("You")
                .icon(iconFactory.fromBitmap(userBmp)),
        )
    }

    // When the user picks Live mode, retick the marker layout at 1Hz
    // between real polls — dead-reckons each plane forward from its last
    // known position using heading + ground speed. Snaps back to the real
    // position on the next real poll. When NOT in Live mode, this tick
    // just stays at 0 and markers are placed at the polled positions.
    var liveTick by remember { mutableStateOf(0L) }
    LaunchedEffect(liveMode, sightings) {
        if (!liveMode) {
            liveTick = 0L
            return@LaunchedEffect
        }
        val anchor = System.currentTimeMillis()
        while (true) {
            liveTick = System.currentTimeMillis() - anchor
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // Highlight ring around the user-selected aircraft. Separate marker
    // tracked alongside the regular plane markers; rebuilt whenever the
    // selection changes OR the underlying sighting moves (live tick).
    var highlightMarker by remember { mutableStateOf<Marker?>(null) }
    val highlightIcon = remember(ctx) {
        iconFactory.fromBitmap(
            rotatedAircraftBitmap(
                ctx,
                headingDeg = 0f,
                sizeScale = 1.5f,
                drawableRes = R.drawable.ic_aircraft_highlight,
            ),
        )
    }

    // Past + future trail polylines for the selected aircraft.
    val trailHistory = remember { mutableMapOf<String, MutableList<LatLng>>() }
    val trailPolylines = remember { mutableListOf<Polyline>() }

    // Track previous selection so we can pan back to the user pin when the
    // user deselects (selected → null transition). Don't re-centre on
    // every refresh — only on the transition, otherwise we'd fight the
    // user's manual pan when nothing's selected.
    var prevSelected by remember { mutableStateOf<String?>(null) }

    // Sun + moon markers. Computed once per minute (their positions don't
    // move fast at map zoom levels — daily motion is ~0.25°/min).
    var sunMarker by remember { mutableStateOf<Marker?>(null) }
    var moonMarker by remember { mutableStateOf<Marker?>(null) }
    val sunIcon = remember(ctx) {
        iconFactory.fromBitmap(rotatedAircraftBitmap(ctx, 0f, 1.6f, R.drawable.ic_sun_marker))
    }
    val moonIcon = remember(ctx) {
        iconFactory.fromBitmap(rotatedAircraftBitmap(ctx, 0f, 1.6f, R.drawable.ic_moon_marker))
    }
    var sunMoonTick by remember { mutableStateOf(0L) }
    // Tracks the map's current camera centre so we can place sun/moon
    // markers near the *visible* area as the user pans, rather than
    // anchored to the user's lat/lon (would scroll off-screen).
    var mapCenter by remember { mutableStateOf<LatLng?>(null) }
    var visibleRadiusKm by remember { mutableStateOf(0.0) }
    LaunchedEffect(showSunMoon) {
        if (!showSunMoon) {
            sunMoonTick = 0L
            return@LaunchedEffect
        }
        while (true) {
            sunMoonTick = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000L)
        }
    }

    // Refresh marker layer on sighting updates (real polls) and on liveTick
    // (between-poll dead reckoning). We rebuild rather than diff: ~20
    // markers max, trivial cost, dodges the bookkeeping of "did this
    // ICAO move or leave."
    LaunchedEffect(sightings, map, liveTick, selectedIcao, sunMoonTick, showSunMoon, mapCenter, visibleRadiusKm) {
        val m = map ?: return@LaunchedEffect
        markers.forEach { m.removeMarker(it) }
        markers.clear()
        highlightMarker?.let { m.removeMarker(it) }
        highlightMarker = null
        trailPolylines.forEach { m.removePolyline(it) }
        trailPolylines.clear()
        sunMarker?.let { m.removeMarker(it) }
        sunMarker = null
        moonMarker?.let { m.removeMarker(it) }
        moonMarker = null

        // Trail history bookkeeping — track every active sighting, drop
        // entries for aircraft that left the search radius.
        val activeIcaos = sightings.map { it.icaoHex }.toSet()
        trailHistory.keys.filterNot { it in activeIcaos }.forEach { trailHistory.remove(it) }
        sightings.forEach { s ->
            val h = trailHistory.getOrPut(s.icaoHex) { mutableListOf() }
            val last = h.lastOrNull()
            if (last == null || last.latitude != s.lat || last.longitude != s.lon) {
                h += LatLng(s.lat, s.lon)
                // Cap at 60 entries (~10 min at 10s polls).
                while (h.size > 60) h.removeAt(0)
            }
        }

        // Render trails for ALL aircraft so the user can see who's heading
        // where at a glance. Non-selected planes get a thin, faint trail;
        // the selected one gets the bright cyan treatment so it stays
        // visually distinct.
        sightings.forEach { s ->
            val isSelected = s.icaoHex == selectedIcao
            val (sLat, sLon) =
                if (liveMode) deadReckon(s, liveTick) else s.lat to s.lon
            val history = trailHistory[s.icaoHex] ?: emptyList()
            val pastPoints = history.toMutableList()
            val nowPt = LatLng(sLat, sLon)
            if (pastPoints.lastOrNull()?.let {
                    it.latitude != nowPt.latitude || it.longitude != nowPt.longitude
                } != false) {
                pastPoints += nowPt
            }
            // Selected: bright cyan, thick. Others: dim cyan, thin — same
            // colour family so the eye can still link them, just toned down.
            val pastColor = if (isSelected)
                android.graphics.Color.argb(240, 0x00, 0xE5, 0xFF)
            else
                android.graphics.Color.argb(100, 0x00, 0xBC, 0xD4)
            val pastWidth = if (isSelected) 5.0f else 1.5f
            if (pastPoints.size >= 2) {
                trailPolylines += m.addPolyline(
                    PolylineOptions()
                        .addAll(pastPoints).color(pastColor).width(pastWidth),
                )
            }
            // Future: selected gets the 3-minute dashed projection; other
            // aircraft get a short ~30s prediction (first 2 segments only)
            // so the map doesn't drown in lines.
            val effectiveHeading = s.headingDeg ?: derivedHeading(history)
            val futureSegs = futureTrailSegments(s, sLat, sLon, effectiveHeading)
                .let { if (isSelected) it else it.take(2) }
            val futureColor = if (isSelected)
                android.graphics.Color.argb(220, 0x00, 0xE5, 0xFF)
            else
                android.graphics.Color.argb(90, 0x00, 0xBC, 0xD4)
            val futureWidth = if (isSelected) 4.0f else 1.5f
            futureSegs.forEach { seg ->
                trailPolylines += m.addPolyline(
                    PolylineOptions()
                        .add(seg.first).add(seg.second)
                        .color(futureColor).width(futureWidth),
                )
            }
        }

        // Highlight ring + camera follow for the selected aircraft.
        val sel = selectedIcao?.let { hex -> sightings.firstOrNull { it.icaoHex == hex } }
        if (sel != null) {
            val (selLat, selLon) =
                if (liveMode) deadReckon(sel, liveTick) else sel.lat to sel.lon
            highlightMarker = m.addMarker(
                MarkerOptions()
                    .position(LatLng(selLat, selLon))
                    .icon(highlightIcon),
            )
            // Pan to keep the selection in view, but don't zoom (annoying
            // when the user is exploring a wider area).
            m.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                    LatLng(selLat, selLon),
                ),
                400,
            )
        } else if (prevSelected != null) {
            // selected → null transition: pan back to the user pin so the
            // camera doesn't stay aimed at where the deselected aircraft was.
            m.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                    LatLng(centreLat, centreLon),
                ),
                400,
            )
        }
        prevSelected = selectedIcao

        // Sun + moon markers, drawn under aircraft markers but above
        // highlight. Positioned at radiusKm × 0.85 from the user pin along
        // each body's azimuth so they sit visibly inside the search circle
        // edge. Hidden when the body is below the horizon.
        if (showSunMoon) {
            // Sun/moon position encodes the real altitude angle: a body
            // on the horizon appears near the visible edge; one at the
            // zenith sits at the centre; linear in between. The visible-
            // area radius is the "edge" reference so the encoding stays
            // legible at any zoom.
            val anchorCenter = mapCenter ?: LatLng(centreLat, centreLon)
            val areaRadiusKm = if (visibleRadiusKm > 0) visibleRadiusKm else radiusKm.toDouble()
            val sm = computeSunMoonOnMap(
                userLat = centreLat, userLon = centreLon,
                anchorLat = anchorCenter.latitude, anchorLon = anchorCenter.longitude,
                areaRadiusKm = areaRadiusKm,
            )
            android.util.Log.i(
                "AircraftWatch",
                "sun/moon: anchor=(${"%.4f,%.4f".format(anchorCenter.latitude, anchorCenter.longitude)}) " +
                    "areaR=${"%.1f".format(areaRadiusKm)}km " +
                    "sun=${sm.sun?.let { "%.4f,%.4f".format(it.latitude, it.longitude) } ?: "BELOW HORIZON"} " +
                    "moon=${sm.moon?.let { "%.4f,%.4f".format(it.latitude, it.longitude) } ?: "BELOW HORIZON"}",
            )
            sm.sun?.let { latlng ->
                sunMarker = m.addMarker(
                    MarkerOptions().position(latlng).icon(sunIcon).title("Sun"),
                )
            }
            sm.moon?.let { latlng ->
                moonMarker = m.addMarker(
                    MarkerOptions().position(latlng).icon(moonIcon).title("Moon"),
                )
            }
        }

        sightings.forEach { s ->
            val (drawLat, drawLon) =
                if (liveMode) deadReckon(s, liveTick) else s.lat to s.lon
            val title = (s.callsign ?: s.icaoHex.uppercase()) +
                String.format(Locale.US, " · %.1f km", s.distanceKm)
            val bucket = (((s.headingDeg ?: 0.0) / 15.0).toInt().mod(24)) * 15
            val size = aircraftSizeFor(s.typeCode, s.model)
            // Marker fill colour matches the row's proximity band so a
            // plane that reads as "red" on the list also reads as red on
            // the map. Compose Color → Android Color int via toArgb().
            val tint = proximityColor(s.distanceKm, radiusKm).let {
                android.graphics.Color.argb(
                    (it.alpha * 255).toInt(),
                    (it.red * 255).toInt(),
                    (it.green * 255).toInt(),
                    (it.blue * 255).toInt(),
                )
            }
            val icon = iconCache.getOrPut(Triple(bucket, size, tint)) {
                iconFactory.fromBitmap(
                    rotatedAircraftBitmap(
                        ctx,
                        headingDeg = bucket.toFloat(),
                        sizeScale = size.scale,
                        tintColor = tint,
                    ),
                )
            }
            markers += m.addMarker(
                MarkerOptions()
                    .position(LatLng(drawLat, drawLon))
                    .title(title)
                    .snippet(s.icaoHex)  // used by onMarkerClickListener to identify which plane was tapped
                    .icon(icon),
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(240.dp),
    ) {
        AndroidView(
            factory = { ctx ->
                MapLibre.getInstance(ctx)
                MapView(ctx).apply {
                    mapViewRef.value = this
                    onCreate(null)
                    getMapAsync { ml ->
                        map = ml
                        ml.setStyle(if (isDark) STYLE_POSITRON else STYLE_LIBERTY)
                        ml.uiSettings.isAttributionEnabled = true
                        ml.uiSettings.isLogoEnabled = false

                        // Zoom approximate so the configured radius roughly
                        // fills the viewport at startup. radius → zoom is
                        // empirical (zoom 10 ≈ 50 km half-width at lat 45).
                        val zoom = when {
                            radiusKm <= 10 -> 11.5
                            radiusKm <= 30 -> 10.0
                            radiusKm <= 60 -> 9.0
                            radiusKm <= 120 -> 8.0
                            else -> 7.0
                        }
                        ml.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(centreLat, centreLon))
                            .zoom(zoom)
                            .build()

                        // Tapping a plane marker selects the same way as
                        // tapping its row in the list. Aircraft markers
                        // carry their ICAO hex in `snippet`; the user pin
                        // + heading cone + sun + moon have no snippet, so
                        // we early-return for those.
                        ml.setOnMarkerClickListener { marker ->
                            val icao = marker.snippet
                            if (!icao.isNullOrBlank()) {
                                onMarkerSelect(icao)
                                true   // consume — no info-window popup
                            } else {
                                false
                            }
                        }

                        // Track the visible region so the sun/moon overlay
                        // can follow as the user pans or zooms. Camera-idle
                        // fires after both gestures + after programmatic
                        // animations — covers everything.
                        ml.addOnCameraIdleListener {
                            val target = ml.cameraPosition.target ?: return@addOnCameraIdleListener
                            mapCenter = target
                            val ne = ml.projection.visibleRegion.latLngBounds.northEast
                            visibleRadiusKm = haversineKm(
                                target.latitude, target.longitude,
                                ne.latitude, ne.longitude,
                            )
                        }

                        // User pin + heading cone are added by the
                        // LaunchedEffect(map, deviceAzimuth) above — they
                        // need to re-stack on every azimuth change so the
                        // pin stays on top of the cone.
                    }
                }
            },
        )
    }
}

private const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron"

/** Project a position [distanceM] along a great-circle bearing of
 *  [bearingDeg] from (lat, lon). Flat-earth approximation — fine for
 *  the kilometre-scale projections we use on the Aircraft Watch map. */
private fun projectAlong(
    lat: Double, lon: Double, bearingDeg: Double, distanceM: Double,
): LatLng {
    val brgRad = bearingDeg * kotlin.math.PI / 180.0
    val latRad = lat * kotlin.math.PI / 180.0
    val dLat = (distanceM / 111_111.0) * kotlin.math.cos(brgRad)
    val dLon = (distanceM / (111_111.0 * kotlin.math.cos(latRad))) * kotlin.math.sin(brgRad)
    return LatLng(lat + dLat, lon + dLon)
}

/** Six visible 20s segments separated by 10s gaps = a dashed-looking
 *  ~3-minute predicted track. Projects far enough that the line stays
 *  visible across the map even at zoomed-out levels. Skipped only if the
 *  body is essentially stationary; falls back to a heading derived from
 *  the past trail when the transponder didn't report `headingDeg`. */
private fun futureTrailSegments(
    s: AircraftSighting, startLat: Double, startLon: Double, heading: Double?,
): List<Pair<LatLng, LatLng>> {
    val hdg = heading ?: return emptyList()
    val gs = s.groundSpeedKt ?: return emptyList()
    if (gs <= 0.5) return emptyList()  // stationary / data noise
    // 6 visible 20s segments at t = 0, 30, 60, 90, 120, 150 s; each runs
    // for 20s, then a 10s gap before the next.
    val msPerSec = 1000.0
    val visibleStarts = doubleArrayOf(0.0, 30.0, 60.0, 90.0, 120.0, 150.0)
    return visibleStarts.map { tStart ->
        val a = deadReckon(
            s.copy(lat = startLat, lon = startLon, headingDeg = hdg),
            (tStart * msPerSec).toLong(),
        )
        val b = deadReckon(
            s.copy(lat = startLat, lon = startLon, headingDeg = hdg),
            ((tStart + 20.0) * msPerSec).toLong(),
        )
        LatLng(a.first, a.second) to LatLng(b.first, b.second)
    }
}

/** Rough full-frame focal-length recommendation for the aircraft to occupy
 *  ~30 % of the frame's horizontal width. Wingspan is keyed off the
 *  AircraftSize class (typical span per category, not per-model — would
 *  need a per-typecode table for that). Result is snapped to common lens
 *  steps so it reads as a hint, not a precision calculation. APS-C users
 *  divide by 1.5, MFT by 2. */
internal fun recommendedFocalLengthMm(s: AircraftSighting): Int {
    val wingspanM = when (com.ehrocha.pulsar.transport.aircraft.aircraftSizeFor(s.typeCode, s.model)) {
        com.ehrocha.pulsar.transport.aircraft.AircraftSize.LIGHT  -> 10.0   // Cessna 172 ≈ 11 m, R44 ≈ 10 m
        com.ehrocha.pulsar.transport.aircraft.AircraftSize.MEDIUM -> 35.0   // B737/A320 family
        com.ehrocha.pulsar.transport.aircraft.AircraftSize.LARGE  -> 60.0   // B767/777, A330/350
        com.ehrocha.pulsar.transport.aircraft.AircraftSize.HEAVY  -> 75.0   // B747-8 ≈ 68 m, A380 ≈ 80 m
    }
    val distM = s.distanceKm * 1000.0
    val altM = (s.altitudeFt ?: 0.0) * 0.3048
    val slantM = kotlin.math.sqrt(distM * distM + altM * altM)
    if (slantM < 1.0) return 24
    // 10.8 = (full-frame sensor width 36 mm) × (target fill 0.30). Small-angle
    // approximation — fine because plane angles at spotter distances are <5°.
    val raw = (10.8 * slantM / wingspanM).toInt()
    val steps = intArrayOf(24, 35, 50, 85, 135, 200, 300, 400, 500, 600, 800, 1000, 1200, 1500, 2000)
    return steps.firstOrNull { it >= raw } ?: steps.last()
}

/** Approximate heading (degrees, 0=N) from the last two trail points.
 *  Used as a fallback when the OpenSky sighting's `headingDeg` is null
 *  (some transponders only report mode-S without an ADS-B track). */
private fun derivedHeading(history: List<LatLng>): Double? {
    if (history.size < 2) return null
    val a = history[history.size - 2]
    val b = history[history.size - 1]
    val latRad = a.latitude * kotlin.math.PI / 180.0
    val dLat = b.latitude - a.latitude
    val dLon = (b.longitude - a.longitude) * kotlin.math.cos(latRad)
    if (dLat == 0.0 && dLon == 0.0) return null
    val deg = Math.toDegrees(kotlin.math.atan2(dLon, dLat))
    return (deg + 360.0) % 360.0
}

/** Sun + moon positions projected onto the map. Azimuth is computed from
 *  the user's lat/lon (astronomically correct), but the marker placement
 *  is anchored to the current map-camera centre + a distance offset so
 *  the markers stay visible as the user pans or zooms. Returns null for
 *  whichever body is below the horizon at the user's location. */
private data class SunMoonOnMap(val sun: LatLng?, val moon: LatLng?)

private fun computeSunMoonOnMap(
    userLat: Double, userLon: Double,
    anchorLat: Double, anchorLon: Double,
    areaRadiusKm: Double,
): SunMoonOnMap {
    val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
    val date = now.toLocalDate()
    val utcHour = now.hour + now.minute / 60.0 + now.second / 3600.0
    val lst = com.ehrocha.pulsar.astro.AstroCalculator.lst(date, utcHour, userLon)
    val (sunRa, sunDec) = com.ehrocha.pulsar.astro.AstroCalculator.sunPosition(date)
    val sunAlt = com.ehrocha.pulsar.astro.AstroCalculator.altitude(userLat, sunDec, lst - sunRa)
    val sunAz = com.ehrocha.pulsar.astro.AstroCalculator.azimuth(userLat, sunDec, lst - sunRa)
    val (moonRa, moonDec) = com.ehrocha.pulsar.astro.AstroCalculator.moonPosition(date, utcHour)
    val moonAlt = com.ehrocha.pulsar.astro.AstroCalculator.altitude(userLat, moonDec, lst - moonRa)
    val moonAz = com.ehrocha.pulsar.astro.AstroCalculator.azimuth(userLat, moonDec, lst - moonRa)
    val sun = projectCelestial(sunAlt, sunAz, anchorLat, anchorLon, areaRadiusKm)
    val moon = projectCelestial(moonAlt, moonAz, anchorLat, anchorLon, areaRadiusKm)
    return SunMoonOnMap(sun, moon)
}

/** Project a celestial body onto the map. Radial offset from the map
 *  centre encodes the body's altitude:
 *   - Below horizon (alt ≤ 0) → null (don't draw).
 *   - On the horizon (alt = 0+) → near the visible-area edge (90% of
 *     the area radius, so it doesn't clip off-screen).
 *   - At the zenith (alt = 90°) → at the anchor centre. The azimuth
 *     formula divides by cos(alt) and goes numerically unstable as
 *     alt → 90°, so above 85° we snap to centre regardless.
 *   - In between → linear in altitude. */
private fun projectCelestial(
    altDeg: Double, azDeg: Double,
    anchorLat: Double, anchorLon: Double, areaRadiusKm: Double,
): LatLng? = when {
    altDeg <= 0 -> null
    altDeg > 85 -> LatLng(anchorLat, anchorLon)
    else -> {
        val fraction = ((90.0 - altDeg) / 90.0).coerceIn(0.0, 0.9)
        projectAlong(anchorLat, anchorLon, azDeg, areaRadiusKm * 1000.0 * fraction)
    }
}

/** Great-circle distance between two lat/lon points in kilometres. Used
 *  by the sun/moon overlay to compute the visible-area radius. */
private fun haversineKm(
    lat1: Double, lon1: Double, lat2: Double, lon2: Double,
): Double {
    val r = 6371.0
    val dLat = (lat2 - lat1) * kotlin.math.PI / 180.0
    val dLon = (lon2 - lon1) * kotlin.math.PI / 180.0
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(lat1 * kotlin.math.PI / 180.0) *
        kotlin.math.cos(lat2 * kotlin.math.PI / 180.0) *
        kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * r * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}

/** Dead-reckon a sighting forward from its last polled position by
 *  [elapsedMs]. Uses heading + ground speed; returns the original position
 *  when either is unknown (we can't extrapolate without both). The
 *  flat-earth approximation is good enough for the ~10-second windows
 *  this is used for — accumulated error stays under ~50 m at typical
 *  cruise speeds. */
private fun deadReckon(s: AircraftSighting, elapsedMs: Long): Pair<Double, Double> {
    val hdg = s.headingDeg ?: return s.lat to s.lon
    val gs = s.groundSpeedKt ?: return s.lat to s.lon
    val elapsedSec = elapsedMs / 1000.0
    if (elapsedSec <= 0.0) return s.lat to s.lon
    val distanceM = gs * 0.514444 * elapsedSec  // knots → m/s × seconds
    val hdgRad = hdg * kotlin.math.PI / 180.0
    val latRad = s.lat * kotlin.math.PI / 180.0
    val dLat = (distanceM / 111_111.0) * kotlin.math.cos(hdgRad)
    val dLon = (distanceM / (111_111.0 * kotlin.math.cos(latRad))) * kotlin.math.sin(hdgRad)
    return (s.lat + dLat) to (s.lon + dLon)
}

/** Device compass state — azimuth (compass heading, 0 = N, snapped to a
 *  5° bucket) plus accuracy. Accuracy comes from
 *  [SensorEvent.accuracy] / [SensorEventListener.onAccuracyChanged] which
 *  for the rotation-vector sensor reflects the magnetometer's calibration
 *  state. Low / unreliable → the UI should prompt the user to do the
 *  figure-8 calibration dance. */
private data class DeviceCompass(val azimuth: Int, val accuracy: Int)

/** [userLat] / [userLon] enable magnetic-to-true declination correction.
 *  The rotation-vector sensor reports azimuth relative to MAGNETIC north;
 *  map tiles + every astro/photo app render against TRUE north. Without
 *  the correction the heading cone points at the wrong city by exactly
 *  the local magnetic declination — up to ~20° in some parts of the
 *  world (Brazil, eastern Canada, southern Australia). */
@Composable
private fun rememberDeviceCompass(userLat: Double?, userLon: Double?): DeviceCompass {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var azimuthBucket by remember { mutableStateOf(0) }
    var accuracy by remember { mutableStateOf(android.hardware.SensorManager.SENSOR_STATUS_NO_CONTACT) }
    // Recompute declination only when the user location changes meaningfully.
    // The world-magnetic-model field varies smoothly — recomputing on every
    // sensor tick would burn cycles for no visible accuracy gain.
    val declination: Float = remember(userLat, userLon) {
        if (userLat != null && userLon != null) {
            runCatching {
                android.hardware.GeomagneticField(
                    userLat.toFloat(), userLon.toFloat(), 0f,
                    System.currentTimeMillis(),
                ).declination
            }.getOrDefault(0f)
        } else 0f
    }
    DisposableEffect(ctx, declination) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : android.hardware.SensorEventListener {
            private val rotMat = FloatArray(9)
            private val orient = FloatArray(3)
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                android.hardware.SensorManager.getOrientation(rotMat, orient)
                val magneticDeg = Math.toDegrees(orient[0].toDouble())
                // Magnetic → true: add declination. Same convention every
                // astronomy app uses. Wrap to [0, 360).
                val trueDeg = ((magneticDeg + declination + 360.0) % 360.0).toInt()
                val bucket = (trueDeg / 5) * 5
                if (bucket != azimuthBucket) {
                    android.util.Log.d(
                        "AircraftWatch",
                        "sensor: rad=${"%.3f".format(orient[0])} " +
                            "magDeg=${"%.1f".format(magneticDeg)} " +
                            "decl=${"%.1f".format(declination)} " +
                            "trueDeg=$trueDeg bucket=$bucket",
                    )
                    azimuthBucket = bucket
                }
                if (event.accuracy != accuracy) accuracy = event.accuracy
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, acc: Int) {
                if (acc != accuracy) accuracy = acc
            }
        }
        if (sensor != null) {
            sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm?.unregisterListener(listener) }
    }
    return DeviceCompass(azimuthBucket, accuracy)
}

/** Render the given drawable to a bitmap rotated by [headingDeg] and
 *  scaled by [sizeScale] (1.0 = baseline, see [AircraftSize]). Source
 *  vectors point north (heading 0); we rotate around the bitmap centre so
 *  the plane nose points at the real heading on the map. The drawable is
 *  rasterised onto a transparent canvas of fixed size so all marker
 *  bitmaps share dimensions — MapLibre anchors at the centre, so a
 *  smaller plane just has more transparent padding around it rather than
 *  shifting position.
 *
 *  Optional [tintColor] applies a `SRC_IN` tint to the drawable before
 *  rasterising — used to colour aircraft markers by proximity. */
private fun rotatedAircraftBitmap(
    ctx: android.content.Context,
    headingDeg: Float,
    sizeScale: Float = 1f,
    @androidx.annotation.DrawableRes drawableRes: Int = R.drawable.ic_aircraft_marker,
    tintColor: Int? = null,
): android.graphics.Bitmap {
    val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, drawableRes)
        ?: error("drawable $drawableRes missing")
    val workingDrawable = if (tintColor != null) {
        val mutated = drawable.mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTint(mutated, tintColor)
        androidx.core.graphics.drawable.DrawableCompat.setTintMode(
            mutated, android.graphics.PorterDuff.Mode.SRC_IN,
        )
        mutated
    } else drawable
    val canvasSize = 144  // pixels — large enough for HEAVY at scale 1.35
    val drawnSize = (96 * sizeScale).toInt().coerceAtLeast(24)
    val offset = (canvasSize - drawnSize) / 2
    val base = android.graphics.Bitmap.createBitmap(canvasSize, canvasSize, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(base).also { c ->
        workingDrawable.setBounds(offset, offset, offset + drawnSize, offset + drawnSize)
        workingDrawable.draw(c)
    }
    if (headingDeg == 0f) return base
    val matrix = android.graphics.Matrix().apply {
        postRotate(headingDeg, canvasSize / 2f, canvasSize / 2f)
    }
    return android.graphics.Bitmap.createBitmap(base, 0, 0, canvasSize, canvasSize, matrix, true)
}

@Composable
private fun SettingsPanel(
    lat: Double,
    lon: Double,
    radiusKm: Int,
    onRadiusChange: (Int) -> Unit,
    maxAltFt: Int,
    onMaxAltChange: (Int) -> Unit,
    intervalSec: Int,
    onIntervalChange: (Int) -> Unit,
    showSunMoon: Boolean,
    onShowSunMoonChange: (Boolean) -> Unit,
    mapHeadingLock: Boolean,
    onMapHeadingLockChange: (Boolean) -> Unit,
    compassAccuracy: Int,
    onShowCalibrate: () -> Unit,
    watching: Boolean,
    lastUpdateMs: Long,
    providerName: String,
) {
    // No outer Surface — the BottomSheetScaffold provides one. Status text
    // is FIRST so it's the line visible in the peek (collapsed) state; the
    // sliders are below the fold and revealed when the user drags up.
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        val statusText = when {
            !watching -> stringResource(R.string.aircraft_watch_paused)
            lastUpdateMs == 0L -> stringResource(R.string.aircraft_watch_fetching)
            else -> stringResource(
                R.string.aircraft_watch_updated_at,
                formatTime(lastUpdateMs),
                providerName,
            )
        }
        Text(
            statusText + "  ·  " + String.format(Locale.US, "%.4f°, %.4f°", lat, lon),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Sliders — only visible when the sheet is expanded. Stacked
        // vertically with full-row widths for thumb-friendly hit targets.
        SliderRow(
            label = stringResource(R.string.aircraft_watch_radius_label, radiusKm),
            value = radiusKm.toFloat(),
            range = 5f..200f,
            steps = 38,
            onChange = { onRadiusChange(it.roundToInt()) },
        )
        SliderRow(
            label = stringResource(R.string.aircraft_watch_max_alt_label, maxAltFt),
            value = maxAltFt.toFloat(),
            range = 1_000f..50_000f,
            steps = 48,
            // Snap to 1 000-ft increments — finer is noise at these
            // altitudes and the slider feels jumpier.
            onChange = { onMaxAltChange((it / 1000f).roundToInt() * 1000) },
        )
        Text(
            stringResource(R.string.aircraft_watch_alt_hints),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 0.dp, bottom = 4.dp),
        )
        // Interval slider: 0 is the "Live" sentinel (drag fully left), 5..60
        // are real seconds. Snap the unreachable 1..4 zone up to 5 — the
        // slider visually allows landing there but the value commits to 5.
        SliderRow(
            label = if (intervalSec == 0) stringResource(R.string.aircraft_watch_interval_live)
                    else stringResource(R.string.aircraft_watch_interval_label, intervalSec),
            value = intervalSec.toFloat(),
            range = 0f..60f,
            steps = 60,
            onChange = {
                val r = it.roundToInt()
                onIntervalChange(if (r in 1..4) 5 else r)
            },
        )
        // Sun / moon overlay toggle. Below the sliders so it doesn't push
        // the more-commonly-used controls down.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.aircraft_show_sun_moon),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Switch(
                checked = showSunMoon,
                onCheckedChange = onShowSunMoonChange,
            )
        }
        // Map-rotates-with-phone toggle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.aircraft_map_heading_lock),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Switch(
                checked = mapHeadingLock,
                onCheckedChange = onMapHeadingLockChange,
            )
        }
        // Compass status + manual calibrate. Always visible: the sensor
        // accuracy banner above the map only fires when Android self-
        // reports low confidence, but the compass can read consistently
        // wrong (near metal / electronics) while the sensor still says
        // "HIGH". Manual calibrate gives the user an escape hatch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    R.string.aircraft_compass_status,
                    compassAccuracyLabel(compassAccuracy),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onShowCalibrate) {
                Text(stringResource(R.string.aircraft_compass_calibrate))
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NoLocationCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.aircraft_watch_no_location_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.aircraft_watch_no_location_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Yellow banner shown when the rotation-vector sensor reports low /
 *  unreliable accuracy. The figure-8 motion remagnetises Android's
 *  magnetometer model and is the standard fix recommended by Google. */
@Composable
private fun CompassCalibrationBanner(onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color(0xFFFFF59D),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Explore,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color(0xFF7C5D00),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.aircraft_compass_calibration),
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color(0xFF5D4500),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = androidx.compose.ui.graphics.Color(0xFF7C5D00),
                )
            }
        }
    }
}

/** Orange banner shown when the OS GPS fix is stale (age > 15 min) or
 *  imprecise (accuracy worse than 200 m). Lets the user trigger a fresh
 *  single-shot GPS read. */
@Composable
private fun GpsAccuracyBanner(
    accuracyM: Float?,
    ageMs: Long?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val detail = buildString {
        accuracyM?.let { append("±", it.toInt(), " m") }
        ageMs?.let {
            if (isNotEmpty()) append(" · ")
            val mins = (it / 60_000L).toInt()
            append(if (mins < 60) "${mins}m" else "${mins / 60}h${mins % 60}m", " old")
        }
    }.ifBlank { "—" }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color(0xFFFFCC80),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.FlightTakeoff,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color(0xFF7C3E00),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.aircraft_gps_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFF5D2A00),
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFF7C3E00),
                )
            }
            if (refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.ui.graphics.Color(0xFF7C3E00),
                )
            } else {
                androidx.compose.material3.TextButton(
                    onClick = onRefresh,
                ) {
                    Text(
                        stringResource(R.string.aircraft_gps_refresh),
                        color = androidx.compose.ui.graphics.Color(0xFF5D2A00),
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = androidx.compose.ui.graphics.Color(0xFF7C3E00),
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(error: String) {
    val text = when (error) {
        "no_location" -> stringResource(R.string.aircraft_watch_no_location_title)
        "fetch_failed" -> stringResource(R.string.aircraft_watch_fetch_failed)
        else -> error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private val PROXIMITY_NEAR = androidx.compose.ui.graphics.Color(0xFF2E7D32)
private val PROXIMITY_MID = androidx.compose.ui.graphics.Color(0xFFF9A825)
private val PROXIMITY_FAR = androidx.compose.ui.graphics.Color(0xFFE65100)

/** Map distance / radius ratio onto the green / yellow / red palette.
 *  Thresholds at 1/3 and 2/3 so each band feels like a meaningful chunk
 *  of the user's chosen search radius. */
private fun proximityColor(distanceKm: Double, radiusKm: Int): androidx.compose.ui.graphics.Color {
    val ratio = if (radiusKm <= 0) 1.0 else distanceKm / radiusKm.toDouble()
    return when {
        ratio < 1.0 / 3.0 -> PROXIMITY_NEAR
        ratio < 2.0 / 3.0 -> PROXIMITY_MID
        else -> PROXIMITY_FAR
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AircraftRow(
    s: AircraftSighting,
    radiusKm: Int,
    userLat: Double,
    userLon: Double,
    selected: Boolean,
    onSelectOnMap: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    val accent = proximityColor(s.distanceKm, radiusKm)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rowBadges = aircraftBadges(s)
    // Selected rows get a stronger background + a leading accent so the
    // tap-to-highlight feedback is obvious without a separate selection
    // chip. The colour matches the map's highlight ring.
    val bgAlpha = if (selected) 0.28f else 0.13f
    Surface(
        onClick = onSelectOnMap,
        shape = RoundedCornerShape(12.dp),
        // Tinted background lets the proximity band read at a glance without
        // shouting — the colour saturation stays around the existing
        // dashboard-card palette levels.
        color = accent.copy(alpha = bgAlpha),
        border = if (selected) androidx.compose.foundation.BorderStroke(
            2.dp,
            androidx.compose.ui.graphics.Color(0xFFFFEB3B),
        ) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.callsign ?: s.icaoHex.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                // Subline: model + operator if known, otherwise fall back
                // to ICAO hex + country. The progressive enhancement means
                // the row reads usefully on the first poll and gets richer
                // as the metadata cache fills.
                val sub = listOfNotNull(
                    s.model,
                    s.operator?.takeIf { it != s.model },
                    s.registration,
                ).joinToString(" · ").ifEmpty {
                    "${s.icaoHex.uppercase()}${s.originCountry?.let { " · $it" } ?: ""}"
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rowBadges.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        rowBadges.forEach { BadgeChip(it) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    formatFlightLine(s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format(Locale.US, "%.1f km", s.distanceKm),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    "${bearingArrow(s.bearingDeg)} ${s.bearingDeg.roundToInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Info button — the secondary action, since the row body now
            // selects-on-map. Tap opens the full detail dialog.
            IconButton(
                onClick = { showDetails = true },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = stringResource(R.string.aircraft_info_button),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showDetails) {
        AircraftDetailDialog(
            s = s,
            onDismiss = { showDetails = false },
            onLogSighting = {
                SpottingLogStore.add(
                    ctx,
                    LoggedSighting(
                        icaoHex = s.icaoHex,
                        callsign = s.callsign,
                        model = s.model,
                        registration = s.registration,
                        operator = s.operator,
                        distanceKm = s.distanceKm,
                        whenMs = System.currentTimeMillis(),
                        userLat = userLat,
                        userLon = userLon,
                    ),
                )
                android.widget.Toast.makeText(
                    ctx,
                    ctx.getString(R.string.aircraft_log_added),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }
}

/** Full-info modal — shown on tap. Everything we know about the aircraft
 *  in one place; values fall back to "—" when the metadata cache hasn't
 *  resolved that field yet (rare after the first cycle). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AircraftDetailDialog(
    s: AircraftSighting,
    onDismiss: () -> Unit,
    onLogSighting: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val badges = aircraftBadges(s)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                onLogSighting()
                onDismiss()
            }) {
                Text(stringResource(R.string.aircraft_log_this))
            }
        },
        title = {
            Text(
                s.callsign ?: s.icaoHex.uppercase(),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Hero photo + credit. Tapping opens the source page in the
                // browser — planespotters.net AUP requires the link to be
                // surfaced, not just hidden in About.
                if (s.photoUrl != null) {
                    coil3.compose.AsyncImage(
                        model = s.photoUrl,
                        contentDescription = s.model ?: s.icaoHex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                s.photoSourceUrl?.let { url ->
                                    runCatching {
                                        ctx.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url),
                                            ),
                                        )
                                    }
                                }
                            },
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Text(
                        stringResource(
                            R.string.aircraft_photo_credit,
                            s.photoCredit ?: "Planespotters.net",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (badges.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        badges.forEach { BadgeChip(it) }
                    }
                }
                DetailRow(stringResource(R.string.aircraft_detail_registration), s.registration)
                DetailRow(stringResource(R.string.aircraft_detail_model), s.model)
                DetailRow(stringResource(R.string.aircraft_detail_manufacturer), s.manufacturer)
                DetailRow(stringResource(R.string.aircraft_detail_operator), s.operator)
                DetailRow(stringResource(R.string.aircraft_detail_type_code), s.typeCode)
                DetailRow(stringResource(R.string.aircraft_detail_built), s.builtYear?.toString())
                DetailRow(stringResource(R.string.aircraft_detail_icao), s.icaoHex.uppercase())
                DetailRow(stringResource(R.string.aircraft_detail_origin), s.originCountry)
                DetailRow(stringResource(R.string.aircraft_detail_squawk), s.squawk)
                Spacer(Modifier.height(4.dp))
                DetailRow(
                    stringResource(R.string.aircraft_detail_distance),
                    String.format(Locale.US, "%.2f km", s.distanceKm),
                )
                DetailRow(
                    stringResource(R.string.aircraft_detail_bearing),
                    String.format(Locale.US, "%d° %s", s.bearingDeg.roundToInt(), bearingArrow(s.bearingDeg)),
                )
                DetailRow(stringResource(R.string.aircraft_detail_altitude),
                    s.altitudeFt?.let { "${it.roundToInt()} ft" })
                DetailRow(stringResource(R.string.aircraft_detail_speed),
                    s.groundSpeedKt?.let { "${it.roundToInt()} kt" })
                DetailRow(stringResource(R.string.aircraft_detail_heading),
                    s.headingDeg?.let { "${it.roundToInt()}° ${bearingArrow(it)}" })
                DetailRow(stringResource(R.string.aircraft_detail_vertical),
                    s.verticalRateFpm?.let {
                        val sign = if (it >= 0) "↑" else "↓"
                        "$sign ${kotlin.math.abs(it).roundToInt()} fpm"
                    })
                DetailRow(stringResource(R.string.aircraft_detail_position),
                    String.format(Locale.US, "%.4f°, %.4f°", s.lat, s.lon))
                Spacer(Modifier.height(4.dp))
                // Suggested lens — derived from slant range + wingspan class.
                // Hint, not a precision figure; the FF caveat helps users
                // with crop-sensor bodies translate (÷1.5 APS-C, ÷2 MFT).
                DetailRow(
                    stringResource(R.string.aircraft_detail_suggested_lens),
                    stringResource(R.string.aircraft_detail_suggested_lens_value,
                        recommendedFocalLengthMm(s)),
                )
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatFlightLine(s: AircraftSighting): String {
    val alt = s.altitudeFt?.let { "${it.roundToInt()} ft" }
    val spd = s.groundSpeedKt?.let { "${it.roundToInt()} kt" }
    val hdg = s.headingDeg?.let { "${it.roundToInt()}°" }
    val vr = s.verticalRateFpm?.let {
        val sign = if (it >= 0) "↑" else "↓"
        "$sign ${kotlin.math.abs(it).roundToInt()} fpm"
    }
    return listOfNotNull(
        alt,
        spd,
        hdg,
        vr,
        if (s.onGround) "GND" else null,
    ).joinToString(" · ")
}

/** Cheap unicode arrow per 45° octant — enough to glance at heading. */
private fun bearingArrow(deg: Double): String {
    val n = ((deg + 22.5) / 45.0).toInt().mod(8)
    return when (n) {
        0 -> "↑"; 1 -> "↗"; 2 -> "→"; 3 -> "↘"
        4 -> "↓"; 5 -> "↙"; 6 -> "←"; else -> "↖"
    }
}

private fun formatTime(epochMs: Long): String {
    val t = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).format(t)
}

// ── Feature 3 — Rare / interesting badges ────────────────────────────────
// Pure-derivation from existing AircraftSighting fields. Cheap to compute,
// nothing to cache. Each badge has a short label + a tint colour; the row
// shows zero-or-more chips.

private enum class AircraftBadgeKind(
    val labelRes: Int,
    val color: androidx.compose.ui.graphics.Color,
) {
    EMERGENCY(R.string.aircraft_badge_emergency,
        androidx.compose.ui.graphics.Color(0xFFD32F2F)),
    VINTAGE(R.string.aircraft_badge_vintage,
        androidx.compose.ui.graphics.Color(0xFF6D4C41)),
    MILITARY(R.string.aircraft_badge_military,
        androidx.compose.ui.graphics.Color(0xFF455A64)),
    HEAVY(R.string.aircraft_badge_heavy,
        androidx.compose.ui.graphics.Color(0xFF1565C0)),
}

private fun aircraftBadges(s: AircraftSighting): List<AircraftBadgeKind> {
    val out = mutableListOf<AircraftBadgeKind>()
    // Emergency squawks per ICAO: 7500 hijack, 7600 radio failure, 7700 general
    if (s.squawk in setOf("7500", "7600", "7700")) out += AircraftBadgeKind.EMERGENCY
    if (s.builtYear != null && s.builtYear < 1980) out += AircraftBadgeKind.VINTAGE
    // Military: operator/owner string contains the obvious keywords; or the
    // callsign starts with a known military prefix.
    val opUpper = (s.operator ?: "").uppercase()
    val csUpper = (s.callsign ?: "").uppercase()
    val militaryWords = listOf("FORCE", "NAVY", "ARMY", "MARINE", "COAST GUARD", "MILITARY", "DEFENSE", "DEFENCE")
    val militaryCallsignPrefixes = listOf("RCH", "REACH", "RAF", "NATO", "GRIZZLY", "DUKE", "BLUE")
    if (militaryWords.any { it in opUpper } ||
        militaryCallsignPrefixes.any { csUpper.startsWith(it) }) {
        out += AircraftBadgeKind.MILITARY
    }
    if (com.ehrocha.pulsar.transport.aircraft.aircraftSizeFor(s.typeCode, s.model)
        == com.ehrocha.pulsar.transport.aircraft.AircraftSize.HEAVY) {
        out += AircraftBadgeKind.HEAVY
    }
    return out
}

@Composable
private fun BadgeChip(badge: AircraftBadgeKind) {
    Surface(
        shape = RoundedCornerShape(50),
        color = badge.color.copy(alpha = 0.18f),
    ) {
        Text(
            stringResource(badge.labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = badge.color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Feature 1 — Spotting log persistence ─────────────────────────────────
// Local JSON file under app filesDir. Records what the user tapped "Log
// this" on. No remote sync — Pulsar is offline-first.

internal data class LoggedSighting(
    val icaoHex: String,
    val callsign: String?,
    val model: String?,
    val registration: String?,
    val operator: String?,
    val distanceKm: Double,
    val whenMs: Long,
    val userLat: Double,
    val userLon: Double,
)

internal object SpottingLogStore {
    private const val FILE = "pulsar_spotting_log.json"
    private const val LIMIT = 500  // cap so the log can't grow unbounded

    fun load(ctx: android.content.Context): List<LoggedSighting> {
        val f = java.io.File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                LoggedSighting(
                    icaoHex = j.getString("icao"),
                    callsign = j.optString("callsign").takeIf { it.isNotEmpty() },
                    model = j.optString("model").takeIf { it.isNotEmpty() },
                    registration = j.optString("reg").takeIf { it.isNotEmpty() },
                    operator = j.optString("operator").takeIf { it.isNotEmpty() },
                    distanceKm = j.optDouble("distKm", 0.0),
                    whenMs = j.optLong("whenMs", 0L),
                    userLat = j.optDouble("userLat", 0.0),
                    userLon = j.optDouble("userLon", 0.0),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: android.content.Context, s: LoggedSighting) {
        val current = load(ctx).toMutableList()
        // De-dup on (icao, day) so quickly tapping log twice doesn't double-
        // record the same plane, but a different flight tomorrow does add.
        val dayMs = 24L * 3600_000L
        current.removeAll {
            it.icaoHex == s.icaoHex && kotlin.math.abs(it.whenMs - s.whenMs) < dayMs
        }
        current += s
        if (current.size > LIMIT) {
            val drop = current.size - LIMIT
            for (i in 0 until drop) current.removeAt(0)
        }
        save(ctx, current)
    }

    fun delete(ctx: android.content.Context, whenMs: Long, icao: String) {
        val current = load(ctx).filterNot { it.whenMs == whenMs && it.icaoHex == icao }
        save(ctx, current)
    }

    fun clear(ctx: android.content.Context) {
        save(ctx, emptyList())
    }

    private fun save(ctx: android.content.Context, list: List<LoggedSighting>) {
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(org.json.JSONObject().apply {
                put("icao", e.icaoHex)
                e.callsign?.let { put("callsign", it) }
                e.model?.let { put("model", it) }
                e.registration?.let { put("reg", it) }
                e.operator?.let { put("operator", it) }
                put("distKm", e.distanceKm)
                put("whenMs", e.whenMs)
                put("userLat", e.userLat)
                put("userLon", e.userLon)
            })
        }
        java.io.File(ctx.filesDir, FILE).writeText(arr.toString())
    }
}
