/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.model

import org.json.JSONObject

/**
 * One entry in the network preset/flow catalog (`catalog/index.json`, schema
 * `pulsar-catalog/1`). Metadata only — the actual preset/flow JSON lives in a
 * separate file ([file]) fetched on install (apt-style: index = the package
 * list, [file] = the package).
 */
data class CatalogEntry(
    val id: String,
    /** "mode" (a [UserMode] preset) or "flow" (a [SavedFlow]). */
    val kind: String,
    /** [com.ehrocha.pulsar.ble.TriggerMode] name, for grouping/filtering. */
    val mode: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    /** Bumped by the catalog author to signal an update to an installed entry. */
    val version: Int,
    /** Path of the entry's JSON, relative to the catalog base URL. */
    val file: String,
) {
    val isMode: Boolean get() = kind == "mode"
    val isFlow: Boolean get() = kind == "flow"

    companion object {
        const val SCHEMA_ID = "pulsar-catalog/1"

        /** Parse `index.json`. Returns empty on an unknown schema (forward-safe:
         *  a future major bump won't be misread as v1). */
        fun parseIndex(json: String): List<CatalogEntry> {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
            if (obj.optString("schema") != SCHEMA_ID) return emptyList()
            val arr = obj.optJSONArray("entries") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = e.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val kind = e.optString("kind").takeIf { it == "mode" || it == "flow" }
                    ?: return@mapNotNull null
                CatalogEntry(
                    id = id,
                    kind = kind,
                    mode = e.optString("mode"),
                    name = e.optString("name", id),
                    description = e.optString("description", ""),
                    tags = e.optJSONArray("tags")?.let { t ->
                        (0 until t.length()).map { t.getString(it) }
                    } ?: emptyList(),
                    version = e.optInt("version", 1),
                    file = e.optString("file"),
                )
            }
        }
    }
}
