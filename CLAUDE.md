# CLAUDE.md — Pulsar Trigger

Quick reference for AI assistants working on this project. See README.md for full details.

## Project

Open-source camera intervalometer + trigger. Five transports:
- **Pulsar ESP32 firmware** ↔ BLE (`bleController` in the app — universal, any camera with a wired remote port)
- **Canon CCAPI** ↔ Wi-Fi (`_canonCcapiTransport`, for EOS R-series with CCAPI activated — adds live view + Star Focus + lens info + battery)
- **USB PTP** ↔ USB-C (`_ptpTransport`, for Canon EOS R/RP and any PTP-capable body — the only phone-side path for the EOS R since CCAPI doesn't activate there)
- **Canon BLE direct** ↔ BLE (`_canonBleTransport`, speaks Canon's BR-E1 + smartphone-mode protocols — wireless, no hardware, every BR-E1-compatible body. Capability is bulb-class; no live view / lens / battery)
- **Canon Wi-Fi PTP (PTP/IP)** ↔ Wi-Fi (`_ptpIpTransport`, EOS bodies in "Remote Control (EOS Utility)" mode. Shares `PtpClient` with USB PTP via the `PtpWire` interface — only the wire differs. Wireless control path for the EOS R)

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
- CompositionLocals (`LocalDeviceStatus`, `LocalDeviceConnected`, `LocalRunState`, `LocalCurrentFlowStep`, `LocalNightMode`) for global state
- Transports are mutually exclusive. `bleController.connected`, `_canonTransport`, `_ptpTransport`, and `_simulatorActive` together drive `_connected`; picking one disconnects the others. **All four `connectX` methods must do mutual-exclusion teardown** — bug from v0.252 was simulator skipping this, causing PTP auto-reconnect to override the simulator tap.

## Key Gotchas

- **Five transports, one viewmodel.** Almost every viewmodel method that touches the wire has a Pulsar-BLE branch + CCAPI branch + PTP branch + Canon-BLE branch + PtpIp branch + simulator branch in `executeFlowStep`. When adding a new wire call think through all six paths. The four transports that implement `CameraTransport` (CCAPI, USB PTP, Canon BLE, PTP/IP) share `CanonRunner.kt`'s `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp`; ESP32 firmware owns its own run loop. USB PTP and PTP/IP additionally share `PtpClient` and split only at the `PtpWire` (USB-bulk vs TCP-packet) layer.
- **Run loops are transport-agnostic.** `transport/CanonRunner.kt` (`runCanonTimelapse` / `runCanonBulb` / `runCanonRamp`) takes a `CameraTransport`. Both `CcapiTransport` and `PtpTransport` implement it; ESP32 has its own firmware-side run loop and doesn't go through this. Despite the name, the runners are not Canon-specific — they're the phone-driven-shot-timing pattern.
- **CCAPI / PTP bulb capability is body-dependent.** `_canonCcapiTransport.value?.supportsBulb` (CCAPI: `/shutterbutton/manual` in endpoint matrix) and `_ptpTransport.value?.supportsBulb` (PTP: Canon `RemoteRelease` ops `0x9128` / `0x9125` advertised in `GetDeviceInfo`). UI dims bulb-based tiles when false. Canon BLE always reports `supportsBulb = true` — the press-and-hold pattern works on every BR-E1-compatible body.
- **Intervalometer gap semantics** — `intervalMs` is the GAP between exposures (expose → wait gap → repeat), NOT the total cycle time. Cycle = `exposureMs + intervalMs`.
- **Timelapse vs Intervalometer on Canon transports** — both produce `FlowStep.Intervalometer`. Timelapse stores `exposureMs = AppConfig.TIMELAPSE_PULSE_MS` as a sentinel; `executeFlowStep` dispatches on that to pick single-shot path (`fireShutter` → `InitiateCapture` on PTP, `/shutterbutton` on CCAPI) vs. bulb path (`startBulb`/`stopBulb`).
- **PTP locks the body's controls.** Canon's `SetRemoteMode(1)` puts the body in PC-remote mode — the dial and menu show "busy" until Pulsar disconnects. The user has to disconnect to change Bulb/Manual on the body. Document this in `ptp_connected_hint` copy.
- **PTP AF is wire-controlled.** `useAutofocus` toggle picks `RemoteReleaseOn` mode parameter: `2` = no AF, `3` = with AF. Track `lastBulbMode` so `RemoteReleaseOff` matches.
- **PTP `0x000C` is R-class only.** `setShutterMode(bulb=true)` writes that value as Bulb code; other Canon bodies need different codes. Best-effort: log on failure, user falls back to setting Bulb on the dial.
- **AF toggle UI gate.** All 5 wizards (Intervalometer, Astro, DarkFrame, Ramp, Timelapse) gate the `AutofocusToggle` on `onCanon || onPtp` (`val canControlAf = ...`). BLE-ESP path doesn't get a say in AF.
- **BLE packets are TLV** — not 20-byte fixed any more. Use `CommandBuilder` / `TlvReader`. The old fixed-frame doc is gone.
- **State is authoritative on firmware for BLE** — the app reads via TLV status notifications. For CCAPI + PTP, the app owns the run loop and writes `_status` simulator-style.
- **Simulator mode** — all command methods check `_simulatorActive` before touching BLE. Canon / PTP transports are similarly gated.
- **Mutual exclusion symmetry.** `connectTo` (Pulsar BLE), `connectCanonCcapi`, `connectPtp`, `connectCanonBle`, `connectSimulator` must each disconnect the others. `connectSimulator` originally didn't — the v0.253 fix added it. New transports should mirror this teardown in their `connect*` and be torn down from every other `connect*`.
- **Per-UDN / per-vendor-product / per-MAC persistence.** Canon CCAPI credentials (`pulsar_canon_creds`) and nicknames (`pulsar_canon_nicks`) are keyed by UDN, encrypted. PTP auto-reconnect target is keyed by `(vendorId, productId)`, in-memory only. Canon BLE auto-reconnect target is the last-good MAC (`pulsar_canon_ble.last_address`), plain SharedPrefs (the bond itself is in the OS keystore; this is just the hint).

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
- CCAPI / PTP changes that touch wire-level behavior should be tested against an actual Canon body. The Tools tab "Camera Test" tile is the fastest end-to-end probe (25 shots across all 5 modes).
- Update `docs/`, `README.md` when changing architecture, protocol, or hardware

## Adding a New Trigger Mode

1. `protocol.h` (firmware) → add `MODE_XXX` enum + packed params struct
2. `triggers.cpp` → parse in `set_mode()`, handle in `triggers_tick()` (non-blocking)
3. `Protocol.kt` → add `TriggerMode.XXX` + opcode
4. `CommandBuilder.kt` → add `setXxx()` builder
5. `PulsarViewModel.executeFlowStep()` → add the new `FlowStep` dispatch covering all four paths (BLE-ESP, CCAPI, PTP, simulator)
6. New wizard screen in `ui/screens/` or extend an existing one. Gate the AF toggle on `canControlAf = onCanon || onPtp`. Add a stepSummary case in `Intervalometer2Screen.stepSummary()` if the new mode should surface in the CurrentStepChip during multi-step runs.
7. Add menu tile in `MainMenuScreen.kt`
8. `docs/ble-protocol.md` → document the new opcode + TLVs
9. Add a new step to `vm.runCameraTest()` if the mode should be exercised by the Tools→Camera Test diagnostic

## Reference Docs

- [README.md](README.md) — full architecture, hardware, protocols
- [docs/ble-protocol.md](docs/ble-protocol.md) — BLE TLV wire format
- [docs/ccapi.md](docs/ccapi.md) — Canon CCAPI integration design
- [docs/ptp.md](docs/ptp.md) — USB PTP transport design (Canon EOS R + RP)
- [docs/ptp-ip.md](docs/ptp-ip.md) — Canon Wi-Fi PTP (PTP/IP) transport — same `PtpClient` over TCP
- [docs/canon-ble.md](docs/canon-ble.md) — Canon BLE direct transport (BR-E1 protocol, all BR-E1-compatible bodies)
- [docs/canon-ble-research.md](docs/canon-ble-research.md) — BR-E1 reverse-engineering log: all 6 refs decoded, EOS R GATT dump, the open "pairs-but-won't-shoot" bug. Diagnostic driver: `tools/canon_ble_test.py`
- [docs/mode-schema.md](docs/mode-schema.md) — user-mode preset JSON schema
- [docs/wiring.md](docs/wiring.md) — hardware schematics
- [.github/copilot-instructions.md](.github/copilot-instructions.md) — AI assistant conventions
