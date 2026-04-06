/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.location.Geocoder
import android.view.MotionEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.BatteryIndicator
import com.ehrocha.pulsar.ui.components.NightModeToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.util.Locale

@Composable
fun MapLocationPicker(
    onBack: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double) -> Unit,
    initialLat: Double = 0.0,
    initialLon: Double = 0.0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedLat by remember { mutableDoubleStateOf(initialLat) }
    var selectedLon by remember { mutableDoubleStateOf(initialLon) }
    var locationName by remember { mutableStateOf("") }
    var hasSelection by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val marker = remember { mutableStateOf<Marker?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top bar ──────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.planner_pick_on_map),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (hasSelection) {
                IconButton(onClick = {
                    val name = locationName.ifBlank {
                        String.format(Locale.US, "%.4f, %.4f", selectedLat, selectedLon)
                    }
                    onConfirm(name, selectedLat, selectedLon)
                }) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                }
            }
            BatteryIndicator()
            NightModeToggle()
        }

        // ── Selection info ───────────────────────────────────────────
        if (hasSelection) {
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (resolving) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.planner_resolving_location),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else if (locationName.isNotBlank()) {
                        Text(
                            locationName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", selectedLat, selectedLon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.planner_tap_to_select),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ── Map ──────────────────────────────────────────────────────
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(4.0)
                    if (initialLat != 0.0 || initialLon != 0.0) {
                        controller.setCenter(GeoPoint(initialLat, initialLon))
                        controller.setZoom(10.0)
                    }

                    // Tap listener
                    overlays.add(object : Overlay() {
                        override fun onSingleTapConfirmed(e: MotionEvent?, mapView: MapView?): Boolean {
                            if (e == null || mapView == null) return false
                            val proj = mapView.projection
                            val geoPoint = proj.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                            selectedLat = geoPoint.latitude
                            selectedLon = geoPoint.longitude
                            hasSelection = true

                            // Update or create marker
                            val m = marker.value ?: Marker(mapView).also {
                                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                mapView.overlays.add(it)
                                marker.value = it
                            }
                            m.position = geoPoint
                            m.title = String.format(Locale.US, "%.5f, %.5f", geoPoint.latitude, geoPoint.longitude)
                            mapView.invalidate()

                            // Reverse geocode
                            resolving = true
                            locationName = ""
                            scope.launch {
                                val name = reverseGeocodeMap(ctx, geoPoint.latitude, geoPoint.longitude)
                                locationName = name ?: ""
                                resolving = false
                            }
                            return true
                        }
                    })
                }
            },
        )
    }
}

@Suppress("DEPRECATION")
private suspend fun reverseGeocodeMap(ctx: android.content.Context, lat: Double, lon: Double): String? =
    withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            addresses?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.locality, addr.adminArea, addr.countryCode)
                    .joinToString(", ")
                    .takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) { null }
    }
