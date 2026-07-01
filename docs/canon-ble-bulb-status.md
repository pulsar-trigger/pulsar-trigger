# Canon BR-E1 BLE — bulb implementation status & handoff

_Snapshot: 2026-07-01 (v0.609). Companion to `docs/canon-ble-research.md` (protocol
reference) and `docs/canon-ble.md` (user-facing). This is the "where are we, what's
the camera actually doing, what's next" for the BR-E1 **bulb** work. The camera
model below was **mapped by driving a real EOS R directly from the dev machine**
(`bleak` over BlueZ), not inferred from app logs — earlier guesses ("silent
self-close", "dropped toggle") were wrong; this is the ground truth._

## TL;DR

- Current app **v0.609**. Bulb (intervalometer / astro), **manual Hold**, single
  shot, bonding, auto-reconnect, and a safety-close on return to menu are all in
  and working on the EOS R.
- **The camera is a toggle with a deterministic PERIOD-3 quirk** (see below). A
  bulb *close* routinely needs 2 clicks; the extra click fires **no frame**
  (confirmed by card count). The app absorbs this with a verify-retry loop.

## Wire protocol (BR-E1, service `00050000-…-d8492fffa821`)

| Char | Handle | Role |
|---|---|---|
| `00050002` | pair | arm write `[0x03, "Pulsar"]` (WRITE_NO_RESPONSE), every connect |
| `00050003` | 0x0019 | control — **2-byte** writes `[cmd, 0x00]`, `cmd = button \| mode` |
| `00050004` | 0x001b | status — camera indicates/serves `0x01`=active, `0x03`=closed, `0x00`=idle |

- `cmd`: shutter `0x8C`, release `0x0C`, AF half-press `0x4C`, zoom-wide `0x1C`,
  zoom-tele `0x2C`, movie `0x88`. Mode nibble: immediate `0x0C`, 2-sec `0x04`,
  movie `0x08`. (Matches Ian Douglas's bit map + our nRF sniffer.)
- **2-byte writes matter:** 1-byte control writes toggle *erratically* on the EOS R
  (period changes); always send the `0x00` trailer, like the app + real remote.

## THE camera model (definitive, from direct drive 2026-07-01)

Raw one-click-at-a-time, reading the status char before/after each click:

```
click 0: 0000→0100  TOGGLED
click 1: 0100→0300  TOGGLED
click 2: 0300→0100  TOGGLED
click 3: 0100→0100  DEAD  (no-op)
click 4: 0100→0300  TOGGLED
click 5: 0300→0100  TOGGLED
click 6: 0100→0100  DEAD
click 7: 0100→0300  TOGGLED
click 8: 0300→0100  TOGGLED
click 9: 0100→0100  DEAD
```

- **Period-3 quirk:** once the shutter is OPEN, the *very next* `0x8C` click is a
  **DEAD no-op** — it does **not** flip the state, and it **fires no frame**
  (proved: shooting exactly 3 exposures produced **exactly 3** frames on the card).
  The click after the dead one closes. So **a close needs 2 clicks** (dead + close);
  an open needs 1.
- **`0x0C` is inert** button-up (a lone `0x0C` never closes — that was the original
  every-other-shot bug).
- **AF pollutes the raw status char:** an AF half-press `0x4C` sets `00050004` to
  `0x0100` and it **stays `0x0100`** even after `0x0C`. So a *raw read* of the char
  right after an AF wrongly says "open".
- **Bonding matters:** an unbonded link self-closes bulb early; bonded (v0.602) it
  holds.
- Manual (single user open→hold→close) is easy; the hard part was **back-to-back
  automated cycles** keeping open/close in phase through the dead clicks.

## Version history (what each change did)

| ver | change | verdict |
|---|---|---|
| v0.594 | 2-byte control writes `[cmd,0x00]` | ✅ |
| v0.595 | bulb as a toggle click | ✅ concept |
| v0.596–601 | status-indication closed-loop state machine | ❌ reverted (AF/self-close fragile) |
| v0.600 | `requestConnectionPriority(HIGH)` + status seed read | ✅ |
| v0.601 | AF/zoom `0x01` **gating** on the cached indication + seed→closed | ✅ (key) |
| v0.602 | **bonding** (`createBond`) on BR-E1 connect | ✅ reconnect + hold |
| v0.603 | press-and-hold (from a wrong 3rd-party quote) | ❌ every-other-shot |
| v0.604 | raw toggle, no idempotency | ❌ defensive stopBulb inverts |
| v0.605 | idempotent single toggle (cached) | ❌ can't recover a dead click |
| v0.606 | verify-retry via a **raw read** of `00050004` | ⚠️ fixed bulb, broke manual (AF pollutes the raw read) |
| v0.607 | safety-close on return to main menu | ✅ |
| v0.608 | (audit M6: security-crypto → stable — unrelated) | ✅ |
| **v0.609** | verify-retry against the **AF-gated cached indication** | ✅ **current — bulb + manual both work** |

## Current implementation (v0.609)

`CanonBleTransport.ensureShutter(wantOpen)`:
1. If `client.shutterOpen` (the cached indication) already == target → done
   (idempotent; the runner's defensive `stopBulb` is a safe no-op).
2. Else `bre1BulbToggle()` (one `0x8C`→`0x0C` click) and `withTimeoutOrNull` wait
   for the indication to flip `shutterOpen`. A real toggle returns in ~70 ms; a
   **dead click** leaves it unchanged → times out → retry. Cap `MAX_SHUTTER_ATTEMPTS=4`.
- **Why the cached indication, not a raw read:** `shutterOpen` is updated from the
  `0x001b` indication **only when the last control write was a shutter press**
  (`lastControlWasShutterPress`, v0.601), so AF's `0x0100` is ignored. A raw read
  isn't gated → it's fooled by AF (the v0.606 manual regression). `readShutterState()`
  was removed.
- `startBulb` still does the AF half-press when `af=true` (harmless now — gated).
- **Safety-close** (v0.607): `MainMenuScreen` `LaunchedEffect` → `vm.ensureShutterSafelyClosed()`
  → `ensureShutterClosedSafely()` runs `ensureShutter(false)` so no return-to-menu
  path (finished / stopped / backed-out / un-released manual) leaves the sensor open.

**Known cost:** each close is 1–2 clicks (~0.3–1 s), so exposures run slightly long —
negligible for astro, minor for short intervalometer. Inherent to the period-3 quirk.

## Driving the camera directly (dev machine)

Claude/dev runs **native on `orion`** now (not the old Flatpak sandbox), so BlueZ is
reachable and the camera can be driven live — seconds-long experiments vs an app
rebuild. This is how the model above was mapped.

```
# phone BT OFF (one BLE central), camera → Bluetooth → Remote → Pair, dial on BULB
python3 tools/canon_ble_test.py --bulb --bulb-secs 3 --mac DC:FE:23:40:0C:02
```
- `--bulb` bonds, arms, subscribes to `00050004`, runs toggle / cycle / hold
  experiments, reading the char during each hold. `--mac` scans-first (BlueZ can't
  connect to a bare address). 2-byte writes.
- **Gotchas:** unbonded connect works once a **stale bond is cleared**
  (`bluetoothctl remove DC:FE:23:40:0C:02`); the EOS R allows unbonded GATT. Pairing
  window is short — connect promptly after entering Pair mode. `client.pair()`
  returns `None` on this BlueZ but the bond still forms.
- EOS R MAC `DC:FE:23:40:0C:02`; EOS R6 (smartphone mode) `74:38:B7:48:3C:F7`.
- Ad-hoc characterization was done with inline `python3 - <<PY` scripts (raw
  click→state mapping, close-hold sweep, countable N-exposure card test) — trivial
  to reproduce for movie/zoom/timing next.

## Open / next

- **Awaiting Eduardo's v0.609 on-phone confirm** (manual Hold + intervalometer/astro).
- Periodic **camera-initiated disconnects** (`status=19`, ~every 2–3 min) — bonding +
  autoConnect recover each without re-pairing; only a problem if one lands mid-exposure.
- **Future features** now easy to characterize + wire (sniffer-decoded): movie record
  `0x88`, power-zoom `0x20`/`0x10`, 2-s self-timer `0x04`.
