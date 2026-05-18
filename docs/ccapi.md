# Canon CCAPI integration

Pulsar's second supported transport: speak directly to a Canon EOS R-series
camera over its built-in WiFi, no ESP32 in the middle. This document
describes the protocol subset we use and how it plugs into the existing
trigger architecture. The canonical Canon docs are NDA-covered and shipped
locally under `/Canon-API/` (Operation Guide rev 1.3 + Reference v1.40
rev 1.4) — this file is our Pulsar-specific design notes, not a reproduction
of Canon's spec.

## Why CCAPI

- Stock Canon firmware on EOS R5 / R6 / R7 / R8 / R10 / R50 / R100 and several
  recent DSLRs / PowerShots. No Magic Lantern required. No camera-side build.
- Plain HTTP+JSON. Any language can speak it. No native SDK link.
- Same shutter / bulb / state-polling primitives the ESP32 path uses — maps
  cleanly into our existing transport abstraction.

## What CCAPI is NOT good for

- **Sub-second exposures** — every bulb open/close is two HTTP round-trips,
  with ~30–80 ms WiFi jitter per round-trip. Fine for ≥1 s exposures
  (timing error <5%). For 1/30 s and shorter you must use the camera's own
  shutter speed setting and the single-shot `shutterbutton` endpoint
  (camera owns the timing), or fall back to the ESP32.
- **Long unattended runs** — Canon's WiFi drops the connection after the
  camera's auto-off timer. Astro nights of 4+ hours need careful auto-off
  handling. ESP32 BLE is more resilient for this case.
- **Multi-brand**: Canon only. Sony, Nikon, Fuji etc. each have their own
  protocols (separate transports if/when we add them).

## Target bodies

Initial scope: **Canon EOS R-series** (R5, R6 / R6 II, R7, R8, R10, R50, R100,
R5 II, R1, R6 Mark II) running stock firmware with CCAPI activated.

Per-body capability differences exist. Section 6.3 of the Reference PDF has
the full matrix of which endpoints each model supports. Our client must:

1. After connect, `GET /ccapi` to retrieve the supported endpoint list for
   that body.
2. Cache it.
3. Gracefully degrade — if a body lacks `/shutterbutton/manual`, fall back
   to single-shot mode with camera-owned shutter timing.

## Activation (one-time, user-side)

Some bodies ship with CCAPI active in the Wi-Fi menu. Others need the user
to run Canon's PC-side **CCAPI Activation Tool** with the camera plugged in
via USB. After activation the camera's Wi-Fi settings menu shows
`Camera Control API` alongside the standard `EOS Utility` / `Smartphone`
modes. We don't automate this; we surface it as a one-line setup instruction
in the connect-via-WiFi flow.

## Discovery: SSDP / UPnP

The camera advertises itself as a UPnP device.

- Multicast group: `239.255.255.250:1900`
- Camera periodically multicasts `NOTIFY` with service type
  `urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1`
- Client can actively probe with `M-SEARCH` (same service type)
- Both responses include a `Location:` header pointing at
  `http://[camera-ip]:[port]/upnp/CameraDevDesc.xml`

The device description XML has the fields we need:

- `<friendlyName>` — model name, e.g. "EOS R10"
- `<UDN>` — stable UUID for the device, our preferred identifier
- `<X_accessURL>` — the CCAPI base URL (custom Canon extension)
- `<X_deviceNickname>` — user-set nickname (optional)

Implementation notes:

- Android `MulticastSocket` joins the group; we must hold a
  `WifiManager.MulticastLock` while listening (otherwise the OS drops
  multicast traffic).
- Camera AP mode: the camera is the access point. Phone joins the AP, then
  scans the local network for the SSDP advertisement.
- Infrastructure mode: camera and phone share a LAN. SSDP works the same.

## Endpoint base URL & versions

Once we have the access URL from `CameraDevDesc.xml`:

- `GET /ccapi` returns the supported API versions and endpoints for this body.
- Versions are `ver100`, `ver110`, `ver120`, `ver130`, `ver140`.
- Pulsar pins to the highest version the camera reports that satisfies our
  needs. Most R-series support `ver110` or newer.

## Authentication

Optional HTTP digest auth, configurable on the camera side (up to 3
accounts). Pulsar's flow:

- First connect — try unauthenticated. If we get `401 Unauthorized`, prompt
  the user for credentials.
- Credentials stored in SharedPreferences keyed by the camera's UDN.
- The Pulsar HTTP client uses an OkHttp interceptor for digest. No need to
  roll our own; the digest algorithm is RFC 7616.

## Endpoint subset used by Pulsar

Path prefix: `http://[ip]:[port]/ccapi/[version]`

### Shutter control

- `POST /shooting/control/shutterbutton` — one-shot, full press + release.
  Body: `{"af": true | false}`. Used by **Timelapse** mode and any path where
  the camera owns the exposure timing.

- `POST /shooting/control/shutterbutton/manual` — explicit press lifecycle.
  Body: `{"action": "half_press" | "full_press" | "release", "af": bool}`.
  Used by **Intervalometer / Astro / Dark Frame / Ramp** (bulb mode) and
  **Manual hold**.

### Mode setting

- `PUT /shooting/settings/shootingmode` — set capture mode. We use
  `{"value": "bulb"}` before bulb-based modes, and `{"value": "m"}` (or leave
  alone) before Timelapse.
- Some bodies have a physical mode dial that overrides this. On those we
  must call `POST /shooting/control/ignoreshootingmodedialmode {"action":"on"}`
  first, then change the mode, then turn the dial-ignore back off when done.
  Capability-detected per body.

### State polling

- `GET /event/polling?timeout=short` — long-poll for state changes.
  `timeout` values: `immediately` (no wait), `short` (~10 s), `long` (~30 s).
  Older bodies use the `?continue=on` form which leverages HTTP 100-continue
  instead.
- Returns JSON with only the fields that changed since the last poll.
- Fields we read: `battery` (level + quality), `recordable`, `temperature`,
  `addedcontents` (so we know a shot fired).
- One outstanding poll at a time per camera. `DELETE /event/polling` cancels.

### Device info

- `GET /ccapi` — supported endpoint matrix (capability discovery).
- `GET /deviceinformation` — model name, firmware version, serial.
- `GET /devicestatus/battery` — current battery state (also delivered via
  polling).

## Pulsar mode → CCAPI mapping

| Wizard mode | Pre-roll | Per-shot loop body |
|---|---|---|
| Timelapse | optional `shootingmode = m` | `POST /shutterbutton {"af":true}` → wait `intervalMs` |
| Intervalometer | `shootingmode = bulb` | `POST /shutterbutton/manual {"action":"full_press","af":true}` → wait `exposureMs` → `POST /shutterbutton/manual {"action":"release"}` → wait `intervalMs` |
| Astro | same as Intervalometer; `exposureMs` computed from NPF/400/500 | same |
| Dark Frame | `shootingmode = bulb`; lens-cap reminder | same |
| Ramp | `shootingmode = bulb`; `exposureMs` interpolated per step | same |
| Manual (hold) | none | `full_press` on touch-down, `release` on touch-up |

Status updates: every wizard's `RunningView` consumes the existing
`StatusFrame` shape. The CCAPI transport translates `/event/polling`
responses into that shape — `shotsTaken` derived from `addedcontents.length`
or our own counter, `state` derived from request lifecycle (RUNNING during
the full-press window, WAITING during the gap, IDLE before / after).

## Timing budget

Bulb open + close costs two HTTP requests, each ~30–80 ms WiFi RTT plus
camera response latency. Total ≈ 100–200 ms per shot of overhead, not in
the exposure duration but in the gap. We don't subtract that from the
user-set `intervalMs` — if the user set 2 s gap, the actual cycle is
~2.1 s. Documented in the UI hint, not silently compensated.

For sub-second exposures via bulb, the timing error is significant (~10–20 %
at 1 s, useless below). Pulsar shows a warning when the user sets exposure
< 1 s on a CCAPI transport, and recommends Timelapse mode (camera owns
exposure) or switching to the ESP32 transport.

## Error handling

HTTP status codes used:

- `200` — success
- `400` — bad parameter (we should validate client-side)
- `401` — auth needed
- `403` — CORS / origin mismatch (rare for native client)
- `404` — endpoint not on this body
- `503` — `Device busy`, `Mode not supported`, `During shooting`,
  `Out of focus`, `Can not write to card`. Pulsar surfaces these via the
  same error path as BLE errors.

## Transport abstraction

The wizards (`Intervalometer2`, `Astro2`, etc.) don't know which transport
is active. They call `vm.startFlow()` and observe `vm.runState` /
`vm.status`. A new `CameraTransport` interface sits between the wizard
layer and the actual I/O:

```kotlin
interface CameraTransport {
    val label: StateFlow<String>            // "M5Stack Core2" / "EOS R10"
    val connected: StateFlow<Boolean>
    val status: StateFlow<CameraStatus?>    // unified status shape

    suspend fun setShutterMode(bulb: Boolean)
    suspend fun fireShutter(af: Boolean = true)
    suspend fun startBulb(af: Boolean = true)
    suspend fun stopBulb()
    suspend fun stop()                      // abort current operation
}
```

Two implementations live side-by-side:

- `BleEspTransport` — wraps the existing `BleController`, sends TLV v2
  commands over GATT.
- `CcapiTransport` — HTTP client, talks to the camera's
  `http://[ip]:[port]/ccapi/[version]` base URL.

The viewmodel holds a `MutableStateFlow<CameraTransport>`; only one is
active at a time. The scan screen lists devices from both discovery
sources — BLE scanner finds Pulsar triggers, SSDP listener finds CCAPI
cameras — and tapping a device sets it as the active transport.

## Files (planned)

- `transport/CameraTransport.kt` — interface + `CameraStatus` data class
- `transport/BleEspTransport.kt` — refactor of existing BLE path
- `transport/ccapi/CcapiDiscovery.kt` — SSDP listener + M-SEARCH
- `transport/ccapi/CameraDescription.kt` — UPnP XML parser
- `transport/ccapi/CcapiClient.kt` — HTTP wrapper, digest auth, version pin
- `transport/ccapi/CcapiTransport.kt` — implements `CameraTransport`

Scan card shows both kinds with a transport-distinguishing icon
(Bluetooth glyph for BLE Pulsar, WiFi glyph for CCAPI cameras).

## Build phases

1. **Discovery + connect (this phase)**. SSDP listener, XML parse, list
   Canon cameras in the scan card alongside the ESP32 BLE scan results.
   Tapping a Canon entry connects via HTTP and confirms `GET /ccapi` works.
   No wizard wiring yet.
2. **Transport abstraction + Timelapse**. Extract the `CameraTransport`
   interface, port `BleController` behind it, implement `CcapiTransport`
   for the single-shot `shutterbutton` path. Timelapse wizard works
   end-to-end against an EOS R body.
3. **Bulb support**. Add `shutterbutton/manual` + `shootingmode` to
   `CcapiTransport`. Intervalometer / Astro / Dark Frame / Ramp all
   working.
4. **Polish**. Manual hold, WiFi reconnect on dropout, per-body capability
   detection, sub-second-exposure warning, in-app explainer for "join the
   camera's WiFi AP".

## References

- `Canon-API/CameraControlAPI_OperationGuide_EN_rev_1.3/CameraControlAPI_OperationGuide_EN.pdf`
  — Canon's CCAPI activation + Wi-Fi setup guide (25 pages).
- `Canon-API/CameraControlAPI_Reference_v140_EN_rev.1.4/CameraControlAPI_Reference_v140_EN_rev.1.4.pdf`
  — full endpoint catalogue + per-model support matrices (925 pages).
- Both are NDA — keep under `/Canon-API/`, do not commit, do not copy text
  verbatim into this repo.
