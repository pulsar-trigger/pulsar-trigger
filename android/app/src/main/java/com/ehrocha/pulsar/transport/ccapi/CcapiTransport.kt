/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.transport.ccapi

import android.util.Log
import com.ehrocha.pulsar.canonble.CanonBleLog
import com.ehrocha.pulsar.transport.CameraImage
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
        private const val PATH_CONTENTS = "/contents"
        /** Canon pages content listings at 100 entries; a short page is last. */
        private const val CONTENTS_PAGE_SIZE = 100
        /** Diagnostics tag for the contents walk (mirrors PTP's PtpContent). */
        private const val CONTENT_TAG = "CcapiContent"
        private const val PATH_LIVEVIEW = "/shooting/liveview"
        private const val PATH_LIVEVIEW_FLIP = "/shooting/liveview/flip"
        private const val PATH_DRIVE_FOCUS = "/shooting/control/drivefocus"
        // Camera-params (v0.338) — three independently capability-gated
        // exposure settings.
        private const val PATH_ISO = "/shooting/settings/iso"
        private const val PATH_AV = "/shooting/settings/av"
        private const val PATH_TV = "/shooting/settings/tv"
    }

    override val kind = TransportKind.CCAPI

    private val client = CcapiClient(camera.accessUrl, credentials)

    // No wire-serialization mutex here — unlike the PTP transports, CCAPI's
    // wire is HTTP, and OkHttp queues concurrent calls per-host on its own
    // dispatcher. Methods on this class can be invoked from any coroutine
    // and OkHttp serialises the wire side appropriately.
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
    /** CCAPI honors the per-shot AF flag via `"af": true/false` on the
     *  `/shutterbutton[/manual]` POST body. */
    override val supportsAfToggle: Boolean = true

    override val supportsBulb: Boolean
        get() = client.supports(PATH_SHUTTER_MANUAL, "post")

    // The CCAPI protocol exposes endpoints for all four of these. The camera-
    // params wizard tab (ISO/Av/Tv) is the only UI that consumes
    // `supportsSettings` today and it's parked; flagging it true now keeps
    // the capability honest about what the transport can actually do.
    override val supportsSettings: Boolean = true
    override val supportsLiveView: Boolean = true
    override val supportsLensInfo: Boolean = true
    override val supportsBatteryReadout: Boolean = true

    // Per-setting gates — each setting is independently advertised in
    // the endpoint matrix, so the wizard's Camera tab hides rows per
    // body capability rather than all-or-nothing.
    override val supportsIso: Boolean get() = client.supports(PATH_ISO, "put")
    override val supportsAperture: Boolean get() = client.supports(PATH_AV, "put")
    override val supportsShutterSpeed: Boolean get() = client.supports(PATH_TV, "put")

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
    @Volatile override var lastLiveViewError: String? = null
        private set

    /** Begin a live-view session. Try a sequence of `liveviewsize` values —
     *  older bodies (EOS RP) only accept `small`, newer ones accept `medium`
     *  and `large` too. First one that gets a 200 wins. Returns true on
     *  success; on failure [lastLiveViewError] holds the most informative
     *  response body we saw. */
    override suspend fun startLiveView(): Boolean {
        if (!_connected.value) return false
        CanonBleLog.i(TAG, "startLiveView (ccapi ver=${client.version})")
        // The CCAPI liveview-start body is `liveviewsize` + the optional
        // `cameradisplay` (on/keep/off). The old code sent `cameraposition`,
        // which isn't a liveview field — the RP rejected the whole request
        // ("Invalid parameter"). Try the documented payload (with
        // cameradisplay) first, then a size-only fallback for bodies that want
        // neither; first 200 wins.
        val sizes = listOf("small", "medium", "large")
        var lastError: String? = null
        for (size in sizes) {
            val bodies = listOf(
                JSONObject().put("liveviewsize", size).put("cameradisplay", "on"),
                JSONObject().put("liveviewsize", size),
            )
            for (body in bodies) {
                val r = client.post(PATH_LIVEVIEW, body)
                if (r is CcapiClient.Result.Ok) {
                    CanonBleLog.i(TAG, "liveview started @ $size body=$body")
                    lastLiveViewError = null
                    return true
                }
                logResult("liveview start @ $size $body", r)
                lastError = when (r) {
                    is CcapiClient.Result.Http -> "HTTP ${r.code}: ${r.body.take(200)}"
                    is CcapiClient.Result.NeedsAuth -> "auth required"
                    is CcapiClient.Result.Network -> "network error: ${r.cause.message}"
                    else -> "unknown"
                }
            }
        }
        lastLiveViewError = lastError
        return false
    }

    /** End the live-view session. Mirrors start's payload — `liveviewsize:off`
     *  plus `cameradisplay:keep` (don't disturb the body's screen), falling
     *  back to size-only. */
    override suspend fun stopLiveView() {
        if (!_connected.value) return
        val withDisplay = JSONObject().put("liveviewsize", "off").put("cameradisplay", "keep")
        if (client.post(PATH_LIVEVIEW, withDisplay) is CcapiClient.Result.Ok) return
        logResult("liveview stop", client.post(PATH_LIVEVIEW, JSONObject().put("liveviewsize", "off")))
    }

    /** Fetch one JPEG frame from the running live-view session. Returns the
     *  raw bytes or null on transport error. Canon's flip endpoint returns
     *  the most recent frame on each request — the caller paces frame rate. */
    override suspend fun getLiveViewFrame(): ByteArray? {
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
    override suspend fun driveFocus(action: String) {
        if (!_connected.value) return
        val body = JSONObject().put("action", action)
        logResult("drivefocus $action", client.post(PATH_DRIVE_FOCUS, body))
    }

    /** Read the currently mounted lens via `/devicestatus/lens`. Older
     *  bodies (EOS RP) return only `{mount, name}` — no native focal-length
     *  field — so the focal length(s) are parsed from the model name with
     *  [com.ehrocha.pulsar.transport.parseFocalFromName] as a fallback.
     *  Returns null on network / parse failure. */
    override suspend fun getLensInfo(): com.ehrocha.pulsar.transport.LensInfo? {
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
        val (parsedFocal, parsedRange) = com.ehrocha.pulsar.transport.parseFocalFromName(name)
        return com.ehrocha.pulsar.transport.LensInfo(
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

    // Routed through CanonBleLog so CCAPI op results (shutter / bulb / live
    // view / settings) show in the in-app diagnostics dump, like the PTP path.
    private fun logResult(tag: String, r: CcapiClient.Result<*>) {
        when (r) {
            is CcapiClient.Result.Ok -> CanonBleLog.d(TAG, "$tag ok")
            is CcapiClient.Result.Http -> CanonBleLog.w(TAG, "$tag HTTP ${r.code}: ${r.body.take(120)}")
            is CcapiClient.Result.NeedsAuth -> CanonBleLog.w(TAG, "$tag needs auth")
            is CcapiClient.Result.Network -> CanonBleLog.w(TAG, "$tag network: ${r.cause.message}")
        }
    }

    // ── Camera-params: ISO / aperture / shutter speed ──────────────────────

    override suspend fun listIsoValues(): List<String> = listAbility(PATH_ISO)
    override suspend fun listApertureValues(): List<String> = listAbility(PATH_AV)
    override suspend fun listShutterSpeedValues(): List<String> = listAbility(PATH_TV)

    /** Read the `ability` array from a CCAPI settings endpoint. CCAPI
     *  shape: `{"value": "1600", "ability": ["AUTO", "100", "200", …]}`. */
    private suspend fun listAbility(path: String): List<String> {
        if (!_connected.value || !client.supports(path, "get")) return emptyList()
        val r = client.get(path)
        if (r !is CcapiClient.Result.Ok) { logResult("listAbility($path)", r); return emptyList() }
        return try {
            val arr = JSONObject(r.value).optJSONArray("ability") ?: return emptyList()
            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
        } catch (_: Exception) { emptyList() }
    }

    override suspend fun readCurrentSettings(): com.ehrocha.pulsar.transport.CameraSettings {
        if (!_connected.value) return com.ehrocha.pulsar.transport.CameraSettings.EMPTY
        return com.ehrocha.pulsar.transport.CameraSettings(
            iso = readValue(PATH_ISO),
            aperture = readValue(PATH_AV),
            shutterSpeed = readValue(PATH_TV),
        )
    }

    private suspend fun readValue(path: String): String? {
        if (!client.supports(path, "get")) return null
        val r = client.get(path)
        if (r !is CcapiClient.Result.Ok) return null
        return try {
            JSONObject(r.value).optString("value").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    override suspend fun applySettings(
        settings: com.ehrocha.pulsar.transport.CameraSettings,
    ): com.ehrocha.pulsar.transport.SettingsApplyResult {
        if (!_connected.value || !settings.hasAny) {
            return com.ehrocha.pulsar.transport.SettingsApplyResult.NOOP
        }
        var appliedIso: String? = null
        var skippedIso: String? = null
        var appliedAv: String? = null
        var skippedAv: String? = null
        var appliedTv: String? = null
        var skippedTv: String? = null
        settings.iso?.let { v ->
            if (writeValue(PATH_ISO, v)) appliedIso = v else skippedIso = v
        }
        settings.aperture?.let { v ->
            if (writeValue(PATH_AV, v)) appliedAv = v else skippedAv = v
        }
        settings.shutterSpeed?.let { v ->
            if (writeValue(PATH_TV, v)) appliedTv = v else skippedTv = v
        }
        return com.ehrocha.pulsar.transport.SettingsApplyResult(
            applied = com.ehrocha.pulsar.transport.CameraSettings(appliedIso, appliedAv, appliedTv),
            skipped = com.ehrocha.pulsar.transport.CameraSettings(skippedIso, skippedAv, skippedTv),
        )
    }

    private suspend fun writeValue(path: String, value: String): Boolean {
        if (!client.supports(path, "put")) return false
        val r = client.put(path, JSONObject().put("value", value))
        logResult("PUT $path=$value", r)
        return r is CcapiClient.Result.Ok
    }

    // ── Photo transfer ──────────────────────────────────────────────────
    // Walk the contents tree (storages → directories → paginated file lists),
    // keeping still images. Thumbnails via ?kind=thumbnail, full files
    // streamed via ?kind=main. CCAPI lists oldest-first, so reverse for the
    // newest-first gallery.
    override val supportsContentTransfer: Boolean = true

    override suspend fun listContents(): List<CameraImage> {
        val out = mutableListOf<CameraImage>()
        // Seed from the advertised endpoint URL (correct version baked in),
        // NOT a pinned-version prefix — /contents lives under a specific
        // CCAPI version and 404s otherwise.
        val contentsUrl = client.endpointUrl(PATH_CONTENTS)
        if (contentsUrl == null) {
            CanonBleLog.w(CONTENT_TAG, "no $PATH_CONTENTS endpoint advertised by this body")
            return out
        }
        val storages = client.getContentPaths(contentsUrl)
        CanonBleLog.i(CONTENT_TAG, "GET $contentsUrl -> ${storages.size} storages")
        for (storage in storages) {
            val dirs = client.getContentPaths(storage)
            CanonBleLog.i(CONTENT_TAG, "$storage -> ${dirs.size} dirs")
            for (dir in dirs) {
                var page = 1
                var dirImages = 0
                while (true) {
                    val files = client.getContentPaths("$dir?kind=list&page=$page")
                    if (files.isEmpty()) break
                    for (f in files) {
                        val name = f.substringAfterLast('/').substringBefore('?')
                        if (CameraImage.isImageName(name)) {
                            out += CameraImage(
                                id = f,
                                fileName = name,
                                byteSize = 0L,           // CCAPI list omits size; fetched lazily if needed
                                isRaw = CameraImage.isRawName(name),
                            )
                            dirImages++
                        }
                    }
                    if (files.size < CONTENTS_PAGE_SIZE) break
                    page++
                }
                CanonBleLog.i(CONTENT_TAG, "$dir -> $dirImages images")
            }
        }
        CanonBleLog.i(CONTENT_TAG, "listContents -> ${out.size} images")
        return out.asReversed()
    }

    override suspend fun getThumbnail(image: CameraImage): ByteArray? =
        when (val r = client.getContentBytes(image.id, "?kind=thumbnail")) {
            is CcapiClient.Result.Ok -> r.value
            else -> { CanonBleLog.d(CONTENT_TAG, "thumbnail(${image.fileName}) -> $r"); null }
        }

    override suspend fun downloadImage(
        image: CameraImage,
        sink: java.io.OutputStream,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val ok = client.streamContent(image.id, "?kind=main", sink, onProgress)
        if (!ok) CanonBleLog.w(CONTENT_TAG, "download(${image.fileName}) failed")
        return ok
    }
}

