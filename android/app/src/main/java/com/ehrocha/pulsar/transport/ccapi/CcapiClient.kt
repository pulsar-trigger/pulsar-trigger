/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin HTTP client for Canon CCAPI. Wraps `HttpURLConnection` to keep the
 * dependency footprint small — no OkHttp until/unless we need digest auth
 * (Phase 4 polish).
 *
 * Versioning: at connect time we call `GET /ccapi` and pick the highest
 * supported version. The pinned [version] is then prefixed onto every
 * subsequent request, so callers say `post("/shooting/control/shutterbutton")`
 * and the client emits `…/ccapi/ver110/shooting/control/shutterbutton`.
 *
 * Unauthenticated only for now. If the camera returns 401 we surface
 * [Result.NeedsAuth] and the wizard prompts the user; digest support
 * lands in Phase 4.
 */
class CcapiClient(
    /** From `CameraDescription` — e.g. `http://192.168.1.2:8080/ccapi/` */
    private val baseAccessUrl: String,
) {
    companion object {
        private const val TAG = "CcapiClient"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        /** Versions in descending preference. */
        private val PREFERRED_VERSIONS = listOf(
            "ver140", "ver130", "ver120", "ver110", "ver100",
        )
    }

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Http(val code: Int, val body: String) : Result<Nothing>
        data object NeedsAuth : Result<Nothing>
        data class Network(val cause: Throwable) : Result<Nothing>
    }

    /** Pinned API version, set after [connect]. */
    var version: String? = null
        private set

    /** Cached `/ccapi` endpoint list once we've connected. */
    var endpoints: Map<String, JSONArray> = emptyMap()
        private set

    private val rootUrl: String = baseAccessUrl.trimEnd('/')

    /**
     * Probe `GET /ccapi`, pin the highest supported version, and cache the
     * endpoint matrix. Must succeed before any other call.
     */
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        when (val r = rawGet(rootUrl)) {
            is Result.Ok -> {
                val obj = try { JSONObject(r.value) }
                          catch (e: Exception) {
                              return@withContext Result.Network(e)
                          }
                val available = mutableMapOf<String, JSONArray>()
                obj.keys().forEach { key ->
                    obj.optJSONArray(key)?.let { available[key] = it }
                }
                val pinned = PREFERRED_VERSIONS.firstOrNull { it in available.keys }
                if (pinned == null) {
                    Log.w(TAG, "No supported version in /ccapi response: ${available.keys}")
                    return@withContext Result.Network(IllegalStateException("no supported CCAPI version"))
                }
                version = pinned
                endpoints = available
                Log.i(TAG, "Connected to CCAPI, pinned $pinned (${available[pinned]?.length()} endpoints)")
                Result.Ok(Unit)
            }
            is Result.Http -> r
            is Result.NeedsAuth -> r
            is Result.Network -> r
        }
    }

    /** True if the given path (relative to `/ccapi/<ver>`) appears in the
     *  cached endpoint matrix with the requested method. */
    fun supports(path: String, method: String = "post"): Boolean {
        val arr = endpoints[version ?: return false] ?: return false
        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            if (ep.optString("path") == "/ccapi/$version$path" &&
                ep.optString("method").equals(method, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /** POST a JSON body to a versioned path; relative path begins with `/`. */
    suspend fun post(path: String, body: JSONObject? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val ver = version ?: return@withContext Result.Network(
                IllegalStateException("CcapiClient not connected; call connect() first"))
            rawJsonRequest("POST", "$rootUrl/$ver$path", body)
        }

    suspend fun put(path: String, body: JSONObject? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val ver = version ?: return@withContext Result.Network(
                IllegalStateException("CcapiClient not connected; call connect() first"))
            rawJsonRequest("PUT", "$rootUrl/$ver$path", body)
        }

    suspend fun get(path: String): Result<String> =
        withContext(Dispatchers.IO) {
            val ver = version ?: return@withContext Result.Network(
                IllegalStateException("CcapiClient not connected; call connect() first"))
            rawGet("$rootUrl/$ver$path")
        }

    private fun rawGet(url: String): Result<String> = openConnection(url, "GET", null)

    private fun rawJsonRequest(method: String, url: String, body: JSONObject?): Result<String> =
        openConnection(url, method, body?.toString())

    private fun openConnection(url: String, method: String, jsonBody: String?): Result<String> {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = method
                doInput = true
                if (jsonBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        } catch (e: Exception) {
            return Result.Network(e)
        }
        return try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                code in 200..299 -> Result.Ok(text)
                code == 401 -> Result.NeedsAuth
                else -> {
                    Log.w(TAG, "HTTP $code from $url: $text")
                    Result.Http(code, text)
                }
            }
        } catch (e: Exception) {
            Result.Network(e)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
