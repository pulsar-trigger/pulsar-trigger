# Canon CCAPI integration

Pulsar's Canon-over-Wi-Fi transport: drive an EOS R-series body directly over its built-in Wi-Fi, no ESP32 in the middle. This doc covers the **Pulsar-side design** — what's supported, how it fits the transport abstraction, and where the code lives.

> **NDA note.** Canon's CCAPI specification (Operation Guide + Reference) is NDA-covered and ships locally under `/Canon-API/`, outside this repo. The wire-level integration detail — exact endpoints, request/response payloads, version-specific quirks, the capability-matrix schema, per-model support matrices, and camera-side error strings — lives **only** in the private notes there (`/Canon-API/Pulsar-CCAPI-Integration-Notes.md`), never in this public repo. This file is deliberately limited to Pulsar's own architecture and rationale.

## Why CCAPI

- Works against stock Canon firmware on EOS R-series and recent DSLRs/PowerShots. No Magic Lantern, no camera-side build.
- Plain HTTP + JSON over Wi-Fi. No native SDK link, no platform-specific wrappers.
- Maps onto the same shutter / bulb / state primitives the ESP32 path uses, so it slots into the existing `CameraTransport` abstraction.

## What CCAPI is *not* good for

- **Sub-second bulb exposures.** Every bulb open/close costs two HTTP round-trips, with ~30–80 ms Wi-Fi jitter per round-trip. Fine for ≥1 s exposures (timing error <5%). For 1/30 s and shorter, use Timelapse mode (the camera's own shutter-speed setting) or the ESP32 transport. The wizards warn when `exposureMs < 1000` on a Canon transport.
- **Long unattended runs without prep.** Canon's Wi-Fi drops the connection after the body's auto-off timer. Disable auto-off before multi-hour astro nights. Pulsar reconnects for ~2 min after a dropout, but can't keep the session alive past auto-off on its own.
- **Multi-brand.** Canon only. Sony, Nikon, Fuji etc. each have their own protocols — separate transports if/when we add them.

## Supported bodies

In scope: **EOS R-series** running stock firmware with CCAPI activated (R5, R6 / R6 II, R7, R8, R10, R50, R100, R5 II, R1, RP). Other CCAPI-compatible Canon bodies (recent PowerShots, EOS Utility-class DSLRs) may also work — Pulsar **capability-detects per body** rather than gating on a model whitelist.

After connecting, Pulsar reads the body's advertised capability set, caches it, and the wizards check it before enabling features: a body that doesn't advertise the manual-shutter (bulb) capability has its bulb-based modes (Intervalometer / Astro / Dark Frame / Ramp) dimmed with an explanatory banner, while Timelapse and Manual still work. A "Capabilities…" menu item on each Canon card surfaces what the body advertises as a chip list.

## Activation (one-time, user-side)

Some bodies ship with CCAPI active in the Wi-Fi menu. Most need the user to run Canon's PC-side **CCAPI Activation Tool** with the camera plugged in via USB. After activation the camera's Wi-Fi settings menu shows `Camera Control API` alongside the standard `EOS Utility` / `Smartphone` modes. Pulsar doesn't automate activation — the scan screen's **Camera setup help** dialog walks the user through it.

## Discovery

Pulsar finds the camera on the LAN via standard **SSDP/UPnP** multicast, filtering for Canon's camera-control service, then reads the returned device description for the model name, a stable per-device UID (the key for saved nicknames / credentials), and the API base URL. (Service identifiers and descriptor field names: private notes.)

Implementation notes (Pulsar / Android side):

- Android `MulticastSocket` joins the multicast group; Pulsar holds a `WifiManager.MulticastLock` while listening (the OS otherwise drops multicast to apps that don't acquire one).
- Works in both **camera-AP mode** (phone joins the camera's AP) and **infrastructure mode** (camera and phone share a LAN).

Source: `transport/ccapi/CcapiDiscovery.kt`, `transport/ccapi/CameraDescription.kt`.

## API version & capability matrix

On connect, `CcapiClient.connect()` pins the highest API version the body supports, prefixes it onto every call, and caches that version's capability matrix; `client.supports(path, method)` queries the cache. The wizards gate bulb-based modes on the relevant capability so an unsupported body fails gracefully (dimmed UI) rather than erroring at run time. (Version list and matrix schema: private notes.)

## Authentication

The camera may require HTTP **Digest** auth (a standard, RFC 7616; configurable on the body, up to 3 accounts). Pulsar's flow:

1. **First connect** — try unauthenticated. On `401`, parse the `WWW-Authenticate: Digest …` challenge.
2. **Prompt** — `canonAuthPrompt` flows non-null; the scan screen pops a credentials dialog (username + password, show/hide toggle).
3. **Persist** — on a successful authenticated connect, credentials are stored in `SharedPreferences("pulsar_canon_creds")` keyed by the device UID, so the next session skips the prompt.
4. **Stale creds** — a stored set that yields `401` (user changed the body password) is dropped and re-prompted.

Digest is hand-rolled on `HttpURLConnection` (no OkHttp / okhttp-digest dependency): `MD5`, `MD5-sess`, `SHA-256`, `SHA-256-sess`, `qop=auth` (with `nc` + `cnonce`) and the older non-`qop` form. After the first 401-and-retry the challenge is cached and the `Authorization` header is pre-flighted so we don't pay the round-trip every call.

Source: `transport/ccapi/CcapiClient.kt` (`parseDigestChallenge`, `buildDigestHeader`).

## Mode mapping (Pulsar architecture)

Every wizard mode is an app-orchestrated coroutine loop in `PulsarViewModel.executeFlowStep`, dispatching to:

- `runCanonTimelapse` — camera owns exposure timing (single shutter pulse, app waits the interval).
- `runCanonBulb` — app owns timing (press → wait `exposureMs` → release → wait the gap, per shot).
- `runCanonRamp` — interpolated exposures per step.

Each bulb loop wraps the sequence in `try { … } finally { withContext(NonCancellable) { stopBulb() } }` so a cancelled flow can't leave the shutter open mid-exposure. Timelapse stores its pulse sentinel as `exposureMs = AppConfig.TIMELAPSE_PULSE_MS`; `executeFlowStep` dispatches to the timelapse path on that sentinel and the bulb path otherwise. (Exact per-mode call sequences: private notes.)

### Per-shot autofocus toggle

Shots can optionally trigger autofocus; Pulsar exposes this as a **per-preset switch** on the Intervalometer / Astro / Timelapse / Dark Frame / Ramp wizards — shown only on CCAPI, irrelevant on BLE. Defaults: bulb-based modes off (don't hunt on stars between shots, which would drift); Timelapse on (daylight subjects). Persisted in `FlowStep.useAutofocus` + `UserMode.Body.useAutofocus` and threaded through to the run loops.

### Star Focus Assist (Tools tab)

Four-step guided wizard for nailing pinpoint focus on stars before an astro run. CCAPI-only (gated on `canonTransport != null`):

1. **Prep** — instructions only: lens AF/MF switch to AF, mode dial to M (live view is disabled in Bulb).
2. **Aim** — start live view; user taps a bright star, a 32×32-px ROI locks on it.
3. **Focus** — per frame, Pulsar extracts the ROI and computes peak luminance (integer BT.601 luma approx): sharp star = high peak, defocused = lower. Six focus-step buttons drive the lens motor at fine→coarse increments.
4. **Lock** — live view stops to save battery; guidance to flip the lens to MF where possible, otherwise rely on the per-shot AF-off backstop. Don't touch the focus ring.

(Live-view payload + drive-focus specifics, including version-dependent fields: private notes.)

### Lens detection (Astro wizard)

Pulsar reads the mounted lens **name** from the camera and parses the focal length(s) with a regex (`(?<!\d)(\d+)(?:-(\d+))?\s*mm`):

- Prime: `RF16mm F2.8` → `focalMm = 16` (auto-fills the Astro focal-length field on a fresh Canon connection).
- Zoom: `RF24-105mm F4` → `zoomRangeMm = 24..105` shown as a hint chip (current zoom position isn't reported on older revisions — user types it).

Bodies that report a numeric focal length directly are honoured without name parsing. Loaded presets are never overridden.

### Add-by-IP fallback

Some Canon AP modes block multicast, or a body skips UPnP entirely. The scan screen offers a broadened discovery burst plus an **"Add by IP"** dialog that probes the API base URL directly (host:port, with sensible default ports tried in order). On success it fetches device metadata for a stable UID and adds the camera to the same list discovery would have. Manual entries survive scan restarts. (Probe endpoint + port order: private notes.)

## Status surfacing

Pulsar's wizards consume the same `StatusFrame` shape regardless of transport. The CCAPI path synthesizes frames simulator-style:

- Run-loop transitions write directly to `_status` (RUNNING during a shot, WAITING during the gap, IDLE before / after).
- A polling job overlays `batteryPct` (coarse camera battery-level labels mapped to percentages) and nudges a cumulative shot counter up when the camera reports added captures — so the body's own hardware-button presses are reflected too.

A small **battery chip** appears next to the StatusPill in `RunningView` whenever `batteryPct > 0` — gated this way so it stays hidden on the ESP32 path, where firmware doesn't currently report battery.

## Reconnect on dropout

Transient polling failures don't immediately end the session. After 5 consecutive failures (~10 s of error streak), the polling loop enters reconnect mode:

- `_canonReconnecting` flips true. The Trigger-tab banner morphs to `errorContainer` colours with a spinner and "Reconnecting to camera over Wi-Fi…" copy.
- The loop re-calls `client.connect()` every 3 s; cached digest state is preserved so re-auth is one round-trip.
- Success within 2 minutes: polling resumes, banner reverts. Timeout: session dropped, `_canonError = "dropped"`, user returns to the scan screen.

Manual disconnect cleans up: `disconnectCanon()` cancels the polling job, releases the transport (restoring any capability toggles it engaged), and clears `_canonTransport.value`.

## Error handling

Pulsar branches on standard HTTP status:

- `200` — success.
- `400` — bad parameter (one retry of an alternate request form where applicable, before bubbling up).
- `401` — auth needed → credentials dialog / stale-creds invalidation.
- `403` — logged, surfaced as a generic HTTP error.
- `404` — endpoint not on this body (the capability matrix should have caught it earlier).
- `503` — carries a camera-side reason (busy, wrong mode, mid-shot, focus or card errors), surfaced through the same path as BLE errors.

`CcapiClient.Result` is the sealed return type for every call: `Ok` / `Http(code, body)` / `NeedsAuth` / `Network(cause)`. Callers branch on it explicitly.

## Battery & power

CCAPI + Wi-Fi roughly halves a body's normal battery life. Rough field numbers for the EOS RP on a fresh LP-E17 (~1040 mAh):

| Scenario | ~Life | vs. baseline |
|---|---|---|
| Idle, no Wi-Fi | 6–8 h | 1× |
| CCAPI long-poll, no live view | 3–4 h | 2× |
| CCAPI + live view continuous (Star Focus) | 1–2 h | 4× |

For multi-hour astro sessions, USB-C power passthrough is the real answer — on the RP that's the Canon **DR-E18 dummy battery coupler + AC-E6N adapter** (or a third-party USB-C PD coupler with the same shape). Cold weather drops LP-E17 capacity ~30% below 0 °C.

Pulsar minimises avoidable drain: polling is one request every ~10 s (no worse than EOS Utility); live view is gated to Star Focus's Aim + Focus steps and auto-stops on Lock or screen exit; manual disconnect cleans up; the setup-help dialog nags about disabling the body's auto power off.

## File layout

| File | Purpose |
|------|---------|
| `transport/CameraTransport.kt` | Interface implemented by both BLE and CCAPI transports |
| `transport/ccapi/CanonCamera.kt` | Discovered-camera data class (UID, friendly name, access URL, etc.) |
| `transport/ccapi/CameraDescription.kt` | Fetches and parses the device description |
| `transport/ccapi/CcapiDiscovery.kt` | SSDP listener + probe; multicast lock; service filter |
| `transport/ccapi/CcapiClient.kt` | HTTP wrapper, version pin, digest auth (RFC 7616), capability lookup, byte-stream `getBytes` for JPEG frames |
| `transport/ccapi/CcapiTransport.kt` | `CameraTransport` impl — shutter, bulb lifecycle, polling, live view, drive-focus, lens info, version-aware payloads |
| `viewmodel/PulsarViewModel.kt` | `connectCanon` / `disconnectCanon`, polling job, reconnect loop, per-UID creds + nicknames, add-by-host probe, `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp`, `stopCanonLiveView` |
| `ui/screens/ScanScreen.kt` | Canon card, connect/auth/rename/capabilities dialogs, manual-add dialog, setup-help walkthrough |
| `ui/screens/StarFocusScreen.kt` | 4-step focus wizard (Prep / Aim / Focus / Lock) |
| `ui/components/AutofocusToggle.kt` | Per-shot AF toggle rendered on wizards when on CCAPI |

## References

- **`/Canon-API/`** (outside this repo) — Canon's NDA-covered CCAPI **Operation Guide** + **Reference**, plus **`Pulsar-CCAPI-Integration-Notes.md`**, which holds the full wire-level integration detail (endpoints, payloads, version quirks, per-model matrices, error strings) moved out of this public doc.
- These are **NDA-covered**: kept under `/Canon-API/` outside the repo. Never commit the Canon spec or the private notes; never copy Canon's text verbatim.
