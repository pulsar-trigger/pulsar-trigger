/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppRelease(
    val version: String,
    val releasePageUrl: String,
    val publishedAt: String,
    val body: String,
)

enum class AppUpdateState {
    IDLE,
    CHECKING,
    AVAILABLE,
    UP_TO_DATE,
    ERROR,
}

/**
 * Polls GitHub releases for newer Pulsar APKs. The actual download + install
 * is delegated to the user's browser — we open the release page in
 * [openReleasePage] and let them sideload from there. We deliberately do NOT
 * hold `REQUEST_INSTALL_PACKAGES` or trigger the package installer ourselves
 * (that combination gets flagged by Google Play Protect as a sideloading
 * dropper, even for legitimate open-source self-updaters).
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

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
        _state.value = AppUpdateState.IDLE
        _errorMessage.value = null
        _latestRelease.value = null
    }

    /** Open the GitHub release page in the user's browser. The user
     *  downloads + installs the APK from there using the system flow. */
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

    private fun fetchLatestAppRelease(): AppRelease? {
        val asset = fetchGitHubRelease(tagPrefix = "app-v", assetSuffix = ".apk")
            ?: return null
        // Derive the release-page URL from the asset's browser_download_url.
        //   .../releases/download/<tag>/<file>.apk → .../releases/tag/<tag>
        val pageUrl = asset.downloadUrl.replace(
            Regex("/download/([^/]+)/[^/]+$"), "/tag/$1"
        )
        return AppRelease(
            version = asset.version,
            releasePageUrl = pageUrl,
            publishedAt = asset.publishedAt,
            body = asset.body,
        )
    }
}
