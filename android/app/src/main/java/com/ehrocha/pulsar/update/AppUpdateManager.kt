/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ehrocha.pulsar.AppConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val version: String,
    val apkUrl: String,
    val apkChecksumUrl: String?,
    val releasePageUrl: String,
    val publishedAt: String,
    val body: String,
)

enum class AppUpdateState {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    UP_TO_DATE,
    ERROR,
}

/**
 * Polls GitHub releases for newer Pulsar APKs and (if the user opts in)
 * downloads + hands the APK to the system installer so the user doesn't have
 * to round-trip through the browser. [openReleasePage] remains as a fallback
 * for users who would rather sideload manually.
 *
 * Note on Play Protect: the combination of `REQUEST_INSTALL_PACKAGES` + a
 * locally cached APK + a self-triggered install intent has, in the past,
 * tripped Play Protect's sideloading-dropper heuristics. If a release ever
 * gets flagged, we can fall back to the release-page-only flow.
 */
class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object { private const val TAG = "AppUpdate" }

    private val _state = MutableStateFlow(AppUpdateState.IDLE)
    val state: StateFlow<AppUpdateState> = _state

    private val _latestRelease = MutableStateFlow<AppRelease?>(null)
    val latestRelease: StateFlow<AppRelease?> = _latestRelease

    /** Recent releases (newest first, up to ~10). Populated by
     *  [fetchRecentReleases]. Drives the Settings rollback picker. */
    private val _recentReleases = MutableStateFlow<List<AppRelease>>(emptyList())
    val recentReleases: StateFlow<List<AppRelease>> = _recentReleases

    private val _recentReleasesLoading = MutableStateFlow(false)
    val recentReleasesLoading: StateFlow<Boolean> = _recentReleasesLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /** 0.0..1.0 during DOWNLOADING; stays at last value at other times. */
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    /** The on-disk path of the downloaded APK once [downloadAndInstall] has
     *  finished writing it. Cleared on [reset]. */
    private var downloadedApk: File? = null
    private var downloadJob: Job? = null

    fun checkForUpdate(currentVersion: String) {
        scope.launch(Dispatchers.IO) {
            _state.value = AppUpdateState.CHECKING
            _errorMessage.value = null
            try {
                val release = fetchLatestAppRelease()
                _latestRelease.value = release
                if (release != null && isNewerVersion(release.version, currentVersion)) {
                    _state.value = AppUpdateState.AVAILABLE
                } else {
                    _state.value = AppUpdateState.UP_TO_DATE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check failed", e)
                _errorMessage.value = e.message
                _state.value = AppUpdateState.ERROR
            }
        }
    }

    fun reset() {
        downloadJob?.cancel()
        downloadJob = null
        downloadedApk = null
        _state.value = AppUpdateState.IDLE
        _errorMessage.value = null
        _latestRelease.value = null
        _downloadProgress.value = 0f
    }

    /** Open the GitHub release page in the user's browser. Fallback for
     *  users who prefer manual sideloading or whose system installer is
     *  locked down. */
    fun openReleasePage() {
        val release = _latestRelease.value ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.releasePageUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open release page", e)
            _errorMessage.value = e.message
            _state.value = AppUpdateState.ERROR
        }
    }

    /** Populate [recentReleases] with up to 10 prior published releases —
     *  used by the Settings rollback picker. Safe to call repeatedly; just
     *  refreshes the list. */
    fun fetchRecentReleases() {
        scope.launch(Dispatchers.IO) {
            _recentReleasesLoading.value = true
            try {
                val list = fetchGitHubReleases(tagPrefix = "app-v", assetSuffix = ".apk", count = 10)
                _recentReleases.value = list.map { asset ->
                    val pageUrl = asset.downloadUrl.replace(
                        Regex("/download/([^/]+)/[^/]+$"), "/tag/$1"
                    )
                    AppRelease(
                        version = asset.version,
                        apkUrl = asset.downloadUrl,
                        apkChecksumUrl = asset.checksumUrl,
                        releasePageUrl = pageUrl,
                        publishedAt = asset.publishedAt,
                        body = asset.body,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchRecentReleases failed", e)
                _errorMessage.value = e.message
            } finally {
                _recentReleasesLoading.value = false
            }
        }
    }

    /** Download the APK from GitHub, validate its SHA-256 (when published),
     *  then trigger the system package installer. The user still has to
     *  approve the install — we just shortcut the browser-download dance.
     *  Pass [override] to install a specific (older) release from
     *  [recentReleases] instead of [latestRelease]. */
    fun downloadAndInstall(override: AppRelease? = null) {
        val release = override ?: _latestRelease.value ?: return
        if (override != null) _latestRelease.value = override
        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.IO) {
            _state.value = AppUpdateState.DOWNLOADING
            _errorMessage.value = null
            _downloadProgress.value = 0f
            val cacheDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(cacheDir, "pulsar-${release.version}.apk")
            try {
                downloadTo(release.apkUrl, out) { fraction ->
                    _downloadProgress.value = fraction
                }
                val expected = release.apkChecksumUrl?.let { fetchExpectedChecksum(it) }
                if (expected != null) {
                    val actual = sha256Hex(out)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        out.delete()
                        throw Exception("Checksum mismatch: expected $expected, got $actual")
                    }
                    Log.i(TAG, "APK SHA-256 verified")
                } else {
                    Log.w(TAG, "No checksum published for ${release.version} — skipping verify")
                }
                downloadedApk = out
                _state.value = AppUpdateState.READY_TO_INSTALL
                // Hand off to the system installer immediately. Users can
                // re-trigger via [launchInstaller] if they cancel and want
                // to retry without re-downloading.
                launchInstaller()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _errorMessage.value = e.message
                _state.value = AppUpdateState.ERROR
            }
        }
    }

    /** Re-launch the system installer for the already-downloaded APK. */
    fun launchInstaller() {
        val apk = downloadedApk ?: return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
            _errorMessage.value = e.message
            _state.value = AppUpdateState.ERROR
        }
    }

    private fun downloadTo(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.API_CONNECT_TIMEOUT_MS
        conn.readTimeout = AppConfig.API_READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) {
                throw Exception("Download HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var written = 0L
            dest.outputStream().buffered().use { out ->
                conn.inputStream.buffered().use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) onProgress(written.toFloat() / total)
                    }
                }
            }
            onProgress(1f)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchLatestAppRelease(): AppRelease? {
        val asset = fetchGitHubRelease(tagPrefix = "app-v", assetSuffix = ".apk")
            ?: return null
        val pageUrl = asset.downloadUrl.replace(
            Regex("/download/([^/]+)/[^/]+$"), "/tag/$1"
        )
        return AppRelease(
            version = asset.version,
            apkUrl = asset.downloadUrl,
            apkChecksumUrl = asset.checksumUrl,
            releasePageUrl = pageUrl,
            publishedAt = asset.publishedAt,
            body = asset.body,
        )
    }
}
