/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.aircraft

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Public photo lookup against planespotters.net. The `/pub/` endpoint
 * exposes a single airframe photo per ICAO 24-bit address with the
 * photographer credit and source page — no API key required, but the AUP
 * requires attribution (credit + tap-through link) on display, which the
 * Aircraft Watch detail dialog respects.
 *
 * Pulsar caches results forever per icao24 because airframes don't change
 * — the photo we get this run is fine to show next year as well.
 */
object PlanespottersClient {
    private const val TAG = "PlanespottersClient"
    private const val BASE = "https://api.planespotters.net/pub/photos/hex/"

    data class Photo(
        val thumbnailUrl: String,
        val sourceUrl: String,
        val photographer: String,
    )

    suspend fun photoByIcao(icaoHex: String): Photo? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(BASE + icaoHex.lowercase())
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Pulsar-Trigger/1 (open source)")
            }
            val code = conn.responseCode
            if (code !in 200..299) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(body)
            val photos = j.optJSONArray("photos") ?: return@runCatching null
            if (photos.length() == 0) return@runCatching null
            val first = photos.getJSONObject(0)
            // Prefer the 280-px "thumbnail_large" — readable in the dialog
            // without blowing through user bandwidth. Falls back to the
            // smaller thumbnail if the larger one isn't present.
            val thumb = first.optJSONObject("thumbnail_large")
                ?: first.optJSONObject("thumbnail")
                ?: return@runCatching null
            val src = thumb.optString("src").takeIf { it.isNotBlank() }
                ?: return@runCatching null
            Photo(
                thumbnailUrl = src,
                sourceUrl = first.optString("link").takeIf { it.isNotBlank() }
                    ?: "https://www.planespotters.net/",
                photographer = first.optString("photographer").trim()
                    .takeIf { it.isNotEmpty() } ?: "Planespotters.net",
            )
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w(TAG, "photo fetch failed for $icaoHex: ${it.message}")
        }.getOrNull()
    }
}
