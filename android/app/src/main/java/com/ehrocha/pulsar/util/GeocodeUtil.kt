/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reverse-geocode (lat, lon) to a short "City, Region, CC" label, or null.
 *
 * One home for what used to be three identical call sites (map picker,
 * planner GPS button, astro dashboard). Uses the async `GeocodeListener`
 * overload on API 33+ — the synchronous one is deprecated there — and the
 * sync call on older devices (minSdk 26). Always runs off the main thread
 * and never throws.
 */
suspend fun reverseGeocodeName(context: Context, lat: Double, lon: Double): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            val address: Address? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                }
            address?.let { addr ->
                listOfNotNull(addr.locality, addr.adminArea, addr.countryCode)
                    .joinToString(", ")
                    .takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
