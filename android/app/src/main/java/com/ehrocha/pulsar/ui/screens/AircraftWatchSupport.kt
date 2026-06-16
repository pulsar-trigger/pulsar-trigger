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
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────
// Aircraft Watch — map + sensor + derived-metric support. Split out of
// AircraftWatchScreen.kt (the audit's S1 god-file finding): the MapLibre
// composable, marker rasterising, compass/declination plumbing, geo
// projections, and the photographer metrics (lighting, closest approach,
// lens recommendation). Same package — the screen file stays the only
// public entry point.
// ─────────────────────────────────────────────────────────────────────────
/** Cache key for a rasterised marker bitmap. One entry per distinct
 *  combination of heading bucket, size class, proximity tint, and airframe
 *  category (which selects the plane vs helicopter shape). */
internal data class MarkerKey(
    val bucket: Int,
    val size: AircraftSize,
    val tint: Int,
    val category: com.ehrocha.pulsar.transport.aircraft.AircraftCategory,
)

// Aircraft markers render via a data-driven SymbolLayer (non-deprecated):
// one GeoJSON source holding a point feature per plane, each feature naming
// its pre-rendered icon image (rotation + proximity tint + size + category
// baked in, registered into the style by name). Replaces the deprecated
// per-marker addMarker/removeMarker. Trails, sun/moon, the user pin + cone,
// and the highlight ring are still on the annotation API for now — this
// slice proves the data-driven icon + tap-to-select query first.
private const val AC_SOURCE = "aircraft-src"
private const val AC_LAYER = "aircraft-layer"

/** Stable per-icon image name so the same (heading, size, tint, category)
 *  combination registers once and is re-fetched by name after a style swap. */
private fun aircraftImageName(k: MarkerKey): String =
    "ac_${k.bucket}_${k.size.name}_${k.tint}_${k.category.name}"

/** Embedded MapLibre map showing the user's centre plus a marker per
 *  aircraft. Refreshes its marker layer every time [sightings] changes.
 *  Camera centres once on the user location and respects user pan/zoom
 *  thereafter — we don't reset the camera on each poll because that
 *  would fight the user's manual exploration. */
@Composable
internal fun AircraftMap(
    centreLat: Double,
    centreLon: Double,
    radiusKm: Int,
    sightings: List<AircraftSighting>,
    liveMode: Boolean = false,
    selectedIcao: String? = null,
    showSunMoon: Boolean = false,
    deviceAzimuth: Int = 0,
    mapHeadingLock: Boolean = false,
    mapHybrid: Boolean = false,
    onMarkerSelect: (String) -> Unit = {},
) {
    val isDark = when (LocalNightMode.current.value) {
        ThemeMode.Dark, ThemeMode.RedLight -> true
        ThemeMode.Light, ThemeMode.Outdoor -> false
    }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped each time a new style finishes loading. Changing the style
    // clears all annotations (markers/polylines belong to the style layer),
    // so every effect that adds markers keys on this to re-add them.
    var styleEpoch by remember { mutableStateOf(0) }

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
    // Theme roles snapshotted at composition — the marker effect below is
    // not a composable scope, so it captures this value instead.
    val pc = com.ehrocha.pulsar.ui.theme.PulsarTheme.colors
    val iconFactory = remember(ctx) { IconFactory.getInstance(ctx) }
    // Rotated marker icons are expensive to build (drawable → bitmap →
    // matrix rotate). Cache key = (heading-bucket, size-class, tint,
    // category). The theoretical key space is large (24 buckets × 4 sizes ×
    // 15 tint/alpha variants × 2 shapes ≈ 2 900 bitmaps @ ~80 KB each), so
    // cap it as an access-ordered LRU — a busy session realistically uses a
    // few dozen, and 256 × 80 KB ≈ 20 MB worst case.
    val iconCache = remember {
        object : LinkedHashMap<MarkerKey, android.graphics.Bitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MarkerKey, android.graphics.Bitmap>) =
                size > 256
        }
    }

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
            // logcat-only — mirroring into CanonBleLog (the v0.400 chair-spin
            // diagnostic) flooded the shared 1000-line transport ring buffer
            // and evicted the Canon wire history. Rotation bug is fixed.
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
    LaunchedEffect(map, deviceAzimuth, styleEpoch) {
        val m = map ?: return@LaunchedEffect
        headingMarker?.let { m.removeMarker(it) }
        userMarker?.let { m.removeMarker(it) }
        // 1) heading cone first → renders below.
        // Compensate for the current map bearing: marker bitmaps are
        // screen-aligned in MapLibre's legacy API, so we have to subtract
        // the map's rotation to keep the cone pointing in the world
        // direction the device is facing. When heading-lock is on, map
        // bearing == device azimuth → cone rotation = 0 → cone is always
        // upright on screen. When off, map bearing = 0 → cone rotation =
        // device azimuth (the old behaviour). Without this subtraction,
        // both the map AND the cone visibly rotated with the user, which
        // read as "the map rotates twice" during a spin.
        val mapBearing = m.cameraPosition.bearing.toFloat()
        val coneRotation = ((deviceAzimuth.toFloat() - mapBearing) + 360f) % 360f
        val coneBmp = rotatedAircraftBitmap(
            ctx,
            headingDeg = coneRotation,
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
    val highlightIcon = remember(ctx, pc) {
        iconFactory.fromBitmap(
            rotatedAircraftBitmap(
                ctx,
                headingDeg = 0f,
                sizeScale = 1.5f,
                drawableRes = R.drawable.ic_aircraft_highlight,
                tintColor = android.graphics.Color.argb(
                    200,
                    (pc.selection.red * 255).toInt(),
                    (pc.selection.green * 255).toInt(),
                    (pc.selection.blue * 255).toInt(),
                ),
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
    LaunchedEffect(sightings, map, liveTick, selectedIcao, sunMoonTick, showSunMoon, mapCenter, visibleRadiusKm, styleEpoch) {
        val m = map ?: return@LaunchedEffect
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
                android.graphics.Color.argb(240, (pc.trail.red * 255).toInt(), (pc.trail.green * 255).toInt(), (pc.trail.blue * 255).toInt())
            else
                android.graphics.Color.argb(100, (pc.trail.red * 255).toInt(), (pc.trail.green * 255).toInt(), (pc.trail.blue * 255).toInt())
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
                android.graphics.Color.argb(220, (pc.trail.red * 255).toInt(), (pc.trail.green * 255).toInt(), (pc.trail.blue * 255).toInt())
            else
                android.graphics.Color.argb(90, (pc.trail.red * 255).toInt(), (pc.trail.green * 255).toInt(), (pc.trail.blue * 255).toInt())
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

        // ── Aircraft markers → data-driven SymbolLayer ───────────────
        // A style swap drops the source/layer/images, so recreate the
        // source + layer when missing (this effect re-runs on styleEpoch).
        val style = m.style
        if (style != null) {
            if (style.getSourceAs<GeoJsonSource>(AC_SOURCE) == null) {
                style.addSource(GeoJsonSource(AC_SOURCE))
                style.addLayer(
                    SymbolLayer(AC_LAYER, AC_SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        // Rotation is baked into each bitmap (screen-aligned,
                        // exactly as the legacy Marker drew it), so keep the
                        // icon viewport-aligned and don't use iconRotate.
                        PropertyFactory.iconRotationAlignment(
                            Property.ICON_ROTATION_ALIGNMENT_VIEWPORT,
                        ),
                    ),
                )
            }
            val features = sightings.map { s ->
                val (drawLat, drawLon) =
                    if (liveMode) deadReckon(s, liveTick) else s.lat to s.lon
                // Subtract current map bearing so the icon always shows the
                // aircraft's WORLD direction even when the map is rotated.
                // Without this, planes appear rotated by their heading +
                // map bearing — visibly wrong on a heading-locked map and the
                // second contributor to the "everything rotates twice" report.
                val currentMapBearing = m.cameraPosition.bearing
                val rawHeading = (s.headingDeg ?: 0.0) - currentMapBearing
                val bucket = ((rawHeading / 15.0).toInt().mod(24)) * 15
                val size = aircraftSizeFor(s.typeCode, s.model)
                val category = com.ehrocha.pulsar.transport.aircraft
                    .aircraftCategoryFor(s.typeCode, s.model)
                // Marker fill colour matches the row's proximity band so a
                // plane that reads as "red" on the list also reads as red on
                // the map. Opacity encodes ALTITUDE: low aircraft (the
                // shootable subjects) are solid, high cruisers fade back into
                // the sky. Hue = near/far, alpha = low/high — two orthogonal
                // channels. Compose Color → Android Color int.
                val op = altitudeOpacity(s.altitudeFt)
                val tint = proximityColor(s.distanceKm, radiusKm, pc).let {
                    android.graphics.Color.argb(
                        (it.alpha * op * 255).toInt().coerceIn(0, 255),
                        (it.red * 255).toInt(),
                        (it.green * 255).toInt(),
                        (it.blue * 255).toInt(),
                    )
                }
                val markerDrawable = when (category) {
                    com.ehrocha.pulsar.transport.aircraft.AircraftCategory.HELICOPTER ->
                        R.drawable.ic_helicopter_marker
                    else -> R.drawable.ic_aircraft_marker
                }
                val key = MarkerKey(bucket, size, tint, category)
                val name = aircraftImageName(key)
                val bmp = iconCache.getOrPut(key) {
                    rotatedAircraftBitmap(
                        ctx,
                        headingDeg = bucket.toFloat(),
                        sizeScale = size.scale,
                        tintColor = tint,
                        drawableRes = markerDrawable,
                    )
                }
                // Register on first use AND after a style swap (getImage
                // returns null once the style that held it was replaced).
                if (style.getImage(name) == null) style.addImage(name, bmp)
                Feature.fromGeometry(Point.fromLngLat(drawLon, drawLat)).apply {
                    addStringProperty("icon", name)
                    addStringProperty("icao", s.icaoHex)
                }
            }
            style.getSourceAs<GeoJsonSource>(AC_SOURCE)
                ?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }

    // Apply (and re-apply on toggle) the map style. street = vector
    // OpenFreeMap (light/dark); hybrid = Esri satellite imagery + place
    // labels. On load, bump styleEpoch so the marker effects re-add their
    // annotations (a style swap clears them).
    LaunchedEffect(map, mapHybrid, isDark) {
        val m = map ?: return@LaunchedEffect
        applyMapStyle(m, mapHybrid, isDark) { styleEpoch++ }
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
                        // Style is applied by the LaunchedEffect below (so it
                        // can switch street ↔ hybrid at runtime). Listeners +
                        // camera are set here; they survive a style change.
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

                        // Tapping a plane selects the same way as tapping its
                        // row in the list. Aircraft live in a SymbolLayer now,
                        // so resolve the tap by querying rendered features at
                        // the tap point and reading the feature's `icao`. The
                        // user pin / cone / sun / moon are still annotations
                        // (no feature on AC_LAYER), so an empty hit falls
                        // through and the tap does nothing.
                        ml.addOnMapClickListener { point ->
                            val screen = ml.projection.toScreenLocation(point)
                            val icao = ml.queryRenderedFeatures(screen, AC_LAYER)
                                .firstOrNull()?.getStringProperty("icao")
                            if (!icao.isNullOrBlank()) {
                                onMarkerSelect(icao)
                                true   // consume the tap
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
                            visibleRadiusKm = com.ehrocha.pulsar.transport.aircraft.GeoMath.haversineKm(
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

/** Hybrid satellite style — Esri World Imagery raster with the
 *  World_Boundaries_and_Places reference layer stacked on top for place
 *  names + admin boundaries. Esri's services are XYZ Web-Mercator with the
 *  ArcGIS `/{z}/{y}/{x}` tile path. Free to use with attribution; the
 *  attribution string is surfaced via the map's attribution control. */
internal const val HYBRID_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "esri-imagery": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "Imagery © Esri, Maxar, Earthstar Geographics, and the GIS User Community"
    },
    "esri-reference": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    { "id": "esri-imagery", "type": "raster", "source": "esri-imagery" },
    { "id": "esri-reference", "type": "raster", "source": "esri-reference" }
  ]
}
"""

/** Apply the street (vector) or hybrid (satellite raster) style and fire
 *  [onLoaded] once it's ready. */
internal fun applyMapStyle(
    ml: MapLibreMap,
    hybrid: Boolean,
    isDark: Boolean,
    onLoaded: () -> Unit,
) {
    if (hybrid) {
        ml.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(HYBRID_STYLE_JSON)) { onLoaded() }
    } else {
        ml.setStyle(if (isDark) STYLE_POSITRON else STYLE_LIBERTY) { onLoaded() }
    }
}

/** Project a position [distanceM] along [bearingDeg] from (lat, lon),
 *  wrapped to MapLibre's LatLng. Maths lives in [GeoMath]. */
internal fun projectAlong(
    lat: Double, lon: Double, bearingDeg: Double, distanceM: Double,
): LatLng {
    val (pLat, pLon) = com.ehrocha.pulsar.transport.aircraft.GeoMath
        .projectMeters(lat, lon, bearingDeg, distanceM)
    return LatLng(pLat, pLon)
}

/** Six visible 20s segments separated by 10s gaps = a dashed-looking
 *  ~3-minute predicted track. Projects far enough that the line stays
 *  visible across the map even at zoomed-out levels. Skipped only if the
 *  body is essentially stationary; falls back to a heading derived from
 *  the past trail when the transponder didn't report `headingDeg`. */
internal fun futureTrailSegments(
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

// ── Feature: photographer's derived metrics ───────────────────────────────
// All three (elevation angle, lighting direction, closest-approach timing)
// are pure functions of data we already hold — no extra network. They turn
// the raw position/sun data into "where to point / when / will it be lit".

/** Current sun azimuth + altitude at the user location, or null on error.
 *  Reuses the same AstroCalculator path the map overlay uses. */
internal data class SunDir(val azimuthDeg: Double, val altitudeDeg: Double)

internal fun currentSunDir(userLat: Double, userLon: Double): SunDir? = runCatching {
    val now = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
    val date = now.toLocalDate()
    val utcHour = now.hour + now.minute / 60.0 + now.second / 3600.0
    val lst = com.ehrocha.pulsar.astro.AstroCalculator.lst(date, utcHour, userLon)
    val (ra, dec) = com.ehrocha.pulsar.astro.AstroCalculator.sunPosition(date)
    SunDir(
        azimuthDeg = com.ehrocha.pulsar.astro.AstroCalculator.azimuth(userLat, dec, lst - ra),
        altitudeDeg = com.ehrocha.pulsar.astro.AstroCalculator.altitude(userLat, dec, lst - ra),
    )
}.getOrNull()

/** Elevation angle (degrees above the horizon) to point the camera up at.
 *  `distanceKm` is great-circle GROUND distance; combined with altitude it
 *  gives the tilt. Null if the aircraft has no altitude reading. */
internal fun elevationDeg(s: AircraftSighting): Double? {
    val altM = s.altitudeFt?.let { it * 0.3048 } ?: return null
    val groundM = s.distanceKm * 1000.0
    if (groundM < 1.0) return 90.0
    return Math.toDegrees(kotlin.math.atan2(altM, groundM))
}

internal enum class LightingKind(val labelRes: Int) {
    GOLDEN(R.string.aircraft_lighting_golden),
    FRONT_LIT(R.string.aircraft_lighting_front),
    SIDE_LIT(R.string.aircraft_lighting_side),
    BACK_LIT(R.string.aircraft_lighting_back),
    NIGHT(R.string.aircraft_lighting_night),
}

/** Smallest unsigned angular difference between two bearings, 0..180. */
internal fun angularDiff(a: Double, b: Double): Double {
    val d = ((a - b + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return kotlin.math.abs(d)
}

/** Photographic lighting on the aircraft, given where the photographer is
 *  pointing (toward the aircraft, [aircraftBearingDeg]) and where the sun
 *  is. Front-lit = sun behind the photographer (best); back-lit = sun
 *  behind the subject (silhouette); golden = front-lit with a low sun. */
internal fun lightingFor(aircraftBearingDeg: Double, sun: SunDir?): LightingKind {
    if (sun == null || sun.altitudeDeg <= 0.0) return LightingKind.NIGHT
    val diff = angularDiff(sun.azimuthDeg, aircraftBearingDeg)
    val frontish = diff > 120.0
    return when {
        frontish && sun.altitudeDeg <= 8.0 -> LightingKind.GOLDEN
        frontish -> LightingKind.FRONT_LIT
        diff < 60.0 -> LightingKind.BACK_LIT
        else -> LightingKind.SIDE_LIT
    }
}

/** Time + distance of closest approach for a moving aircraft relative to
 *  the user. Closed-form closest-point-on-line in local ENU metres.
 *  Null when heading/speed are missing or the aircraft is ~stationary. */
internal data class ClosestApproach(
    val secondsUntil: Double,
    val minDistanceKm: Double,
    val receding: Boolean,
)

internal fun closestApproach(s: AircraftSighting, userLat: Double, userLon: Double): ClosestApproach? {
    val hdg = s.headingDeg ?: return null
    val gs = s.groundSpeedKt ?: return null
    if (gs <= 1.0) return null
    val speedMs = gs * 0.514444
    val latRad = Math.toRadians(userLat)
    // Aircraft position relative to user, local ENU metres.
    val eastM = (s.lon - userLon) * 111_111.0 * kotlin.math.cos(latRad)
    val northM = (s.lat - userLat) * 111_111.0
    val hdgRad = Math.toRadians(hdg)
    val vE = speedMs * kotlin.math.sin(hdgRad)
    val vN = speedMs * kotlin.math.cos(hdgRad)
    val vDotV = vE * vE + vN * vN
    if (vDotV < 1e-6) return null
    val tStar = -(eastM * vE + northM * vN) / vDotV   // seconds to closest
    val tClamped = tStar.coerceAtLeast(0.0)
    val eAt = eastM + vE * tClamped
    val nAt = northM + vN * tClamped
    val groundMin = kotlin.math.sqrt(eAt * eAt + nAt * nAt)
    val altM = (s.altitudeFt ?: 0.0) * 0.3048
    val slantMin = kotlin.math.sqrt(groundMin * groundMin + altM * altM)
    return ClosestApproach(
        secondsUntil = tClamped,
        minDistanceKm = slantMin / 1000.0,
        receding = tStar <= 0.0,
    )
}

/** True for the rare/notable aircraft worth a beep — military, emergency
 *  squawk, or vintage airframe. Deliberately excludes HEAVY: near an
 *  airport every widebody would trigger it, which would be noise. */
internal fun isAlertWorthy(s: AircraftSighting): Boolean =
    aircraftBadges(s).any {
        it == AircraftBadgeKind.MILITARY ||
            it == AircraftBadgeKind.EMERGENCY ||
            it == AircraftBadgeKind.VINTAGE
    }

/** Short beep + vibrate for a notable-aircraft alert. Best-effort; both
 *  the tone and vibration are independently wrapped so a missing
 *  vibrator (tablets) or audio-focus denial doesn't crash the other. */
internal fun playNotableAlert(ctx: android.content.Context) {
    runCatching {
        val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        vib.vibrate(
            android.os.VibrationEffect.createOneShot(
                250, android.os.VibrationEffect.DEFAULT_AMPLITUDE,
            ),
        )
    }
    runCatching {
        val tg = android.media.ToneGenerator(
            android.media.AudioManager.STREAM_NOTIFICATION, 90,
        )
        tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 300)
        // Release after the tone finishes so we don't leak the generator.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { tg.release() }
        }, 600)
    }
}

/** Approximate heading (degrees, 0=N) from the last two trail points.
 *  Used as a fallback when the OpenSky sighting's `headingDeg` is null
 *  (some transponders only report mode-S without an ADS-B track). */
internal fun derivedHeading(history: List<LatLng>): Double? {
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
internal data class SunMoonOnMap(val sun: LatLng?, val moon: LatLng?)

internal fun computeSunMoonOnMap(
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
internal fun projectCelestial(
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

// haversineKm moved to [com.ehrocha.pulsar.transport.aircraft.GeoMath].

/** Dead-reckon a sighting forward from its last polled position by
 *  [elapsedMs]. Uses heading + ground speed; returns the original position
 *  when either is unknown (we can't extrapolate without both). The
 *  flat-earth approximation is good enough for the ~10-second windows
 *  this is used for — accumulated error stays under ~50 m at typical
 *  cruise speeds. */
internal fun deadReckon(s: AircraftSighting, elapsedMs: Long): Pair<Double, Double> {
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
internal data class DeviceCompass(val azimuth: Int, val accuracy: Int)

/** [userLat] / [userLon] enable magnetic-to-true declination correction.
 *  The rotation-vector sensor reports azimuth relative to MAGNETIC north;
 *  map tiles + every astro/photo app render against TRUE north. Without
 *  the correction the heading cone points at the wrong city by exactly
 *  the local magnetic declination — up to ~20° in some parts of the
 *  world (Brazil, eastern Canada, southern Australia). */
@Composable
internal fun rememberDeviceCompass(userLat: Double?, userLon: Double?): DeviceCompass {
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
                if (bucket != azimuthBucket) azimuthBucket = bucket
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
internal fun rotatedAircraftBitmap(
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

