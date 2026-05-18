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
 * `CameraTransport` implementation for Canon CCAPI cameras. Phase 2 supports
 * the single-shot `shutterbutton` path (Timelapse). Bulb-mode lifecycle
 * (`shutterbutton/manual`) and capability-detected fallbacks land in Phase 3.
 *
 * State model: [connect] performs `GET /ccapi` to pin the API version and
 * cache the endpoint matrix. On success [connected] flips to true and
 * subsequent `fireShutter` calls go through.
 */
class CcapiTransport(
    val camera: CanonCamera,
) : CameraTransport {
    companion object { private const val TAG = "CcapiTransport" }

    override val kind = TransportKind.CCAPI

    private val client = CcapiClient(camera.accessUrl)

    private val _label = MutableStateFlow(camera.nickname ?: camera.friendlyName)
    override val label: StateFlow<String> = _label

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    /** Probe `GET /ccapi`, pin version, cache endpoints. Returns the client's
     *  [CcapiClient.Result] verbatim so callers can route 401 / network errors. */
    suspend fun connect(): CcapiClient.Result<Unit> {
        val r = client.connect()
        if (r is CcapiClient.Result.Ok) _connected.value = true
        return r
    }

    override suspend fun release() {
        _connected.value = false
    }

    override suspend fun fireShutter(af: Boolean) {
        if (!_connected.value) {
            Log.w(TAG, "fireShutter called while disconnected — ignored")
            return
        }
        val body = JSONObject().put("af", af)
        when (val r = client.post("/shooting/control/shutterbutton", body)) {
            is CcapiClient.Result.Ok -> { /* fired */ }
            is CcapiClient.Result.Http -> Log.w(TAG, "shutterbutton HTTP ${r.code}: ${r.body}")
            is CcapiClient.Result.NeedsAuth -> Log.w(TAG, "shutterbutton needs auth (not yet implemented)")
            is CcapiClient.Result.Network -> Log.w(TAG, "shutterbutton network error", r.cause)
        }
    }

    override suspend fun stop() {
        // Phase 2: only single-shot fire — nothing in flight to abort.
        // Phase 3 will cancel an open bulb here.
    }
}
