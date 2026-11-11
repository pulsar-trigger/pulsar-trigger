/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.ble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import com.ehrocha.pulsar.AppConfig
import com.ehrocha.pulsar.R
import com.ehrocha.pulsar.update.FirmwareAssetPick
import com.ehrocha.pulsar.update.expectedChipId
import com.ehrocha.pulsar.update.fetchNewestRelease
import com.ehrocha.pulsar.update.fetchReleasesPage
import com.ehrocha.pulsar.update.fetchSmallText
import com.ehrocha.pulsar.update.isNewerVersion
import com.ehrocha.pulsar.update.parseSha256
import com.ehrocha.pulsar.update.pickFirmwareAsset
import com.ehrocha.pulsar.update.sha256Hex

data class FirmwareRelease(
    val version: String,
    val downloadUrl: String,
    val checksumUrl: String?,
    val publishedAt: String,
    val body: String,
    /** The board this image was picked for; [FirmwareUpdateManager.startUpdate]
     *  refuses it for any other board. */
    val board: BoardKind,
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

/** Why a firmware check or update failed. Each has its own message. */
enum class OtaFailure {
    CHECK_FAILED,
    NO_RELEASE,
    UNKNOWN_BOARD,
    NO_ASSET_FOR_BOARD,
    BOARD_CHANGED,
    NO_OTA_SUPPORT,
    DOWNLOAD_FAILED,
    CHECKSUM_MISSING,
    CHECKSUM_MISMATCH,
    BEGIN_REJECTED,
    BUSY,
    WRONG_BOARD,
    TOO_LARGE,
    WRITE_FAILED,
    VALIDATE_FAILED,
    NO_ANSWER,
    UNCONFIRMED,
    UNKNOWN_STATUS,
}

data class OtaError(val failure: OtaFailure, val detail: String? = null) {
    fun message(context: Context): String = when (failure) {
        OtaFailure.CHECK_FAILED -> context.getString(R.string.ota_err_check_failed)
        OtaFailure.NO_RELEASE -> context.getString(R.string.ota_err_no_release)
        OtaFailure.UNKNOWN_BOARD -> context.getString(R.string.ota_err_unknown_board)
        OtaFailure.NO_ASSET_FOR_BOARD -> context.getString(R.string.ota_err_no_asset, detail ?: "?")
        OtaFailure.BOARD_CHANGED -> context.getString(R.string.ota_err_board_changed)
        OtaFailure.NO_OTA_SUPPORT -> context.getString(R.string.ota_err_no_support)
        OtaFailure.DOWNLOAD_FAILED -> context.getString(R.string.ota_err_download)
        OtaFailure.CHECKSUM_MISSING -> context.getString(R.string.ota_err_checksum_missing)
        OtaFailure.CHECKSUM_MISMATCH -> context.getString(R.string.ota_err_checksum_mismatch)
        OtaFailure.BEGIN_REJECTED -> context.getString(R.string.ota_err_begin)
        OtaFailure.BUSY -> context.getString(R.string.ota_err_busy)
        OtaFailure.WRONG_BOARD -> context.getString(R.string.ota_err_board)
        OtaFailure.TOO_LARGE -> context.getString(R.string.ota_err_size)
        OtaFailure.WRITE_FAILED -> context.getString(R.string.ota_err_write)
        OtaFailure.VALIDATE_FAILED -> context.getString(R.string.ota_err_validate)
        OtaFailure.NO_ANSWER -> context.getString(R.string.ota_err_no_answer)
        OtaFailure.UNCONFIRMED -> context.getString(R.string.ota_err_unconfirmed)
        OtaFailure.UNKNOWN_STATUS -> context.getString(R.string.ota_err_unknown_status)
    }
}

private class OtaException(val error: OtaError) : Exception(error.failure.name + (error.detail?.let { ": $it" } ?: ""))

private fun fail(failure: OtaFailure, detail: String? = null): Nothing =
    throw OtaException(OtaError(failure, detail))

/** Map a firmware OTA error status to the failure the user sees. */
internal fun failureFor(status: OtaStatus): OtaFailure = when (status) {
    OtaStatus.ERR_BEGIN -> OtaFailure.BEGIN_REJECTED
    OtaStatus.ERR_WRITE -> OtaFailure.WRITE_FAILED
    OtaStatus.ERR_VALIDATE -> OtaFailure.VALIDATE_FAILED
    OtaStatus.ERR_SIZE -> OtaFailure.TOO_LARGE
    OtaStatus.ERR_BOARD -> OtaFailure.WRONG_BOARD
    OtaStatus.ERR_BUSY -> OtaFailure.BUSY
    OtaStatus.OK, OtaStatus.READY, OtaStatus.COMPLETE, OtaStatus.UNKNOWN -> OtaFailure.UNKNOWN_STATUS
}

class FirmwareUpdateManager(
    private val bleManager: PulsarBleManager,
    private val scope: CoroutineScope,
    /** The connected Pulsar's board, from its scan-response manufacturer
     *  data. Images are chosen by board, never by chip. */
    private val boardProvider: () -> BoardKind,
) {
    companion object {
        private const val TAG = "FirmwareOTA"
        private const val CHUNK_DELAY_MS = AppConfig.OTA_CHUNK_DELAY_MS
        private const val TAG_PREFIX = "firmware-v"
        private const val READY_TIMEOUT_MS = 5_000L
        private const val END_TIMEOUT_MS = 10_000L
    }

    private val _state = MutableStateFlow(OtaState.IDLE)
    val state: StateFlow<OtaState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _latestRelease = MutableStateFlow<FirmwareRelease?>(null)
    val latestRelease: StateFlow<FirmwareRelease?> = _latestRelease

    private val _error = MutableStateFlow<OtaError?>(null)
    val error: StateFlow<OtaError?> = _error

    private var otaJob: Job? = null

    fun checkForUpdate(currentVersion: String) {
        scope.launch(Dispatchers.IO) {
            _state.value = OtaState.CHECKING
            _error.value = null
            _latestRelease.value = null
            try {
                val board = boardProvider()
                val release = fetchNewestRelease(TAG_PREFIX) { page -> fetchReleasesPage(page) }
                when (val pick = pickFirmwareAsset(release, TAG_PREFIX, board)) {
                    FirmwareAssetPick.UnknownBoard -> fail(OtaFailure.UNKNOWN_BOARD)
                    FirmwareAssetPick.NoRelease -> fail(OtaFailure.NO_RELEASE)
                    is FirmwareAssetPick.NoAssetForBoard -> {
                        // Only a problem if it would have been an update.
                        if (isNewerVersion(pick.version, currentVersion)) {
                            fail(OtaFailure.NO_ASSET_FOR_BOARD, pick.version)
                        }
                        _state.value = OtaState.UP_TO_DATE
                    }
                    is FirmwareAssetPick.Found -> {
                        val a = pick.asset
                        Log.i(TAG, "Firmware ${a.version} for $board: ${a.downloadUrl}")
                        _latestRelease.value = FirmwareRelease(
                            version = a.version,
                            downloadUrl = a.downloadUrl,
                            checksumUrl = a.checksumUrl,
                            publishedAt = a.publishedAt,
                            body = a.body,
                            board = board,
                        )
                        _state.value = if (isNewerVersion(a.version, currentVersion)) {
                            OtaState.AVAILABLE
                        } else OtaState.UP_TO_DATE
                    }
                }
            } catch (e: OtaException) {
                Log.w(TAG, "Check refused: ${e.message}")
                _error.value = e.error
                _state.value = OtaState.ERROR
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Check failed", e)
                _error.value = OtaError(OtaFailure.CHECK_FAILED, e.message)
                _state.value = OtaState.ERROR
            }
        }
    }

    @Synchronized
    fun startUpdate() {
        // One update at a time: a second tap must not start a parallel upload.
        if (otaJob?.isActive == true) return
        val release = _latestRelease.value ?: return
        if (!bleManager.hasOtaSupport()) {
            _error.value = OtaError(OtaFailure.NO_OTA_SUPPORT)
            _state.value = OtaState.ERROR
            return
        }
        if (release.board == BoardKind.UNKNOWN || boardProvider() != release.board) {
            // A different Pulsar connected since the check — its image may
            // be for another board.
            _error.value = OtaError(OtaFailure.BOARD_CHANGED)
            _state.value = OtaState.ERROR
            return
        }

        otaJob = scope.launch(Dispatchers.IO) {
            try {
                // Phase 1: download, then verify — fail closed.
                _state.value = OtaState.DOWNLOADING
                _progress.value = 0f
                val fw = try {
                    downloadFirmware(release.downloadUrl)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed", e)
                    fail(OtaFailure.DOWNLOAD_FAILED, e.message)
                }
                if (fw.isEmpty()) fail(OtaFailure.DOWNLOAD_FAILED)
                Log.i(TAG, "Downloaded ${fw.size} bytes")
                val checksumUrl = release.checksumUrl ?: fail(OtaFailure.CHECKSUM_MISSING)
                val expected = fetchSmallText(checksumUrl)?.let(::parseSha256)
                    ?: fail(OtaFailure.CHECKSUM_MISSING)
                if (sha256Hex(fw) != expected) fail(OtaFailure.CHECKSUM_MISMATCH)
                Log.i(TAG, "Firmware checksum verified")

                // Phase 2: BEGIN. Clear the last status first so a READY or
                // error from an earlier attempt can't answer this one.
                _state.value = OtaState.UPLOADING
                _progress.value = 0f
                bleManager.resetOtaStatus()
                bleManager.writeOtaControl(CommandBuilder.otaBegin(fw.size))
                val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
                    bleManager.otaStatus.first { it == OtaStatus.READY || it?.isError == true }
                } ?: fail(OtaFailure.NO_ANSWER)
                if (ready != OtaStatus.READY) fail(failureFor(ready))

                // The chip in OTA_READY must be the one this board carries.
                val expectedChip = expectedChipId(release.board)
                val reportedChip = bleManager.otaChipModel.value
                if (expectedChip != null && reportedChip != null && reportedChip != expectedChip) {
                    fail(OtaFailure.WRONG_BOARD, "chip $reportedChip, expected $expectedChip")
                }

                // Phase 3: stream chunks; stop at once if the device reports
                // an error (flash write failure, abandoned upload).
                val chunkSize = bleManager.otaChunkSize()
                var offset = 0
                while (offset < fw.size) {
                    ensureActive()
                    bleManager.otaStatus.value?.takeIf { it.isError }?.let { fail(failureFor(it)) }
                    val end = minOf(offset + chunkSize, fw.size)
                    val chunk = fw.copyOfRange(offset, end)
                    if (!bleManager.writeOtaData(chunk)) {
                        fail(OtaFailure.WRITE_FAILED, "offset $offset")
                    }
                    offset = end
                    _progress.value = offset.toFloat() / fw.size
                    delay(CHUNK_DELAY_MS)
                }

                // Phase 4: END — the firmware validates (and, from 0.33,
                // checks the image's board tag) and reboots.
                _state.value = OtaState.VALIDATING
                bleManager.writeOtaControl(CommandBuilder.otaEnd())
                val endStatus = withTimeoutOrNull(END_TIMEOUT_MS) {
                    bleManager.otaStatus.first { it == OtaStatus.COMPLETE || it?.isError == true }
                } ?: fail(OtaFailure.UNCONFIRMED)
                if (endStatus != OtaStatus.COMPLETE) fail(failureFor(endStatus))

                _state.value = OtaState.COMPLETE
                bleManager.requestCacheRefresh()
                Log.i(TAG, "OTA complete — device is rebooting")
            } catch (e: OtaException) {
                Log.e(TAG, "OTA failed: ${e.message}")
                bleManager.writeOtaControl(CommandBuilder.otaAbort())
                _error.value = e.error
                _state.value = OtaState.ERROR
            } catch (e: CancellationException) {
                // A real cancel (the user's Cancel, or the scope ending).
                // Timeouts no longer land here: they use withTimeoutOrNull.
                bleManager.writeOtaControl(CommandBuilder.otaAbort())
                _state.value = OtaState.IDLE
                Log.i(TAG, "OTA cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "OTA failed", e)
                bleManager.writeOtaControl(CommandBuilder.otaAbort())
                _error.value = OtaError(OtaFailure.WRITE_FAILED, e.message)
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
        _error.value = null
        _latestRelease.value = null
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
