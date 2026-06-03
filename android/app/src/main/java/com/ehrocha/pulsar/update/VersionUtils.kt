/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import com.ehrocha.pulsar.AppConfig
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Compare semver strings (e.g. "0.2.0" > "0.1.0"). */
fun isNewerVersion(remote: String, local: String): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val l = local.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, l.size)) {
        val rv = r.getOrElse(i) { 0 }
        val lv = l.getOrElse(i) { 0 }
        if (rv > lv) return true
        if (rv < lv) return false
    }
    return false
}

data class GitHubAsset(
    val version: String,
    val downloadUrl: String,
    val checksumUrl: String?,
    val publishedAt: String,
    val body: String,
)

/**
 * Fetch the latest GitHub Release matching [tagPrefix] that contains an asset
 * whose name ends with [assetSuffix].
 */
fun fetchGitHubRelease(
    tagPrefix: String,
    assetSuffix: String,
    perPage: Int = 10,
): GitHubAsset? {
    val url = URL("${AppConfig.GITHUB_RELEASES_URL}?per_page=$perPage")
    val conn = url.openConnection() as HttpURLConnection
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
    conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
    try {
        if (conn.responseCode != 200) {
            throw Exception("GitHub API returned ${conn.responseCode}")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(body)
        var best: GitHubAsset? = null
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            val tagName = rel.getString("tag_name")
            if (!tagName.startsWith(tagPrefix)) continue

            val version = tagName.removePrefix(tagPrefix)
            val assets = rel.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val assetName = asset.getString("name")
                if (assetName.endsWith(assetSuffix)) {
                    // Look for companion .sha256 checksum file
                    var checksumUrl: String? = null
                    for (k in 0 until assets.length()) {
                        val csAsset = assets.getJSONObject(k)
                        if (csAsset.getString("name") == "$assetName.sha256") {
                            checksumUrl = csAsset.getString("browser_download_url")
                            break
                        }
                    }
                    val candidate = GitHubAsset(
                        version = version,
                        downloadUrl = asset.getString("browser_download_url"),
                        checksumUrl = checksumUrl,
                        publishedAt = rel.getString("published_at"),
                        body = rel.optString("body", ""),
                    )
                    if (best == null || isNewerVersion(version, best.version)) {
                        best = candidate
                    }
                    break
                }
            }
        }
        return best
    } finally {
        conn.disconnect()
    }
}

/**
 * Fetch up to [count] most-recent GitHub releases matching [tagPrefix] that
 * contain an asset whose name ends with [assetSuffix]. Returned newest-first
 * (by parsed semver). Used by the Settings → Updates "pick previous version"
 * picker for rollbacks.
 */
fun fetchGitHubReleases(
    tagPrefix: String,
    assetSuffix: String,
    count: Int = 10,
): List<GitHubAsset> {
    val url = URL("${AppConfig.GITHUB_RELEASES_URL}?per_page=${count.coerceAtLeast(1) * 3}")
    val conn = url.openConnection() as HttpURLConnection
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
    conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
    try {
        if (conn.responseCode != 200) throw Exception("GitHub API returned ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(body)
        val out = mutableListOf<GitHubAsset>()
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            val tagName = rel.getString("tag_name")
            if (!tagName.startsWith(tagPrefix)) continue
            val version = tagName.removePrefix(tagPrefix)
            val assets = rel.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val assetName = asset.getString("name")
                if (assetName.endsWith(assetSuffix)) {
                    var checksumUrl: String? = null
                    for (k in 0 until assets.length()) {
                        val cs = assets.getJSONObject(k)
                        if (cs.getString("name") == "$assetName.sha256") {
                            checksumUrl = cs.getString("browser_download_url")
                            break
                        }
                    }
                    out += GitHubAsset(
                        version = version,
                        downloadUrl = asset.getString("browser_download_url"),
                        checksumUrl = checksumUrl,
                        publishedAt = rel.getString("published_at"),
                        body = rel.optString("body", ""),
                    )
                    break
                }
            }
        }
        // Newest first by semver, capped at [count].
        return out.sortedWith(
            Comparator { a, b -> versionPartComparator.compare(b.version.toVersionParts(), a.version.toVersionParts()) }
        ).take(count)
    } finally {
        conn.disconnect()
    }
}

private fun String.toVersionParts(): List<Int> =
    split(".").map { it.toIntOrNull() ?: 0 }

private val versionPartComparator: Comparator<List<Int>> = Comparator { a, b ->
    for (i in 0 until maxOf(a.size, b.size)) {
        val cmp = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (cmp != 0) return@Comparator cmp
    }
    0
}

/** Compute SHA-256 hex digest of a file. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Compute SHA-256 hex digest of a byte array. */
fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data).joinToString("") { "%02x".format(it) }
}

/**
 * Fetch the expected SHA-256 hash from a `.sha256` URL.
 * Returns null if the checksum file is unavailable.
 */
fun fetchExpectedChecksum(checksumUrl: String): String? {
    return try {
        val conn = URL(checksumUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
        conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
        try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
                .trim()
                .split("\\s+".toRegex())
                .firstOrNull()
                ?.lowercase()
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
