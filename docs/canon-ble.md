# Canon direct-BLE transport (researched, not building)

Pulsar's would-be **fourth transport**: drive a Canon body directly over its BLE-Remote service from the phone — no ESP32 in the middle, no WiFi, no CCAPI activation. This doc was the design intent; it now also records why we backed off after a real-device verification.

**Status (2026-05-19):** researched, not implemented. The verification gate against an EOS RP found that the R-series GATT layout differs fundamentally from the M50 our protocol research was based on, and the RP's BLE stack actively defends against blind probing — one wrong byte and the camera drops the link. Without a BLE sniffer to capture Canon Camera Connect's real traffic, the R-series cost/risk doesn't justify the WiFi-battery savings, given CCAPI already works on these bodies. See the *Probe findings* section below.

For M-series bodies (M50 / M50 II / M6 II) the [robot9706/CanonBLEIntervalometer](https://github.com/robot9706/CanonBLEIntervalometer) protocol notes should still apply, but Pulsar has no verified implementation against any M-body. The implementation plan further down is preserved as scaffolding if someone with an M-class body picks this up later. **Don't start building this transport for the R-series.**

The Canon-side wire format was reverse-engineered by robot9706 from an M50. Canon publishes nothing about BLE-Remote, so all constants here are best-effort from one device (M50) plus one probe (RP).

## Why a fourth transport

CCAPI shipped (v0.209–v0.221) and is great for *settings control*: ISO, aperture, live view, drive-focus, lens info, battery readout. But it has three real-world pain points:

| Pain point | CCAPI today | Direct-BLE answer |
|---|---|---|
| **Camera battery** | WiFi + web server burns ~30 %/h on the RP. A 4-hour astro night kills a full LP-E17. | BLE-Remote on the body costs a few % per hour. Phone-side BLE central draws ~2–5 mA vs. ~80–150 mA for WiFi. Multi-night runs on one charge become realistic. |
| **Sub-second bulb exposures** | Each open/close is ~100–200 ms HTTP RTT with WiFi jitter. We warn the user below 1 s. | BLE writes are ~30–50 ms with much less jitter. The astro / dark-frame short-exposure regime becomes accurate. |
| **EOS M-series coverage** | M50 / M50 II / M6 II never got CCAPI. Pulsar can't talk to them today. | M-series supports BLE-Remote. The same protocol covers them. |

It is **not** a replacement for CCAPI. The protocol is shutter-only — no settings, no live view, no lens info, no battery readout, no drive-focus. The endgame is *both transports active on the same body*: BLE for triggering, CCAPI for everything else, and the user picks per-session which they want.

## Probe findings — EOS RP, 2026-05-19

Used `tools/canon_ble_probe.py` (bleak / BlueZ from the Fedora host) against an EOS RP in *Wireless features → Bluetooth function → Remote → Pair* mode. Three rounds, all from real GATT discovery.

**Round 1 — advertisement scan.** The body advertises:

- Local name: `MonsteRP`
- Manufacturer data: company ID `0x01A9` (Canon Inc.) + 19 zero-padded bytes
- One 128-bit service UUID: `00050000-0000-1000-0000-d8492fffa821` (Canon namespace, family `0x0005`)
- Standard Bluetooth fields (TX power +6 dBm, Appearance = Generic Tag)

**Round 2 — service discovery post-connect.** After connecting + Just-Works bond, the RP exposes **one** Canon-namespace service, family `0x0005`, with 10 characteristics. The M50's pair (`0x0001`) and trig (`0x0003`) services are **not present**. Characteristic layout:

| Short UUID | Properties | Initial value |
|---|---|---|
| `00050001` | read | `01 00 00 00` (looks like a version flag) |
| `00050002` | write, write-no-response | — |
| `00050003` | write, write-no-response | — |
| `00050004` | read, indicate | `00 00` |
| `00050005` | write, write-no-response | — |
| `00050006` | read, indicate | `01` |
| `00050007` | read, indicate | `00 00` |
| `0005000a` | write, write-no-response | — |
| `0005000b` | read, indicate | 18 bytes of zeros |
| `0005000c` | write, write-no-response | — |

**Round 3 — brute-force probe (5 writable chars × 3 payload variants).** Subscribed to all 4 indicate chars first to capture any push responses, then walked the writable chars in sequence with candidate payloads (M50's `00 01`/`00 02`, byte-swapped, single-byte). Results:

- Writes to `00050003` and `0005000c` were **silently accepted** — no shutter, no indications, no visible reaction. Likely "ignored unless preceded by the right handshake."
- Write to `00050002` was **actively rejected** with GATT error `0x0E` ("Unlikely Error"). The camera's state machine validates payload content per char.
- **The single rejected write caused the body to drop the link**, after which every subsequent write failed with "service discovery has not been performed yet" until the script reconnected. The RP defends against unknown-protocol probing by disconnecting on first invalid input.

**Conclusion:** the M50 protocol bytes don't apply to the RP, the RP's protocol can't be reverse-engineered by blind GATT probing (the disconnect-on-bad-write behaviour makes brute force infeasible), and the only viable path to support direct-BLE on R-series bodies is sniffing Canon Camera Connect's real traffic with a hardware sniffer (nRF52840 dongle + Wireshark, ~$60 hardware + a weekend of work).

The probe tool is at [`tools/canon_ble_probe.py`](../tools/canon_ble_probe.py). It supports `--dump` (GATT enumeration), `--pair` (M50 pair handshake), `--trigger` (M50 trigger), and `--brute` (walk writable chars). Reusable if someone returns to this with an M-series body or a sniffer.

---

## Protocol summary (M50, unverified on Pulsar)

Source: robot9706's reverse-engineering against a Canon EOS M50. **Not tested against the R-series and known not to apply to the RP.** Preserved here in case someone tackles an M-body in the future.

**UUID encoding gotcha:** robot9706's notation lists bytes in wire order (BLE adv data is little-endian for 128-bit UUIDs). When you convert to standard MSB-first UUIDs that bleak / nRF Connect / Android show, you reverse the 16 bytes. The standard form follows Canon's custom base `XXXXXXXX-0000-1000-0000-d8492fffa821`, where `XXXXXXXX` is a 32-bit short. Examples:

- M50 pair service: standard `00010000-0000-1000-0000-d8492fffa821` ← family `0x0001`
- M50 pair-command char: `00010006-0000-1000-0000-d8492fffa821`
- M50 pair-data char: `0001000a-0000-1000-0000-d8492fffa821`
- M50 trig service: `00030000-0000-1000-0000-d8492fffa821` ← family `0x0003`
- M50 trig char: `00030030-0000-1000-0000-d8492fffa821`
- M50 trig notify: `00030031-0000-1000-0000-d8492fffa821`
- M50 trig config: `00030010-0000-1000-0000-d8492fffa821`

The original "wire-order" notation in case you cross-reference the robot9706 source — service UUIDs end in `:00:00:01:00` (pair) / `:00:00:03:00` (trig), and the prefix `21:a8:ff:2f:49:d8:…` is the LE byte order of the standard-form Canon base.

**Pair flow** (one-time per camera, after the user puts the body in pairing mode from the Wi-Fi/BT menu):

1. BLE bond. Just Works, secure-connections + MITM. Android creates the bond record before any GATT writes go through.
2. Write `[0x01, 'T', 'I', 'M', 'E', 'R']` to the pair-command characteristic. This is the nickname the camera will show in its remote-device list. ASCII, ≤ 8 bytes by convention; Pulsar will use `[0x01, 'P', 'U', 'L', 'S', 'A', 'R']`.
3. Write `[0x05, 0x02]` to the pair-data characteristic. `0x05` = platform-info opcode, `0x02` = Android. (`0x03` = "Remocon" — what the Canon BR-E1 sends.) Pulsar uses `0x02` so the body knows it's a phone.
4. Write `[0x01]` to the pair-data characteristic. Confirm.

**Trigger** (every shot, after bonding):

- Write `0x00 0x01` to the trigger characteristic → shutter press.
- Write `0x00 0x02` to the trigger characteristic → shutter release.

The gap between press and release **is** the bulb duration. Pulsar's run loop owns the exposure timing, the same way it does for CCAPI bulb. No `af` flag, no shooting-mode setup — the body shoots in whatever mode the user dialled in.

## Architecture decision: per-transport state flows

The viewmodel already has `bleController.connected` (ESP) and `_canonTransport` (CCAPI) as the two transport handles, with mutual exclusion in `_connected`. Adding a third raises the question: keep adding parallel `StateFlow`s, or unify under a single `activeTransport: StateFlow<CameraTransport?>` field?

**Decision: Option A — keep separate flows per transport, derive an active selector on top.**

```kotlin
private val _esp: StateFlow<EspBleTransport?>       = bleController.connected   // existing
private val _ccapi: MutableStateFlow<CcapiTransport?>     = MutableStateFlow(null)
private val _canonBle: MutableStateFlow<CanonBleTransport?> = MutableStateFlow(null)

val activeTransport: StateFlow<CameraTransport?> =
    combine(_esp, _ccapi, _canonBle) { esp, ccapi, ble ->
        esp ?: ccapi ?: ble
    }.stateIn(scope, SharingStarted.Eagerly, null)
```

**Why:** the UI panels (Canon CCAPI card, Pulsar BLE card, Canon BLE card) each consume their own flow on the scan screen and capabilities dialogs. Forcing them through a single sealed `ActiveTransport` would push union-type unwrapping into every consumer. The derived `activeTransport` covers the run-loop case (`executeFlowStep` only cares "what's connected"); the per-transport flows cover the per-card UI case. Mutual-exclusion logic stays in `connectXxx` / `disconnectXxx` methods, same as today.

## Five-phase implementation plan (deferred — preserved for future M-series work)

The plan below was the original roadmap before the RP probe. It's preserved as scaffolding for anyone with an M50-class body who wants to take it up — *Phase 1 already landed* (v0.242.0, see [`transport/CanonRunner.kt`](../android/app/src/main/java/com/ehrocha/pulsar/transport/CanonRunner.kt) and [`transport/CameraTransport.kt`](../android/app/src/main/java/com/ehrocha/pulsar/transport/CameraTransport.kt) for the capability-bearing interface + runner extraction). Phases 2–5 are on hold.

### Phase 1 — Foundation refactor (✅ shipped v0.242.0)

Promote `CameraTransport` from the marker-ish interface it is today into a real capability-bearing contract. This is the pairing with the long-deferred stateful-runner tests.

- Add capability flags to `CameraTransport`:
  ```kotlin
  interface CameraTransport {
      val supportsBulb: Boolean
      val supportsSettings: Boolean      // ISO / Av / Tv
      val supportsLiveView: Boolean
      val supportsLensInfo: Boolean
      val supportsBatteryReadout: Boolean
      // …existing shutter / startBulb / stopBulb signatures…
  }
  ```
- Create `BleEspTransport: CameraTransport` wrapping `bleController`. Flips three of the booleans on/off the way ESP firmware actually behaves today.
- Refactor `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp` to accept a `CameraTransport` rather than the concrete `CcapiTransport`. Now the run loop is transport-agnostic.
- Write the deferred stateful-runner tests against the interface (using a `FakeTransport` recording calls) — they were parked precisely because the interface didn't exist.

After this phase the existing CCAPI + ESP code is unchanged behaviourally; the seams just got wider.

### Phase 2 — Canon BLE discovery + pair (8–12 h, deferred)

New package `transport/canonble/`, mirroring `transport/ccapi/`:

| File | Purpose |
|---|---|
| `CanonBleProtocol.kt` | UUID constants, opcode bytes, pair/trigger payload builders. Pure functions, no Android deps — testable. |
| `CanonBleManager.kt` | Nordic BLE manager (`BleManager` subclass). Bonding, GATT services discovered, pair-handshake state machine, trigger writes. |
| `CanonBleTransport.kt` | Implements `CameraTransport` with `supportsBulb=true` and every other capability `false`. |
| `CanonBleDiscovery.kt` | `BluetoothLeScanner` filtered on the pair-service UUID. Differentiates from Pulsar ESP triggers (different service UUID). |

UI:

- New section on the scan screen for "Canon BLE cameras", alongside the existing Pulsar BLE and Canon CCAPI sections.
- **4-step pair-flow wizard** the first time the user connects a new Canon BLE body:
  1. **Prep** — instructions to put the body into pairing mode from its Wi-Fi/Bluetooth menu. Per-body copy (RP path differs from M50 path).
  2. **Pair** — discover, bond, send the nickname (`PULSAR`).
  3. **Platform** — send `[0x05, 0x02]` then `[0x01]`. Show a spinner; on success the body shows Pulsar in its registered-device list.
  4. **Done** — body is paired; subsequent connects skip straight to bond + trigger.

Saved cameras keyed by BLE MAC, same way the ESP path keys by MAC.

### Phase 3 — Trigger + verification gate (4–6 h, deferred — RP gate failed)

- Implement `startBulb()` (write `0x00 0x01`) and `stopBulb()` (write `0x00 0x02`).
- Hook into the existing `runCanonBulb` loop — should just work, because Phase 1 made the loop transport-agnostic.

**Hard gate before merging this phase:** use **nRF Connect on Android** to confirm the Canon pair-service UUID (`…:00:00:01:00`) is visible on the EOS RP when it's in pairing mode. If it's not, the M50 protocol doesn't map to the RP and the plan needs revisiting before more code is written. We don't burn weeks on something that doesn't work on Eduardo's actual body.

If the gate passes, the rest of phase 3 is wiring real-device tests on the RP into the chunk-G framework: bond → pair → fire a single shot → fire a 30 s bulb → cancel mid-bulb.

### Phase 4 — UX polish (4 h, deferred)

- Group BLE + CCAPI for the same body. If a Canon BLE camera and a CCAPI camera share a friendly name (or get matched by serial), the scan screen shows them as **one card with two badges**, and the user picks per-mode which transport to use.
- Per-camera "default transport" preference. Save it under the existing per-UDN settings store; key Canon-BLE entries by MAC and link MAC↔UDN when both have been seen on the same body.
- Banner copy: if a flow is bulb-based and the user is on CCAPI but Canon BLE is also available, hint "switch to BLE for ~10× battery on the body."

### Phase 5 — Robustness (4–6 h, deferred)

- **Bond verification** before any trigger write. Android can show a bond as `BOND_BONDED` while the actual link key on the camera side is stale. If a trigger write returns `GATT_INSUFFICIENT_AUTHENTICATION`, drop the bond record, re-prompt the user to repeat the body-side pair flow, then retry.
- **Reconnect loop.** Long astro runs need the same "5 consecutive failures → enter reconnect mode → re-bond → resume" behaviour CCAPI has today. The `_canonReconnecting` flow generalises to per-transport reconnect state.
- **Pair-failure UX.** The pair handshake can fail at any of steps 2/3/4. Each has its own copy: bond OK but body didn't acknowledge nickname, body refused platform byte, etc.

## Total effort

**25–35 hours of focused work.** Comparable to the CCAPI Phase 2–3 push but lighter, because the wire format is dramatically simpler (no HTTP, no digest, no XML, no version pinning).

## What this transport gives / doesn't give

**Gives:** shutter trigger + app-owned bulb timing, multi-hour battery life on the body, sub-second-exposure accuracy, EOS M-series coverage, no CCAPI activation required.

**Doesn't give:** exposure settings (ISO/Av/Tv), live view, lens info, battery readout, drive-focus.

Practical mode coverage:

| Wizard mode | Direct-BLE works? | Notes |
|---|---|---|
| Intervalometer | yes | Bulb sequence, app-timed |
| Astro | yes | Same |
| Dark Frame | yes | Same |
| Ramp | yes | Same |
| Timelapse (camera owns timing) | yes | Single press+release per shot, no bulb |
| Manual hold | yes | Press on touch-down, release on touch-up |
| Star Focus | **no** | Needs live view + drive-focus — CCAPI only |
| Camera-params tab (planned) | **no** | Needs settings — CCAPI only |

So the natural pairing on a body that supports both: **direct-BLE for the run, CCAPI session in parallel for setup** (Star Focus, lens check, settings). Either transport can run alone.

## Honest caveats

- Protocol is from one device (M50). The RP is a year newer with a different SoC. It *should* match — Canon reuses BLE-Remote across the M-series and R-series — but the verification gate exists exactly because "should" isn't enough.
- Android BLE bonding is historically OEM-quirky. Samsung's stack has had bond-key-loss bugs, Pixel's bonding dialog races on Android 12+, some Xiaomi ROMs strip bond records on reboot. Phase 5's bond verification is non-negotiable.
- Reverse-engineered protocols aren't future-proofed against Canon firmware updates. If a body firmware update breaks pairing, we may have nothing to fix it with.
- Canon-BLE-Remote only. Sony/Nikon/Fuji have their own remote protocols; the ESP32 + optocoupler path stays the broad-coverage answer for anything-with-a-remote-port.

## Decision status

**Not building, indefinitely.** As of 2026-05-19:

- Phase 1's foundation refactor (capability-bearing `CameraTransport` interface + runner extraction + virtual-time tests) **has landed** in v0.242.0 — that work is intrinsically useful even without Canon-BLE, because it makes the bulb/timelapse/ramp loops transport-agnostic and unit-testable. Keep.
- Phases 2–5 (Canon-BLE-specific discovery, pair, trigger, UX, robustness) are **deferred**. The RP probe found the body's protocol differs fundamentally from the M50's and the RP's BLE stack disconnects on bad input, so blind reverse-engineering is infeasible. CCAPI already covers everything Pulsar's wizards need on the R-series.

**Conditions under which to revisit:**
- Someone with an M-series body (M50 / M50 II / M6 II) wants the implementation. Phases 2–5 against the documented M50 protocol should work — the probe tool can validate before committing to Android code.
- A real BLE sniffer (nRF52840 dongle + Wireshark + nRF Sniffer for Bluetooth LE) captures Canon Camera Connect's RP protocol and we get ground truth for the `0x0005` service family. Then phases 2–5 can be retargeted at the R-series.

Neither is on Pulsar's roadmap right now.

## References

- robot9706/CanonBLEIntervalometer — `https://github.com/robot9706/CanonBLEIntervalometer` — the reverse-engineering source. ESP32 firmware, not Android. The constants and pair flow above are extracted from `src/`.
- `docs/ccapi.md` — Canon CCAPI transport (sibling). Same body, different wire.
- `docs/ble-protocol.md` — Pulsar ESP32 BLE protocol. **Unrelated to Canon BLE** — different services, different UUIDs, different framing. Don't conflate.
