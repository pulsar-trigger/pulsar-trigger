# Canon BLE — bulb implementation status & handoff

_Snapshot: 2026-07-03 (**v0.629**). Companion to `docs/canon-ble-research.md`
(protocol reference) and `docs/canon-ble.md` (user-facing). This is the "where
are we, what's the camera actually doing, what's next" for Canon BLE **bulb**
work across **both** protocols — BR-E1 remote (EOS R) and smartphone mode
(EOS RP / R5 / R6 / newer)._

_The camera models below were mapped by driving a real EOS R directly from the
dev machine (`bleak` over BlueZ) **and**, for the RP, by iterating on-device with
the app (the RP can't be direct-driven — see "Direct drive" below). Earlier
guesses ("silent self-close", "dropped toggle", "smartphone `[00,02]` closes
bulb") were all wrong; what's below is card/shutter-proven ground truth._

## TL;DR — the two protocols are the same machine

BR-E1 and smartphone mode are conceptually **identical** — a "button remote"
protocol where the camera's **mode dial decides what a press means** — and differ
mainly in the opcodes:

| | BR-E1 (EOS R) | Smartphone (EOS RP) |
|---|---|---|
| **Bulb** | TOGGLE — `0x8C` flips open↔close, `0x0C` inert | TOGGLE — `[00,01]` flips open↔close, `[00,02]` inert |
| **Manual / single-shot (M dial)** | press `0x8C` / release `0x0C` | press `[00,01]` / release `[00,02]` |
| **~4 s post-frame cooldown** (eats presses / skips frames below ~4 s) | ✅ | ✅ |
| **Shutter-state char** | `00050004` (`01`=open `03`=closed `00`=idle) | `00030031` byte[1] (`01`/`03`) |
| **State read trustworthy?** | partly (lies during the cooldown) | **no** (read is stuck at `010101` regardless) |
| **AF half-press over BLE** | ✅ `0x4C` | ❌ (`afToggle=false`) |
| **Wake nudge for a sleeping body** | ✅ `0x1C` zoom-wide | not needed / not observed |

The single fact that explains toggle-vs-press, the inert release, and every
dial-mismatch desync: **the same code is a bulb toggle on the Bulb dial and a
shutter press on M — a physical button doesn't know the mode, the camera resolves
it from the dial, and the app can't see the dial.**

**Current state (v0.629):** bulb (intervalometer / astro / dark / ramp), manual
Hold, single shot, timelapse, bonding, auto-reconnect, and a return-to-menu
safety-close all work on **both** the EOS R (BR-E1) and the EOS RP (smartphone).
The 4 s minimum-interval clamp applies to both.

---

## BR-E1 model (EOS R — direct-drive proven 2026-07-01→02)

Raw one-click-at-a-time, reading `00050004` before/after each `0x8C`:

```
click 0: 00→01  OPEN
click 1: 01→03  CLOSE
click 2: 03→01  OPEN
click 3: 01→01  DEAD (eaten)   ← inside the post-frame cooldown
...
```

- **~3–4 s POST-FRAME COOLDOWN (THE key fact):** after a frame the EOS R **eats**
  shutter presses while still **acking them** — and *every* wire channel reports
  the phantom state (`0x001b` ack **and** a raw read of `00050004` both lie:
  card-proven — nine "verified open" reads with 0.4–0.5 s frames on the card).
  There is **no truth signal** on the wire during the cooldown. **The real fix is
  the 4 s minimum interval** (`canonBleSafeInterval`), not any read-verify.
- Below 4 s the "period-3 dead click" appears — an artifact of the cooldown eating
  the open; the "close needs 2 clicks" is its retrospective fingerprint.
- **`0x0C` is inert** button-up (a lone `0x0C` never closes — the original
  every-other-shot bug).
- **AF pollutes the raw status char:** `0x4C` sets `00050004` to `0x01` and it
  *stays* `0x01` — so a raw read right after AF wrongly says "open" (the v0.606
  manual regression). The cached indication is AF-gated (`lastControlWasShutterPress`,
  v0.601) so it isn't fooled.
- **Wake-eat:** the first button press after sleep/long idle is consumed as the
  wake (a ~0.5 s first frame). Arm-writes / reads / CCCD subscribes do **not**
  wake the shooting engine — only a button does. `setShutterMode` sends a
  harmless **zoom-wide `0x1C` wake nudge** + settle at run start so the first real
  open fires.
- **Bonding matters:** an unbonded link self-closes bulb early; bonded (v0.602) it
  holds. Bonding also fixed reconnect.

## Smartphone-mode model (EOS RP — on-device proven 2026-07-02→03)

Control service `00030000` (paired write + notify chars):

| Char | props | Role |
|---|---|---|
| `00030010` / `00030011` | write / notify | mode select (write `MODE_SHOOT = 0x02`) |
| `00030030` / `00030031` | write / read+notify | **shutter** control / **state** |

- **Bulb is a TOGGLE on `[00,01]`** (like BR-E1). One `[00,01]` opens, the next
  closes. **`[00,02]` is INERT in Bulb** — it's the release event in **M**, but
  Bulb tracks state on `[00,01]` only and ignores it. Closing a bulb frame with
  `[00,02]` never closed it — that was the "manual hold won't stop" +
  every-other-shot regression (`6ca49dd`, fixed in v0.620 by restoring the
  `[00,01]` toggle for bulb, `smartBulbToggle()`).
- **M single-shot = `[00,01]` press / `[00,02]` release** (`smartShutterTap`) —
  fires one frame at the dial's shutter speed. Used by single-shot / timelapse /
  cable-release.
- **Dial-dependence:** `[00,01]` is a bulb toggle on Bulb and a shutter press on
  M. The app can't see the dial (this is the whole source of desyncs — see below).
- **~4 s post-frame cooldown, same as BR-E1** — the RP *also* eats presses / skips
  frames below ~4 s (Eduardo, 2026-07-03). The v0.619 attempt to bypass the clamp
  for smart mode was **wrong** and was reverted in v0.628. `canonBleSafeInterval`
  clamps sub-4 s intervals to 4 s for **both** protocols.
- **`00030031` is NOT a usable state read.** It looked like a clean `01`=open /
  `03`=closed signal (byte[1]) at first, but a full session proved the **READ is
  stuck at `010101` regardless of actual state** — it returned `010101` at
  connect-rest (closed), after a manual tap (closed), and after a clean run that
  ended closed. The `010301` value only ever showed up on a NOTIFY, once, and
  never reproduced. **Acting on the read fired stray shots** (v0.623–627); we now
  **do not act on it** (read is kept log-only). Smart mode has **no trustworthy
  readable state** — same conclusion as BR-E1's "the reads lie."

### Smart-mode close: parity flag, not state read

With no trustworthy state, the smart bulb tracks open/closed with a **`bulbOpen`
parity flag** (flipped by our own toggles) and the safety-close only toggles when
`bulbOpen` says open:

```kotlin
// ensureShutterClosedSafely() — smart branch (v0.628)
runCatching { client.readSmartShutterState() }   // log-only, we don't trust it
if (bulbOpen) runCatching { client.smartBulbToggle() }
```

This **never toggles a shutter we believe is closed → never fires a stray.** The
cost: a **parity desync** the flag can't see (below) isn't auto-healed.

### The dial-mismatch desync + the even-tap fix (v0.629)

A single-shot **tap** sends `[00,01]`. On the **Bulb** dial that `[00,01]`
**toggles the physical shutter open** but never touches `bulbOpen` (the tap is a
`smartShutterTap`, not a bulb toggle). So a tap fired with the dial on Bulb leaves
physical ≠ flag — and a following bulb run ends open. This bit the **Camera Test**,
whose manual/single-shot phase (meant for M) taps before the bulb phase (BULB); if
the user leaves the dial on Bulb the whole time, the odd `[00,01]` inverts the
bulb phase → sensor left open.

We can't detect the dial, so we don't try. **Fix: make the parity even.** On
Canon BLE the Camera Test's manual phase fires **2** shots instead of 1
(`cameraTestManualShots`). An even `[00,01]` count returns a toggle-shutter to
**closed on either dial** — M = 2 real shots, Bulb = one open→close frame — so the
bulb phase always starts closed. Deterministic, no state read.

---

## Version history (what each change did)

BR-E1 (EOS R):

| ver | change | verdict |
|---|---|---|
| v0.594–602 | 2-byte writes, toggle model, AF-gating, **bonding** | ✅ foundation |
| v0.609 | verify-retry against the AF-gated cached indication | ✅ bulb + manual |
| v0.610–612 | drop-verify via raw read → **removed** ("the raw read lies too") | ❌→ retrospective-fingerprint only |
| **v0.611** | **4 s minimum interval** (`canonBleSafeInterval`) + stray-shot fix + Camera Test marathon | ✅ **THE fix** |
| v0.613 | **wake nudge** (`0x1C`) at run start (camera eats the first press after sleep) | ✅ |
| v0.614 | removed `CONNECTION_PRIORITY_HIGH`; **opLock** serializes normal-flow ops | ✅ |
| v0.615 | transport-level 4 s post-frame cooldown floor (backstops step boundaries) | ✅ |

Smartphone mode (EOS RP) + shared UX:

| ver | change | verdict |
|---|---|---|
| v0.616 | "Preparing…" run-screen state for the Canon BLE wake/settle window | ✅ |
| v0.617 | reconnect-after-sleep: recent-device tile always tappable + **direct `autoConnect=false`** manual reconnect; `_canonBleReconnecting` leak fixed | ✅ |
| v0.618 | `RunState.Preparing` so the "Preparing…" pill shows in the real modes, not just Camera Test | ✅ |
| v0.619 | protocol-aware clamp (smart bypass) — **later proven WRONG** | ❌ reverted v0.628 |
| **v0.620** | **smart bulb close = `[00,01]` toggle** (`smartBulbToggle`), not `[00,02]` — `[00,02]` is inert in Bulb | ✅ **key** |
| v0.621–622 | notify-scan + full-GATT-dump diagnostic (found `00030031`) | ✅ (diagnostic) |
| v0.623–627 | **`00030031`-read guaranteed close** — read said "open" → toggle | ❌ read unreliable → fired strays |
| **v0.628** | **reverted to flag-based close** (read log-only); re-enabled 4 s clamp for smart | ✅ **no strays** |
| **v0.629** | **even-tap Camera Test** (2 shots on Canon BLE) → dial-proof, sensor always ends closed | ✅ |

Also shipped this arc: intention-labelled wire log (`▶ Starting INTERVALOMETER …`,
`══ CAMERA TEST — … phase ══`, `▶ MANUAL — …`) + navigation/dialog logging
(`Nav: → screen=…`, `◆ Dialog: …`, `◆ Camera Test phase → …`) so diags narrate
themselves.

## Current implementation (v0.629)

- **BR-E1 bulb:** `ensureShutter(wantOpen)` — verify-retry against the AF-gated
  cached indication; the 4 s minimum interval is the real defense against the
  cooldown; transport-level cooldown floor backstops step boundaries; `0x1C` wake
  nudge at run start.
- **Smart bulb:** `smartBulbToggle()` (`[00,01]`) flag-gated on `bulbOpen`
  (open only closed→open, close only open→closed; defensive/safety closes send
  nothing when already closed). `fireShutter` (M single-shot) uses
  `smartShutterTap` (`[00,01]`/`[00,02]`). No AF, no wake nudge.
- **Both:** `canonBleSafeInterval` clamps sub-4 s intervals to 4 s (with a
  one-shot snackbar); the **transport-level post-frame cooldown floor**
  (`startBulb` waits ≥4 s since the last real close — `lastRealCloseElapsed`)
  backstops STEP BOUNDARIES for **both** protocols (v0.630 — was `!isSmart`; the
  RP's Camera Test had ~1 s inter-step opens eaten → sensor left open);
  safety-close on return to menu; Camera Test manual phase fires 2 shots on Canon
  BLE (even parity).
- **`00030031` read** is called at safety-close for **diagnostics only** — we do
  not act on it (it lies).

## Direct drive (dev machine)

- **EOS R (BR-E1):** direct-drivable from `orion` via `tools/canon_ble_test.py`
  (`bleak`). Allows **unbonded** GATT once a stale bond is cleared
  (`bluetoothctl remove DC:FE:23:40:0C:02`). This is how the BR-E1 model was
  mapped. MAC `DC:FE:23:40:0C:02`.
- **EOS RP (smartphone): direct drive is BLOCKED — do not retry.** The RP
  **requires** a bonded/encrypted link for GATT, and BlueZ ↔ RP reconnect fails
  authentication every time: the pairing connection resolves services once, but
  every fresh `bleak` (or `bluetoothctl connect`) drops with
  `LE.Disconnected Reason.Authentication` — persists even after clearing the
  camera's wireless settings and a fresh mutual pair. Android's BLE stack handles
  the RP's bonding correctly (the app connects and fires reliably), so **the app
  is the probe** for smart-mode work. MAC `D0:40:EF:6C:F1:08` (MonsteRP).
  `tools/canon_ble_test.py` gained `--smart-interval/--smart-exposure/--smart-gap`
  + a direct-address fallback, but they're moot given the auth wall.

## ✅ CONFIRMED (2026-07-03, v0.630)

Full Camera Test — **flawless on all three** — including the deliberate dial-mismatch
(manual phase on the Bulb dial):

- **EOS RP (smartphone)** — the cooldown floor now fires at the ~1 s inter-step
  boundaries (`post-frame cooldown — waiting 2998ms`); no eaten toggles, sensor
  ends closed.
- **EOS R6 (BR-E1 remote)** — first confirmation on R6; clean 1-toggle open/close,
  wake nudge per step, cooldown floor firing.
- **EOS R6 (smartphone)** — clean, cooldown floor firing.

**Drive-mode nuance (Eduardo, 2026-07-03):** **BR-E1 / Remote mode requires the
camera's drive mode = Remote** to honour shutter writes (the "pairs-but-won't-shoot"
gate). **Smartphone mode does NOT** — it fires regardless of drive mode. A user
picking the BR-E1 path who forgets Remote drive mode will pair but not shoot;
smartphone-mode users don't hit this.

## Open / next
- **Periodic camera-initiated disconnects** (`status=19`, ~every 3 min) — happen on
  both bodies regardless of activity; bonding + autoConnect recover each without
  re-pairing. Only a problem if one lands mid-exposure on a long run; the robust
  fix (not yet built) is mid-run reconnect-and-resume instead of aborting.
- **No wire-truth for either body** is the standing constraint — every "just read
  the state" idea has failed (BR-E1 lies during the cooldown; smart's `00030031`
  read is stuck at `010101`). Parity + the 4 s clamp + even-tap are the working
  model; don't re-attempt a read-based guaranteed close without new evidence.
