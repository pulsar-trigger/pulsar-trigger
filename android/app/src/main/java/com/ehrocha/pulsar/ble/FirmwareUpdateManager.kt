/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

data class FirmwareRelease(
    val version: String,
    val downloadUrl: String,
    val publishedAt: String,
    val body: String,
)

enum class OtaState {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    UPLOADING,
    VALIDATING,
    COMPLETE,
    ERROR,
}

class FirmwareUpdateManager(
    private val bleManager: PulsarBleManager,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "FirmwareOTA"
        private const val GITHUB_REPO = "ehrocha/pulsar-trigger"
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases"
        private const val CHUNK_DELAY_MS = 10L  // throttle between BLE writes
    }

    private val _state = MutableStateFlow(OtaState.IDLE)
    val state: StateFlow<OtaState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _latestRelease = MutableStateFlow<FirmwareRelease?>(null)
    val latestRelease: StateFlow<FirmwareRelease?> = _latestRelease

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var firmwareBytes: ByteArray? = null
    private var otaJob: Job? = null

    fun checkForUpdate(currentVersion: String) {
        scope.launch(Dispatchers.IO) {
            _state.value = OtaState.CHECKING
            _errorMessage.value = null
            try {
                val release = fetchLatestRelease()
                _latestRelease.value = release
                if (release != null && release.version != currentVersion) {
                    _state.value = OtaState.AVAILABLE
                } else {
                    _state.value = OtaState.IDLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check failed", e)
                _errorMessage.value = e.message
                _state.value = OtaState.ERROR
            }
        }
    }

    fun startUpdate() {
        val release = _latestRelease.value ?: return
        if (!bleManager.hasOtaSupport()) {
            _errorMessage.value = "Device firmware does not support OTA"
            _state.value = OtaState.ERROR
            return
        }

        otaJob = scope.launch(Dispatchers.IO) {
            try {
                // Phase 1: Download firmware from GitHub
                _state.value = OtaState.DOWNLOADING
                _progress.value = 0f
                firmwareBytes = downloadFirmware(release.downloadUrl)
                val fw = firmwareBytes ?: throw Exception("Download returned empty")
                Log.i(TAG, "Downloaded ${fw.size} bytes")

                // Phase 2: Send OTA_BEGIN
                _state.value = OtaState.UPLOADING
                _progress.value = 0f
                bleManager.writeOtaControl(CommandBuilder.otaBegin(fw.size))

                // Wait for OTA_READY from firmware
                val readyStatus = withTimeout(5000) {
                    bleManager.otaStatus.first { it == OtaStatus.READY || it == OtaStatus.ERR_BEGIN }
                }
                if (readyStatus != OtaStatus.READY) {
                    throw Exception("Firmware rejected OTA begin")
                }

                // Phase 3: Stream firmware chunks
                val chunkSize = bleManager.otaChunkSize()
                var offset = 0
                while (offset < fw.size) {
                    ensureActive()
                    val end = minOf(offset + chunkSize, fw.size)
                    val chunk = fw.copyOfRange(offset, end)
                    if (!bleManager.writeOtaData(chunk)) {
                        throw Exception("BLE write failed at offset $offset")
                    }
                    offset = end
                    _progress.value = offset.toFloat() / fw.size
                    delay(CHUNK_DELAY_MS)
                }

                // Phase 4: Send OTA_END to validate and reboot
                _state.value = OtaState.VALIDATING
                bleManager.writeOtaControl(CommandBuilder.otaEnd())

                // Wait for COMPLETE or error (device will reboot)
                val endStatus = withTimeoutOrNull(10000) {
                    bleManager.otaStatus.first {
                        it == OtaStatus.COMPLETE || it == OtaStatus.ERR_VALIDATE
                    }
                }

                if (endStatus == OtaStatus.ERR_VALIDATE) {
                    throw Exception("Firmware validation failed")
                }

                _state.value = OtaState.COMPLETE
                Log.i(TAG, "OTA complete — device is rebooting")

            } catch (e: CancellationException) {
                bleManager.writeOtaControl(CommandBuilder.otaAbort())
                _state.value = OtaState.IDLE
                Log.i(TAG, "OTA cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "OTA failed", e)
                bleManager.writeOtaControl(CommandBuilder.otaAbort())
                _errorMessage.value = e.message
                _state.value = OtaState.ERROR
            }
        }
    }

    fun cancel() {
        otaJob?.cancel()
        otaJob = null
        _state.value = OtaState.IDLE
        _progress.value = 0f
    }

    fun reset() {
        cancel()
        _errorMessage.value = null
        _latestRelease.value = null
    }

    private fun fetchLatestRelease(): FirmwareRelease? {
        val url = URL("$RELEASES_URL?per_page=5")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) {
                throw Exception("GitHub API returned ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                val tagName = rel.getString("tag_name")
                if (!tagName.startsWith("firmware-")) continue

                val version = tagName.removePrefix("firmware-v")
                val assets = rel.getJSONArray("assets")
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.getString("name")
                    if (name.endsWith(".bin")) {
                        return FirmwareRelease(
                            version = version,
                            downloadUrl = asset.getString("browser_download_url"),
                            publishedAt = rel.getString("published_at"),
                            body = rel.optString("body", ""),
                        )
                    }
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadFirmware(downloadUrl: String): ByteArray {
        val url = URL(downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) {
                throw Exception("Download failed: HTTP ${conn.responseCode}")
            }
            val totalSize = conn.contentLength
            val input = BufferedInputStream(conn.inputStream);
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream(maxOf(totalSize, 256 * 1024))
            var bytesRead: Int
            var totalRead = 0
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalSize > 0) {
                    _progress.value = totalRead.toFloat() / totalSize
                }
            }
            return output.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
