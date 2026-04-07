/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ui.screens

import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.ui.components.BatteryIndicator
import com.ehrocha.pulsar.ui.components.NightModeToggle
import com.ehrocha.pulsar.ui.theme.LocalNightMode
import com.ehrocha.pulsar.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.Locale

private const val STYLE_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_POSITRON = "https://tiles.openfreemap.org/styles/positron"

@Composable
fun MapLocationPicker(
    onBack: () -> Unit,
    onConfirm: (name: String, lat: Double, lon: Double) -> Unit,
    initialLat: Double = 0.0,
    initialLon: Double = 0.0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = LocalNightMode.current.value != ThemeMode.Light

    var selectedLat by remember { mutableDoubleStateOf(initialLat) }
    var selectedLon by remember { mutableDoubleStateOf(initialLon) }
    var locationName by remember { mutableStateOf("") }
    var hasSelection by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val markerRef = remember { mutableStateOf<Marker?>(null) }

    // Forward lifecycle events to MapView
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
                MapLibre.getInstance(ctx)
                MapView(ctx).apply {
                    mapViewRef.value = this
                    onCreate(null)
                    getMapAsync { map ->
                        mapRef.value = map
                        map.setStyle(if (isDark) STYLE_POSITRON else STYLE_LIBERTY)
                        map.uiSettings.isAttributionEnabled = true
                        map.uiSettings.isLogoEnabled = false

                        val startZoom = if (initialLat != 0.0 || initialLon != 0.0) 10.0 else 2.0
                        map.cameraPosition = CameraPosition.Builder()
                            .target(LatLng(initialLat, initialLon))
                            .zoom(startZoom)
                            .build()

                        map.addOnMapClickListener { latLng ->
                            selectedLat = latLng.latitude
                            selectedLon = latLng.longitude
                            hasSelection = true

                            // Replace existing marker
                            markerRef.value?.let { map.removeMarker(it) }
                            markerRef.value = map.addMarker(
                                MarkerOptions()
                                    .position(latLng)
                                    .title(String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude)),
                            )

                            // Reverse geocode
                            resolving = true
                            locationName = ""
                            scope.launch {
                                val name = reverseGeocodeMap(ctx, latLng.latitude, latLng.longitude)
                                locationName = name ?: ""
                                resolving = false
                            }
                            true
                        }
                    }
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
