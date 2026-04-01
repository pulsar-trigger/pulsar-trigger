# Pulsar Trigger — Copilot Instructions

## Project

Open-source BLE camera intervalometer/trigger: ESP32 firmware + Android companion app.

## Architecture

**Firmware** (ESP32 / PlatformIO / Arduino):
- Single-threaded `loop()` — all modes driven by non-blocking `triggers_tick()`
- Source: `firmware/src/` (main, ble_server, camera, triggers, status) + `firmware/include/` (config.h, protocol.h)
- Only blocking function: `camera_shutter()` via `interruptible_delay()` (checks STATE_IDLE every 10 ms)

**Android** (Kotlin / Jetpack Compose / Nordic BLE 2.7.5):
- Package: `com.ehrocha.pulsar`, minSdk 26, compileSdk 35
- Navigation: manual sealed class `AppScreen` (not Jetpack Navigation)
- All source: `android/app/src/main/java/com/ehrocha/pulsar/`

## Conventions

- **Non-blocking firmware**: sensor triggers use timestamp-based debounce, HDR uses bracket-index state machine, intervalometer uses `millis()` comparisons. Never use `delay()` in trigger tick.
- **millis() wraparound**: use unsigned subtraction pattern `(now - target) < 0x80000000UL`
- **Parameter clamping**: all BLE parameter values must be clamped to ranges in `config.h`
- **BLE packets**: always 20 bytes (ATT MTU). See `CommandBuilder.kt` for format.
- **BLE char refs**: must be `@Volatile` in `PulsarBleManager.kt`
- **Device names**: sanitize to printable ASCII (0x20–0x7E) before NVS storage

## Key Gotchas

- **ASTRO and INTERVALOMETER share firmware mode `0x01`** — distinction is app-side only. Auto-nav on reconnect must use `vm.currentMode`, not the mode byte from firmware.
- **`_focus_ms` single source of truth** is in `triggers.cpp` (not in camera.cpp)
- **BLE Just Works pairing** — ESP32 has no I/O for PIN, uses `ESP_IO_CAP_NONE`
- **`build.gradle.kts` namespace** must be `com.ehrocha.pulsar` (not `com.ehrocha`)
- **PlatformIO** lives in a Python venv — use full path or activate `zassist/.venv`

## Adding a New Trigger Mode

1. `protocol.h` → add `MODE_XXX` enum + packed `XxxParams` struct
2. `triggers.cpp` → parse in `set_mode()`, handle in `triggers_tick()` (must be non-blocking)
3. `Protocol.kt` → add `TriggerMode.XXX`
4. `CommandBuilder.kt` → add `setXxx()` builder
5. `PulsarViewModel.kt` → add `sendConfig()` case + state flows
6. `ControlScreen.kt` → add `XxxPanel` composable
7. `ModeScreen.kt` → wire panel + action buttons
8. `MainMenuScreen.kt` → add menu card
9. `docs/ble-protocol.md` → document new payload format

## Build

```bash
# Firmware
cd firmware && pio run              # build
pio run -t upload                   # flash

# Android
cd android && ./gradlew assembleDebug
```

## General Instructions
- Use `git pull --rebase` to keep a clean history.
- Follow existing code style and architecture patterns as closely as possible.
- For firmware, prioritize non-blocking code and careful state management.
- For Android, prefer Jetpack Compose idioms and clean separation of concerns (ViewModel for state, composables for UI).
- Always test BLE interactions with a real device, as simulators may not accurately reflect timing and connection behavior. 
- Always update documentation in `docs/` and `README.md` when making changes to architecture, BLE protocol, or hardware wiring.
- When editing screens always generate previews to verify UI changes render correctly.

## Reference

See [README.md](../README.md) for full architecture, hardware pinout, and BLE protocol details.
See [docs/ble-protocol.md](../docs/ble-protocol.md) for packet format specification.
See [docs/wiring.md](../docs/wiring.md) for hardware schematics.
