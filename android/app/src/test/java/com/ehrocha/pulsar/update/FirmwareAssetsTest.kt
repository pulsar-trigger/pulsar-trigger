/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import com.ehrocha.pulsar.ble.BoardKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Release discovery + per-board image choice (audit U-H1, U-H2). */
class FirmwareAssetsTest {

    private fun release(tag: String, vararg assets: String, draft: Boolean = false) = JSONObject().apply {
        put("tag_name", tag)
        put("published_at", "2026-11-11T00:00:00Z")
        put("body", "notes $tag")
        put("draft", draft)
        put("assets", JSONArray().apply {
            assets.forEach { put(JSONObject().put("name", it).put("browser_download_url", "https://x/$it")) }
        })
    }

    private fun page(vararg rels: JSONObject) = JSONArray().apply { rels.forEach { put(it) } }.toString()

    private val fw = "firmware-v0.33.0"
    private val fullRelease = ReleaseInfo(
        tagName = fw, publishedAt = "", body = "",
        assets = listOf(
            "pulsar-firmware-v0.33.0-esp32.bin" to "u1",
            "pulsar-firmware-v0.33.0-esp32.bin.sha256" to "u1s",
            "pulsar-firmware-v0.33.0-esp32s3.bin" to "u2",
            "pulsar-firmware-v0.33.0-esp32s3.bin.sha256" to "u2s",
            "pulsar-firmware-v0.33.0-m5core2.bin" to "u3",
            "pulsar-firmware-v0.33.0-m5core2.bin.sha256" to "u3s",
        ),
    )

    // ── Board → asset ───────────────────────────────────────────────────

    @Test fun boardSuffixes() {
        assertEquals("-esp32.bin", firmwareAssetSuffix(BoardKind.GENERIC_ESP32))
        assertEquals("-esp32s3.bin", firmwareAssetSuffix(BoardKind.M5STICK_S3))
        assertEquals("-m5core2.bin", firmwareAssetSuffix(BoardKind.M5CORE2))
        assertNull(firmwareAssetSuffix(BoardKind.UNKNOWN))
    }

    @Test fun eachBoardGetsItsOwnImageAndChecksum() {
        val expect = mapOf(
            BoardKind.GENERIC_ESP32 to ("u1" to "u1s"),
            BoardKind.M5STICK_S3 to ("u2" to "u2s"),
            BoardKind.M5CORE2 to ("u3" to "u3s"),
        )
        for ((board, urls) in expect) {
            val pick = pickFirmwareAsset(fullRelease, "firmware-v", board) as FirmwareAssetPick.Found
            assertEquals(urls.first, pick.asset.downloadUrl)
            assertEquals(urls.second, pick.asset.checksumUrl)
            assertEquals("0.33.0", pick.asset.version)
        }
    }

    @Test fun unknownBoardIsRefused() {
        assertEquals(FirmwareAssetPick.UnknownBoard,
            pickFirmwareAsset(fullRelease, "firmware-v", BoardKind.UNKNOWN))
    }

    /** An M5Core2 must never get the generic ESP32 image, even though both
     *  are the same chip. */
    @Test fun missingBoardAssetIsRefusedNotSubstituted() {
        val old = ReleaseInfo(fw, "", "", listOf(
            "pulsar-firmware-v0.33.0-esp32.bin" to "u1",
            "pulsar-firmware-v0.33.0-esp32s3.bin" to "u2",
        ))
        assertEquals(FirmwareAssetPick.NoAssetForBoard("0.33.0"),
            pickFirmwareAsset(old, "firmware-v", BoardKind.M5CORE2))
    }

    @Test fun noReleaseIsReported() {
        assertEquals(FirmwareAssetPick.NoRelease,
            pickFirmwareAsset(null, "firmware-v", BoardKind.GENERIC_ESP32))
    }

    @Test fun checksumAbsentLeavesUrlNull() {
        val r = ReleaseInfo(fw, "", "", listOf("pulsar-firmware-v0.33.0-esp32.bin" to "u1"))
        val pick = pickFirmwareAsset(r, "firmware-v", BoardKind.GENERIC_ESP32) as FirmwareAssetPick.Found
        assertNull(pick.asset.checksumUrl)
    }

    @Test fun expectedChipsPerBoard() {
        assertEquals(1, expectedChipId(BoardKind.GENERIC_ESP32))
        assertEquals(3, expectedChipId(BoardKind.M5STICK_S3))
        assertEquals(1, expectedChipId(BoardKind.M5CORE2))
        assertNull(expectedChipId(BoardKind.UNKNOWN))
    }

    // ── Paging ──────────────────────────────────────────────────────────

    /** The U-H1 bug: the newest firmware release sat behind hundreds of
     *  app releases, so a 5-release window never saw it. */
    @Test fun pagesUntilAFirmwareReleaseAppears() {
        val requested = mutableListOf<Int>()
        val pages = mapOf(
            1 to page(*Array(100) { release("app-v0.${700 - it}.0", "a.apk") }),
            2 to page(*Array(100) { release("app-v0.${600 - it}.0", "a.apk") }),
            3 to page(
                release("app-v0.400.0", "a.apk"),
                release("firmware-v0.32.0", "pulsar-firmware-v0.32.0-esp32.bin"),
                release("firmware-v0.33.0", "pulsar-firmware-v0.33.0-esp32.bin"),
            ),
            4 to page(release("firmware-v9.9.9")),
        )
        val r = fetchNewestRelease("firmware-v") { requested += it; pages.getValue(it) }
        assertEquals("firmware-v0.33.0", r?.tagName)
        assertEquals(listOf(1, 2, 3), requested)
    }

    @Test fun stopsAtTheEndOfTheList() {
        val requested = mutableListOf<Int>()
        val r = fetchNewestRelease("firmware-v") {
            requested += it
            if (it == 1) page(release("app-v1.0.0")) else "[]"
        }
        assertNull(r)
        assertEquals(listOf(1, 2), requested)
    }

    @Test fun stopsAtThePageCap() {
        var calls = 0
        val r = fetchNewestRelease("firmware-v", maxPages = 3) { calls++; page(release("app-v1.0.0")) }
        assertNull(r)
        assertEquals(3, calls)
    }

    @Test fun draftsAreIgnored() {
        val rels = parseReleasesPage(page(
            release("firmware-v0.34.0", draft = true),
            release("firmware-v0.33.0"),
        ))
        assertEquals("firmware-v0.33.0", newestWithPrefix(rels, "firmware-v")?.tagName)
    }

    @Test fun newestBySemverNotByStringOrder() {
        val rels = parseReleasesPage(page(release("firmware-v0.9.0"), release("firmware-v0.10.0")))
        assertEquals("firmware-v0.10.0", newestWithPrefix(rels, "firmware-v")?.tagName)
    }

    // ── Checksum file ───────────────────────────────────────────────────

    @Test fun parsesSha256sumFormat() {
        val hex = "a".repeat(64)
        assertEquals(hex, parseSha256("$hex  pulsar-firmware-v0.33.0-esp32.bin\n"))
        assertEquals(hex, parseSha256(hex.uppercase()))
    }

    @Test fun rejectsAnythingButSixtyFourHexDigits() {
        assertNull(parseSha256(""))
        assertNull(parseSha256("abc123  file.bin"))
        assertNull(parseSha256("z".repeat(64)))
        assertNull(parseSha256("<html>Not Found</html>"))
    }

    @Test fun sha256OfKnownInput() {
        assertTrue(sha256Hex("abc".toByteArray()) ==
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}
