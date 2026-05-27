# Pulsar

An open-source camera intervalometer and trigger system for DSLR and mirrorless cameras. Two transports, one app:

- **ESP32 firmware** — wired remote-release driver over BLE. Works with any camera that has a remote port (Canon, Nikon, Sony, Fuji, etc.). Sub-millisecond accuracy, no WiFi required.
- **Canon CCAPI** — drives EOS R-series bodies directly over WiFi, no ESP32 in the middle. Bulb, Timelapse, Astro, Dark Frame, Ramp, Manual — all without extra hardware.

The Android app picks whichever transport you tapped in the scan screen. The wizards don't know or care which one is active.

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
| Firmware | 0.30.0 | `firmware/platformio.ini` build flags → `config.h` |
| Android | 0.220.0 | `android/app/build.gradle.kts` → `BuildConfig.VERSION_NAME` |

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

| Mode | Description | BLE | CCAPI |
|------|-------------|:---:|:-----:|
| **Intervalometer** | Bulb timelapse — configurable interval, exposure, shot count, start delay | ✅ | ✅\* |
| **Astro** | Star photography — auto-calculates max exposure via 500/400/NPF rule from focal length + crop factor | ✅ | ✅\* |
| **Timelapse** | Camera owns exposure (set shutter speed on the body); Pulsar pulses the shutter on a schedule | ✅ | ✅ |
| **Dark Frame** | Same shape as Intervalometer with lens-cap reminder, separate preset library | ✅ | ✅\* |
| **Ramp** | Exposure ramp — linear interpolation from start to end across N steps (sunset / sunrise) | ✅ | ✅\* |
| **Manual** | Press & hold (shutter open while button held) or press & lock (toggle) | ✅ | ✅ (press only) |
| **Custom Flow** | Multi-step sequence builder — chain any combination of modes and pauses | ✅ | ✅\* |

\* CCAPI bulb-based modes require `/shooting/control/shutterbutton/manual` support on the camera body. Pulsar detects this at connect time and dims the bulb modes if the body doesn't advertise it.

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

The viewmodel has both a `bleController` and a `_canonTransport`; whichever the user picks in the scan screen is the active transport. Wizards observe `vm.runState` and call `vm.startFlow()` — they don't reach down to either transport directly.

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
| `ble/` | BLE transport — Nordic BLE manager, scanner, TLV protocol, command builder, firmware OTA |
| `transport/` | `CameraTransport` interface + `ccapi/` subpackage for the Canon WiFi path |
| `model/` | Domain types — `FlowStep` (sealed), `RunState` (sealed), `UserMode` (presets), `ShotLog` |
| `viewmodel/` | `PulsarViewModel` — single state hub, flow runner, transport dispatch, persistence |
| `astro/` | Astro dashboard — sun/moon ephemeris, Milky Way visibility, Bortle, photo windows |
| `planner/` | Event/session planner with weather feasibility checks (Open-Meteo) |
| `sensor/` | Compass sensor wrapper (for Alignment screen) |
| `service/` | Foreground notification for running jobs |
| `update/` | APK self-update and background update checks |
| `ui/screens/` | All composable screens — see table below |
| `ui/components/` | Reusable widgets (`PulsarTopBar`, `ScrubField`, `NumPad`, `BatteryIndicator`) |

**Screens**

| Screen | Purpose |
|--------|---------|
| `ScanScreen` | Lists Pulsar BLE triggers + Canon CCAPI cameras, current WiFi SSID, connection dialogs, auth + setup help |
| `MainMenuScreen` | Pager with Dashboard / Trigger / Tools tabs; mode tile grid, Canon banner with reconnect state |
| `Intervalometer2Screen` | Wizard: Exposure → Interval → Delay → Shots tabs, Save preset, RunningView with battery chip |
| `AstroMode2Screen` | Wizard: Focal length / crop factor / rule → Gap → Delay → Shots; auto-computes exposure |
| `TimelapseScreen` | Wizard: Interval → Delay → Shots (no exposure tab — camera owns it) |
| `DarkFrame2Screen` | Wizard: Exposure → Interval → Shots, with lens-cap reminder |
| `Ramp2Screen` | Wizard: Start exposure → End exposure → Interval → Steps |
| `PresetPickerScreen` | Lists saved presets for a given mode; tap to open the wizard pre-filled |
| `ModeScreen` | Manual (press & hold / press & lock) — touch the big button to fire |
| `CustomFlowScreen` | Multi-step flow editor — add/edit/reorder/delete steps, save/load named flows |
| `ControlScreen` | Settings (device, GPIO pins, OTA, backup/restore, about) |
| `DashboardScreen` | Astro ephemeris dashboard — sun/moon, Milky Way visibility, weather, best windows |
| `PlannerScreen` / `SessionDetailScreen` / `MapLocationPicker` | Astro event planner with location/time/weather |
| `AlignmentScreen` | Polar alignment helper using phone compass + Polaris position |
| `WhatsUpScreen` | What's visible tonight — objects above horizon at the planned location/time |
| `StarFocusScreen` | 4-step CCAPI focus wizard (Prep → Aim → Focus → Lock); live view + tap-to-mark + drive-focus stepper |
| `ShotLogScreen` | History of completed runs — mode, shots, exposure, status |

**Key design decisions:**
- **CompositionLocals** (`LocalDeviceStatus`, `LocalDeviceConnected`, `LocalRunState`, `LocalNightMode`) provide global device state and theme mode to all screens — avoids parameter threading through deeply nested composables.
- **Night Mode** applies a red-tint color scheme for night-vision preservation at the telescope; toggled via the header icon.
- **Wizard pattern** — each multi-parameter mode is a TabRow of parameter tabs + Prev/Next + Start on the final tab. Start is always clickable; if config is incomplete, tapping it jumps to the first invalid tab.
- **Presets** are stored as `UserMode` records (per-fwMode) and live in `pulsar_user_modes` SharedPreferences. Bookmarked presets show as tiles in the Trigger tab; the rest live in `PresetPickerScreen` per mode.
- **Simulator mode** — virtual device that fakes a StatusFrame stream; lets you explore the UI without hardware.

---

## Transports

Pulsar talks to cameras through three mutually-exclusive transports, all routed through the same viewmodel: `bleController` (BLE-ESP32), `_canonTransport` (Canon CCAPI over Wi-Fi), and `_ptpTransport` (USB PTP). Tapping a device of one kind disconnects the others; the simulator is treated as a fourth transport for mutual-exclusion purposes.

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
- **Mid-shoot disconnect** — the reconnect banner shows on cable unplug; in-flight wire calls fail soft (no crash) but the running flow does not currently resume on replug. Full mid-shoot resume is future work.

Honest caveats:

- **PC-remote mode locks the body's controls.** Once Pulsar enters PC-remote mode (Canon `SetRemoteMode(1)`), the camera's mode dial and menu show "busy" until Pulsar disconnects. To change Bulb/Manual on the body, disconnect first. Canon's design, not a Pulsar bug.
- **`0x000C` is the R-class Bulb code.** Other Canon bodies may use a different `SetDevicePropValue(0xD102, ...)` value. Best-effort: if the write returns non-OK, Pulsar logs and falls back to the user-set-on-dial workflow.
- **Bulb modes only on Canon today.** `InitiateCapture` is universal (works across PTP-capable Nikon / Sony / Fuji bodies for Timelapse), but `RemoteReleaseOn/Off` are Canon vendor ops. Non-Canon bulb needs vendor-specific plumbing.

Full design: [docs/ptp.md](docs/ptp.md). The Tools tab has a "Camera Test" tile that fires 25 shots across all 5 modes to verify the active transport end-to-end.

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
│       ├── MainActivity.kt         ← Permissions, navigation, CompositionLocalProvider
│       ├── PulsarApp.kt
│       ├── AppConfig.kt            ← Compile-time constants
│       ├── ble/                    ← BleController, PulsarBleManager, Protocol, CommandBuilder, FirmwareUpdateManager
│       ├── transport/              ← CameraTransport interface
│       │   └── ccapi/              ← CcapiDiscovery, CcapiClient, CcapiTransport, CanonCamera, CameraDescription
│       ├── model/                  ← FlowStep, RunState, UserMode, ShotLog (sealed where appropriate)
│       ├── viewmodel/
│       │   └── PulsarViewModel.kt
│       ├── astro/                  ← Astro dashboard data + ephemeris
│       ├── planner/                ← Event planner + weather checks
│       ├── sensor/                 ← Compass for polar alignment
│       ├── service/                ← Foreground notification
│       ├── update/                 ← APK self-update
│       └── ui/
│           ├── screens/            ← All composable screens
│           ├── components/         ← Reusable widgets
│           └── theme/              ← Color schemes, CompositionLocals
├── web/                            ← ESP Web Tools browser-based installer
├── scripts/                        ← bump.sh (version + commit + push), build-android.sh
├── .github/workflows/              ← CI/CD (android.yml, firmware.yml)
└── docs/
    ├── ble-protocol.md             ← BLE wire format spec (TLV)
    ├── ccapi.md                    ← Canon CCAPI integration design
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

A live ephemeris view: sun/moon altitude and azimuth, Milky Way visibility window, Bortle-scale light-pollution estimate, hourly weather forecast (Open-Meteo), and best-window highlighting for nightscapes. Powered by `astro/AstroDashboardData.kt`.

### Planner

Create astro-imaging events, attach sessions with location/time/conditions, check weather feasibility. Uses Open-Meteo for forecasts; locations are picked on a MapLibre map with city search via Open-Meteo geocoding.

### What's Up

What's visible tonight at the planned location/time — Messier and NGC catalog filtered by altitude.

### Polar Alignment

Uses the phone's compass + Polaris position calculation to help align an equatorial mount.

### Star Focus Assist (CCAPI + PTP)

Four-step guided wizard for nailing pinpoint focus on stars before kicking off an astro run. Works on both Canon transports — `StarFocusScreen` takes a `CameraTransport` and reads from whichever Canon transport is active (CCAPI over Wi-Fi, or PTP over USB on bodies that advertise the live-view op). Live view streams from the camera, the user taps a bright star, and a peak-luminance sharpness readout updates per frame as they walk focus with six drive-focus buttons (`«««` / `««` / `«` / `»` / `»»` / `»»»`). Auto-stops live view on screen leave or disconnect to save battery. See [docs/ccapi.md](docs/ccapi.md#star-focus-assist-tools-tab).

### Shot Log

Every completed run gets a log entry: mode, exposure, interval, shot count, status (completed / stopped), timestamps. Survives uninstalls via SAF backup.

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
