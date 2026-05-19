# Canon direct-BLE transport (planned)

Pulsar's planned **fourth transport**: drive a Canon body directly over its BLE-Remote service from the phone — no ESP32 in the middle, no WiFi, no CCAPI activation. This doc captures the design before any code is written, so the implementation conversation has something to anchor on.

The Canon-side wire format was reverse-engineered by [robot9706/CanonBLEIntervalometer](https://github.com/robot9706/CanonBLEIntervalometer) (M50 firmware). Canon publishes nothing about BLE-Remote, so the constants below are best-effort from one device. The verification gate (below) exists for exactly this reason.

## Why a fourth transport

CCAPI shipped (v0.209–v0.221) and is great for *settings control*: ISO, aperture, live view, drive-focus, lens info, battery readout. But it has three real-world pain points:

| Pain point | CCAPI today | Direct-BLE answer |
|---|---|---|
| **Camera battery** | WiFi + web server burns ~30 %/h on the RP. A 4-hour astro night kills a full LP-E17. | BLE-Remote on the body costs a few % per hour. Phone-side BLE central draws ~2–5 mA vs. ~80–150 mA for WiFi. Multi-night runs on one charge become realistic. |
| **Sub-second bulb exposures** | Each open/close is ~100–200 ms HTTP RTT with WiFi jitter. We warn the user below 1 s. | BLE writes are ~30–50 ms with much less jitter. The astro / dark-frame short-exposure regime becomes accurate. |
| **EOS M-series coverage** | M50 / M50 II / M6 II never got CCAPI. Pulsar can't talk to them today. | M-series supports BLE-Remote. The same protocol covers them. |

It is **not** a replacement for CCAPI. The protocol is shutter-only — no settings, no live view, no lens info, no battery readout, no drive-focus. The endgame is *both transports active on the same body*: BLE for triggering, CCAPI for everything else, and the user picks per-session which they want.

## Protocol summary

Source: robot9706's reverse-engineering, tested on a Canon EOS M50.

**Service UUIDs** (Canon namespace, prefix `21:a8:ff:2f:49:d8:00:00:00:10:00:00:`):

- **Pair service** — `…:00:00:01:00`
  - Pair-command characteristic — `…:06:00:01:00`
  - Pair-data characteristic — `…:0a:00:01:00`
- **Trigger service** — `…:00:00:03:00`
  - Trigger characteristic — `…:30:00:03:00`
  - Trigger notification — `…:31:00:03:00`
  - Trigger config — `…:10:00:03:00`

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

## Five-phase implementation plan

### Phase 1 — Foundation refactor (4–6 h)

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

### Phase 2 — Canon BLE discovery + pair (8–12 h)

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

### Phase 3 — Trigger + EOS RP verification gate (4–6 h)

- Implement `startBulb()` (write `0x00 0x01`) and `stopBulb()` (write `0x00 0x02`).
- Hook into the existing `runCanonBulb` loop — should just work, because Phase 1 made the loop transport-agnostic.

**Hard gate before merging this phase:** use **nRF Connect on Android** to confirm the Canon pair-service UUID (`…:00:00:01:00`) is visible on the EOS RP when it's in pairing mode. If it's not, the M50 protocol doesn't map to the RP and the plan needs revisiting before more code is written. We don't burn weeks on something that doesn't work on Eduardo's actual body.

If the gate passes, the rest of phase 3 is wiring real-device tests on the RP into the chunk-G framework: bond → pair → fire a single shot → fire a 30 s bulb → cancel mid-bulb.

### Phase 4 — UX polish (4 h)

- Group BLE + CCAPI for the same body. If a Canon BLE camera and a CCAPI camera share a friendly name (or get matched by serial), the scan screen shows them as **one card with two badges**, and the user picks per-mode which transport to use.
- Per-camera "default transport" preference. Save it under the existing per-UDN settings store; key Canon-BLE entries by MAC and link MAC↔UDN when both have been seen on the same body.
- Banner copy: if a flow is bulb-based and the user is on CCAPI but Canon BLE is also available, hint "switch to BLE for ~10× battery on the body."

### Phase 5 — Robustness (4–6 h)

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

**Not building now.** Foundational work (chunks A–G — flow resume, encrypted creds, BLE perms, schema versioning, unit tests) has landed. Direct-BLE is the obvious next big architectural piece, but it's deliberately gated behind one cheap experiment: **nRF Connect on the RP in pair mode**, looking for the Canon pair-service UUID. If that's there, phase 1 starts. If it's not, the plan revisits whether RP needs a different protocol or whether we ship the M-series transport alone.

## References

- robot9706/CanonBLEIntervalometer — `https://github.com/robot9706/CanonBLEIntervalometer` — the reverse-engineering source. ESP32 firmware, not Android. The constants and pair flow above are extracted from `src/`.
- `docs/ccapi.md` — Canon CCAPI transport (sibling). Same body, different wire.
- `docs/ble-protocol.md` — Pulsar ESP32 BLE protocol. **Unrelated to Canon BLE** — different services, different UUIDs, different framing. Don't conflate.
