# Pulsar

An open-source camera intervalometer and trigger system.
I have started this project a few years ago, but as I did not have time to take on development it was frozen since then. I still do not have much time available now, but with the help of AI this is finally becoming something useful.

I built this for my own benefit while photographing, but I figured if this is useful for me, maybe this is useful to other people, so, why not open source it? 

If you happen to try the app/firmware and want to suggest a fix, improvement or new feature, please open an issue and I will consider it. While i love the idea of collaboration, I still have not thought about the details on how to make this happen, so if you feel like contributing, just reach out and we will try to figure this out.


**Android app** (BLE client) ↔ **ESP32 firmware** (BLE server + camera control).

### Author

**Eduardo Henrique Rocha** — System Z Infrastructure Architect, passionate about mainframe modernization, cloud architecture, electronics, and photography.

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

| | Version | Source of truth |
|---|---------|----------------|
| Firmware | 0.5.0 | `firmware/platformio.ini` build flags → `config.h` via `#ifndef` |
| Android  | 0.30.0 | `android/app/build.gradle.kts` → `BuildConfig.VERSION_NAME` |

> ### 🐉 Here Be Dragons — Safety Warning
>
> **Pulsar drives camera shutter/focus lines through ESP32 GPIO pins.**
> Connecting GPIOs directly to your camera's remote-release port can permanently damage the camera's internal circuitry.
>
> **Always use an optocoupler** (e.g., PC817, 4N35) between the ESP32 and the camera.
> The optocoupler provides electrical isolation — the ESP32 side and the camera side share no common ground, so voltage spikes, logic-level mismatches, or wiring mistakes cannot reach the camera.
>
> **We are not responsible for any damage to cameras, ESP32 devices, or other equipment, use this at your own risk.**
> Double-check your circuit before powering on. If you are unsure, consult the [wiring guide](docs/wiring.md) or open an issue.

---

## Trigger Modes

| Mode | Firmware ID | Description |
|------|:-----------:|-------------|
| **Intervalometer** | `0x01` | Time-lapse with configurable interval, exposure, shot count, and start delay |
| **Astro** | `0x01` | Star photography — auto-calculates max exposure via 500/400 rule. Reuses `INTERVALOMETER` on firmware; the Android app computes exposure from focal length/crop factor and sends it as an intervalometer config |
| **Press & Hold** | `0x06` | Shutter open while START is held |
| **Press & Lock** | `0x07` | Toggle shutter open/closed |
| **Custom Flow** | app-only | Multi-step sequence builder — chain any combination of modes and pauses. App-orchestrated; sends individual mode commands to firmware in sequence (see [Custom Flow](#custom-flow)) |

> **Note:** Astro mode shares firmware mode `0x01` with Intervalometer. The distinction is app-side only — the ViewModel tracks which mode was selected. Custom Flow (`0x7F`) has no firmware counterpart — it is orchestrated entirely by the Android app.

---

## Architecture

### Firmware (ESP32 / PlatformIO / Arduino framework)

Single-threaded `loop()` architecture. All trigger modes are driven by `triggers_tick()` which runs once per loop iteration. Long operations use `interruptible_delay()` that yields to FreeRTOS and checks for STOP commands.

| File | Responsibility |
|------|---------------|
| `main.cpp` | `setup()` / `loop()` — LED status blink, calls `triggers_tick()` and `ble_handle_reinit()` |
| `ble_server.cpp` | BLE GATT server — service, command/status characteristics, bonding, name management |
| `camera.cpp` | GPIO control for shutter/focus optocouplers, `camera_shutter()` with interruptible pre-focus |
| `triggers.cpp` | State machine for all trigger modes, parameter parsing, tick dispatch |
| `status.cpp` | Battery ADC reading (cached 5 s), StatusFrame construction and BLE notify |
| `config.h` | All GPIO pins, timing defaults, parameter min/max ranges, BLE UUIDs |
| `protocol.h` | Shared enums (`Cmd`, `Mode`, `State`), packed payload structs, `StatusFrame` layout |

**Key design decisions:**
- All trigger modes are **non-blocking** — intervalometer uses `millis()` comparisons
- `camera_shutter()` is the only function that blocks (via `interruptible_delay`) and it checks `STATE_IDLE` every 10 ms to allow abort
- Parameter values from BLE are always **clamped** to safe ranges defined in `config.h`
- Battery ADC reads are **cached for 5 seconds** to avoid slow ADC reads on every status frame
- Device name bytes from BLE are **sanitized** to printable ASCII (0x20–0x7E) before saving to NVS
- `millis()` wraparound is handled via unsigned subtraction (`now - target < 0x80000000UL`)

### Android (Kotlin / Jetpack Compose / Nordic BLE)

| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Permission handling, `PulsarNavHost` composable navigation (Scan → Menu → Mode/Settings/Dashboard/Planner/Flows), `CompositionLocalProvider` for global device state |
| `PulsarApp.kt` | Application subclass (empty, used for manifest) |
| `AppConfig.kt` | Compile-time constants — timing limits, API URLs, default values |
| **ble/** | |
| `Protocol.kt` | UUIDs (`PulsarUuids`), command IDs (`Cmd`), `TriggerMode` enum, `DeviceState` enum, `StatusFrame` parser |
| `CommandBuilder.kt` | Builds 20-byte BLE command packets — one function per command/mode |
| `PulsarBleManager.kt` | Nordic BLE manager — GATT discovery, notifications, `sendCommand()`, connection lifecycle |
| `FirmwareUpdateManager.kt` | OTA firmware update orchestration — download from GitHub, BLE chunked upload, progress tracking |
| **model/** | |
| `FlowStep.kt` | `FlowStep` data class (step types + parameters), `SavedFlow` data class (named flow presets), JSON serialization |
| **viewmodel/** | |
| `PulsarViewModel.kt` | All app state — scan, connection, mode config, notification lifecycle, settings persistence (SharedPreferences), simulator engine, custom flow execution |
| **astro/** | |
| `AstroDashboardData.kt` | Sun/moon ephemeris, Milky Way visibility, Bortle-scale calculations, best photo window computation |
| **planner/** | |
| `PlannerData.kt` | Data models for events, sessions, locations, and weather conditions |
| `PlannerManager.kt` | Event/session CRUD, persistence, condition checking orchestration |
| `ConditionCheckWorker.kt` | Background `CoroutineWorker` — fetches weather forecasts and evaluates session conditions |
| `GeocodingApi.kt` | Open-Meteo geocoding API client for city/location search |
| **update/** | |
| `AppUpdateManager.kt` | APK self-update — checks GitHub releases for newer `app-v*` tags, downloads APK, triggers system installer |
| `UpdateCheckWorker.kt` | Background periodic check for new app/firmware versions |
| `VersionUtils.kt` | Semantic version comparison utilities |
| **service/** | |
| `PulsarNotificationService.kt` | Foreground notification showing job progress, cancel action via broadcast |
| **ui/screens/** | |
| `ScanScreen.kt` | BLE scan UI with device list + simulator entry point |
| `MainMenuScreen.kt` | Mode selection cards (Intervalometer, Astro, Manual, Custom Flow) + Dashboard + Planner + Settings |
| `ModeScreen.kt` | Mode-specific config panel + running status display with live countdown timer + Settings screen (device, GPIO, updates, backup, about) + OTA firmware update UI |
| `ControlScreen.kt` | Reusable panels: `IntervalometerPanel`, `AstroPanel`, `ManualPanel`, settings section panels (defaults, GPIO pins, backup/restore, app/firmware update, device info), action buttons |
| `CustomFlowScreen.kt` | Flow builder UI — add/edit/reorder/delete steps, save/load named flows, execute with live progress |
| `DashboardScreen.kt` | Astro dashboard — sun/moon ephemeris, Milky Way visibility, Bortle scale, weather, best photo windows, hourly forecasts for a given location and date |
| `PlannerScreen.kt` | Event planner with sessions — create astro-imaging events, add sessions with location/time/conditions, check weather feasibility |
| `SessionDetailScreen.kt` | Session detail view — condition results, weather data, go/no-go assessment |
| `MapLocationPicker.kt` | Interactive map picker for session locations with city search via Open-Meteo geocoding |
| **ui/components/** | |
| `TimePicker.kt` | hh:mm:ss scroll-wheel time input, converts to/from milliseconds |
| `ScrollPicker.kt` | Generic scroll-wheel number picker with tap-to-type |
| `IntStepperField.kt` | Integer input with +/- stepper buttons |
| `LiveStatusPanel.kt` | Real-time status display (state dot, battery, mode, shot count) |
| `BatteryIndicator.kt` | Header battery indicator with status LED dot (green=idle, red=running, amber=error) + night mode toggle |

**Key design decisions:**
- Navigation is manual state (`AppScreen` sealed class), not Jetpack Navigation — simpler for a screen-based app
- `TriggerMode.ASTRO` and `TriggerMode.INTERVALOMETER` share firmware id `0x01`. Auto-navigation on reconnect uses `vm.currentMode` (the ViewModel's local state) rather than the firmware's mode byte to resolve ambiguity
- BLE characteristic references are `@Volatile` for thread safety between the BLE callback thread and the main thread
- `CommandBuilder` always pads to 20 bytes (ATT MTU friendly)
- Cancel action from notification → broadcast → ViewModel → `stop()` → firmware
- **CompositionLocals** (`LocalDeviceStatus`, `LocalDeviceConnected`, `LocalNightMode`) provide global device state and theme mode to all screens — avoids parameter threading through deeply nested composables
- **Night Mode** applies a red-tint color scheme (`RedLightColorScheme`) for night-vision preservation at the telescope; toggled via header icon on every screen

### Settings Persistence

Intervalometer defaults (interval, exposure, shot count, start delay), the max shot count limit, and GPIO pin assignments are persisted via **SharedPreferences** (file: `pulsar_settings`). Values are loaded at ViewModel init and applied as working values. The SettingsPanel provides controls to adjust defaults and a "Reset to Factory Defaults" button.

### GPIO Pin Configuration

Shutter and focus GPIO pins are **configurable at runtime** (defaults: 25 and 26). The Settings screen shows dropdowns restricted to **safe output pins**: `[4, 13, 14, 16, 17, 18, 19, 21, 22, 23, 25, 26, 27]`. Validation ensures both pins are different. Pin configuration is sent to the ESP32 via the `SET_PINS` command (`0x09`) on every BLE connection. Pins are persisted in SharedPreferences and included in settings export/import.

### Custom Flow

Custom Flow is an **app-orchestrated** multi-step shooting sequence. The user builds a flow from any combination of trigger modes and pauses:

| Step Type | Parameters | Execution |
|-----------|-----------|-----------|
| Intervalometer | interval, exposure, shot count, delay | Sends intervalometer command; waits for firmware IDLE |
| Astro | focal length, crop factor, rule divisor, gap, count, delay | Computes exposure; sends as intervalometer; waits for IDLE |
| Pause | label text | Blocks until user taps "Continue" |

The flow builder lets you add, edit, reorder (move up/down), and delete steps. During execution, the UI highlights the current step and marks completed ones.

### Saved Flows

Flows can be **saved as named presets** and loaded later. The saved flow library is persisted in SharedPreferences and included in settings export/import. From the Custom Flow screen:

- **Save Flow** — prompts for a name; overwrites if a flow with the same name exists
- **Load Flow** — shows the library with step counts; tap to load, swipe to delete

### OTA Firmware Update

The app can update the ESP32 firmware over BLE without USB. The OTA flow has **4 phases**:

1. **DOWNLOADING** — fetches the latest `firmware-v*` release from the GitHub Releases API, downloads the `.bin` asset
2. **UPLOADING** — sends the binary to the ESP32 in **8 KB chunks** (10 ms throttle) via the OTA Data characteristic
3. **VALIDATING** — device verifies CRC and prepares to reboot
4. **COMPLETE** — device reboots automatically; app prompts to reconnect

During OTA, a **full-screen overlay** blocks all interaction with a "Do not disconnect device" warning. The back button is disabled. A progress bar and percentage are shown for download and upload phases.

**OTA BLE Service:**

| UUID | Characteristic | Purpose |
|------|---------------|---------|
| `0000ff10-...` | OTA Service | — |
| `0000ff11-...` | OTA Control (Write) | BEGIN (`0x01`), END (`0x02`), ABORT (`0x03`) |
| `0000ff12-...` | OTA Data (Write) | Firmware binary chunks |

### App Self-Update

The Settings screen includes an **app update checker** that queries the GitHub Releases API for `app-v*` tags. If a newer version is found:

1. The user taps "Download" — the APK is streamed to the app cache with a progress bar
2. Once downloaded, the app triggers the system package installer via `FileProvider` + `ACTION_INSTALL_PACKAGE`

Version comparison uses semantic versioning (`isNewer()` compares major.minor.patch).

### Backup & Restore

Settings can be exported/imported via Android's **Storage Access Framework** (SAF). This supports Google Drive, local storage, OneDrive, Dropbox, or any other DocumentsProvider installed on the device — no extra dependencies or OAuth required.

- **Export:** Opens the system file picker (`CreateDocument`) with default filename `pulsar-settings.json`. Writes all persisted settings as formatted JSON.
- **Import:** Opens the system file picker (`OpenDocument`) filtered to `application/json`. Reads and applies settings.

**Exported JSON includes:**
- Intervalometer defaults (interval, exposure, shot count, delay, max shots)
- GPIO pin assignments (shutter, focus)
- Active custom flow steps
- Saved flows library (all named flow presets)

### Simulator Mode

The app can run without a physical ESP32 device. On the scan screen, a "Use Simulator" card connects to a virtual device with a fake 85% battery. The simulator:

- Creates synthetic `StatusFrame`s so all UI screens render correctly
- Simulates intervalometer/astro runs with realistic timing (delay → expose → gap → next shot → idle)
- Supports `start`, `stop`, `singleShot`, `shutterDown`/`shutterUp`, and `renameDevice`
- Disconnects cleanly back to the scan screen

### BLE Security

- The command characteristic (`FF01`) requires **encrypted writes** (`ESP_GATT_PERM_WRITE_ENCRYPTED`)
- The firmware enables **BLE Secure Connections with MITM protection and bonding** (`ESP_LE_AUTH_REQ_SC_MITM_BOND`)
- Pairing uses **Just Works** (`ESP_IO_CAP_NONE`) since the ESP32 has no display/keyboard
- Once bonded, reconnections reuse the stored keys automatically
- The status characteristic (`FF02`, notify) is readable without encryption

---

## Repository Structure

```
pulsar-trigger/
├── firmware/                 ← ESP32 PlatformIO project
│   ├── platformio.ini        ← Board: esp32dev, framework: arduino
│   ├── partitions.csv        ← Custom partition table (with OTA)
│   ├── include/
│   │   ├── config.h          ← GPIO pins, ranges, BLE UUIDs, battery constants
│   │   ├── protocol.h        ← Cmd/Mode/State enums, packed payload structs
│   │   ├── ble_server.h
│   │   ├── camera.h
│   │   ├── triggers.h
│   │   └── status.h
│   └── src/
│       ├── main.cpp
│       ├── ble_server.cpp
│       ├── camera.cpp
│       ├── triggers.cpp
│       └── status.cpp
├── android/                  ← Android Studio / Gradle project
│   ├── app/build.gradle.kts  ← compileSdk 35, minSdk 26, Compose + Nordic BLE
│   └── app/src/main/java/com/ehrocha/pulsar/
│       ├── MainActivity.kt
│       ├── PulsarApp.kt
│       ├── ble/
│       │   ├── Protocol.kt
│       │   ├── CommandBuilder.kt
│       │   ├── PulsarBleManager.kt
│       │   └── FirmwareUpdateManager.kt
│       ├── AppConfig.kt              ← Compile-time constants
│       ├── model/
│       │   └── FlowStep.kt           ← FlowStep + SavedFlow data models
│       ├── astro/
│       │   └── AstroDashboardData.kt ← Ephemeris, Milky Way, Bortle, photo windows
│       ├── planner/
│       │   ├── PlannerData.kt        ← Event/session/location data models
│       │   ├── PlannerManager.kt     ← CRUD + condition orchestration
│       │   ├── ConditionCheckWorker.kt ← Background weather check
│       │   └── GeocodingApi.kt       ← Open-Meteo city search
│       ├── viewmodel/
│       │   └── PulsarViewModel.kt
│       ├── update/
│       │   ├── AppUpdateManager.kt   ← APK self-update from GitHub
│       │   ├── UpdateCheckWorker.kt  ← Background update check
│       │   └── VersionUtils.kt       ← Semantic version comparison
│       ├── service/
│       │   └── PulsarNotificationService.kt
│       └── ui/
│           ├── screens/
│           │   ├── ScanScreen.kt
│           │   ├── MainMenuScreen.kt
│           │   ├── ModeScreen.kt
│           │   ├── ControlScreen.kt
│           │   ├── CustomFlowScreen.kt
│           │   ├── DashboardScreen.kt
│           │   ├── PlannerScreen.kt
│           │   ├── SessionDetailScreen.kt
│           │   └── MapLocationPicker.kt
│           ├── components/
│           │   ├── TimePicker.kt
│           │   ├── ScrollPicker.kt
│           │   ├── IntStepperField.kt
│           │   ├── LiveStatusPanel.kt
│           │   └── BatteryIndicator.kt
│           └── theme/
│               └── Theme.kt          ← Color schemes, CompositionLocals
├── web/                      ← ESP Web Tools browser-based installer
│   └── index.html
├── .github/workflows/        ← CI/CD
│   ├── android.yml           ← Build + sign + release APK
│   └── firmware.yml          ← Build + release firmware + ESP Web Tools manifest
└── docs/
    ├── ble-protocol.md       ← BLE packet format specification
    └── wiring.md             ← Full wiring guide with circuits
```

---

## Hardware

| Component | GPIO | Direction | Purpose |
|-----------|:----:|:---------:|---------|
| Shutter optocoupler | 25 | Output | Camera remote shutter |
| Focus optocoupler | 26 | Output | Camera remote focus |
| Status LED | 2 | Output | On-board LED |
| Battery voltage | 33 | Analog In | Resistor divider (2:1), 3.2–4.2 V LiPo |

See [docs/wiring.md](docs/wiring.md) for full schematics.

---

## Building

### Firmware

**Prerequisites:** [PlatformIO CLI](https://docs.platformio.org/en/latest/core/installation.html)

```bash
cd firmware
pio run                  # build
pio run -t upload        # flash to connected ESP32
pio device monitor       # serial console (115200 baud)
```

The build produces `firmware/.pio/build/esp32dev/firmware.bin` (~1.1 MB).

### Android

**Prerequisites:**
- JDK 17 (e.g. [Eclipse Temurin](https://adoptium.net/))
- Android SDK with platform 35 and build-tools 35.0.0

**SDK setup** (if not using Android Studio):
```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

**Configure `android/local.properties`** (not tracked in git):
```properties
sdk.dir=/path/to/your/Android/Sdk
```

JDK 17 is pinned in `gradle.properties` via `org.gradle.java.home`. Update the path if your JDK 17 installation differs.

```bash
cd android
./gradlew assembleDebug        # build
./gradlew installDebug         # build + install on connected device
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

**Android Studio:** Open the `android/` directory directly.

---

## BLE Protocol

See [docs/ble-protocol.md](docs/ble-protocol.md) for the full packet specification.

**Quick reference:**

| UUID | Characteristic | Security |
|------|---------------|----------|
| `0000ff00-...` | Service | — |
| `0000ff01-...` | Command (Write) | Encrypted (bonded) |
| `0000ff02-...` | Status (Notify) | Open |
| `0000ff10-...` | OTA Service | — |
| `0000ff11-...` | OTA Control (Write) | BEGIN/END/ABORT |
| `0000ff12-...` | OTA Data (Write) | Firmware binary chunks |

**Commands** (byte 0 of write to FF01):

| Byte | Command | Payload |
|:----:|---------|---------|
| `0x01` | SET_MODE | mode(u8) + mode-specific params |
| `0x02` | START | — |
| `0x03` | STOP | — |
| `0x04` | SHUTTER | — (single manual fire) |
| `0x05` | STATUS_REQ | — |
| `0x06` | SET_FOCUS | focus_ms(u16 LE) |
| `0x08` | SET_NAME | suffix bytes (printable ASCII, max 12) |
| `0x09` | SET_PINS | shutter_pin(u8), focus_pin(u8) |

**StatusFrame** (20 bytes, notify on FF02):

| Offset | Field | Type |
|:------:|-------|------|
| 0 | state | u8 (0=IDLE, 1=RUNNING, 2=WAITING, 3=ERROR) |
| 1 | mode | u8 |
| 2–3 | shots_taken | u16 LE |
| 4–7 | time_remaining_ms | u32 LE |
| 8 | battery_pct | u8 (0–100) |
| 9 | error_code | u8 |
| 10–19 | reserved | — |

---

## CI/CD

GitHub Actions workflows automate builds and releases on push to `master`.

### Firmware (`firmware.yml`)

Triggers on changes to `firmware/**`. Extracts version from `platformio.ini` build flags, builds with PlatformIO, generates an ESP Web Tools `manifest.json`, and creates a GitHub Release tagged `firmware-v<version>` with the `.bin` artifact.

### Android (`android.yml`)

Triggers on changes to `android/**`. Extracts version from `build.gradle.kts`, decodes the release keystore from the `KEYSTORE_BASE64` GitHub secret, builds a signed release APK via `assembleRelease`, and creates a GitHub Release tagged `app-v<version>` with the APK attached.

**Required GitHub Secrets:** `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

---

## ESP Web Tools

A browser-based firmware installer is available at `web/index.html`. It uses [ESP Web Tools](https://esphome.github.io/esp-web-tools/) to flash the ESP32 via USB directly from the browser (WebSerial API). The firmware CI/CD generates the required `manifest.json` with bootloader, partition, and firmware binary offsets.

---

## Development Notes

### Conventions

- **License:** GPL-3.0-or-later — every source file has the SPDX header
- **Firmware language:** C++ (Arduino framework on ESP32)
- **Android language:** Kotlin, Jetpack Compose (Material 3), no XML layouts
- **BLE library:** Nordic Android BLE (`no.nordicsemi.android:ble:2.7.5`)
- **Permissions library:** Accompanist Permissions (`com.google.accompanist:accompanist-permissions:0.36.0`)
- **All packed structs** use `__attribute__((packed))` and match the BLE wire format exactly
- **Parameter validation** happens in `triggers_set_mode()` — all values clamped to `config.h` ranges
- **State is authoritative on firmware** — the Android app reads state via StatusFrame notifications

### Adding a New Trigger Mode

1. Add `MODE_XXX` to `enum Mode` in `protocol.h` (pick next unused byte)
2. Define a packed `XxxParams` struct in `protocol.h`
3. Add parsing case in `triggers_set_mode()` in `triggers.cpp`
4. Add tick logic in `triggers_tick()` — must be **non-blocking**
5. Add `TriggerMode.XXX` to the Kotlin enum in `Protocol.kt`
6. Add `CommandBuilder.setXxx()` in `CommandBuilder.kt`
7. Add `sendConfig()` case in `PulsarViewModel.kt`
8. Add UI screen/panel in `ControlScreen.kt` and wire into `ModeScreen.kt`
9. Add menu entry in `MainMenuScreen.kt`
10. Update `docs/ble-protocol.md`

### Intervalometer Timing

The intervalometer uses **gap semantics**: expose → wait gap → repeat. The gap timer starts *after* the exposure ends, not at the start of the shot. Serial debug output (115200 baud) prints `[INTV] config:`, `[INTV] shot N firing at`, and `[INTV] gap X ms` for timing verification.

### Live Countdown Timer

The running status screen updates in real-time using a client-side 100 ms tick (`LaunchedEffect`). The countdown interpolates from the last firmware status update, so the progress bar and time remaining animate smoothly between shot boundaries. Uses `animateFloatAsState` for the progress bar.

### Known Limitations

- **Astro/Intervalometer ID collision:** Both use firmware mode `0x01`. Auto-nav on reconnect uses the ViewModel's tracked mode, not the firmware byte
- **BLE pairing is Just Works** — no passkey since ESP32 has no I/O for PIN entry
- **Single concurrent connection** — only one phone can control the device at a time

---

## License

GNU General Public License v3.0 or later (GPL-3.0-or-later).

See [LICENSE](LICENSE) for the full text.
