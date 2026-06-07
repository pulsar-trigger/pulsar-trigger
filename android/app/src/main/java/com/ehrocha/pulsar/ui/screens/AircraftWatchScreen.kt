/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AircraftWatchScreen(vm: PulsarViewModel, onBack: () -> Unit) {
    val rawSightings by vm.aircraftSightings.collectAsState()
    val watching by vm.aircraftWatching.collectAsState()
    val error by vm.aircraftWatchError.collectAsState()
    val lastUpdateMs by vm.aircraftWatchLastUpdateMs.collectAsState()
    val radiusKm by vm.aircraftWatchRadiusKm.collectAsState()
    val maxAltFt by vm.aircraftWatchMaxAltitudeFt.collectAsState()
    val intervalSec by vm.aircraftWatchIntervalSec.collectAsState()
    val location = vm.aircraftWatchLocation()
    // Altitude filter is applied locally so the slider feels instant —
    // changing it doesn't wait for the next poll. Aircraft with no altitude
    // (typically ground traffic or transponders not reporting) pass through:
    // they're the most interesting subjects for spotters anyway.
    val sightings = androidx.compose.runtime.remember(rawSightings, maxAltFt) {
        rawSightings.filter { it.altitudeFt == null || it.altitudeFt <= maxAltFt }
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

            // Map — fixed height so the list still gets meaningful space
            // on phone-sized devices. Lives above the (now-collapsible)
            // settings sheet.
            AircraftMap(
                centreLat = location.first,
                centreLon = location.second,
                radiusKm = radiusKm,
                sightings = sightings,
                liveMode = intervalSec == 0,
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
                            AircraftRow(s, radiusKm = radiusKm)
                        }
                    }
                }
            }
        }
    }
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
    // rotate). Cache one per (heading-bucket, size-class) pair: 15° heading
    // buckets × 4 size classes = up to 96 cache entries built lazily as
    // they're needed. Visually indistinguishable from per-degree rotation.
    val iconCache = remember { mutableMapOf<Pair<Int, AircraftSize>, Icon>() }

    // Device compass heading — used to draw a directional cone over the
    // user pin so the user can see "the plane east of me is to my right
    // RIGHT NOW." Snapped to 5° to avoid burning the map redrawing every
    // sensor tick.
    val deviceAzimuth = rememberDeviceAzimuth()

    // Directional cone over the user pin — re-created when the device
    // azimuth bucket changes (snapped to 5° to keep the map redraws cheap).
    var headingMarker by remember { mutableStateOf<Marker?>(null) }
    LaunchedEffect(map, deviceAzimuth) {
        val m = map ?: return@LaunchedEffect
        headingMarker?.let { m.removeMarker(it) }
        val bmp = rotatedAircraftBitmap(
            ctx,
            headingDeg = deviceAzimuth.toFloat(),
            drawableRes = R.drawable.ic_user_heading,
        )
        headingMarker = m.addMarker(
            MarkerOptions()
                .position(LatLng(centreLat, centreLon))
                .icon(iconFactory.fromBitmap(bmp)),
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

    // Refresh marker layer on sighting updates (real polls) and on liveTick
    // (between-poll dead reckoning). We rebuild rather than diff: ~20
    // markers max, trivial cost, dodges the bookkeeping of "did this
    // ICAO move or leave."
    LaunchedEffect(sightings, map, liveTick) {
        val m = map ?: return@LaunchedEffect
        markers.forEach { m.removeMarker(it) }
        markers.clear()
        sightings.forEach { s ->
            val (drawLat, drawLon) =
                if (liveMode) deadReckon(s, liveTick) else s.lat to s.lon
            val title = (s.callsign ?: s.icaoHex.uppercase()) +
                String.format(Locale.US, " · %.1f km", s.distanceKm)
            val bucket = (((s.headingDeg ?: 0.0) / 15.0).toInt().mod(24)) * 15
            val size = aircraftSizeFor(s.typeCode, s.model)
            val icon = iconCache.getOrPut(bucket to size) {
                iconFactory.fromBitmap(
                    rotatedAircraftBitmap(
                        ctx,
                        headingDeg = bucket.toFloat(),
                        sizeScale = size.scale,
                    ),
                )
            }
            markers += m.addMarker(
                MarkerOptions()
                    .position(LatLng(drawLat, drawLon))
                    .title(title)
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

                        // "Me" marker — person-pin so it's distinct from the
                        // plane markers above. Added once at map setup; the
                        // user location doesn't move during a session. The
                        // direction-cone marker stacked on top is managed
                        // separately so we can re-orient it without
                        // rebuilding this one.
                        val userIcon = iconFactory.fromBitmap(
                            rotatedAircraftBitmap(
                                ctx,
                                headingDeg = 0f,
                                drawableRes = R.drawable.ic_user_marker,
                            ),
                        )
                        ml.addMarker(
                            MarkerOptions()
                                .position(LatLng(centreLat, centreLon))
                                .title("You")
                                .icon(userIcon),
                        )
                    }
                }
            },
        )
    }
}

private const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron"

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

/** Subscribe to the device's rotation-vector sensor and return the current
 *  azimuth (compass heading, 0 = north) snapped to a 5° bucket. Snapping
 *  caps the recomposition rate to a sane number — the map rebuilds the
 *  user direction-cone marker on every change, and 5° is well below human
 *  perception of map-icon-pointing-the-wrong-way. */
@Composable
private fun rememberDeviceAzimuth(): Int {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var azimuthBucket by remember { mutableStateOf(0) }
    DisposableEffect(ctx) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : android.hardware.SensorEventListener {
            private val rotMat = FloatArray(9)
            private val orient = FloatArray(3)
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                android.hardware.SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                android.hardware.SensorManager.getOrientation(rotMat, orient)
                val deg = Math.toDegrees(orient[0].toDouble())
                val normalized = ((deg + 360.0) % 360.0).toInt()
                val bucket = (normalized / 5) * 5
                if (bucket != azimuthBucket) azimuthBucket = bucket
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        if (sensor != null) {
            sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm?.unregisterListener(listener) }
    }
    return azimuthBucket
}

/** Render the given drawable to a bitmap rotated by [headingDeg] and
 *  scaled by [sizeScale] (1.0 = baseline, see [AircraftSize]). Source
 *  vectors point north (heading 0); we rotate around the bitmap centre so
 *  the plane nose points at the real heading on the map. The drawable is
 *  rasterised onto a transparent canvas of fixed size so all marker
 *  bitmaps share dimensions — MapLibre anchors at the centre, so a
 *  smaller plane just has more transparent padding around it rather than
 *  shifting position. */
private fun rotatedAircraftBitmap(
    ctx: android.content.Context,
    headingDeg: Float,
    sizeScale: Float = 1f,
    @androidx.annotation.DrawableRes drawableRes: Int = R.drawable.ic_aircraft_marker,
): android.graphics.Bitmap {
    val drawable = androidx.core.content.ContextCompat.getDrawable(ctx, drawableRes)
        ?: error("drawable $drawableRes missing")
    val canvasSize = 144  // pixels — large enough for HEAVY at scale 1.35
    val drawnSize = (96 * sizeScale).toInt().coerceAtLeast(24)
    val offset = (canvasSize - drawnSize) / 2
    val base = android.graphics.Bitmap.createBitmap(canvasSize, canvasSize, android.graphics.Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(base).also { c ->
        drawable.setBounds(offset, offset, offset + drawnSize, offset + drawnSize)
        drawable.draw(c)
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

@Composable
private fun AircraftRow(s: AircraftSighting, radiusKm: Int) {
    var showDetails by remember { mutableStateOf(false) }
    val accent = proximityColor(s.distanceKm, radiusKm)
    Surface(
        onClick = { showDetails = true },
        shape = RoundedCornerShape(12.dp),
        // Tinted background lets the proximity band read at a glance without
        // shouting — the colour saturation stays around the existing
        // dashboard-card palette levels.
        color = accent.copy(alpha = 0.13f),
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
        }
    }
    if (showDetails) {
        AircraftDetailDialog(s, onDismiss = { showDetails = false })
    }
}

/** Full-info modal — shown on tap. Everything we know about the aircraft
 *  in one place; values fall back to "—" when the metadata cache hasn't
 *  resolved that field yet (rare after the first cycle). */
@Composable
private fun AircraftDetailDialog(s: AircraftSighting, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
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
