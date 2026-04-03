/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(
    val version: String,
    val downloadUrl: String,
    val publishedAt: String,
    val body: String,
)

enum class AppUpdateState {
    IDLE,
    CHECKING,
    AVAILABLE,
    UP_TO_DATE,
    DOWNLOADING,
    READY_TO_INSTALL,
    ERROR,
}

class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "AppUpdate"
        private const val GITHUB_REPO = "pulsar-trigger/pulsar-trigger"
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases"
        private const val APK_FILENAME = "pulsar-update.apk"
    }

    private val _state = MutableStateFlow(AppUpdateState.IDLE)
    val state: StateFlow<AppUpdateState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _latestRelease = MutableStateFlow<AppRelease?>(null)
    val latestRelease: StateFlow<AppRelease?> = _latestRelease

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private var downloadJob: Job? = null

    fun checkForUpdate(currentVersion: String) {
        scope.launch(Dispatchers.IO) {
            _state.value = AppUpdateState.CHECKING
            _errorMessage.value = null
            try {
                val release = fetchLatestAppRelease()
                _latestRelease.value = release
                if (release != null && isNewer(release.version, currentVersion)) {
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

    fun downloadAndInstall() {
        val release = _latestRelease.value ?: return

        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                _state.value = AppUpdateState.DOWNLOADING
                _progress.value = 0f

                val apkFile = downloadApk(release.downloadUrl)

                _state.value = AppUpdateState.READY_TO_INSTALL
                installApk(apkFile)

            } catch (e: CancellationException) {
                _state.value = AppUpdateState.IDLE
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _errorMessage.value = e.message
                _state.value = AppUpdateState.ERROR
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = AppUpdateState.IDLE
        _progress.value = 0f
    }

    fun reset() {
        cancel()
        _errorMessage.value = null
        _latestRelease.value = null
    }

    /** Trigger Android package installer. */
    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun fetchLatestAppRelease(): AppRelease? {
        val url = URL("$RELEASES_URL?per_page=10")
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
                if (!tagName.startsWith("app-v")) continue

                val version = tagName.removePrefix("app-v")
                val assets = rel.getJSONArray("assets")
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk")) {
                        return AppRelease(
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

    private fun downloadApk(downloadUrl: String): File {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        val apkFile = File(updatesDir, APK_FILENAME)

        val url = URL(downloadUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) {
                throw Exception("Download failed: HTTP ${conn.responseCode}")
            }
            val totalSize = conn.contentLength
            val input = BufferedInputStream(conn.inputStream)
            apkFile.outputStream().use { output ->
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
            return apkFile
        } finally {
            conn.disconnect()
        }
    }

    /** Compare semver strings (e.g. "0.2.0" > "0.1.0"). */
    private fun isNewer(remote: String, local: String): Boolean {
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
}
