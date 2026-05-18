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
        private const val PATH_LIVEVIEW = "/shooting/liveview"
        private const val PATH_LIVEVIEW_FLIP = "/shooting/liveview/flip"
        private const val PATH_DRIVE_FOCUS = "/shooting/control/drivefocus"
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

    /** True iff the body reports `/shooting/control/shutterbutton/manual` in
     *  its endpoint matrix. Set after [connect]; bulb-based modes refuse to
     *  start otherwise. */
    val supportsBulb: Boolean
        get() = client.supports(PATH_SHUTTER_MANUAL, "post")

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

    // ── Live view + focus drive (Star Focus tool) ───────────────────────

    /** Last error message from a failed [startLiveView] attempt. Cleared on
     *  the next successful start. Surfaced in the UI when live view can't
     *  begin so the user (and us, debugging) can see why. */
    @Volatile var lastLiveViewError: String? = null
        private set

    /** Begin a live-view session. Try a sequence of `liveviewsize` values —
     *  older bodies (EOS RP) only accept `small`, newer ones accept `medium`
     *  and `large` too. First one that gets a 200 wins. Returns true on
     *  success; on failure [lastLiveViewError] holds the most informative
     *  response body we saw. */
    suspend fun startLiveView(): Boolean {
        if (!_connected.value) return false
        // Prefer small first — it's the universally supported size, lowest
        // bandwidth, and renders fast enough for focus work.
        val sizesToTry = listOf("small", "medium", "large")
        var lastError: String? = null
        for (size in sizesToTry) {
            val body = JSONObject()
                .put("liveviewsize", size)
                .put("cameraposition", "off")
            val r = client.post(PATH_LIVEVIEW, body)
            if (r is CcapiClient.Result.Ok) {
                Log.i(TAG, "liveview started @ $size")
                lastLiveViewError = null
                return true
            }
            logResult("liveview start @ $size", r)
            lastError = when (r) {
                is CcapiClient.Result.Http -> "HTTP ${r.code}: ${r.body.take(200)}"
                is CcapiClient.Result.NeedsAuth -> "auth required"
                is CcapiClient.Result.Network -> "network error: ${r.cause.message}"
                else -> "unknown"
            }
        }
        lastLiveViewError = lastError
        return false
    }

    /** End the live-view session. */
    suspend fun stopLiveView() {
        if (!_connected.value) return
        val body = JSONObject().put("liveviewsize", "off")
        logResult("liveview stop", client.post(PATH_LIVEVIEW, body))
    }

    /** Fetch one JPEG frame from the running live-view session. Returns the
     *  raw bytes or null on transport error. Canon's flip endpoint returns
     *  the most recent frame on each request — the caller paces frame rate. */
    suspend fun getLiveViewFrame(): ByteArray? {
        if (!_connected.value) return null
        return when (val r = client.getBytes(PATH_LIVEVIEW_FLIP, timeoutMs = 3_000)) {
            is CcapiClient.Result.Ok -> r.value
            else -> { logResult("liveview/flip", r); null }
        }
    }

    /** Drive the focus motor relative to its current position. `action` is
     *  one of `near1`/`near2`/`near3`/`far1`/`far2`/`far3` — 1 is fine, 3 is
     *  coarse. Requires the lens to be in AF mode (motor disconnected when
     *  the lens switch is MF). */
    suspend fun driveFocus(action: String) {
        if (!_connected.value) return
        val body = JSONObject().put("action", action)
        logResult("drivefocus $action", client.post(PATH_DRIVE_FOCUS, body))
    }

    /**
     * Snapshot of `/devicestatus/lens`. Older bodies (EOS RP) return only
     * `{mount, name}` — no native focal-length field — so [focalMm] /
     * [zoomRangeMm] are parsed out of the lens model name when possible.
     * Newer R-bodies that include `focallength` directly aren't covered yet
     * because we don't have a body to verify the exact field name against.
     */
    data class LensInfo(
        val mounted: Boolean,
        val name: String,
        /** Single focal length parsed from the name (e.g. "RF16mm F2.8" → 16).
         *  Null for zoom lenses or unrecognised name shapes. */
        val focalMm: Int?,
        /** Zoom range parsed from the name (e.g. "RF24-105mm F4" → 24..105).
         *  Null for primes / unrecognised shapes. Current zoom position isn't
         *  reported by the older CCAPI revisions Pulsar targets, so the user
         *  has to type the actual value. */
        val zoomRangeMm: IntRange?,
    ) {
        val isPrime get() = focalMm != null && zoomRangeMm == null
        val isZoom get() = zoomRangeMm != null
    }

    /** Read the currently mounted lens. Returns null on network / parse
     *  failure — caller treats a null as "we don't know what's on there". */
    suspend fun getLensInfo(): LensInfo? {
        if (!_connected.value) return null
        val r = client.get("/devicestatus/lens")
        if (r !is CcapiClient.Result.Ok) {
            logResult("devicestatus/lens", r)
            return null
        }
        val json = runCatching { JSONObject(r.value) }.getOrNull() ?: return null
        val mount = json.optBoolean("mount", false)
        val name = json.optString("name", "")
        // Try Canon's native field first if present (newer bodies). Fall back
        // to parsing the model name for older firmware like the EOS RP.
        val nativeFocal = json.optInt("focallength", -1).takeIf { it > 0 }
        val (parsedFocal, parsedRange) = parseFocalFromName(name)
        return LensInfo(
            mounted = mount,
            name = name,
            focalMm = nativeFocal ?: parsedFocal,
            zoomRangeMm = parsedRange,
        )
    }

    /** Direct, non-polling battery read. Used at connect time to seed the
     *  UI before `/event/polling` has a chance to deliver a change event —
     *  polling only returns *changed* fields, so a battery that hasn't
     *  ticked since boot is invisible to it. Returns null on failure. */
    suspend fun getBatteryStatus(): JSONObject? {
        if (!_connected.value) return null
        return when (val r = client.get("/devicestatus/battery")) {
            is CcapiClient.Result.Ok -> runCatching { JSONObject(r.value) }.getOrNull()
            else -> { logResult("devicestatus/battery", r); null }
        }
    }

    /**
     * Long-poll `/event/polling`. Camera blocks up to ~10 s (`timeout=short`
     * on ver110+; `continue=on` on ver100) and returns only the fields that
     * changed. Used by the ViewModel's polling job for battery / shot-count
     * updates and dropout detection.
     *
     * On HTTP 400 from the primary query we retry once with the alternate
     * form — covers bodies that report ver100 endpoints with a ver110-style
     * version banner or vice versa.
     */
    suspend fun pollEvents(): CcapiClient.Result<JSONObject> {
        val primary = primaryPollQuery()
        return when (val r = client.get("$PATH_POLL?$primary")) {
            is CcapiClient.Result.Ok -> parsePollBody(r.value)
            is CcapiClient.Result.Http -> {
                if (r.code == 400) {
                    val alt = alternatePollQuery(primary)
                    when (val r2 = client.get("$PATH_POLL?$alt")) {
                        is CcapiClient.Result.Ok -> parsePollBody(r2.value)
                        else -> r2 as CcapiClient.Result<JSONObject>
                    }
                } else r
            }
            is CcapiClient.Result.NeedsAuth -> r
            is CcapiClient.Result.Network -> r
        }
    }

    private fun primaryPollQuery(): String =
        if (client.version == "ver100") "continue=on" else "timeout=short"

    private fun alternatePollQuery(primary: String): String =
        if (primary == "timeout=short") "continue=on" else "timeout=short"

    private fun parsePollBody(text: String): CcapiClient.Result<JSONObject> = try {
        CcapiClient.Result.Ok(JSONObject(text))
    } catch (e: Exception) {
        CcapiClient.Result.Network(e)
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

/** Parse focal length(s) out of a Canon lens model name. Returns
 *  `(focalMm, zoomRangeMm)` — exactly one of the two is non-null on success,
 *  both null if no usable number pattern is found. Handles names like:
 *   - "RF16mm F2.8 STM" → (16, null)
 *   - "EF 50mm f/1.8 STM" → (50, null)
 *   - "RF24-105mm F4 L IS USM" → (null, 24..105)
 *   - "EF 70-200mm f/2.8L II USM" → (null, 70..200) */
internal fun parseFocalFromName(name: String): Pair<Int?, IntRange?> {
    // Match an `N` or `N-M` immediately followed by an optional space and
    // `mm`. The number must be at the start of a token so we don't trip on
    // aperture digits like "F2.8" or extender markers like "1.4x".
    val regex = Regex("""(?<!\d)(\d+)(?:-(\d+))?\s*mm""", RegexOption.IGNORE_CASE)
    val m = regex.find(name) ?: return null to null
    val low = m.groupValues[1].toIntOrNull() ?: return null to null
    val high = m.groupValues[2].toIntOrNull()
    return if (high != null && high > low) null to (low..high) else low to null
}
