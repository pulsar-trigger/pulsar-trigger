# Canon CCAPI integration

Pulsar's second transport: drive a Canon EOS R-series body directly over its built-in WiFi, no ESP32 in the middle. This doc covers the shipped implementation — what's supported, where the code lives, and how it fits with the BLE path.

The Canon-side spec is NDA-covered and ships locally under `/Canon-API/` (Operation Guide rev 1.3 + Reference v1.40 rev 1.4). This file is Pulsar's design notes, not a reproduction of Canon's spec.

## Why CCAPI

- Works against stock Canon firmware on EOS R-series and recent DSLRs/PowerShots. No Magic Lantern, no camera-side build.
- Plain HTTP + JSON. No native SDK link, no platform-specific wrappers.
- Same shutter / bulb / state primitives the ESP32 path uses — maps cleanly onto the existing transport abstraction.

## What CCAPI is *not* good for

- **Sub-second bulb exposures.** Every bulb open/close costs two HTTP round-trips, with ~30–80 ms WiFi jitter per round-trip. Fine for ≥1 s exposures (timing error <5%). For 1/30 s and shorter you should either use Timelapse mode (the camera's own shutter speed setting) or the ESP32 transport. The wizards surface a warning when `exposureMs < 1000` on a Canon transport.
- **Long unattended runs without prep.** Canon's WiFi drops the connection after the camera's auto-off timer. Set the body's auto-off to disabled before multi-hour astro nights. Pulsar will attempt to reconnect for ~2 min after a dropout, but it can't keep the session alive past auto-off on its own.
- **Multi-brand.** Canon only. Sony, Nikon, Fuji etc. each have their own protocols — separate transports if/when we add them.

## Supported bodies

In scope: **EOS R-series** running stock firmware with CCAPI activated (R5, R6 / R6 II, R7, R8, R10, R50, R100, R5 II, R1, RP). Other CCAPI-compatible Canon bodies (recent PowerShots, EOS Utility-class DSLRs) may also work — Pulsar capability-detects per body rather than gating on a model whitelist.

Per-body differences exist:

1. After connect, Pulsar `GET`s `/ccapi` to retrieve the supported endpoint matrix for that body. The result is cached on the client.
2. The wizards check capability before letting the user start. If `/shooting/control/shutterbutton/manual` isn't advertised, the bulb-based modes (Intervalometer/Astro/DarkFrame/Ramp) are dimmed and the banner explains why. Timelapse and Manual still work.
3. The "Capabilities…" menu item on each Canon card surfaces the matrix as a chip list — bulb / shooting-mode / dial-ignore / polling — so the user knows what their body advertises before they connect.

## Activation (one-time, user-side)

Some bodies ship with CCAPI active in the Wi-Fi menu. Most need the user to run Canon's PC-side **CCAPI Activation Tool** with the camera plugged in via USB. After activation the camera's Wi-Fi settings menu shows `Camera Control API` alongside the standard `EOS Utility` / `Smartphone` modes. Pulsar doesn't automate activation — the scan screen's **Camera setup help** dialog walks the user through it.

## Discovery: SSDP / UPnP

The camera advertises itself as a UPnP device:

- Multicast group: `239.255.255.250:1900`.
- Camera periodically multicasts `NOTIFY` with service type `urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1`.
- Pulsar actively probes with `M-SEARCH` on the same service type at scan start.
- Both responses carry a `Location:` header pointing at `http://[camera-ip]:[port]/upnp/CameraDevDesc.xml`.

The filter requires **both** `schemas-canon-com` and `ICPO-CameraControlAPIService` in the service URN, so unrelated UPnP devices on the LAN don't slip through.

The device description XML supplies the fields we need:

- `<friendlyName>` — model name, e.g. "EOS R10"
- `<UDN>` — stable UUID for the device, the primary key for saved nicknames / credentials
- `<X_accessURL>` — the CCAPI base URL (Canon-specific UPnP extension)
- `<X_deviceNickname>` — optional user-set nickname on the body

Implementation notes:

- Android `MulticastSocket` joins the group. Pulsar holds a `WifiManager.MulticastLock` while listening (otherwise the OS drops multicast traffic to apps that don't acquire one).
- Camera AP mode: the camera is the access point; the phone joins its AP and then sees SSDP on the local subnet.
- Infrastructure mode: camera and phone share a LAN. SSDP works the same.

Source: `transport/ccapi/CcapiDiscovery.kt`, `transport/ccapi/CameraDescription.kt`.

## Endpoint base URL & version pinning

Once we have the `accessUrl` from the device description, `CcapiClient.connect()`:

1. `GET /ccapi` and parse the JSON of supported version → endpoint-list.
2. Picks the highest version Pulsar recognises (in order: `ver140`, `ver130`, `ver120`, `ver110`, `ver100`).
3. Caches the endpoint matrix for the pinned version. `client.supports(path, method)` queries this cache.

All subsequent calls prefix `/ccapi/<pinned_version>` onto the path.

## Authentication

The camera may require HTTP Digest auth (configurable on the body, up to 3 accounts). Pulsar's flow:

1. **First connect** — try unauthenticated. If we get `401 Unauthorized`, parse the `WWW-Authenticate: Digest …` challenge from the response.
2. **Prompt** — `canonAuthPrompt` flows non-null; the scan screen pops a credentials dialog (username + password, with a show/hide toggle).
3. **Persist** — on a successful authenticated connect, the credentials are stored in `SharedPreferences("pulsar_canon_creds")` keyed by UDN, so the next session skips the prompt.
4. **Stale creds** — if a stored credential set yields 401 (user changed password on the body), Pulsar drops the saved entry and re-prompts.

Digest is hand-rolled on `HttpURLConnection` — no OkHttp / okhttp-digest dependency. Algorithms supported: `MD5`, `MD5-sess`, `SHA-256`, `SHA-256-sess`. `qop=auth` is supported (with `nc` + `cnonce`); the older non-`qop` form is also supported. Nonce-count auto-increments per request.

After the first successful 401-and-retry, Pulsar caches the challenge and pre-flights the `Authorization` header on subsequent requests so we don't pay the round-trip every call.

Source: `transport/ccapi/CcapiClient.kt` (look for `parseDigestChallenge`, `buildDigestHeader`).

## Endpoint subset used by Pulsar

Path prefix: `http://[ip]:[port]/ccapi/<version>`.

### Shutter control

- **`POST /shooting/control/shutterbutton`** — one-shot, full press + release. Body: `{"af": true|false}`. Used by **Timelapse** (camera owns exposure timing) and by **Manual hold** when the body lacks the manual endpoint.

- **`POST /shooting/control/shutterbutton/manual`** — explicit press lifecycle. Body: `{"action": "half_press" | "full_press" | "release", "af": bool}`. Used by **Intervalometer / Astro / Dark Frame / Ramp** (bulb mode) and by **Manual hold** on touch-down / touch-up.

### Mode setting

- **`PUT /shooting/settings/shootingmode`** — set capture mode. Pulsar uses `{"value": "bulb"}` before bulb-based runs and `{"value": "m"}` to switch back.
- **`POST /shooting/control/ignoreshootingmodedialmode`** — capability-detected per body. On bodies with a physical mode dial, the dial overrides `shootingmode`. Pulsar calls this with `{"action":"on"}` before the mode PUT (when supported) so the PUT actually takes effect, and restores `{"action":"off"}` on transport release.

### State polling

- **`GET /event/polling?timeout=short`** — long-poll for state changes. Camera holds the connection open for ~10 s; returns JSON with the fields that changed. Pulsar reads `battery` (mapped to percentage) and `addedcontents` (length added to a running shot counter so the body's own button presses are reflected too).
- **`GET /event/polling?continue=on`** — older-body alternative (`ver100`). Pulsar picks the form based on the pinned version; on `HTTP 400` the other form is tried as a fallback once.
- `DELETE /event/polling` would cancel an outstanding poll; Pulsar doesn't currently use it since the long-poll completes within ~10 s anyway.

Polling failures don't immediately drop the session — Pulsar tolerates 5 consecutive failures before entering reconnect mode.

### Device info

- **`GET /ccapi`** — supported endpoint matrix (capability discovery, called at connect time).
- **`GET /deviceinformation`** — model, firmware, serial (not currently consumed; kept for future "show camera details").
- **`GET /devicestatus/battery`** — battery state (also delivered via polling).

## Mode mapping

How each Pulsar wizard mode translates to CCAPI calls:

| Wizard mode | Pre-roll | Per-shot loop | Source |
|-------------|----------|---------------|--------|
| **Timelapse** | (optional) `shootingmode=m` | `POST /shutterbutton {"af":true}` → wait `intervalMs` | `runCanonTimelapse` |
| **Intervalometer** | `shootingmode=bulb` | `POST /shutterbutton/manual full_press` → wait `exposureMs` → `release` → wait `intervalMs` | `runCanonBulb` |
| **Astro** | `shootingmode=bulb` | same as Intervalometer; `exposureMs` from NPF/500/400 rule | `runCanonBulb` |
| **Dark Frame** | `shootingmode=bulb` | same as Intervalometer | `runCanonBulb` |
| **Ramp** | `shootingmode=bulb` | per step: full_press → wait interpolated `exposureMs` → release → wait `intervalMs` | `runCanonRamp` |
| **Manual hold** | none | `shutterDown` → `full_press`, `shutterUp` → `release` | `vm.shutterDown` / `shutterUp` |

All these are app-orchestrated coroutine loops in `PulsarViewModel.executeFlowStep`. Each loop wraps the bulb sequence in `try { … } finally { withContext(NonCancellable) { stopBulb() } }` so a cancelled flow can't leave the shutter open mid-exposure.

The Timelapse wizard stores its pulse sentinel as `exposureMs = AppConfig.TIMELAPSE_PULSE_MS`. `executeFlowStep(FlowStep.Intervalometer)` dispatches to `runCanonTimelapse` when it sees that sentinel (camera owns timing) and `runCanonBulb` otherwise (app owns timing).

## Status surfacing

Pulsar's wizards consume the same `StatusFrame` shape regardless of transport. The CCAPI path synthesizes frames simulator-style:

- Run-loop transitions write directly to `_status` (RUNNING during a shot, WAITING during the gap, IDLE before / after).
- The polling job overlays `batteryPct` from `event/polling` battery levels (numeric `85%` strings parsed; `full`/`high`/`half`/`low`/`quarter`/`charge` mapped to coarse percentages).
- `addedcontents` length feeds a cumulative shot counter; if our counter falls behind (e.g. the user fired with the camera's hardware button), the polling job nudges `shotsTaken` up.

A small **battery chip** appears next to the StatusPill in `RunningView` whenever `batteryPct > 0` — gated this way so it stays hidden on the ESP32 path where firmware doesn't currently report battery.

## Reconnect on dropout

Long-poll failures don't immediately end the session. After 5 consecutive failures (~10 s of error streak), the polling loop enters reconnect mode:

- `_canonReconnecting` flips to true. The Trigger-tab banner morphs to `errorContainer` colors with a spinner and "Reconnecting to camera over WiFi…" copy.
- The loop re-calls `client.connect()` every 3 s. Cached digest state is preserved so re-auth is one round-trip.
- On a successful re-probe within 2 minutes: polling resumes; banner reverts.
- On timeout: session is dropped, `_canonError = "dropped"`, user returns to the scan screen.

Manual disconnect cleans up cleanly — `disconnectCanon()` cancels the polling job, releases the transport (sets `ignoreshootingmodedialmode` back to `off` if it was engaged), and clears `_canonTransport.value`.

## Error handling

HTTP status codes Pulsar branches on:

- `200` — success.
- `400` — bad parameter. For `/event/polling` the alternate query form is tried once before bubbling up.
- `401` — auth needed. Triggers the credentials dialog (or stale-creds invalidation).
- `403` — CORS / origin mismatch. Rare on a native client; logged and surfaced as a generic HTTP error.
- `404` — endpoint not on this body. Pulsar caught this earlier via `supports()` for the endpoints it cares about; a 404 here means the matrix lied.
- `503` — `Device busy`, `Mode not supported`, `During shooting`, `Out of focus`, `Can not write to card`, etc. Surfaced via the same error path as BLE errors.

`CcapiClient.Result` is the sealed return type for every call: `Ok` / `Http(code, body)` / `NeedsAuth` / `Network(cause)`. Callers branch on it explicitly.

## File layout

| File | Purpose |
|------|---------|
| `transport/CameraTransport.kt` | Interface implemented by both BLE and CCAPI transports |
| `transport/ccapi/CanonCamera.kt` | Discovered-camera data class (UDN, friendly name, access URL, etc.) |
| `transport/ccapi/CameraDescription.kt` | Fetches and parses `CameraDevDesc.xml` |
| `transport/ccapi/CcapiDiscovery.kt` | SSDP listener + M-SEARCH; multicast lock; service-URN filter |
| `transport/ccapi/CcapiClient.kt` | HTTP wrapper, version pin, digest auth (RFC 7616), capability lookup |
| `transport/ccapi/CcapiTransport.kt` | `CameraTransport` impl — shutter, bulb lifecycle, polling |
| `viewmodel/PulsarViewModel.kt` | `connectCanon` / `disconnectCanon`, polling job, reconnect loop, per-UDN creds + nicknames, `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp` |
| `ui/screens/ScanScreen.kt` | Canon card, connect/auth/rename/capabilities dialogs, setup-help walkthrough |

## References

- `Canon-API/CameraControlAPI_OperationGuide_EN_rev_1.3/CameraControlAPI_OperationGuide_EN.pdf` — Canon's CCAPI activation + Wi-Fi setup guide (25 pages).
- `Canon-API/CameraControlAPI_Reference_v140_EN_rev.1.4/CameraControlAPI_Reference_v140_EN_rev.1.4.pdf` — endpoint catalogue + per-model support matrices (925 pages).
- Both are **NDA-covered**: kept under `/Canon-API/` outside the repo; never commit, never copy text verbatim.
