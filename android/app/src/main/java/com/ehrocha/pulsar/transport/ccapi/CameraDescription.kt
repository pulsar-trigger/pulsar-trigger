/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fetches and parses the UPnP `CameraDevDesc.xml` advertised by a Canon
 * CCAPI camera at the [Location](https://datatracker.ietf.org/doc/html/draft-cai-ssdp)
 * header URL of its SSDP advertisement. Returns null on any I/O or parse
 * error — callers should treat a null as "not a Canon CCAPI camera".
 *
 * Tags we care about (per `docs/ccapi.md`):
 *  - `<friendlyName>` — model name
 *  - `<UDN>` — UUID (primary key)
 *  - `<X_accessURL>` — CCAPI base URL (Canon-specific extension)
 *  - `<X_deviceNickname>` — optional user nickname
 *  - URL itself supplies IP + port.
 */
object CameraDescription {
    private const val TAG = "CcapiDesc"
    private const val FETCH_TIMEOUT_MS = 3_000

    fun fetch(locationUrl: String): CanonCamera? = runCatching {
        val url = URL(locationUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode != 200) return null
            val xml = conn.inputStream.bufferedReader().use { it.readText() }
            parse(xml, url)
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "Failed to fetch $locationUrl", it) }.getOrNull()

    private fun parse(xml: String, sourceUrl: URL): CanonCamera? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false  // Canon's XML uses an `ns:` prefix on extensions
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        fun firstTag(name: String): String? = doc.getElementsByTagName(name)
            .takeIf { it.length > 0 }?.item(0)?.textContent?.trim()
            ?.takeIf { it.isNotEmpty() }

        val udn = firstTag("UDN") ?: return null
        val friendly = firstTag("friendlyName") ?: firstTag("modelName") ?: "Canon Camera"
        val manufacturer = firstTag("manufacturer")
        if (manufacturer != null && !manufacturer.equals("canon", ignoreCase = true)) {
            // Not a Canon device — could be any UPnP device on the network.
            return null
        }
        // Canon extensions are namespace-prefixed; the simple lookup catches them
        // because we disabled namespace awareness.
        val accessUrl = firstTag("ns:X_accessURL") ?: firstTag("X_accessURL")
            ?: return null
        val nickname = firstTag("ns:X_deviceNickname") ?: firstTag("X_deviceNickname")

        return CanonCamera(
            udn = udn.removePrefix("uuid:"),
            friendlyName = friendly,
            nickname = nickname,
            ipAddress = sourceUrl.host,
            port = sourceUrl.port.takeIf { it > 0 } ?: 80,
            accessUrl = accessUrl,
        )
    }
}
