/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.planner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

data class GeocodingResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String,
    val admin1: String?,  // state / region
) {
    val displayName: String
        get() = listOfNotNull(name, admin1, country)
            .joinToString(", ")
}

suspend fun searchCities(query: String): List<GeocodingResult> =
    withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=$encoded&count=8&language=en&format=json"
            val json = JSONObject(URL(url).readText())
            val results = json.optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).map { i ->
                val obj = results.getJSONObject(i)
                GeocodingResult(
                    name = obj.getString("name"),
                    latitude = obj.getDouble("latitude"),
                    longitude = obj.getDouble("longitude"),
                    country = obj.optString("country", ""),
                    admin1 = obj.optString("admin1", "").takeIf { it.isNotEmpty() },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
