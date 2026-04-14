# CLAUDE.md — Pulsar Trigger

Quick reference for AI assistants working on this project. See README.md for full details.

## Project

Open-source BLE camera intervalometer/trigger: **ESP32 firmware** + **Android companion app**.
Repo branch: `master`. License: GPL-3.0-or-later.

## Architecture

**Firmware** (ESP32 / PlatformIO / Arduino):
- Single-threaded `loop()` — all modes driven by non-blocking `triggers_tick()`
- Source: `firmware/src/` + `firmware/include/`
- Only blocking function: `camera_shutter()` via `interruptible_delay()` (checks STATE_IDLE every 10 ms)

**Android** (Kotlin / Jetpack Compose / Nordic BLE 2.7.5):
- Package: `com.ehrocha.pulsar`, minSdk 26, compileSdk 35
- Navigation: manual sealed class `AppScreen` (not Jetpack Navigation)
- Source: `android/app/src/main/java/com/ehrocha/pulsar/`
- CompositionLocals (`LocalDeviceStatus`, `LocalDeviceConnected`, `LocalNightMode`) for global state

## Key Gotchas

- **ASTRO and INTERVALOMETER share firmware mode `0x01`** — distinction is app-side only via `vm.currentMode`
- **Intervalometer gap semantics** — `intervalMs` is the GAP between exposures (expose → wait gap → repeat), NOT the total cycle time. Cycle = exposureMs + intervalMs.
- **BLE packets** always 20 bytes. Char refs must be `@Volatile` in PulsarBleManager.
- **State is authoritative on firmware** — Android reads via StatusFrame BLE notifications
- **Simulator mode** — all command methods check `_simulatorActive` before touching BLE
- **ViewModel owns connection state** — `_connected`/`_status` are MutableStateFlows forwarded from BLE manager

## Build

```bash
# Android
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest

# Firmware
cd firmware && pio run              # build
pio run -t upload                   # flash
pio device monitor                  # serial (115200 baud)
```

## Version Bump (Android)

```bash
./scripts/bump-android.sh "Your commit message"   # custom message
./scripts/bump-android.sh                          # default message
```

Bumps versionCode +1 and versionName minor +1, builds, tests, commits, and pushes.

## Versions (source of truth)

| Component | File |
|-----------|------|
| Firmware | `firmware/platformio.ini` build flags → `config.h` |
| Android | `android/app/build.gradle.kts` → `BuildConfig.VERSION_NAME` |

## Conventions

- Non-blocking firmware: use `millis()` comparisons, never `delay()` in trigger tick
- `millis()` wraparound: unsigned subtraction `(now - target) < 0x80000000UL`
- Parameter clamping: all BLE values clamped to ranges in `config.h`
- Device names: sanitize to printable ASCII (0x20–0x7E) before NVS storage
- Every source file has the SPDX license header
- Compose previews should be generated to verify UI changes
- BLE interactions must be tested on real device (simulator can't validate timing)
- Update `docs/`, `README.md` when changing architecture, protocol, or hardware

## Adding a New Trigger Mode

1. `protocol.h` → add `MODE_XXX` enum + packed params struct
2. `triggers.cpp` → parse in `set_mode()`, handle in `triggers_tick()` (non-blocking)
3. `Protocol.kt` → add `TriggerMode.XXX`
4. `CommandBuilder.kt` → add `setXxx()` builder
5. `PulsarViewModel.kt` → add `sendConfig()` case + state flows
6. `ControlScreen.kt` → add panel composable
7. `ModeScreen.kt` → wire panel + action buttons
8. `MainMenuScreen.kt` → add menu card
9. `docs/ble-protocol.md` → document payload format

## Reference Docs

- [README.md](README.md) — full architecture, hardware, BLE protocol
- [docs/ble-protocol.md](docs/ble-protocol.md) — packet format specification
- [docs/wiring.md](docs/wiring.md) — hardware schematics
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — AI assistant conventions
