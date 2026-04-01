# Pulsar

An open-source camera intervalometer and trigger system.

**Android app** (BLE client) ↔ **ESP32 firmware** (BLE server + camera control)

---

## Trigger Modes

| Mode | Firmware ID | Description |
|------|:-----------:|-------------|
| **Intervalometer** | `0x01` | Time-lapse with configurable interval, exposure, shot count, and start delay |
| **Astro** | `0x01` | Star photography — auto-calculates max exposure via 500/400 rule. Reuses `INTERVALOMETER` on firmware; the Android app computes exposure from focal length/crop factor and sends it as an intervalometer config |
| **Sound** | `0x02` | Fires shutter when sound exceeds threshold (e.g. balloon pop, clap) |
| **Lightning** | `0x03` | Fires on sudden light change (photodiode sensor, 1–5 sensitivity scale) |
| **Laser** | `0x04` | Fires when a laser beam is broken (break-beam sensor) |
| **HDR** | `0x05` | Automatic exposure bracketing (2–5 exposures, non-blocking state machine) |
| **Press & Hold** | `0x06` | Shutter open while START is held |
| **Press & Lock** | `0x07` | Toggle shutter open/closed |

> **Note:** Astro mode shares firmware mode `0x01` with Intervalometer. The distinction is app-side only — the ViewModel tracks which mode was selected.

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
- All trigger modes are **non-blocking** — sensor triggers use timestamp-based debounce, HDR uses a state-machine bracket index, intervalometer uses `millis()` comparisons
- `camera_shutter()` is the only function that blocks (via `interruptible_delay`) and it checks `STATE_IDLE` every 10 ms to allow abort
- Parameter values from BLE are always **clamped** to safe ranges defined in `config.h`
- Battery ADC reads are **cached for 5 seconds** to avoid slow ADC reads on every status frame
- Device name bytes from BLE are **sanitized** to printable ASCII (0x20–0x7E) before saving to NVS
- `millis()` wraparound is handled via unsigned subtraction (`now - target < 0x80000000UL`)

### Android (Kotlin / Jetpack Compose / Nordic BLE)

| File | Responsibility |
|------|---------------|
| `MainActivity.kt` | Permission handling, `PulsarNavHost` composable navigation (Scan → Menu → Mode/Settings) |
| `PulsarApp.kt` | Application subclass (empty, used for manifest) |
| **ble/** | |
| `Protocol.kt` | UUIDs (`PulsarUuids`), command IDs (`Cmd`), `TriggerMode` enum, `DeviceState` enum, `StatusFrame` parser |
| `CommandBuilder.kt` | Builds 20-byte BLE command packets — one function per command/mode |
| `PulsarBleManager.kt` | Nordic BLE manager — GATT discovery, notifications, `sendCommand()`, connection lifecycle |
| **viewmodel/** | |
| `PulsarViewModel.kt` | All app state — scan, connection, mode config (intervalometer/astro/sound/etc.), notification lifecycle |
| **service/** | |
| `PulsarNotificationService.kt` | Foreground notification showing job progress, cancel action via broadcast |
| **ui/screens/** | |
| `ScanScreen.kt` | BLE scan UI with device list |
| `MainMenuScreen.kt` | Mode selection cards (Intervalometer, Astro, Manual) + Settings |
| `ModeScreen.kt` | Mode-specific config panel + running status display + Settings screen |
| `ControlScreen.kt` | Reusable panels: `IntervalometerPanel`, `AstroPanel`, `ManualPanel`, `SettingsPanel`, action buttons |
| **ui/components/** | |
| `TimePicker.kt` | hh:mm:ss scroll-wheel time input, converts to/from milliseconds |
| `ScrollPicker.kt` | Generic scroll-wheel number picker with tap-to-type |
| `LiveStatusPanel.kt` | Real-time status display (state dot, battery, mode, shot count) |

**Key design decisions:**
- Navigation is manual state (`AppScreen` sealed class), not Jetpack Navigation — simpler for a 4-screen app
- `TriggerMode.ASTRO` and `TriggerMode.INTERVALOMETER` share firmware id `0x01`. Auto-navigation on reconnect uses `vm.currentMode` (the ViewModel's local state) rather than the firmware's mode byte to resolve ambiguity
- BLE characteristic references are `@Volatile` for thread safety between the BLE callback thread and the main thread
- `CommandBuilder` always pads to 20 bytes (ATT MTU friendly)
- Cancel action from notification → broadcast → ViewModel → `stop()` → firmware

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
│       │   └── PulsarBleManager.kt
│       ├── viewmodel/
│       │   └── PulsarViewModel.kt
│       ├── service/
│       │   └── PulsarNotificationService.kt
│       └── ui/
│           ├── screens/
│           │   ├── ScanScreen.kt
│           │   ├── MainMenuScreen.kt
│           │   ├── ModeScreen.kt
│           │   └── ControlScreen.kt
│           ├── components/
│           │   ├── TimePicker.kt
│           │   ├── ScrollPicker.kt
│           │   └── LiveStatusPanel.kt
│           └── theme/
│               └── Theme.kt
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
| Sound sensor | 34 | Analog In | Electret mic module |
| Light sensor | 35 | Analog In | Photodiode (lightning) |
| Laser receiver | 32 | Analog In | Photoresistor (break-beam) |
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

### Known Limitations

- **Astro/Intervalometer ID collision:** Both use firmware mode `0x01`. Auto-nav on reconnect uses the ViewModel's tracked mode, not the firmware byte
- **No OTA firmware update** — currently requires USB flash
- **BLE pairing is Just Works** — no passkey since ESP32 has no I/O for PIN entry
- **Single concurrent connection** — only one phone can control the device at a time

---

## License

GNU General Public License v3.0 or later (GPL-3.0-or-later).

See [LICENSE](LICENSE) for the full text.
