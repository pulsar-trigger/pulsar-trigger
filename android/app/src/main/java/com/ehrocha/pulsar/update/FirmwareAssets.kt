/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import com.ehrocha.pulsar.ble.BoardKind
import org.json.JSONArray

/**
 * Firmware release discovery, kept free of HTTP so it can be unit-tested
 * (`FirmwareAssetsTest`). The network side is [fetchNewestRelease]'s
 * `fetchPage` argument.
 */

/** One GitHub release, reduced to what the update paths read. */
data class ReleaseInfo(
    val tagName: String,
    val publishedAt: String,
    val body: String,
    /** asset name → browser download URL, in API order. */
    val assets: List<Pair<String, String>>,
)

/** Parse one page of `GET /repos/…/releases`. Drafts are skipped. */
fun parseReleasesPage(json: String): List<ReleaseInfo> {
    val arr = JSONArray(json)
    val out = ArrayList<ReleaseInfo>(arr.length())
    for (i in 0 until arr.length()) {
        val rel = arr.getJSONObject(i)
        if (rel.optBoolean("draft", false)) continue
        val assets = rel.optJSONArray("assets")
        val list = ArrayList<Pair<String, String>>()
        if (assets != null) {
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                list += a.getString("name") to a.getString("browser_download_url")
            }
        }
        out += ReleaseInfo(
            tagName = rel.getString("tag_name"),
            publishedAt = rel.optString("published_at", ""),
            body = rel.optString("body", ""),
            assets = list,
        )
    }
    return out
}

/** Highest-versioned release on [releases] whose tag starts with [tagPrefix]. */
fun newestWithPrefix(releases: List<ReleaseInfo>, tagPrefix: String): ReleaseInfo? {
    var best: ReleaseInfo? = null
    for (r in releases) {
        if (!r.tagName.startsWith(tagPrefix)) continue
        val b = best
        if (b == null || isNewerVersion(r.tagName.removePrefix(tagPrefix), b.tagName.removePrefix(tagPrefix))) {
            best = r
        }
    }
    return best
}

/** GitHub's page-size ceiling for the releases list. */
const val RELEASES_PER_PAGE = 100

/** How far back to look: 10 × 100 releases. The repo publishes an app
 *  release per build, so firmware releases sit hundreds deep (audit U-H1:
 *  the newest one was at position 408). */
const val RELEASES_MAX_PAGES = 10

/**
 * Walk the release list page by page (newest first, [RELEASES_PER_PAGE] per
 * page) until a page holds a release tagged [tagPrefix]; return the newest
 * such release on that page. Stops early at an empty page (end of list).
 * [fetchPage] takes a 1-based page number and returns the page's JSON.
 */
fun fetchNewestRelease(
    tagPrefix: String,
    maxPages: Int = RELEASES_MAX_PAGES,
    fetchPage: (page: Int) -> String,
): ReleaseInfo? {
    for (page in 1..maxPages) {
        val releases = parseReleasesPage(fetchPage(page))
        if (releases.isEmpty()) return null
        newestWithPrefix(releases, tagPrefix)?.let { return it }
    }
    return null
}

/**
 * Release asset suffix per BOARD (not per chip: the generic ESP32 and the
 * M5Core2 share a chip but not pins, display or power hardware). Null =
 * unknown board, which matches nothing. Names are kept compatible with the
 * releases already published (`-esp32.bin`, `-esp32s3.bin`).
 */
fun firmwareAssetSuffix(board: BoardKind): String? = when (board) {
    BoardKind.GENERIC_ESP32 -> "-esp32.bin"
    BoardKind.M5STICK_S3 -> "-esp32s3.bin"
    BoardKind.M5CORE2 -> "-m5core2.bin"
    BoardKind.UNKNOWN -> null
}

/** Chip id the board reports in OTA_READY (1 = ESP32, 3 = ESP32-S3). */
fun expectedChipId(board: BoardKind): Int? = when (board) {
    BoardKind.GENERIC_ESP32, BoardKind.M5CORE2 -> 1
    BoardKind.M5STICK_S3 -> 3
    BoardKind.UNKNOWN -> null
}

/** Outcome of picking a firmware image for one board from one release. */
sealed class FirmwareAssetPick {
    data class Found(val asset: GitHubAsset) : FirmwareAssetPick()
    /** No `firmware-v*` release could be found at all. */
    data object NoRelease : FirmwareAssetPick()
    /** The connected board didn't identify itself — no image is safe. */
    data object UnknownBoard : FirmwareAssetPick()
    /** The newest release has no image for this board. Never substitute
     *  another board's image. */
    data class NoAssetForBoard(val version: String) : FirmwareAssetPick()
}

/** Pick [board]'s image (and its `<asset>.sha256`, if published) from
 *  [release]. Only an exact suffix match counts. */
fun pickFirmwareAsset(release: ReleaseInfo?, tagPrefix: String, board: BoardKind): FirmwareAssetPick {
    val suffix = firmwareAssetSuffix(board) ?: return FirmwareAssetPick.UnknownBoard
    if (release == null) return FirmwareAssetPick.NoRelease
    val version = release.tagName.removePrefix(tagPrefix)
    val (name, url) = release.assets.firstOrNull { it.first.endsWith(suffix) }
        ?: return FirmwareAssetPick.NoAssetForBoard(version)
    val checksumUrl = release.assets.firstOrNull { it.first == "$name.sha256" }?.second
    return FirmwareAssetPick.Found(
        GitHubAsset(
            version = version,
            downloadUrl = url,
            checksumUrl = checksumUrl,
            publishedAt = release.publishedAt,
            body = release.body,
        ),
    )
}

/** First field of a `sha256sum` line (`<64 hex>  <filename>`), lower-cased,
 *  or null unless it is exactly 64 hex digits. */
fun parseSha256(text: String): String? =
    text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
