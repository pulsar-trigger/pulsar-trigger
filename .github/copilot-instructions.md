# Pulsar Trigger — Copilot Instructions

## Project

Open-source camera intervalometer + trigger. **Two transports** share one app:

- **ESP32 firmware over BLE** (`bleController` in the viewmodel) — for any camera with a remote-release port via optocouplers.
- **Canon CCAPI over WiFi** (`_canonTransport`) — for EOS R-series bodies, no extra hardware.

Transports are mutually exclusive at runtime; the user picks one in the scan screen and the wizards drive whichever is active.

## Architecture

**Firmware** (ESP32 / PlatformIO / Arduino):
- Single-threaded `loop()` — all modes driven by non-blocking `triggers_tick()`
- Source: `firmware/src/` + `firmware/include/`
- Only blocking function: `camera_shutter()` via `interruptible_delay()` (checks STATE_IDLE every 10 ms)
- Wire format: 1-byte opcode + 1-byte version + TLV. See [`docs/ble-protocol.md`](../docs/ble-protocol.md)

**Android** (Kotlin / Jetpack Compose / Nordic BLE 2.7.5):
- Package: `com.ehrocha.pulsar`, minSdk 26, compileSdk 35
- Navigation: manual `AppScreen` sealed class (not Jetpack Navigation)
- All source: `android/app/src/main/java/com/ehrocha/pulsar/`
- Top-level packages: `ble/`, `transport/` (with `ccapi/`), `model/`, `viewmodel/`, `ui/`, plus astro / planner / update / sensor / service

## Conventions

- **Non-blocking firmware**: intervalometer uses `millis()` comparisons. Never use `delay()` in trigger tick.
- **`millis()` wraparound**: use unsigned subtraction `(now - target) < 0x80000000UL`.
- **Parameter clamping**: all BLE parameter values must be clamped to ranges in `config.h`.
- **TLV-only on BLE**: use `CommandBuilder` / `TlvReader`. Fixed 20-byte framing is gone.
- **BLE char refs**: must be `@Volatile` in `PulsarBleManager.kt`.
- **Device names**: sanitize to printable ASCII (0x20–0x7E) before NVS storage.
- **Translate every new string** into all 6 locale files (pt, es, fr, de, ja, zh) — Android falls back to the default, but the locale files are the source of truth for translated UIs.
- **`CcapiClient.Result` is a sealed return**: `Ok` / `Http(code, body)` / `NeedsAuth` / `Network(cause)`. Always branch on it explicitly.

## Key Gotchas

- **Every wire-touching VM method has THREE branches**: BLE, simulator, Canon. Missing the Canon branch is the most common review miss.
- **CCAPI capability is body-dependent**: `_canonTransport.value?.supportsBulb` reflects whether `/shooting/control/shutterbutton/manual` is advertised. UI tiles dim accordingly; `runCanonBulb` defends with an early throw.
- **Timelapse vs Intervalometer on Canon**: both produce `FlowStep.Intervalometer`. Timelapse stores `exposureMs = AppConfig.TIMELAPSE_PULSE_MS`; `executeFlowStep` reads that sentinel to route to single-shot (`runCanonTimelapse`) vs. bulb (`runCanonBulb`).
- **Mutually exclusive transports**: `connectCanon()` calls `bleController.disconnect()` first; `connectTo()` calls `disconnectCanon()` first.
- **BLE collectors are guarded**: `bleController.connected.collect` and `bleController.status.collect` no-op when `_canonTransport.value != null`, so a BLE disconnect doesn't kick the Canon user out.
- **Intervalometer gap semantics** — gap timer starts *after* exposure ends. Cycle = `exposureMs + intervalMs`, not just `intervalMs`.
- **BLE Just Works pairing** — ESP32 has no I/O for PIN, uses `ESP_IO_CAP_NONE`.
- **Per-UDN Canon storage**: credentials live in `pulsar_canon_creds`, nicknames in `pulsar_canon_nicks`, separate from `pulsar_settings`.
- **Camera auto-off** drops the CCAPI session. Pulsar reconnects for ~2 min; beyond that the run aborts. The in-app help dialog tells users to disable the body's auto-off for long runs.

## Adding a New Trigger Mode

1. `protocol.h` (firmware) → add `MODE_XXX` enum + packed `XxxParams` struct.
2. `triggers.cpp` → parse in `set_mode()`, handle in `triggers_tick()` (must be non-blocking).
3. `Protocol.kt` → add `TriggerMode.XXX` and its opcode.
4. `CommandBuilder.kt` → add `setXxx()` builder.
5. `PulsarViewModel.executeFlowStep()` → add the new `FlowStep` dispatch covering BLE / simulator / Canon paths.
6. New wizard screen in `ui/screens/`.
7. Add menu tile in `MainMenuScreen.kt`.
8. `docs/ble-protocol.md` → document new opcode + TLVs.

## Build

```bash
# Firmware (multi-env)
cd firmware
~/.platformio/penv/bin/pio run -e esp32dev      # build one env
~/.platformio/penv/bin/pio run                  # all envs
~/.platformio/penv/bin/pio run -t upload        # flash
~/.platformio/penv/bin/pio test -e native       # unit tests

# Android
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
```

## Version Bump

Use the bump script — never edit version numbers by hand:

```bash
./scripts/bump.sh "commit message"      # bump Android + firmware, commit, push
./scripts/bump.sh -a "commit message"   # Android only
./scripts/bump.sh -f "commit message"   # firmware only
```

The script bumps versions, builds, tests, commits, and pushes in one step.

## General Instructions

- Use `git pull --rebase` to keep a clean history.
- Follow existing code style and architecture patterns as closely as possible.
- For firmware, prioritize non-blocking code and careful state management.
- For Android, prefer Jetpack Compose idioms and clean separation of concerns (ViewModel owns state, composables render it).
- BLE interactions must be tested on a real device — the simulator can't validate timing or pairing.
- CCAPI changes that touch wire-level behavior should be tested against an actual Canon body.
- Always update documentation in `docs/` and `README.md` when making changes to architecture, protocols, or hardware.
- When editing screens, generate previews to verify UI changes render correctly.

## Reference

- [README.md](../README.md) — full architecture, hardware, both protocols
- [docs/ble-protocol.md](../docs/ble-protocol.md) — BLE TLV wire format
- [docs/ccapi.md](../docs/ccapi.md) — Canon CCAPI integration
- [docs/wiring.md](../docs/wiring.md) — hardware schematics
- [CLAUDE.md](../CLAUDE.md) — AI quick reference (similar to this file, kept in sync)
