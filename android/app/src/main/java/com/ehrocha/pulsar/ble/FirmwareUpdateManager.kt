/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.update.GitHubAsset
import com.ehrocha.pulsar.update.fetchGitHubRelease
import com.ehrocha.pulsar.update.fetchExpectedChecksum
import com.ehrocha.pulsar.update.isNewerVersion
import com.ehrocha.pulsar.update.sha256Hex

data class FirmwareRelease(
    val version: String,
    val downloadUrl: String,
    val checksumUrl: String?,
    val publishedAt: String,
    val body: String,
)

enum class OtaState {
    IDLE,
    CHECKING,
    UP_TO_DATE,
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
        private const val CHUNK_DELAY_MS = AppConfig.OTA_CHUNK_DELAY_MS

        /** Map chip model string from DeviceInfo → GitHub release asset suffix. */
        private fun assetSuffixForChip(chipModel: String?): String = when (chipModel) {
            "ESP32" -> "-esp32.bin"
            "ESP32-S3" -> "-esp32s3.bin"
            else -> "-unknown.bin"  // won't match any real asset
        }

        /** Map chip model string from DeviceInfo → expected chip ID byte. */
        private fun chipIdForModel(chipModel: String?): Int = when (chipModel) {
            "ESP32" -> 1
            "ESP32-S2" -> 2
            "ESP32-S3" -> 3
            "ESP32-C3" -> 4
            else -> 0
        }
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
                if (release != null && isNewerVersion(release.version, currentVersion)) {
                    _state.value = OtaState.AVAILABLE
                } else {
                    _state.value = OtaState.UP_TO_DATE
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

                // Verify SHA-256 checksum if available
                release.checksumUrl?.let { url ->
                    val expected = fetchExpectedChecksum(url)
                    if (expected != null) {
                        val actual = sha256Hex(fw)
                        if (actual != expected) {
                            firmwareBytes = null
                            throw Exception("SHA-256 checksum mismatch")
                        }
                        Log.i(TAG, "Firmware checksum verified")
                    }
                }

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

                // Verify chip model from OTA_READY matches device info
                val expectedChipId = chipIdForModel(bleManager.deviceInfo.value?.chipModel)
                val reportedChipId = bleManager.otaChipModel.value
                if (expectedChipId != 0 && reportedChipId != null && reportedChipId != expectedChipId) {
                    throw Exception(
                        "Chip model mismatch: expected $expectedChipId, firmware reported $reportedChipId"
                    )
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
                bleManager.requestCacheRefresh()
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

    private suspend fun fetchLatestRelease(): FirmwareRelease? {
        var chipModel = bleManager.deviceInfo.value?.chipModel
        if (chipModel == null) {
            // Device info may not have arrived yet — request and wait briefly
            bleManager.sendCommand(CommandBuilder.deviceInfoRequest())
            chipModel = withTimeoutOrNull(3000) {
                bleManager.deviceInfo.filterNotNull().first()
            }?.chipModel
        }
        if (chipModel == null) {
            Log.w(TAG, "Device info not available — cannot determine chip model for firmware asset")
            return null
        }
        val suffix = assetSuffixForChip(chipModel)
        Log.i(TAG, "Looking for firmware asset with suffix '$suffix' (chip=$chipModel)")
        val asset = fetchGitHubRelease(tagPrefix = "firmware-v", assetSuffix = suffix, perPage = 5)
            ?: return null
        return FirmwareRelease(
            version = asset.version,
            downloadUrl = asset.downloadUrl,
            checksumUrl = asset.checksumUrl,
            publishedAt = asset.publishedAt,
            body = asset.body,
        )
    }

    private fun downloadFirmware(downloadUrl: String): ByteArray {
        val url = URL(downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.DOWNLOAD_CONNECT_TIMEOUT_MS
        conn.readTimeout = AppConfig.DOWNLOAD_READ_TIMEOUT_MS
        val tempFile = java.io.File.createTempFile("firmware_", ".bin")
        try {
            if (conn.responseCode != 200) {
                throw Exception("Download failed: HTTP ${conn.responseCode}")
            }
            val totalSize = conn.contentLength
            BufferedInputStream(conn.inputStream).use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalSize > 0) {
                            _progress.value = totalRead.toFloat() / totalSize
                        }
                    }
                }
            }
            return tempFile.readBytes()
        } finally {
            tempFile.delete()
            conn.disconnect()
        }
    }
}
