/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import android.util.Log
import com.ehrocha.pulsar.transport.CameraTransport
import com.ehrocha.pulsar.transport.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * `CameraTransport` implementation for Canon CCAPI cameras. Phase 2 wired the
 * single-shot `shutterbutton` path (Timelapse). Phase 3 adds bulb-mode
 * lifecycle (`shutterbutton/manual`) + `shootingmode` switching with
 * capability-detected `ignoreshootingmodedialmode` so bodies with a physical
 * mode dial can still be driven from the app.
 *
 * State model: [connect] performs `GET /ccapi` to pin the API version and
 * cache the endpoint matrix. On success [connected] flips to true and
 * subsequent calls go through.
 */
class CcapiTransport(
    val camera: CanonCamera,
    credentials: CcapiClient.Credentials? = null,
) : CameraTransport {
    companion object {
        private const val TAG = "CcapiTransport"
        private const val PATH_SHUTTER = "/shooting/control/shutterbutton"
        private const val PATH_SHUTTER_MANUAL = "/shooting/control/shutterbutton/manual"
        private const val PATH_SHOOTING_MODE = "/shooting/settings/shootingmode"
        private const val PATH_DIAL_IGNORE = "/shooting/control/ignoreshootingmodedialmode"
        private const val PATH_POLL = "/event/polling"
    }

    override val kind = TransportKind.CCAPI

    private val client = CcapiClient(camera.accessUrl, credentials)

    private val _label = MutableStateFlow(camera.nickname ?: camera.friendlyName)
    override val label: StateFlow<String> = _label

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /** True once we've taken over the camera's physical mode dial — gets
     *  flipped back off on [release] so the user regains control. */
    private var dialIgnoreActive = false

    /** Probe `GET /ccapi`, pin version, cache endpoints. Returns the client's
     *  [CcapiClient.Result] verbatim so callers can route 401 / network errors. */
    suspend fun connect(): CcapiClient.Result<Unit> {
        val r = client.connect()
        if (r is CcapiClient.Result.Ok) _connected.value = true
        return r
    }

    /** Re-runs `GET /ccapi` to confirm the camera is still reachable. Used by
     *  the ViewModel's reconnect loop after polling failures — if this comes
     *  back Ok the existing session can resume; on persistent failure the
     *  caller drops the transport. Lighter than building a fresh transport
     *  because it preserves cached digest state and capability matrix. */
    suspend fun reconnect(): CcapiClient.Result<Unit> = client.connect()

    override suspend fun release() {
        // Best-effort: hand the mode dial back. Swallowed if camera is gone.
        if (dialIgnoreActive) {
            runCatching {
                client.post(PATH_DIAL_IGNORE, JSONObject().put("action", "off"))
            }
            dialIgnoreActive = false
        }
        _connected.value = false
    }

    override suspend fun fireShutter(af: Boolean) {
        if (!_connected.value) {
            Log.w(TAG, "fireShutter called while disconnected — ignored")
            return
        }
        val body = JSONObject().put("af", af)
        logResult("shutterbutton", client.post(PATH_SHUTTER, body))
    }

    override suspend fun setShutterMode(bulb: Boolean) {
        if (!_connected.value) {
            Log.w(TAG, "setShutterMode called while disconnected — ignored")
            return
        }
        // Some bodies have a physical mode dial that overrides this PUT.
        // Engage dial-ignore if the body supports it; the off-side fires
        // on release(). Capability-detected per body.
        if (!dialIgnoreActive && client.supports(PATH_DIAL_IGNORE, "post")) {
            val r = client.post(PATH_DIAL_IGNORE, JSONObject().put("action", "on"))
            if (r is CcapiClient.Result.Ok) dialIgnoreActive = true
            else logResult("ignoreshootingmodedialmode on", r)
        }
        val value = if (bulb) "bulb" else "m"
        logResult("shootingmode→$value", client.put(PATH_SHOOTING_MODE, JSONObject().put("value", value)))
    }

    override suspend fun startBulb(af: Boolean) {
        if (!_connected.value) {
            Log.w(TAG, "startBulb called while disconnected — ignored")
            return
        }
        val body = JSONObject()
            .put("action", "full_press")
            .put("af", af)
        logResult("shutterbutton/manual full_press", client.post(PATH_SHUTTER_MANUAL, body))
    }

    override suspend fun stopBulb() {
        if (!_connected.value) {
            Log.w(TAG, "stopBulb called while disconnected — ignored")
            return
        }
        val body = JSONObject()
            .put("action", "release")
            .put("af", false)
        logResult("shutterbutton/manual release", client.post(PATH_SHUTTER_MANUAL, body))
    }

    override suspend fun stop() {
        // Belt-and-braces: if a bulb exposure is open, release it.
        if (_connected.value) stopBulb()
    }

    /**
     * Long-poll `/event/polling`. Camera blocks up to ~10 s (`timeout=short`)
     * and returns only the fields that changed. Used by the ViewModel's
     * polling job for battery / shot-count updates and dropout detection.
     */
    suspend fun pollEvents(): CcapiClient.Result<JSONObject> {
        return when (val r = client.get("$PATH_POLL?timeout=short")) {
            is CcapiClient.Result.Ok -> try {
                CcapiClient.Result.Ok(JSONObject(r.value))
            } catch (e: Exception) {
                CcapiClient.Result.Network(e)
            }
            is CcapiClient.Result.Http -> r
            is CcapiClient.Result.NeedsAuth -> r
            is CcapiClient.Result.Network -> r
        }
    }

    private fun logResult(tag: String, r: CcapiClient.Result<*>) {
        when (r) {
            is CcapiClient.Result.Ok -> { /* success — quiet */ }
            is CcapiClient.Result.Http -> Log.w(TAG, "$tag HTTP ${r.code}: ${r.body}")
            is CcapiClient.Result.NeedsAuth -> Log.w(TAG, "$tag needs auth (digest in Phase 4)")
            is CcapiClient.Result.Network -> Log.w(TAG, "$tag network error", r.cause)
        }
    }
}
