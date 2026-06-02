# Pulsar

An open-source camera intervalometer and trigger system for DSLR and mirrorless cameras. **Five transports**, one app — Pulsar talks to your camera however it happens to be connected:

- **ESP32 firmware** ↔ BLE — wired remote-release driver. Works with any camera that has a remote-release port (Canon, Nikon, Sony, Fuji, …). Sub-millisecond accuracy, no Wi-Fi required.
- **Canon CCAPI** ↔ Wi-Fi — drives EOS R-series bodies directly over HTTP. No ESP32 in the middle. Live view + lens info + battery + Star Focus available.
- **USB PTP** ↔ USB-C — the EOS R (which doesn't activate CCAPI) and every PTP-capable Canon body. Lowest-latency Canon transport; doubles as a charging cable.
- **Canon BLE direct** ↔ BLE — speaks Canon's own BR-E1 (older bodies) and smartphone-mode (R-series) protocols. Wireless, no hardware, no Wi-Fi.
- **Canon Wi-Fi PTP (PTP/IP)** ↔ Wi-Fi — the EOS R's wireless control path; same op set as USB PTP over a TCP wire on port 15740.

Plus a **simulator** for exploring the app without hardware. The Android app picks whichever you tapped in the scan screen; the wizards don't know or care which one is active — `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp` in `transport/CanonRunner.kt` are shared across all four `CameraTransport` impls.

Beyond triggering, the app ships a **session-intelligence** workflow: an astro dashboard with sun/moon/twilight/weather/Bortle/dew, a DSO-recommendations card that surfaces what's worth shooting tonight, a session log that captures conditions at run start, run-complete notifications, a home-screen widget mirroring the dashboard's summary card, and a multi-body compatibility-report tile for community-driven body matrix testing.

I built this for my own photography, but figured if it's useful to me it might be useful to others. Open issues for bugs / feature requests; contribution mechanics are still loose, so reach out if you'd like to help.

### Author

**Eduardo Henrique Rocha** — System Z Infrastructure Architect, passionate about mainframe modernization, cloud architecture, electronics, DIY, and photography.

> 📷 See my photography on Instagram: [**@ehrocha.br**](https://www.instagram.com/ehrocha.br/)

| | |
|---|---|
| Email | [ehrocha@gmail.com](mailto:ehrocha@gmail.com) |
| GitHub | [github.com/ehrocha](https://github.com/ehrocha) |
| LinkedIn | [linkedin.com/in/ehrocha](https://www.linkedin.com/in/ehrocha/) |
| X / Twitter | [@ehrocha](https://twitter.com/ehrocha) |
| Instagram | [@ehrocha.br](https://www.instagram.com/ehrocha.br/) |
| Credly | [credly.com/users/ehrocha](https://www.credly.com/users/ehrocha) |
| Resume | [github.com/ehrocha/resume](https://github.com/ehrocha/resume) |

| Component | Version | Source of truth |
|-----------|---------|-----------------|
| Firmware | 0.31.0 | `firmware/platformio.ini` build flags → `config.h` |
| Android | 0.334.0 | `android/app/build.gradle.kts` → `BuildConfig.VERSION_NAME` |

> ### 🐉 Here Be Dragons — Safety Warning (BLE/ESP32 path only)
>
> **Pulsar drives camera shutter/focus lines through ESP32 GPIO pins** when you use the ESP32 transport. Connecting GPIOs directly to your camera's remote-release port can permanently damage the camera's internal circuitry.
>
> **Always use an optocoupler** (e.g., PC817, 4N35) between the ESP32 and the camera. The optocoupler provides electrical isolation — the ESP32 side and the camera side share no common ground, so voltage spikes, logic-level mismatches, or wiring mistakes cannot reach the camera.
>
> **We are not responsible for any damage to cameras, ESP32 devices, or other equipment — use this at your own risk.** Double-check your circuit before powering on. If you are unsure, consult the [wiring guide](docs/wiring.md) or open an issue.
>
> The Canon CCAPI transport has no hardware risk — it talks to the camera over WiFi using a stock Canon API.

---

## Trigger Modes

| Mode | Description | ESP32 BLE | CCAPI | USB PTP | Canon BLE | PTP/IP |
|------|-------------|:---:|:-----:|:----:|:----:|:----:|
| **Intervalometer** | Bulb timelapse — configurable interval, exposure, shot count, start delay | ✅ | ✅\* | ✅\* | ✅ | ✅\* |
| **Astro** | Star photography — exposure auto-computed via 500/400/NPF rule from focal length + crop factor, with a detectable-lens auto-fill button on supported transports | ✅ | ✅\* | ✅\* | ✅ | ✅\* |
| **Timelapse** | Camera owns exposure (set shutter speed on the body); Pulsar pulses the shutter on a schedule | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Dark Frame** | Same wire path as Intervalometer-bulb, plus a "pair with last lights" affordance that auto-fills exposure + shot count from the most recent completed light session, and a persistent lens-cap reminder | ✅ | ✅\* | ✅\* | ✅ | ✅\* |
| **Ramp** | Exposure ramp — linear interpolation from start to end across N steps (sunset / sunrise) | ✅ | ✅\* | ✅\* | ✅ | ✅\* |
| **Manual** | Press & hold (shutter open while button held) or press & lock (toggle) | ✅ | ✅ (press only) | ✅ | ✅ | ✅ |
| **Cable Release** | Dedicated single-shot screen with a big red trigger button — for "just take one" workflows where a wizard would be overkill | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Custom Flow** | Multi-step sequence builder — chain any combination of modes + pauses, save under a name, run | ✅ | ✅\* | ✅\* | ✅ | ✅\* |

\* Bulb-based modes need the camera to expose a bulb-class wire path: CCAPI `/shooting/control/shutterbutton/manual`, PTP `RemoteReleaseOn/Off` (`0x9128` / `0x9129`). Pulsar capability-detects per body at connect and dims the bulb tiles when missing.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                         Android app                              │
│                                                                  │
│  Wizards (Intervalometer, Astro, Timelapse, DarkFrame, Ramp,    │
│           Manual, Custom Flow) ── consume vm.runState           │
│                          │                                       │
│                          ▼                                       │
│              PulsarViewModel  (flow runner, state)               │
│                          │                                       │
│      ┌───────────────────┼───────────────────┐                   │
│      ▼                                       ▼                   │
│  BleController                          CcapiTransport            │
│  (Nordic BLE)                          (HTTP + digest auth)       │
└──────┼─────────────────────────────────────────┼─────────────────┘
       │                                         │
       │ BLE GATT (TLV v2)                       │ WiFi HTTP/JSON
       ▼                                         ▼
   ┌────────┐                              ┌─────────────────┐
   │ ESP32  │── optocouplers ──────────►   │ Canon EOS body  │
   │firmware│   to camera remote port      │  (CCAPI v110+)  │
   └────────┘                              └─────────────────┘
```

The viewmodel holds one StateFlow per transport (`bleController` for Pulsar ESP32, `_canonCcapiTransport`, `_ptpTransport`, `_canonBleTransport`); whichever the user picks in the scan screen is the active transport. Wizards observe `vm.runState` and call `vm.startFlow()` — they don't reach down to any individual transport directly.

### Firmware (ESP32 / PlatformIO / Arduino framework)

Single-threaded `loop()` architecture. All trigger modes are driven by `triggers_tick()` which runs once per loop iteration. Long operations use `interruptible_delay()` that yields to FreeRTOS and checks for STOP commands every 10 ms.

| File | Responsibility |
|------|---------------|
| `main.cpp` | `setup()` / `loop()` — LED status blink, calls `triggers_tick()`, OTA dispatch |
| `ble_server.cpp` | BLE GATT server — service, command/status characteristics, bonding, name management, advertising manufacturer data (board kind + firmware version) |
| `camera.cpp` | GPIO control for shutter/focus optocouplers, `camera_shutter()` with interruptible pre-focus |
| `triggers.cpp` | State machine for all trigger modes, TLV parsing, tick dispatch |
| `status.cpp` | Battery ADC reading (cached 5 s), TLV status frame construction, BLE notify |
| `ota.cpp` | Firmware OTA orchestration — chunked writes, CRC validation, reboot |
| `config.h` | All GPIO pins, timing defaults, parameter ranges, BLE UUIDs |
| `protocol.h` / `protocol_v2.h` | Shared enums and TLV tag registry |

**Key design decisions:**
- All trigger modes are **non-blocking** — intervalometer uses `millis()` comparisons.
- `camera_shutter()` is the only function that blocks (via `interruptible_delay`) and it polls `STATE_IDLE` every 10 ms to allow abort.
- Parameter values from BLE are always **clamped** to safe ranges defined in `config.h`.
- Battery ADC reads are **cached for 5 seconds** to avoid slow ADC reads on every status frame.
- Device name bytes are **sanitized** to printable ASCII (0x20–0x7E) before saving to NVS.
- `millis()` wraparound is handled via unsigned subtraction (`(now - target) < 0x80000000UL`).

### Android (Kotlin / Jetpack Compose / Nordic BLE 2.7.5)

Package: `com.ehrocha.pulsar`, `minSdk 26`, `compileSdk 35`. Navigation is a manual `AppScreen` sealed class (not Jetpack Navigation) — simpler for a screen-based app.

**Top-level packages**

| Package | Purpose |
|---------|---------|
| `ble/` | Pulsar BLE transport — Nordic BLE manager, scanner, TLV protocol, command builder, firmware OTA |
| `canonble/` | Canon BLE direct transport — BR-E1 + smartphone-mode protocols, discovery, GATT client, shared `CanonBleLog` ring buffer + `CrashPersister` for forensic diagnostics |
| `ptp/` | USB PTP + PTP/IP — `PtpClient` op layer behind a `PtpWire` interface; `BulkPtpWire` (USB) and `PtpIpWire` (TCP) implementations; `PtpDataHelpers` for shared parsing |
| `transport/` | `CameraTransport` interface, `CanonRunner` (shared bulb / timelapse / ramp loops), `CompatibilityReport`, `ccapi/` subpackage |
| `astro/` | Astro dashboard data + ephemeris + `DsoCatalog` + `DsoRecommender` (suggested targets tonight) |
| `planner/` | Event/session planner with weather feasibility checks (Open-Meteo) |
| `sensor/` | Compass sensor wrapper (for Alignment screen) |
| `notify/` | `RunCompleteNotifier` — one-shot completion notification when a phone-driven flow ends |
| `service/` | Foreground notification for running OTA jobs |
| `update/` | APK self-update and background update checks |
| `widget/` | Home-screen widget — Glance + Dashboard summary snapshot + `WorkManager` 3 h refresh |
| `model/` | Domain types — `FlowStep` (sealed), `RunState` (sealed), `UserMode` (presets), `ShotLog`, `ConditionSnapshot` (per-run sky/weather metadata) |
| `viewmodel/` | `PulsarViewModel` — single state hub, flow runner, transport dispatch, persistence. Per-transport leaf helpers (`awaitXReady` + battery polling) live in extension files alongside their transport |
| `ui/screens/` | All composable screens — see table below |
| `ui/components/` | Reusable widgets (`PulsarTopBar`, `ScrubField`, `NumPad`, `BatteryIndicator`, `WizardWarning`, `AutofocusToggle`) |

**Screens**

| Screen | Purpose |
|--------|---------|
| `ScanLandingScreen` | Six-tile 2×3 transport landing — Pulsar BLE / Canon BLE / Wi-Fi CCAPI / Wi-Fi PTP / USB PTP / Simulator. Recents row underneath. Tools section with Diagnostics. |
| `TransportSetupScreen` | Per-transport scan UI — discovered devices, paired cameras, "Confirm on camera" prompts, auth + setup help. One scaffold drives all five transport sub-flows. |
| `MainMenuScreen` | Pager with Dashboard / Trigger / Tools tabs; mode tile grid grouped into Bulb / Standard / Favorites / Custom sections; Canon banner with reconnect state. |
| `Intervalometer2Screen` | Wizard: Exposure → Interval → Delay → Shots tabs, sub-second-bulb warning, Save preset, RunningView with battery chip. |
| `AstroMode2Screen` | Wizard: Focal length / crop factor / rule → Interval → Delay → Shots; exposure auto-computes via NPF / 500 / 400 rule; "Detect lens" button on transports that expose `LensName`. |
| `TimelapseScreen` | Wizard: Interval → Delay → Shots (no exposure tab — camera owns it). |
| `DarkFrame2Screen` | Wizard: Exposure → Interval → Shots, plus the "Pair with last lights" auto-fill chip and persistent lens-cap reminder. |
| `Ramp2Screen` | Wizard: Start exposure → End exposure → Interval → Steps. |
| `PresetPickerScreen` | Lists saved presets for a given mode; tap to open the wizard pre-filled. |
| `ModeScreen` | Manual (press & hold / press & lock) — big touch button. |
| `CableReleaseScreen` | Dedicated single-shot screen — large red trigger, gated on connected transport. |
| `CustomFlowScreen` | Multi-step flow editor — add/edit/reorder/delete steps, save/load named flows. |
| `SettingsScreen` | Settings sections (device, GPIO pins, OTA, backup/restore, language, planner, about) — connection-aware (GPIO/device sections hide on Canon transports). |
| `DashboardScreen` | Astro ephemeris dashboard — Summary verdict card, sun/moon, Milky Way, Bortle, dew point, twilight timeline, hourly weather, planets, **DSO recommendations** ("Suggested Targets Tonight"). |
| `PlannerScreen` / `SessionDetailScreen` / `EventSessionsScreen` / `MapLocationPicker` | Astro event planner with location/time/weather feasibility. |
| `AlignmentScreen` | Polar alignment helper using phone compass + Polaris position. |
| `WhatsUpScreen` | What's visible tonight — objects above horizon at the planned location/time. |
| `StarFocusScreen` | 4-step focus wizard (Prep → Aim → Focus → Lock); live view + tap-to-mark + drive-focus stepper. Runs on any `CameraTransport` whose `liveViewSupportedFlow` is true (CCAPI / USB PTP / PTP/IP). |
| `TestCameraScreen` | "Camera Test" tile — fires 25 shots across all 5 modes against the active transport to verify end-to-end behaviour. |
| `DiagnosticsScreen` | Scrollable Canon transport wire log (every connect, handshake, arm, shutter / focus / mode write, spontaneous disconnect) + previous-session crash dump from `CrashPersister` if any. Copy / Share buttons. |
| `ShotLogScreen` | History of completed runs — mode, shots, exposure, status, **plus the conditions snapshot** (moon, cloud, dew, Bortle) captured at run start. |

**Key design decisions:**
- **CompositionLocals** (`LocalDeviceStatus`, `LocalDeviceConnected`, `LocalRunState`, `LocalNightMode`) provide global device state and theme mode to all screens — avoids parameter threading through deeply nested composables.
- **Night Mode** applies a red-tint color scheme for night-vision preservation at the telescope; toggled via the header icon.
- **Wizard pattern** — each multi-parameter mode is a TabRow of parameter tabs + Prev/Next + Start on the final tab. Start is always clickable; if config is incomplete, tapping it jumps to the first invalid tab.
- **Presets** are stored as `UserMode` records (per-fwMode) and live in `pulsar_user_modes` SharedPreferences. Bookmarked presets show as tiles in the Trigger tab; the rest live in `PresetPickerScreen` per mode.
- **Simulator mode** — virtual device that fakes a StatusFrame stream; lets you explore the UI without hardware.

---

## Transports

Pulsar talks to cameras through five mutually-exclusive transports, all routed through the same viewmodel: `bleController` (Pulsar ESP32 over BLE), `_canonCcapiTransport` (Canon CCAPI over Wi-Fi), `_ptpTransport` (USB PTP), `_canonBleTransport` (Canon's own BR-E1 / smartphone-mode BLE protocol), and `_ptpIpTransport` (Canon PTP-over-Wi-Fi). Tapping a device of one kind disconnects the others; the simulator is treated as an additional transport for mutual-exclusion purposes.

### BLE / ESP32

- **Discovery** — Nordic BLE scanner filtered on the Pulsar service UUID (`0000ff00-...`). Manufacturer-specific data in the advertisement carries board kind (`GENERIC_ESP32 = 1`, `M5STICK_S3 = 2`, `M5CORE2 = 3`) and firmware version so the scan list can show the right icon and label before connecting.
- **Pairing** — BLE Secure Connections with bonding (`ESP_LE_AUTH_REQ_SC_MITM_BOND`). The command characteristic requires encrypted writes; status notify is open. ESP32 has no I/O for PIN entry → Just Works pairing (`ESP_IO_CAP_NONE`).
- **Wire format** — opcode + version + TLV; see [docs/ble-protocol.md](docs/ble-protocol.md).
- **OTA** — firmware updates over BLE via a separate OTA service (`0000ff10-...`). Chunked 8 KB writes with CRC validation; full-screen overlay during the run.

### Canon CCAPI

- **Discovery** — SSDP multicast on `239.255.255.250:1900`. Pulsar holds a `WifiManager.MulticastLock` while scanning. Filters on Canon's service URN (`urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1`), then fetches and parses `CameraDevDesc.xml` to get the UDN, friendly name, and the CCAPI `accessUrl`.
- **Connect** — `GET /ccapi` pins the highest supported version (`ver140` → `ver100`) and caches the endpoint matrix. Capabilities (bulb support, dial-ignore, polling, shooting-mode PUT) are read from the matrix.
- **Auth** — RFC 7616 digest auth (MD5 / MD5-sess / SHA-256 / SHA-256-sess, with or without `qop=auth`). Hand-rolled on `HttpURLConnection`. First request goes out unauthenticated; on 401, Pulsar parses the `WWW-Authenticate` challenge and retries. Credentials are stored per-UDN in EncryptedSharedPreferences (`pulsar_canon_creds`, AES-256, key in Android Keystore).
- **Polling** — `GET /event/polling?timeout=short` (or `?continue=on` on `ver100` bodies) feeds battery percentage and shot count back into the running UI.
- **Reconnect** — on a streak of poll failures the session enters reconnect mode, re-probes `/ccapi` for ~2 minutes before giving up.

Full design + endpoint mapping: [docs/ccapi.md](docs/ccapi.md). Camera-side activation walkthrough is in the in-app "Camera setup help" dialog on the scan screen.

### USB PTP

The reason this transport exists: the Canon EOS R doesn't support CCAPI even on the latest firmware (the PC activation tool refuses to enable it), and without it the body has no phone-side automation path other than the ESP32 + remote-release cable. USB PTP fills that gap and works on the EOS RP too as a faster + lower-power alternative to CCAPI.

- **Discovery** — `PtpDiscovery` registers a `BroadcastReceiver` on `USB_DEVICE_ATTACHED` / `DETACHED` and filters for interfaces matching PIMA Still-Image-over-USB (class `0x06`, subclass `0x01`, protocol `0x01`). Vendor-agnostic at the discovery layer — Canon, Nikon, Sony, Fuji all expose the same interface class.
- **Connect** — Android's `UsbManager` permission dialog → open device → claim PTP interface → find bulk-IN / bulk-OUT endpoints. Then PTP `OpenSession` (op `0x1002`). On Canon EOS bodies (vendor extension `11`), Pulsar follows with `SetRemoteMode(1)` + `EventMode(1)` so the body accepts subsequent `RemoteRelease` ops.
- **Capture** — `InitiateCapture` (op `0x100E`) for Timelapse (camera owns exposure timing). Canon `RemoteReleaseOn` / `RemoteReleaseOff` (ops `0x9128` / `0x9129`) for bulb modes; mode parameter encodes AF (`2` = no AF, `3` = with AF) and the matching release uses the same mode value.
- **Properties** — battery percentage via `GetDevicePropValue(0x5001)` polled every 30 s; lens name via Canon `0xD157` parsed into focal length for the Astro wizard's auto-fill (same shared helper as CCAPI). Best-effort programmatic Bulb selection via `SetDevicePropValue(0xD102, 0x000C)` at flow start.
- **Auto-reconnect** — viewmodel remembers the last successfully-connected camera by `(vendorId, productId)`. If the cable replugs while no other transport is active, Pulsar reconnects automatically. Explicit user disconnect or switching transports clears the auto-reconnect target.
- **Live view + Star Focus** — `StarFocusScreen` reads from whichever Canon transport is active. Over PTP that's Canon `GetViewFinderData` (op `0x9153`) for JPEG frames + `DriveLens` (op `0x9155`) for the focus stepper, gated on the body advertising the live-view op.
- **Mid-shoot disconnect** — cable bumps no longer end the run. `PtpTransport.reopen(ctx, newDevice)` (v0.320) swaps the dead USB handle in place when the OS reports a matching `(vid, pid)` ATTACHED, and `awaitCanonReady` pauses the runner; the flow resumes on replug.

Honest caveats:

- **PC-remote mode locks the body's controls.** Once Pulsar enters PC-remote mode (Canon `SetRemoteMode(1)`), the camera's mode dial and menu show "busy" until Pulsar disconnects. To change Bulb/Manual on the body, disconnect first. Canon's design, not a Pulsar bug.
- **`0x000C` is the R-class Bulb code.** Other Canon bodies may use a different `SetDevicePropValue(0xD102, ...)` value. Best-effort: if the write returns non-OK, Pulsar logs and falls back to the user-set-on-dial workflow.
- **Bulb modes only on Canon today.** `InitiateCapture` is universal (works across PTP-capable Nikon / Sony / Fuji bodies for Timelapse), but `RemoteReleaseOn/Off` are Canon vendor ops. Non-Canon bulb needs vendor-specific plumbing.

Full design: [docs/ptp.md](docs/ptp.md). The Tools tab has a "Camera Test" tile that fires 25 shots across all 5 modes to verify the active transport end-to-end.

### Canon BLE direct

The reason this transport exists: pair Pulsar with a Canon body and trigger it wirelessly with no Pulsar hardware and no Wi-Fi setup. Pulsar speaks Canon's **two** BLE control protocols — auto-detected at connect time — so it fires both the older bodies that use the BR-E1 remote protocol and the R-series bodies that use the newer smartphone-mode protocol. Capability is bulb-class: every Pulsar mode that the firmware-driven path supports works here too (Timelapse, Intervalometer bulb, Astro, Dark Frame, Ramp), but live view, lens info, and battery readout aren't in either protocol and aren't supported.

- **Discovery** — `BluetoothLeScanner` with two parallel `ScanFilter`s: the BR-E1 service UUID (`00050000-…-d8492fffa821`) and the smartphone-mode service UUID (`00010000-…-d8492fffa821`). `onScanResult` re-verifies the advertisement record so phones / unrelated devices that some Android stacks let through don't pollute the list.
- **Pair** —
  - *Older bodies* (M50/M200/6D II/77D/200D/800D/850D/PowerShots): put the body's Bluetooth menu on **Remote**. Tap the card; Pulsar opens GATT, writes `[0x03, "Pulsar"]` to the BR-E1 pair characteristic (`00050002-…`), Android's pair dialog appears, the bond lands in the OS keystore.
  - *R-series with BLE shutter* (RP, R5, R6, …): body on **Smartphone** Bluetooth mode. Tap the card; Pulsar calls `createBond()` (the camera shows its own confirm prompt — the user taps Confirm on the body), then runs the smartphone-mode identify handshake on `00010000` / `0001000a`, waits for the `0x02` accept indication, and switches to `MODE_SHOOT` on `00030010`.
  - *EOS R (2018)*: registers in smartphone mode but exposes **no `00030000` control service** — Camera Connect needs Wi-Fi to fire it. Pulsar detects this (`NoBleShutter`) and steers you to USB (PTP) or Wi-Fi (CCAPI).
- **Capture** — `CanonBleTransport.fireShutter` / `startBulb` / `stopBulb` dispatch on the detected protocol: BR-E1 writes single bytes (`0x8C` press, `0x0C` release, `0x4C` AF half-press) to `00050003`; smartphone-mode writes the positional toggle `[00,01]` to `00030030` (one toggle = press, two toggles = press + release back to "up"). The wizards / runners are unchanged.
- **Auto-reconnect** — viewmodel persists the last-good MAC in SharedPrefs. A spontaneous link drop fires the GATT callback → `onCanonBleLinkDropped` aborts any in-flight flow (the body may still be exposing), then issues `connectGatt(autoConnect=true)` on a 120 s window — Android completes the connection whenever the bonded body becomes available, **including via directed advertisements a UUID-filtered scan never sees**. The service-UUID scan + collector still runs as a fallback if no cached `BluetoothDevice` is available. Bond survives app restart.

Honest caveats:

- **Mode dial is on the body.** Neither protocol carries a shutter-speed write. Bulb modes need the user to set Bulb manually.
- **No live view / lens info / battery readout.** The protocols are control-only. Star Focus, lens auto-fill, and the battery chip are gated off on this transport. Use CCAPI or PTP if you need them.
- **Diagnostics.** Every connect, handshake, arm, and shutter write goes into an in-app ring buffer; **Tools → Diagnostics** copies / shares the full text without needing `adb`.

Compatibility: every body on Canon's BR-E1 compatibility list — RP, R5, R6, R6 II, Ra, 6D Mark II, 77D, 800D, 200D, 850D, M50, M200, plus G7X III / G5X II — fires either via BR-E1 (older) or smartphone mode (R-series). The 2018 EOS R reports `NoBleShutter`. Bodies without BR-E1 / smartphone-mode listings won't advertise either service UUID and won't appear in the Canon-BLE-remotes section.

Full design: [docs/canon-ble.md](docs/canon-ble.md).

### Canon Wi-Fi PTP (PTP/IP)

The reason this transport exists: the **EOS R** has no BLE shutter and no CCAPI, so the only wireless control path Canon ever shipped for that body is PTP-over-Wi-Fi via "Remote Control (EOS Utility)" mode. Same op set as USB PTP (shared `PtpClient`); only the wire differs — TCP sockets on port 15740 instead of USB bulk endpoints. Works on every EOS body with "Remote Control (EOS Utility)" Wi-Fi mode (R / RP / R5 / R6 / R6 II / R7 / R8 / R10 / R50, plus pre-R bodies from the EOS Utility era).

- **Discovery** — mDNS browse on `_ptp._tcp.local` via Android's `NsdManager`. Cameras in EOS Utility mode announce themselves on the local network; resolved entries surface as `PtpIpCamera(name, host, port)` in the scan list. Multicast must work on the LAN — some enterprise APs block it; use the camera's own Wi-Fi AP mode as a workaround.
- **Pair** — body's Wireless menu → Wi-Fi → **"Remote Control (EOS Utility)"** (not "Connect to smartphone"). Both devices on the same network (or phone joins the camera's AP). Tap the camera card; Pulsar runs the four-message PTP/IP init handshake (two TCP sockets — command + event — with a persisted 16-byte client GUID); the camera shows "Connect this device?" — confirm on the body. Subsequent reconnects skip the prompt because the camera remembers our GUID.
- **Capture** — `PtpIpTransport.fireShutter` / `startBulb` / `stopBulb` go through the same Canon op set as USB PTP (`InitiateCapture`, `RemoteRelease{On,Off}`). Adding a new Canon op needs zero PTP/IP-specific code — the wire abstraction (`PtpWire` / `PtpIpWire` / `BulkPtpWire`) keeps the client agnostic.
- **Phases** — all shipped. Phase 1 (v0.305): discovery + handshake + connect + capability detection. Phase 2 (v0.307–v0.313): shutter, with EOS R quirks discovered along the way (`vendorExt=6` over Wi-Fi vs `11` over USB — gated on manufacturer string; `RemoteRelease(mode=2)` returns `DEVICE_BUSY` on Wi-Fi, force `mode=3`). Phase 3 (v0.314): lens info / battery / live view / drive focus over PTP/IP. Phase 5 (v0.315–v0.316): idle auto-reconnect + mid-flow pause-and-resume via `PtpIpTransport.reopen()`. See [docs/canon-body-matrix.md](docs/canon-body-matrix.md) for the per-body capability matrix discovered through the Compatibility Report tile.

Honest caveats:

- **Wi-Fi doubles camera battery drain** vs USB PTP — plan for a dummy-battery passthrough on long sessions.
- **PC-remote mode locks the camera dial** — same caveat as USB PTP.
- **mDNS requires multicast on the network** — workaround: camera's own AP.

Full design: [docs/ptp-ip.md](docs/ptp-ip.md). USB sibling: [docs/ptp.md](docs/ptp.md).

---

## Repository Structure

```
pulsar-trigger/
├── firmware/                       ← ESP32 PlatformIO project
│   ├── platformio.ini              ← Multi-env (esp32dev, m5stick-s3, m5core2)
│   ├── partitions.csv              ← Partition table with OTA slots
│   ├── include/                    ← config.h, protocol.h/v2, ble_server.h, etc.
│   └── src/                        ← main.cpp, ble_server.cpp, triggers.cpp, ...
├── android/                        ← Android Studio / Gradle project
│   └── app/src/main/java/com/ehrocha/pulsar/
│       ├── MainActivity.kt         ← Permissions, navigation, CompositionLocalProvider, EXTRA_OPEN_TAB widget deep-link
│       ├── PulsarApp.kt            ← Application — CrashPersister.install, schedules WorkManager workers
│       ├── AppConfig.kt            ← Compile-time constants
│       ├── ble/                    ← BleController, PulsarBleManager, Protocol, CommandBuilder, FirmwareUpdateManager
│       ├── canonble/               ← CanonBleDiscovery, CanonBleClient, CanonBleTransport, CanonBleLog, CrashPersister
│       ├── ptp/                    ← PtpTransport (USB), PtpIpTransport (Wi-Fi), PtpClient + PtpWire abstraction, PtpDataHelpers, PtpIpDiscovery
│       ├── transport/              ← CameraTransport interface, CanonRunner (shared bulb/timelapse/ramp loops), CompatibilityReport
│       │   └── ccapi/              ← CcapiDiscovery, CcapiClient, CcapiTransport, CanonCamera, CameraDescription
│       ├── astro/                  ← AstroDashboardData, DsoCatalog, DsoRecommender
│       ├── planner/                ← Event planner + weather checks (Open-Meteo)
│       ├── sensor/                 ← Compass for polar alignment
│       ├── notify/                 ← RunCompleteNotifier
│       ├── service/                ← Foreground notification for OTA
│       ├── update/                 ← APK self-update + GitHub release polling
│       ├── widget/                 ← Glance home-screen widget + DashboardSnapshotStore + DashboardWidgetWorker
│       ├── model/                  ← FlowStep, RunState, UserMode, ShotLog + ConditionSnapshot
│       ├── viewmodel/
│       │   └── PulsarViewModel.kt  ← Plus per-transport extension files in canonble/ptp/transport/ccapi
│       └── ui/
│           ├── screens/            ← All composable screens (incl. SharedSections.kt — Settings panels grab-bag)
│           ├── components/         ← Reusable widgets
│           └── theme/              ← Color schemes, CompositionLocals (Dark / Light / Outdoor / RedLight)
├── web/                            ← ESP Web Tools browser-based installer
├── scripts/                        ← bump.sh (version + commit + push), build-android.sh
├── .github/workflows/              ← CI/CD (android.yml, firmware.yml)
└── docs/
    ├── ble-protocol.md             ← Pulsar BLE wire format spec (TLV)
    ├── ccapi.md                    ← Canon CCAPI integration design
    ├── ptp.md                      ← USB PTP transport design (Canon EOS R / RP)
    ├── ptp-ip.md                   ← Canon Wi-Fi PTP transport (PTP-over-TCP)
    ├── canon-ble.md                ← Canon BLE direct transport (BR-E1 + smartphone-mode)
    ├── canon-ble-research.md       ← Reverse-engineering log (refs + GATT dumps)
    ├── canon-body-matrix.md       ← Per-body × per-transport capability matrix (R + RP populated)
    ├── mode-schema.md              ← User-mode preset JSON schema
    └── wiring.md                   ← Hardware schematics
```

---

## Hardware (BLE path)

| Component | GPIO (ESP32 generic) | Direction | Purpose |
|-----------|:---:|:---------:|---------|
| Shutter optocoupler | 25 | Output | Camera remote shutter |
| Focus optocoupler | 26 | Output | Camera remote focus |
| Status LED | 2 | Output | On-board LED |
| Battery voltage | 33 | Analog In | Resistor divider (2:1), 3.2–4.2 V LiPo |

GPIO pins are **configurable at runtime** via the Settings screen. The set of allowed pins is filtered per chip model (ESP32 / S3 / C3 etc.) — the firmware reports its chip via the `DEVICE_INFO` notification and the app restricts the dropdown accordingly.

Pulsar firmware ships built for three boards:
- **`esp32dev`** — generic ESP32 dev board
- **`m5stick-s3`** — M5StickC Plus2 (ESP32-S3)
- **`m5core2`** — M5Stack Core2

See [docs/wiring.md](docs/wiring.md) for full schematics.

---

## Building

### Firmware

**Prerequisites:** [PlatformIO CLI](https://docs.platformio.org/en/latest/core/installation.html)

```bash
cd firmware
~/.platformio/penv/bin/pio run              # build all envs
~/.platformio/penv/bin/pio run -e esp32dev  # build one env
~/.platformio/penv/bin/pio run -t upload    # flash
~/.platformio/penv/bin/pio device monitor   # serial (115200 baud)
~/.platformio/penv/bin/pio test -e native   # unit tests
```

Build artifacts: `firmware/.pio/build/<env>/firmware.bin` (~1.1 MB).

### Android

**Prerequisites:**
- JDK 17 (Android Studio bundles one — `/opt/android-studio/jbr` on Linux)
- Android SDK with platform 35 and build-tools 35.0.0

```bash
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug      # build
JAVA_HOME=/opt/android-studio/jbr ./gradlew installDebug       # build + install
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest  # unit tests
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

Android Studio: open the `android/` directory directly.

### Versions (bump.sh)

Versions are stamped via `scripts/bump.sh` rather than edited by hand. It increments the version, commits, and pushes in one step:

```bash
./scripts/bump.sh "commit message"      # bump Android + firmware
./scripts/bump.sh -a "commit message"   # Android only
./scripts/bump.sh -f "commit message"   # firmware only
```

---

## Protocols

### BLE wire format

See [docs/ble-protocol.md](docs/ble-protocol.md) for the full TLV specification. Quick reference:

| UUID | Characteristic | Security |
|------|---------------|----------|
| `0000ff00-...` | Service | — |
| `0000ff01-...` | Command (Write) | Encrypted (bonded) |
| `0000ff02-...` | Status (Notify) | Open |
| `0000ff10-...` | OTA Service | — |
| `0000ff11-...` | OTA Control (Write) | BEGIN / END / ABORT |
| `0000ff12-...` | OTA Data (Write) | Firmware binary chunks |

Frames are `[opcode][version=0x02][len][TLV...]`. Setter opcodes live in `0x10–0x4F`, control opcodes in `0x50–0x7F`, notify opcodes in `0x80–0xBF`. Unknown TLV tags are ignored — adding a new parameter doesn't break older firmware/app combos.

### Canon CCAPI

See [docs/ccapi.md](docs/ccapi.md) for the endpoint mapping. Quick reference of the endpoints Pulsar uses:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/ccapi` | GET | Discover supported version + endpoint matrix |
| `/event/polling?timeout=short` (or `?continue=on`) | GET | Long-poll battery + shot count |
| `/shooting/control/shutterbutton` | POST | Single-shot Timelapse fire |
| `/shooting/control/shutterbutton/manual` | POST | Bulb full_press / release |
| `/shooting/settings/shootingmode` | PUT | Set bulb / m before bulb-based runs |
| `/shooting/control/ignoreshootingmodedialmode` | POST | Override physical mode dial (when supported) |

Authentication: HTTP Digest (RFC 7616) when the camera requires it. The Activation Tool is downloaded from Canon's developer portal — the in-app **Camera setup help** dialog has the full walkthrough.

---

## Features Beyond Triggering

### Astro Dashboard

A live ephemeris view: location header, **Summary card** with verdict chips (Sun, Moon, Weather, Milky Way, Bortle — each tinted green or orange based on whether tonight is shootable), sun rise/set, moon phase / illumination / rise / set / good-for-astro flag, Milky Way core window, Bortle-class light pollution, hourly weather forecast (Open-Meteo), dew-point risk, civil/nautical/astronomical twilight timeline, planet altitudes, and best photo-window highlighting. Powered by `astro/AstroDashboardData.kt`.

### DSO Recommendations (Suggested Targets Tonight)

A "what should I shoot tonight?" card on the Dashboard. Walks a curated ~45-target catalog (`astro/DsoCatalog.kt` — Messier highlights M1/8/13/16/17/20/22/27/31/33/42/45/51/57/63/64/65/66/78/81/82/97/101/104, plus popular NGC/IC nebulae like Veil, NA Nebula, Helix, Heart, Soul, Horsehead, Rosette, Crescent, Eta Carinae…), samples each one's altitude across tonight's astro-dark window via `AstroCalculator.lst()` + `altitude()`, scores by peak altitude minus a small magnitude penalty, returns the top 5 above 30°. Renders emoji + ID + common name + magnitude / size / type + peak-altitude + local peak time. Empty-state message when nothing clears 30° (high latitudes in summer, or wrap-around polar dark).

### Session Conditions Log

Every completed run snapshots the Dashboard state at the moment of `startFlow` and stores it on the `ShotLogEntry` as a `ConditionSnapshot`: city, moon phase + illumination + good-for-astro, cloud cover %, temperature, dew point + risk, Bortle class, MW visibility. The Shot Log screen renders a compact one-line summary (`🌑 23%  ☁ 12%  · dew 8°C  💡 B4`) on each row — retroactive "why did my last M31 session come out grainy?" diagnosis. Older entries (pre-v0.327) just don't have the field; they render normally with no conditions row.

### Compatibility Report

Tools tab → `Compatibility Report` runs a **read-only** wire-level capability probe against the active Canon transport — no shutter releases, no property writes. Walks `GetDeviceInfo` (manufacturer / model / firmware / serial / vendor extension ID), checks presence of a fixed set of Canon op codes and prop codes (`InitiateCapture`, `RemoteReleaseOn/Off`, `GetViewFinderData`, `DriveLens`, `BatteryLevel`, `LensName`, `EvfOutput`, `ShutterSpeed`, `Aperture`, `ISO`), tries `getLensInfo` + battery + a live-view round-trip, dumps everything to the shared diagnostics log. The intended workflow: a community tester with a body Pulsar hasn't been verified on (R5, R6, R7, R8, R10…) runs the report on each transport, shares the diag file, and the [body matrix doc](docs/canon-body-matrix.md) gets populated. EOS R and EOS RP are characterised this way.

### Home-Screen Widget

A read-only mirror of the in-app Summary card via Jetpack Glance. Verdict chips (Sun / Moon / Weather / Milky Way / Bortle) with the same green-for-good / orange-for-bad palette as the dashboard, plus rise/set times and the top photo windows. Two write paths to the snapshot:
- `DashboardScreen` writes to `DashboardSnapshotStore` (SharedPrefs) every time `lastUpdated` changes and calls `Glance.updateAll`
- `DashboardWidgetWorker` runs every 3 h via `WorkManager` so the widget stays fresh even if the user doesn't open the app

Tap the widget → opens MainActivity on the Dashboard tab. A refresh icon in the widget enqueues a one-shot `DashboardWidgetWorker` for immediate refresh. Header tints amber + prefixes "Stale ·" when the snapshot is older than 12 h (Samsung's "Sleeping apps" hibernation, no network, etc). Responsive: small / medium / full layouts based on placed size. Lock-screen widget category is included (Android 17+ / One UI 7).

### Run-Complete Notifications

When a phone-driven flow ends — Completed, Stopped, or Failed — a one-shot notification posts on the `pulsar_run_complete` channel: title is `Completed · ASTRO`, body is `60 / 60 shots · 28m 14s`. Tap to reopen the app. Channel is at `IMPORTANCE_DEFAULT` so it's heard but not intrusive — useful when the phone is in a tent, asleep, or on the camera tripod while you're elsewhere.

### Planner

Create astro-imaging events, attach sessions with location/time/conditions, check weather feasibility. Uses Open-Meteo for forecasts; locations are picked on a MapLibre map with city search via Open-Meteo geocoding.

### What's Up

What's visible tonight at the planned location/time — Messier and NGC catalog filtered by altitude.

### Polar Alignment

Uses the phone's compass + Polaris position calculation to help align an equatorial mount.

### Star Focus Assist (CCAPI + PTP)

Four-step guided wizard for nailing pinpoint focus on stars before kicking off an astro run. Works on the two Canon transports that support live view — `StarFocusScreen` takes a `CameraTransport` and reads from whichever transport is active (CCAPI over Wi-Fi, or PTP over USB on bodies that advertise the live-view op). Not available on Canon BLE direct: the BR-E1 protocol has no live-view endpoint. Live view streams from the camera, the user taps a bright star, and a peak-luminance sharpness readout updates per frame as they walk focus with six drive-focus buttons (`«««` / `««` / `«` / `»` / `»»` / `»»»`). Auto-stops live view on screen leave or disconnect to save battery. See [docs/ccapi.md](docs/ccapi.md#star-focus-assist-tools-tab).

### Shot Log

Every completed run gets a log entry: mode, exposure, interval, shot count, status (completed / stopped), timestamps. Survives uninstalls via SAF backup.

### Diagnostics

Tools tab has a Diagnostics tile (also reachable as Scan landing → Diagnostics when nothing's connected). The export is one shareable text blob with:

- App + device header (version, model, Android SDK, time, active transport, Canon BLE state)
- **Previous-session crash dump** (if any) — installed at app start, `CrashPersister` hooks `Thread.setDefaultUncaughtExceptionHandler` so even a JVM-killing crash leaves a full stack + the wire log at moment of crash on disk; the next diagnostics share inlines it
- **Transport wire log** — `CanonBleLog` ring buffer with every connect, handshake, arm, shutter / focus / mode write, and any spontaneous disconnect across CCAPI / USB PTP / Canon BLE / PTP/IP

Same text underlies the Camera Test and Compatibility Report exports. The buffer is also captured to Logcat for `adb` users.

### Backup & Restore

Settings, GPIO pins, custom flows, saved presets, shot log — all exported as JSON via Android's **Storage Access Framework**. Works with Google Drive, OneDrive, Dropbox, local storage, or any other DocumentsProvider installed on the device. No extra dependencies, no OAuth.

### Simulator Mode

A virtual device that fakes a `StatusFrame` stream — lets you explore every screen and even run flows end-to-end without hardware connected. Tap "Use Simulator" on the scan screen.

### Foreground Notification

Long-running jobs (intervalometer, astro, ramp) post a foreground notification with current state, progress, and a Stop action that broadcasts back into the viewmodel.

### OTA Firmware Update

Firmware updates over BLE without USB. Four-phase flow (download → upload → validate → reboot) with progress UI and a "do not disconnect" overlay. The Settings screen polls GitHub Releases for `firmware-v*` tags matching the connected board.

### App Self-Update

Checks GitHub Releases for newer `app-v*` tags. Downloads the APK and opens it in a browser intent — the system installer takes over from there.

---

## CI/CD

GitHub Actions workflows automate builds and releases on push to `master`.

### Firmware (`firmware.yml`)

Triggers on changes to `firmware/**`. Extracts version from `platformio.ini`, builds all envs with PlatformIO, generates an ESP Web Tools `manifest.json`, and creates a GitHub Release tagged `firmware-v<version>` with the `.bin` artifacts.

### Android (`android.yml`)

Triggers on changes to `android/**`. Extracts version from `build.gradle.kts`, decodes the release keystore from `KEYSTORE_BASE64`, builds a signed release APK via `assembleRelease`, and creates a GitHub Release tagged `app-v<version>` with the APK attached.

**Required GitHub Secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

---

## ESP Web Tools

A browser-based firmware installer lives at `web/index.html`. It uses [ESP Web Tools](https://esphome.github.io/esp-web-tools/) to flash the ESP32 via USB directly from the browser (WebSerial API). The firmware CI generates the required `manifest.json` with bootloader, partition, and firmware binary offsets.

---

## Development Notes

### Conventions

- **License:** GPL-3.0-or-later — every source file has the SPDX header.
- **Firmware:** C++ (Arduino framework on ESP32). Non-blocking only — `delay()` is banned in the trigger tick.
- **Android:** Kotlin, Jetpack Compose (Material 3), no XML layouts.
- **BLE:** Nordic Android BLE (`no.nordicsemi.android:ble:2.7.5`).
- **Permissions:** Accompanist Permissions (`com.google.accompanist:accompanist-permissions:0.36.0`).
- **Packed structs** use `__attribute__((packed))` and match the wire format exactly.
- **Parameter validation** happens in firmware `triggers_set_mode()` — all values clamped to `config.h` ranges.
- **State is authoritative on firmware** (for the BLE path) — the app reads it via TLV status notifications. For CCAPI, the app owns the run loop; status is synthesized by the polling job.

### Adding a New Trigger Mode

1. `protocol.h` (firmware) → add `MODE_XXX` enum + packed params struct
2. `triggers.cpp` → parse in `set_mode()`, handle in `triggers_tick()` (non-blocking)
3. `Protocol.kt` → add `TriggerMode.XXX` and corresponding opcode
4. `CommandBuilder.kt` → add `setXxx()` builder
5. `PulsarViewModel.executeFlowStep()` → add the new `FlowStep` variant dispatch (BLE + simulator + Canon paths)
6. New wizard screen in `ui/screens/` or extend an existing one
7. Add menu tile in `MainMenuScreen.kt`
8. Update `docs/ble-protocol.md` with the new opcode/TLVs

### Intervalometer Timing

The intervalometer uses **gap semantics**: expose → wait gap → repeat. The gap timer starts *after* the exposure ends, not at the start of the shot. Total cycle = `exposureMs + intervalMs`. For CCAPI on bulb-based modes, add ~100–200 ms of WiFi round-trip per shot — not subtracted from `intervalMs`, just documented.

### Live Countdown Timer

The running view updates in real time using a 100 ms client-side tick. The countdown interpolates from the last status update so the progress bar and remaining time animate smoothly between shot boundaries.

### Known Limitations

- **CCAPI sub-second exposures** — bulb open/close costs two HTTP round-trips (~100–200 ms each). For exposures below 1 s the timing error is significant; the wizards show a warning when on CCAPI with `exposureMs < 1000`.
- **CCAPI body-capability differences** — bulb-based modes (Intervalometer/Astro/Dark Frame/Ramp) require `/shooting/control/shutterbutton/manual`. Newer R-bodies expose it; older ones may not. Pulsar capability-detects at connect and dims the affected tiles when missing. Programmatic AF↔MF (`/shooting/settings/afoperation` PUT) likewise depends on the body — on the EOS RP it's read-only, so the user has to flip the lens AF/MF switch by hand. The per-shot `useAutofocus` toggle (defaults off for bulb modes) is the software backstop.
- **CCAPI battery cost** — WiFi + CCAPI roughly halves a body's battery life. Plan ~2 h per LP-E17 on the RP for a bulb-no-liveview run; for longer sessions use USB-C power passthrough (DR-E18 dummy battery on the RP). The setup-help dialog in the scan screen has the same note.
- **Single concurrent connection** — only one phone can control a Pulsar BLE device at a time. CCAPI sessions are similarly camera-singular.
- **BLE pairing is Just Works** — no passkey, since the ESP32 has no I/O for PIN entry. Good enough for the threat model (someone in BLE range with intent to mess with your camera).
- **Camera auto-off during long CCAPI runs** — set the body's auto-off to disabled in the camera menu before multi-hour sessions; Pulsar can reconnect but the run will stall during the nap.

---

## License

GNU General Public License v3.0 or later (GPL-3.0-or-later). See [LICENSE](LICENSE) for the full text.
