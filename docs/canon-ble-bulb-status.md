# Canon BR-E1 BLE — bulb implementation status & handoff

_Snapshot: 2026-07-01. Companion to `docs/canon-ble-research.md` (protocol reference)
and `docs/canon-ble.md` (user-facing). This file is the "where are we, what's
broken, what's next" for the BR-E1 **bulb** work._

## TL;DR

- **BR-E1 bulb is a TOGGLE.** One `0x8C` press flips the shutter open↔closed; the
  `0x0C` is inert button-up. Confirmed three ways: (1) Canon's own BR-E1 manual
  (`~/Downloads/br-e1-im-en.pdf`) groups bulb **with movie** in the indicator-lamp
  table as a start/stop toggle; (2) nRF sniffer `~/bulb-toggle.pcapng` shows the
  shutter held open ~5 s between two `0x8C` presses with the `0x0C` doing nothing;
  (3) every-other-shot came back the moment we tried press-and-hold.
- **Current app = v0.605.** WORKS: manual "Hold Shutter" bulb, single-shot,
  2-byte writes, **bonding, auto-reconnect**. BROKEN: **intervalometer / astro —
  the 2nd exposure self-closes and cascades.**

## Wire protocol (BR-E1, service `00050000-…-d8492fffa821`)

| Char | Handle | Role |
|---|---|---|
| `00050002` | pair | arm write `[0x03, "Pulsar"]` (WRITE_NO_RESPONSE), sent every connect |
| `00050003` | 0x0019 | control — **2-byte** writes `[cmd, 0x00]`, `cmd = button \| mode` |
| `00050004` | 0x001b | status — camera **indicates** `0x01`=open / `0x03`=closed; CCCD 0x001c; also readable |

- `cmd` bytes: shutter `0x8C` (BTN_RELEASE `0x80` \| IMMEDIATE `0x0C`), release `0x0C`,
  AF half-press `0x4C`, zoom-wide `0x1C`, zoom-tele `0x2C`, movie-record `0x88`.
  Mode nibble: immediate `0x0C`, 2-sec `0x04`, movie `0x08`. (Matches Ian Douglas's
  bit map: bit7 shutter, bit6 AF, bit5 T, bit4 W, mode bits 2–3.)
- **`0x001b` caveat:** the camera indicates `0x01` for an **AF half-press and zoom
  too**, not just the shutter — so `0x01`/`0x03` is only trustworthy as shutter
  state when the *last control write was a shutter press* (`0x8C`). This is the
  v0.601 `lastControlWasShutterPress` gate.
- **Bulb mechanism:** `0x8C` toggles + indicates; `0x0C` inert; shutter **holds**
  between clicks. **Bonding is required** for a reliable hold — an unbonded link
  self-closes the exposure early (fixed v0.602).

## Version history (what each fix did)

| ver | change | verdict |
|---|---|---|
| v0.594 | 2-byte control writes `[cmd,0x00]` (was 1 byte) | ✅ kept |
| v0.595 | bulb as toggle (start+stop each a full `0x8C→0x0C` click) | ✅ concept right |
| v0.596–601 | status-indication **closed-loop state machine** | ❌ reverted (fragile) |
| v0.600 | `requestConnectionPriority(HIGH)` + status-char read seed | ✅ kept |
| v0.601 | AF/zoom `0x01` **gating** + seed non-`0x01` as closed | ✅ kept |
| v0.602 | **bonding** (`createBond`) on BR-E1 connect | ✅ **fixes reconnect + hold** |
| v0.603 | press-and-hold (from a wrong 3rd-party quote) | ❌ every-other-shot, reverted |
| v0.604 | raw toggle (no idempotency) | ❌ defensive `stopBulb` inverts; never shipped to test |
| **v0.605** | **idempotent toggle** — click only when state disagrees | ⏳ manual ✅, intervalometer ❌ |

## The open bug (v0.605)

**Manual bulb works** (single user-driven open then close). **Intervalometer/astro
fail on the 2nd exposure:**

- Frame 1: open (press→`0x01`), hold, close (press→`0x03`). Perfect.
- Frame 2: open (press→`0x01`), hold … **the camera closes the shutter SILENTLY —
  no `0x001b` indication** … close press→`0x01` (it re-opens, because the camera
  was already closed). Our `shutterOpen` was stale-`true`, so `ensureShutter(false)`
  toggled a *closed* shutter *open* → one long exposure spanning the next interval.
- Deterministic: 2nd frame, both runs, `0x8C`→status sequence `01,03,01,`**`01`**`,03`.
- Same open→close-press gap (~2.9 s) as frame 1, so **not** a fixed timeout — some
  state-dependent camera behavior (silent self-close? ignored toggle? card-write
  settle?) that the **app log cannot disambiguate**. It is **not** our gating (AF
  gating verified correct in the log).

## Next step — `--bulb` host diagnostic

The app can't see a *silent* state change; a host-side **read** of `00050004` can.

```
# phone BT OFF (one BLE central), camera Remote mode + dial on BULB
python3 tools/canon_ble_test.py --bulb --bulb-secs 3
# bond auth error? →  bluetoothctl pair DC:FE:23:40:0C:02   (or `remove` a stale bond)
```

`--bulb` bonds, arms, subscribes to `00050004`, then runs toggle / 3-cycle /
press-hold experiments **and reads `00050004` every ~1 s during each hold and each
interval**. EXP 2 mirrors the intervalometer to reproduce frame 2.

It answers:
1. **Does the shutter self-close during a hold, and when?** (`read 0x0004` flips `01`→`00`/`03` mid-hold)
2. **Does a READ reflect the silent close?** → if yes, the fix is **read-before-decide**
   in `ensureShutter`; if no, the read channel is stale too and we need another signal.
3. **Does cycle 2 differ from cycle 1?**

## Candidate fixes (pending `--bulb` data)

1. **Read-before-decide:** `ensureShutter` reads `00050004` fresh before deciding, so a
   silent self-close is caught and `stopBulb` skips instead of re-opening. Only works
   if the read reflects the silent close.
2. **Settle window:** if the camera needs time to finish the previous exposure
   (card write) before the next open, add a post-close settle and/or don't open until
   the status reads closed.
3. **Re-sync each frame:** read the true state at the top of each `startBulb`.

## Environment note

This Claude Code session ran in a **Flatpak sandbox**: it could reach host `tshark`
(`LD_LIBRARY_PATH=/run/host/usr/lib64 /run/host/usr/bin/tshark`) to decode the pcaps,
but **not** BlueZ — `bleak` returns `org.bluez … ServiceUnknown` and `bluetoothctl`
hangs, so it could **not drive the camera directly**. Eduardo is switching to a
**system (non-containerized) VS Code** so Claude can run `--bulb` itself against `hci0`.
Memory + repo live on the shared home dir (`/home/ehrocha`), so both carry over.

- EOS R (BR-E1) MAC: `DC:FE:23:40:0C:02`
- Captures in `~`: `bulb-toggle.pcapng` (real remote, toggle+hold), `camera-cmd-bre1.pcapng`
  (all buttons/modes), `br-e1-packages.pcapng` (link-layer only).
