/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.catalog

import android.content.Context
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.model.CatalogEntry
import com.ehrocha.pulsar.model.SavedFlow
import com.ehrocha.pulsar.model.UserMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Network catalog of presets/flows, apt-style: [refresh] pulls the index (the
 * "package list") into a local cache; [fetchEntry] downloads one entry's JSON
 * (the "package"), **sanitizes** it, and hands it back for the caller to store.
 * Installed / update state is DERIVED from the stores (does a UserMode/SavedFlow
 * with this catalog id exist, and at what version) — no separate registry.
 *
 * Content is fetched read-only over HTTPS from [AppConfig.CATALOG_BASE_URL]
 * (a public repo). Nothing is bundled — A = network-only.
 */
class CatalogManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Result of [fetchEntry] — a sanitized, ready-to-store preset/flow, or an error. */
    sealed interface Payload {
        data class Mode(val mode: UserMode) : Payload
        data class Flow(val flow: SavedFlow) : Payload
        data class Error(val message: String) : Payload
    }

    data class State(
        val entries: List<CatalogEntry> = emptyList(),
        val lastUpdatedMs: Long? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(
        State(
            entries = CatalogEntry.parseIndex(prefs.getString(KEY_INDEX, "") ?: ""),
            lastUpdatedMs = prefs.getLong(KEY_UPDATED, 0L).takeIf { it > 0L },
        ),
    )
    val state: StateFlow<State> = _state

    /** apt update — fetch + cache the index. */
    suspend fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        runCatching { withContext(Dispatchers.IO) { httpGet("${AppConfig.CATALOG_BASE_URL}/index.json") } }
            .onSuccess { json ->
                val entries = CatalogEntry.parseIndex(json)
                val now = System.currentTimeMillis()
                prefs.edit().putString(KEY_INDEX, json).putLong(KEY_UPDATED, now).apply()
                _state.update { it.copy(entries = entries, lastUpdatedMs = now, loading = false, error = null) }
            }
            .onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Fetch failed") }
            }
    }

    /** apt install (fetch half) — download + parse + sanitize one entry. The
     *  caller stores the result; installed state is derived from the stores. */
    suspend fun fetchEntry(entry: CatalogEntry): Payload = withContext(Dispatchers.IO) {
        runCatching {
            // entry.file comes from the index JSON — keep it a simple relative
            // path so it can't escape the catalog dir or switch host/scheme.
            require(
                entry.file.isNotBlank() &&
                    !entry.file.contains("..") &&
                    entry.file.matches(Regex("[\\w./-]+")),
            ) { "Invalid catalog file path: ${entry.file}" }
            val json = httpGet("${AppConfig.CATALOG_BASE_URL}/${entry.file}")
            val obj = JSONObject(json)
            when {
                entry.isMode -> UserMode.fromJson(obj)?.sanitized()
                    ?.let { Payload.Mode(it) } ?: Payload.Error("Unsupported or malformed preset")
                entry.isFlow -> Payload.Flow(SavedFlow.fromJson(obj).sanitized())
                else -> Payload.Error("Unknown entry kind")
            }
        }.getOrElse { Payload.Error(it.message ?: "Download failed") }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
            readTimeout = AppConfig.API_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val PREFS = "pulsar_catalog"
        private const val KEY_INDEX = "index_json"
        private const val KEY_UPDATED = "updated_ms"
    }
}

/** Outcome of installing a catalog entry into local storage. */
sealed interface CatalogInstallResult {
    data object Ok : CatalogInstallResult
    data object LimitReached : CatalogInstallResult
    data class Error(val message: String) : CatalogInstallResult
}
