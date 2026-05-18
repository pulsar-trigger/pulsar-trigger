# CLAUDE.md — Pulsar Trigger

Quick reference for AI assistants working on this project. See README.md for full details.

## Project

Open-source camera intervalometer + trigger. Two transports:
- **ESP32 firmware** ↔ BLE (`bleController` in the app)
- **Canon CCAPI** ↔ WiFi (`_canonTransport` in the app, for EOS R-series)

License: GPL-3.0-or-later. Repo branch: `master`. Canon SDK PDFs live outside the repo at `../Canon-API/` (NDA — do not commit, do not copy verbatim).

## Architecture

**Firmware** (ESP32 / PlatformIO / Arduino):
- Single-threaded `loop()` — all modes driven by non-blocking `triggers_tick()`
- Source: `firmware/src/` + `firmware/include/`
- Only blocking function: `camera_shutter()` via `interruptible_delay()` (checks STATE_IDLE every 10 ms)
- Wire format: opcode + version + TLV (protocol `0x02`). See [docs/ble-protocol.md](docs/ble-protocol.md)
- Boards: `esp32dev`, `m5stick-s3`, `m5core2` (each is its own PlatformIO env)

**Android** (Kotlin / Jetpack Compose / Nordic BLE 2.7.5):
- Package: `com.ehrocha.pulsar`, minSdk 26, compileSdk 35
- Navigation: manual sealed class `AppScreen` (not Jetpack Navigation)
- Source: `android/app/src/main/java/com/ehrocha/pulsar/`
- CompositionLocals (`LocalDeviceStatus`, `LocalDeviceConnected`, `LocalRunState`, `LocalNightMode`) for global state
- Transports are mutually exclusive. `bleController.connected` and `_canonTransport` together drive `_connected`; flipping between them disconnects the other.

## Key Gotchas

- **Two transports, one viewmodel.** Almost every viewmodel method that touches the wire has a Canon branch + BLE branch + simulator branch. Don't add a new BLE call without thinking about the Canon equivalent.
- **CCAPI bulb capability is body-dependent.** `_canonTransport.value?.supportsBulb` is checked at flow-start; UI dims bulb-based tiles when false.
- **Intervalometer gap semantics** — `intervalMs` is the GAP between exposures (expose → wait gap → repeat), NOT the total cycle time. Cycle = `exposureMs + intervalMs`.
- **Timelapse vs Intervalometer on Canon** — both produce `FlowStep.Intervalometer`. Timelapse stores `exposureMs = AppConfig.TIMELAPSE_PULSE_MS` as a sentinel; `executeFlowStep` dispatches on that to pick single-shot path vs. bulb path.
- **BLE packets are TLV** — not 20-byte fixed any more. Use `CommandBuilder` / `TlvReader`. The old fixed-frame doc is gone.
- **State is authoritative on firmware for BLE** — the app reads via TLV status notifications. For CCAPI, the app owns the run loop and writes `_status` simulator-style.
- **Simulator mode** — all command methods check `_simulatorActive` before touching BLE. Canon transport is similarly gated by `_canonTransport.value`.
- **Per-UDN persistence** — Canon credentials (`pulsar_canon_creds`) and nicknames (`pulsar_canon_nicks`) are keyed by UDN, separate from the main `pulsar_settings` prefs.

## Build

```bash
# Android
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest

# Firmware
cd firmware
~/.platformio/penv/bin/pio run              # build all envs
~/.platformio/penv/bin/pio run -e esp32dev  # build one env
~/.platformio/penv/bin/pio run -t upload    # flash
~/.platformio/penv/bin/pio device monitor   # serial (115200 baud)
~/.platformio/penv/bin/pio test -e native   # unit tests
```

## Version Bump

```bash
./scripts/bump.sh "commit message"      # bump Android + firmware, commit, push
./scripts/bump.sh -a "commit message"   # Android only
./scripts/bump.sh -f "commit message"   # firmware only
./scripts/bump.sh                       # both, default message
```

- **"bump version" always means: increment version, commit, push** — never just edit the number
- **Always bump the version for every component whose code changed** — Android and/or firmware
- Android: `versionCode +1`, `versionName` minor +1 in `android/app/build.gradle.kts`
- Firmware: minor +1 across all envs in `firmware/platformio.ini` (`-DFW_VERSION_MINOR=N`)

## Versions (source of truth)

| Component | File |
|-----------|------|
| Firmware | `firmware/platformio.ini` build flags → `config.h` |
| Android | `android/app/build.gradle.kts` → `BuildConfig.VERSION_NAME` |

## Commit Conventions

- No `Co-Authored-By` trailers unless explicitly asked
- Commit message describes the *why*, not the *what*
- Always bump version(s) as part of the commit when code changes

## Conventions

- Non-blocking firmware: use `millis()` comparisons, never `delay()` in trigger tick
- `millis()` wraparound: unsigned subtraction `(now - target) < 0x80000000UL`
- Parameter clamping: all BLE values clamped to ranges in `config.h`
- Device names: sanitize to printable ASCII (0x20–0x7E) before NVS storage
- Every source file has the SPDX license header
- Always translate new strings into all 6 locale files (pt, es, fr, de, ja, zh) — not just `values/strings.xml`
- BLE interactions must be tested on real device (simulator can't validate timing)
- CCAPI changes that touch wire-level behavior should be tested against an actual Canon body
- Update `docs/`, `README.md` when changing architecture, protocol, or hardware

## Adding a New Trigger Mode

1. `protocol.h` (firmware) → add `MODE_XXX` enum + packed params struct
2. `triggers.cpp` → parse in `set_mode()`, handle in `triggers_tick()` (non-blocking)
3. `Protocol.kt` → add `TriggerMode.XXX` + opcode
4. `CommandBuilder.kt` → add `setXxx()` builder
5. `PulsarViewModel.executeFlowStep()` → add the new `FlowStep` dispatch (BLE + simulator + Canon paths)
6. New wizard screen in `ui/screens/` or extend an existing one
7. Add menu tile in `MainMenuScreen.kt`
8. `docs/ble-protocol.md` → document the new opcode + TLVs

## Reference Docs

- [README.md](README.md) — full architecture, hardware, protocols
- [docs/ble-protocol.md](docs/ble-protocol.md) — BLE TLV wire format
- [docs/ccapi.md](docs/ccapi.md) — Canon CCAPI integration design
- [docs/mode-schema.md](docs/mode-schema.md) — user-mode preset JSON schema
- [docs/wiring.md](docs/wiring.md) — hardware schematics
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — AI assistant conventions
