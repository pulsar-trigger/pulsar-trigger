# Canon BLE protocol — research log & reference catalog

Self-contained record of everything we learned reverse-engineering Canon's
BR-E1 Bluetooth remote protocol while building the [Canon BLE direct
transport](canon-ble.md). **The point of this file is that we never have to
re-clone or re-search the external reference projects again** — every UUID,
constant, write semantic, per-project quirk, and every byte observed on real
hardware is captured below.

It also documents the open bug: **the EOS R / RP pair successfully but will
not actuate the shutter** for any of the six open-source implementations of
the *BR-E1 remote* protocol — ours included. The empirical section explains why.

> **⭐ ANSWER (confirmed firing 2026-05-28): use the smartphone-mode protocol.**
> There is a SECOND Canon BLE protocol (`gkoh/furble`'s smartphone-mode, services
> `00010000`/`00030000`/`00040000`) with a real registration handshake. **The
> EOS RP took a real shot via this path from our own tool** (`--smart`) — the
> BR-E1 remote mode that all six other refs + Pulsar implement simply does not
> fire the R-series. Full details + the confirmed recipe in **§7**. The original
> 2018 EOS R is a further divergence (lacks `00030000`) — still open.

The live diagnostic tool is in-repo at [`tools/canon_ble_test.py`](../tools/canon_ble_test.py).

---

## 1. The protocol (as documented by all references)

### Services & characteristics

| UUID | Role |
|---|---|
| `00050000-0000-1000-0000-d8492fffa821` | Canon "Device Control" service (advertised; scan filter) |
| `00050002-0000-1000-0000-d8492fffa821` | **PAIR / arm** characteristic |
| `00050003-0000-1000-0000-d8492fffa821` | **CONTROL / shutter** characteristic |

The old-DSLR BR-E1 model uses **only** those two characteristics. (The
R-series exposes a much richer set — see §3.)

### Arm / pair write → PAIR char (`00050002`)

```
[0x03, <ASCII remote name bytes…>]
```

`0x03` is a fixed opcode; the rest is the remote's display name (what shows up
in the camera's "paired devices" list). Written **without response**. Re-sent
on every connect by most implementations — the camera treats it as "register
me as the active remote for this session" and ignores control writes until
it's seen.

### Control write → CONTROL char (`00050003`)

A **single byte** = `mode | button` (bitwise OR), written **without response**.

```
Mode bits (low nibble):           Button bits (high nibble):
  MODE_IMMEDIATE = 0b0000_1100 = 0x0C   BUTTON_RELEASE = 0b1000_0000 = 0x80
  MODE_DELAY     = 0b0000_0100 = 0x04   BUTTON_FOCUS   = 0b0100_0000 = 0x40
  MODE_MOVIE     = 0b0000_1000 = 0x08   BUTTON_TELE    = 0b0010_0000 = 0x20
                                        BUTTON_WIDE    = 0b0001_0000 = 0x10
  (buttons-up = mode bits only, e.g. 0x0C)
```

Common composed bytes (immediate mode):

| Byte | Meaning |
|---|---|
| `0x4C` | AF half-press (FOCUS \| IMMEDIATE) |
| `0x8C` | Shutter full-press (RELEASE \| IMMEDIATE) |
| `0x0C` | All buttons up / release (IMMEDIATE, no button) |
| `0x2C` | Zoom tele (TELE \| IMMEDIATE) |
| `0x1C` | Zoom wide (WIDE \| IMMEDIATE) |
| `0x88` | Movie record toggle (RELEASE \| MOVIE) |

**Shutter sequence** (single shot): `0x8C` (press) → short delay → `0x0C`
(release).
**Bulb**: `0x8C` → host-side wait of the exposure length → `0x0C`. The body
must be on **Bulb** on its own mode dial; the protocol has no shutter-speed
write.
**With AF**: `0x4C` half-press → `0x0C` → `0x8C` → `0x0C`.

---

## 2. The six reference implementations (decoded — no need to re-clone)

All six implement the identical two-write protocol above. They diverge only in
addressing (UUID vs raw handle), write semantics, and minor session quirks.
None of them touch the R-series extra characteristics in §3.

### 2.1 `maxmacstn/ESP32-Canon-BLE-Remote` (Arduino/ESP32, tested EOS M50)
Constants live in `CanonBLERemote.h` exactly as §1 (`0b…` literals). Notable:
- **Pair-name quirk**: builds `" " + name + " "` (space-padded), then overwrites
  byte 0 with `0x03` → effectively `[0x03, …name…, ' ']` with a trailing space.
- **Does a disconnect → reconnect right after the pair-write**, lowering the
  encryption level (`ESP_BLE_SEC_ENCRYPT_NO_MITM`) before reconnecting, then
  stores the camera MAC in NVS. *(We trialed this disconnect/reconnect dance in
  Pulsar v0.267 — it did not make the R-series fire; reverted.)*
- Shutter: `writeValue(MODE_IMMEDIATE | BUTTON_RELEASE, false)` then
  `writeValue(MODE_IMMEDIATE, false)`. Writes are no-response (`false`).

### 2.2 `ArthurFDLR/BR-M5` (M5StickC / PlatformIO, tested EOS M50 Mk I)
A straight fork of the ESP32 lib above — `CanonBLERemote.{h,cpp}` byte-for-byte
identical constants and pairing/shutter logic. No independent information.

### 2.3 `iebyt/cbremote` (Android, Apache-2.0, tested EOS 200D)
The canonical Android reference. `CBRGattAttributes.java` only knows
`00050000/00050002/00050003` (no awareness of the extra chars).
- **Pair**: `pairAndConnect()` writes `[0x03] + "CB Remote".getBytes(US_ASCII)`
  to the PAIR char — **with response** (default write type).
- **Control**: encodes the composed bytes as **decimal** `SIGNAL_*` ints, written
  to the CONTROL char as `WRITE_TYPE_NO_RESPONSE`:

  | Constant | Decimal | Hex | = |
  |---|---|---|---|
  | `SIGNAL_ONE_SHUTTER` | 140 | `0x8C` | RELEASE \| IMMEDIATE |
  | `SIGNAL_VIDEO_SHUTTER` | 136 | `0x88` | RELEASE \| MOVIE |
  | `SIGNAL_AF_IMMEDIATE` | 76 | `0x4C` | FOCUS \| IMMEDIATE |
  | `SIGNAL_T_IMMEDIATE` | 44 | `0x2C` | TELE \| IMMEDIATE |
  | `SIGNAL_W_IMMEDIATE` | 28 | `0x1C` | WIDE \| IMMEDIATE |
  | `SIGNAL_WAKE_IMMEDIATE` | 12 | `0x0C` | release (mode-only) |

- **Shutter sequence**: write `SIGNAL_ONE_SHUTTER` (0x8C) → `postDelayed` →
  write `SIGNAL_WAKE_IMMEDIATE` (0x0C). cbremote's "WAKE" is just its name for
  the buttons-up release byte.
- **Tested only on the EOS 200D (a DSLR).** This matters: see §4 — the stock
  unmodified cbremote APK pairs-but-won't-shoot on the EOS R/RP exactly like
  Pulsar, which is what proved the bug is an R-series divergence, not ours.

### 2.4 `pklaus/canoremote` (Python `bleak`, README claims EOS R/RP/R5/R6/Ra + many)
The broadest compatibility claim of any reference. The actual code
(`canoremote.py`) is the **same two writes** and offers both addressing modes:

```python
class IntCharacteristic(enum.IntEnum):   # raw handles
    Pairing = 0xf503
    Event   = 0xf505
class UUIDCharacteristic(StrEnum):        # the standard UUIDs (commented-out alt)
    Pairing = "00050002-…"
    Event   = "00050003-…"
# arm:  write_gatt_char(Pairing, [3, *map(ord, "canoremote")], response=False)
# fire: write_gatt_char(Event,   [mode | button],             response=False)
```

- **It's a library, no `__main__`** — can't be run directly; that's why we wrote
  our own driver.
- Both writes are **`response=False`** (write-without-response). This is the
  one detail that differs from cbremote's with-response pair write — we matched
  it in Pulsar v0.271.
- **Key conclusion**: canoremote's "EOS R support" is exactly this simple
  subset. On our EOS R it produces the same pair-but-don't-fire behavior (§4),
  so the README claim is for the documented subset, not a verified R-series fire.

### 2.5 `ids1024/cannon-bluetooth-remote` (Python driving `btgatt-client`)
Minimal `remote.py`: shells out to BlueZ's `btgatt-client` and issues two
`write-value` commands **by raw handle**:
```
write-value 0xf504 3 <name bytes>     # pair
write-value 0xf506 <button | mode>    # control
```
Same constants as §1. Confirms handle addressing but with **different handles
(0xf504/0xf506)** than canoremote (0xf503/0xf505) and than our EOS R
(0x0016/0x0018, §3) — i.e. **handles are body/firmware-specific; always address
by UUID.** Author's blog posts (Ian Douglas Scott) are the original public
source of the UUIDs and the control-byte bit layout.

### 2.6 `RReverser/eos-remote-web` (Web Bluetooth, JS)
Browser remote. Identical simple protocol:
`UUID_PAIR='…0002…'`, `UUID_SHOOT='…0003…'`, `encodePairCommand` →
`[3, …name.charCodeAt…]`, `pressBtn()` → write `BUTTON_RELEASE|MODE_IMMEDIATE`
(`0x8C`) then `MODE_IMMEDIATE` (`0x0C`). **Does not touch any extra
characteristic.** Surfaced as a possible R-series lead; it is not one.

### Cross-reference summary

| Ref | Lang | Pair write type | Addressing | Tested body | Touches extra chars? |
|---|---|---|---|---|---|
| ESP32-Canon-BLE-Remote | C++ | no-response | UUID | EOS M50 | no |
| BR-M5 | C++ | no-response | UUID | EOS M50 Mk I | no |
| cbremote | Java | **with-response** | UUID | EOS 200D | no |
| canoremote | Python | no-response | handle/UUID | "R/RP/R5/R6" (claim) | no |
| cannon-bluetooth-remote | Python | (btgatt) | **handle** | T7i-era | no |
| eos-remote-web | JS | no-response | UUID | (unstated) | no |

A **7th reference, `gkoh/furble`**, also implements this BR-E1 remote subset
(`CanonEOSRemote`) — but uniquely it *additionally* implements a completely
different **smartphone-mode** protocol (`CanonEOSSmart`) that the others don't.
That's the lead the rest of this doc hinges on — see **§7**.

**Bottom line for the BR-E1/remote protocol: there is no R-series handshake
hiding in it. The R-series fix is a different protocol entirely (§7).**

---

## 3. Empirical findings — real EOS R (`DC:FE:23:40:0C:02`)

Captured with [`tools/canon_ble_test.py`](../tools/canon_ble_test.py) over an
**unbonded, unencrypted** BlueZ/`bleak` link on Linux. The R's Canon service is
far richer than the two-char DSLR model.

### 3.1 Full GATT table (Canon service `00050000`)

| UUID | Handle | Properties | Role / notes |
|---|---|---|---|
| `00050001` | 0x0014 | read | info/version (read = `01000000`) |
| `00050002` | 0x0016 | write, write-no-resp | **PAIR (arm)** |
| `00050003` | 0x0018 | write, write-no-resp | **CONTROL (shutter)** |
| `00050004` | 0x001a | read, **indicate** (+CCCD 0x001c) | status; **indicates on button events** |
| `00050005` | 0x001d | write, write-no-resp | extra write char (unknown) |
| `00050006` | 0x001f | read, **indicate** (+CCCD 0x0021) | status (read = `01`) |
| `00050007` | 0x0022 | read, **indicate** (+CCCD 0x0024) | status (read = `0000`) |
| `0005000a` | 0x0025 | write, write-no-resp | extra write char (unknown) |
| `0005000b` | 0x0027 | read, **indicate** (+CCCD 0x0029) | 18-byte field (read = all zero) |
| `0005000c` | 0x002a | write, write-no-resp | extra write char (unknown) |

Plus the standard GATT services: Device Information `0000180a` (`2a24` model,
`2a26` firmware rev, `2a28` software rev, `2a29` manufacturer), Generic
Attribute `00001801` (`2a05` service-changed), Generic Access `00001800`
(`2a00` device name, `2a01` appearance).

So vs. the documented two-char model, the R adds **four indicate channels**
(`0004/0006/0007/000b`) and **three extra write characteristics**
(`0005/000a/000c`) that **no reference uses**.

### 3.2 Baseline reads (before any write)

```
READ 0x0001: 01000000   (4 bytes)   ← protocol/capability? constant
READ 0x0004: 0000       (2 bytes)   ← status, flips to 0200 on a button event
READ 0x0006: 01         (1 byte)    ← flag (connected/registered = 1 ?)
READ 0x0007: 0000       (2 bytes)
READ 0x000b: 00…00      (18 bytes, all zero) ← looks like a name/token/GPS buffer
```

### 3.3 Observed arm + fire (the decisive run)

```
WRITE ARM pair-name: 0350756c736172   (= [0x03,"Pulsar"])   → OK
WRITE AF half-press: 4c                                     → OK
  ◀ INDICATE 0x0004: 0200          ← camera RESPONDS to the button
WRITE AF release:    0c                                     → OK
WRITE SHUTTER press: 8c                                     → OK
  ◀ INDICATE 0x0004: 0200          ← camera RESPONDS again
WRITE SHUTTER release: 0c                                   → OK
→ Camera did NOT fire.
```

### 3.4 What this tells us

- **Every write returns OK and the camera is *processing* them** — char `0004`
  indicates `0200` in direct response to both the AF (`0x4C`) and shutter
  (`0x8C`) writes. So the body is not ignoring us at the link layer; it
  *acknowledges the button event* and then declines to actuate.
- `0x0200` is therefore best read as a **status/NAK** ("button received, but I
  won't act") rather than a fire confirmation. The baseline `0004 = 0000` ↔
  event `0200` is a state byte the DSLRs never exposed.
- The R is gating actuation on something it expects over the **extra
  characteristics** (`0005/000a/000c` writes and/or the `0006/0007/000b`
  indicate channels) — a registration/handshake step that the old-DSLR
  protocol simply doesn't have, and that none of the six references perform.

---

## 4. The bug, and what's been ruled out

**Symptom:** EOS R and EOS RP both pair (system dialog completes, camera shows
"Paired with: Pulsar"), but **neither fires in any mode**. Camera Bluetooth
must be set to **Remote** (not Smartphone — that's Camera Connect/CCAPI, a
different stack).

**Proven NOT a Pulsar bug:** the **stock unmodified `iebyt/cbremote` APK also
pairs-but-won't-shoot on the EOS R + RP**, identical to Pulsar. Two independent
Android implementations of the documented protocol fail the same way →
R-series divergence, not our code. (cbremote was only ever verified on an EOS
200D DSLR.)

**Proven NOT the write type / link encryption:** the Linux `bleak` run in §3
uses an unbonded, unencrypted link and write-without-response — the most
permissive case possible — and still doesn't fire. So neither Android's
bond/encryption layer nor our write type is the blocker.

**Pulsar fixes trialed (none made the R fire):**
- v0.267 — disconnect/reconnect after pair-write (ESP32-ref approach). Reverted.
- v0.268 — arm-write on *every* connect (matches cbremote `onServicesDiscovered`).
- v0.269/v0.270 — diagnostics only (control-byte + `onCharacteristicWrite`
  status + control-char property bitmask + bondState logging).
- v0.271 — arm-write flipped to WRITE_NO_RESPONSE to match canoremote.

**Conclusion:** the publicly-documented BR-E1 protocol genuinely does not drive
the EOS R-series. The bodies require an additional registration/handshake over
the extra characteristics (§3.1) that is undocumented and absent from all six
references.

---

## 5. Diagnostic tooling — [`tools/canon_ble_test.py`](../tools/canon_ble_test.py)

A `bleak` 3.x driver (Python 3.13+). Auto-scans for the Canon service, dumps
the GATT table, and arms/fires with a log line per write. Run with the camera
in **Bluetooth → Remote → Pair** mode:

```bash
# (a venv with bleak is required; the repo firmware venv or any bleak install)
python3 tools/canon_ble_test.py --dump-only   # GATT table only, no firing
python3 tools/canon_ble_test.py               # plain arm + fire (DSLR subset)
python3 tools/canon_ble_test.py --explore     # read all chars + sub all indicates, then fire
python3 tools/canon_ble_test.py --probe       # observe-everything: enumerate LIVE gatt,
                                              #   sub every notify, snapshot+diff every
                                              #   readable char before/after arm/AF/shutter
python3 tools/canon_ble_test.py --probe --poke # + EXPERIMENTAL blind writes to 0005/000a/000c
python3 tools/canon_ble_test.py --delay-mode  # use MODE_DELAY (2s timer) bit instead of immediate
python3 tools/canon_ble_test.py --mac AA:BB:.. # skip the scan
```

- **`--probe`** is the current best experiment for the R-series. It works off
  the *live* GATT (not hardcoded guesses), subscribes to every notify/indicate
  channel with timestamped logging, and **diffs every readable characteristic**
  before arm → after arm → after AF → after shutter. A value change on an extra
  char across those steps is the fingerprint of the handshake we're hunting.
- **`--poke`** (opt-in, with `--probe`) blind-writes candidate handshake
  payloads to the extra writable chars (`0005/000a/000c`) and retries the
  shutter after each. **Trial-and-error — can leave the body in an odd state;
  power-cycle to recover.**

---

## 6. Remaining paths

1. **⭐ Implement smartphone-mode (§7).** The strongest lead — a documented
   handshake already confirmed firing an EOS RP in furble. First test: set the
   body to "Connect to smartphone" mode and `--dump-only` to confirm it
   advertises `00010000`, then drive the §7 handshake.
2. **Software probing of BR-E1 (`--probe` / `--poke`)** — keep the R in Remote
   mode and hunt the handshake on the `00050000` extra chars. Cheap but blind;
   now secondary to path 1.
3. **Sniff a real BR-E1 ↔ EOS R session** (nRF52840 dongle + a genuine BR-E1
   remote). Ground-truth fallback for the *remote-mode* path if we specifically
   want BR-E1 to work; needs hardware not yet on hand.
4. **Stay bulb-class on bodies that already work** (DSLRs / M-series) and treat
   the R-series remote-mode shutter as best-effort.

---

## 7. ⭐ The smartphone-mode protocol (`gkoh/furble`) — the likely R-series answer

Found 2026-05-28 while chasing the Ian Scott blog. **`gkoh/furble`** (GPL-3.0,
ESP32/NimBLE, a multi-brand remote) is the one project that implements **two**
distinct Canon BLE protocols, chosen by the advertised service UUID
(`CameraList.cpp` tries Smart first, then Remote):

| furble class | Advertised service | = | Camera BT menu |
|---|---|---|---|
| `CanonEOSRemote` | `00050000` | the BR-E1 protocol in §1–§4 (same as all 6 refs) | **Remote** |
| `CanonEOSSmart` | `00010000` | the **smartphone-mode** protocol below — richer, with a real handshake | **Connect to smartphone** / register a device |

**furble's README lists EOS RP (@wolcano) and EOS R6 Mark II (@hijae) as
tested-and-confirmed**, with a feature table row "Canon EOS (Smart) → Shutter
Release ✔️". This is the strongest evidence yet that **the R-series fires over
smartphone-mode, not BR-E1 remote mode** — which explains why six BR-E1
implementations (and Pulsar) all pair-but-won't-shoot on the R.

> Note: smartphone-mode BLE is the channel Canon's *Camera Connect* app uses to
> pair/control over Bluetooth — it is **separate from CCAPI (Wi-Fi)**. furble
> proves a generic BLE central can drive the shutter over this protocol with no
> Wi-Fi and no Camera Connect app, as long as it speaks the handshake and the
> user confirms the pairing on the camera body. (CCAPI not activating on the
> EOS R does **not** block this path.)

### Smartphone-mode GATT map (from `CanonEOSSmart.{h,cpp}`)

| UUID | Short | Role |
|---|---|---|
| `00010000-…` | — | primary / identity service (scan-match) |
| `00010006-…` | `0xf108` | **NAME + pairing indication** (indicate; camera sends accept/reject) |
| `0001000a-…` | `0xf104` | **IDENTITY / registration** (write) |
| `00030000-…` | — | mode + shutter service |
| `00030010-…` | `0xf307` | **MODE select** (write) |
| `00030030-…` | `0xf311` | **SHUTTER** (write) |
| `00040000-…` | — | location/geo service |
| `00040002-…` | — | geo write (GPS + time-sync packet) |
| `00040003-…` | — | geo indicate (camera requests location) |

### Constants

```
PAIR_ACCEPT  = 0x02   PAIR_REJECT = 0x03
MODE_PLAYBACK= 0x01   MODE_SHOOT  = 0x02   MODE_WAKE = 0x03
GEO_REQUEST  = 0x03   GEO_ENABLE  = [0x01] GEO_SUCCESS = 0x02
device UUID  = a generated 128-bit (16-byte) value, persisted per controller
name         = the controller's display string
```

### Connect / registration handshake (exact write order)

```
1. connect(address)
2. secureConnection()                          ← BLE "just works" bond (encrypted+bonded)
3. subscribe(00010006)                          ← pairing-result indication channel
4. write [0x01, <name>]              → 00010006  ("Identifying 1")
5. write [0x03, <16-byte device UUID>] → 0001000a ("Identifying 2")
6. write [0x04, <name>]              → 0001000a  ("Identifying 3")
7. write [0x05, 0x02]                → 0001000a  ("Identifying 4")
8. WAIT ≤60s: camera indicates 0x02 (ACCEPT) or 0x03 (REJECT) on 00010006.
   User must confirm the pairing ON THE CAMERA BODY. If not ACCEPT → deleteBond, fail.
9. (optional) subscribe(00040003); on GEO_REQUEST(0x03) write GEO_ENABLE([0x01]) → 00040002;
   GEO_SUCCESS(0x02) means geo/time-sync is on.
10. write [0x01]                     → 0001000a  (finalize identity)
11. write [MODE_SHOOT = 0x02]        → 00030010  (switch camera to shooting mode)
```

### Shutter (after the handshake)

```
toggle  → write [0x00, 0x01] → 00030030     (button DOWN ↔ UP)
focus   → no-op (smartphone mode does not split AF; the camera AFs on release)
```

**The RP shutter has two distinct events whose meaning is gated by the camera
dial setting** (verified 2026-05-29, refined v0.290):

- **Bulb dial** — `[00 01]` toggles the shutter-open state (open ↔ closed);
  `[00 02]` is inert. Empirical proof: a probe firing 5 `[00 01]`s (odd
  parity) interleaved with `[00 02]`/`[00 00]` left the camera **still
  exposed** at the end. A bulb op is two `[00 01]` toggles → back to closed.
- **M (non-bulb) dial** — `[00 01]` = shutter **press** event, `[00 02]` =
  shutter **release** event. Sending two `[00 01]`s in M leaves the body
  shooting non-stop (each `[00 01]` is interpreted as another press; verified
  on RP via diagnostics log 2026-05-29 18:11 — every write GATT_SUCCESS, body
  still pressed). A single shot in M is `[00 01]` → wait → `[00 02]` (verified
  firing one frame on RP, v0.290).

The earlier-read "[00 02] is inert" was a Bulb-only artifact — Bulb tracks
state on `[00 01]` only and ignores release events.

Pulsar therefore splits the smartphone shutter into two methods:
`CanonBleClient.smartShutter` (bulb-state toggle on `[00 01]` for both press
and release, used by `startBulb` / `stopBulb`), and
`CanonBleClient.smartShutterTap` (M-mode press/release on `[00 01]` / `[00 02]`,
used by `fireShutter`). The UI gates the choice at the tile level (Manual ↔
Bulb, Cable release ↔ M) since BLE can't read the dial.

### Geo / time-sync packet (optional, → 00040002, with response)

Packed little-endian struct: `header 0x04`, lat-dir `'N'|'S'`, `float32 lat`,
lon-dir `'E'|'W'`, `float32 lon`, elev-sign `'+'|'-'`, `float32 elev`,
`uint32 unix-timestamp`.

### ✅ CONFIRMED FIRING: EOS RP, smartphone mode (2026-05-28, body D0:40:EF:6C:F1:08)

The **EOS RP took a real shot** via `tools/canon_ble_test.py --smart` — the first
confirmed fire from this codebase, validating furble's protocol end-to-end. The
working recipe:

1. **Clean BLE bond first.** `bluetoothctl remove <mac>` → `pair` → `trust`.
   A **stale bond** blocked every connect (`failed to discover services, device
   disconnected`) until removed and re-paired clean. The RP requires an
   encrypted/bonded link before it allows GATT discovery — unlike the EOS R,
   which served GATT unbonded.
2. Identify handshake (exactly furble §7): `[01,name]`→`00010006`,
   `[03,<16-byte uuid>]`→`0001000a`, `[04,name]`→`0001000a`, `[05,02]`→`0001000a`.
3. Camera indicates `0x02` on `00010006` (confirm on body) → **ACCEPTED**.
4. Finalize `[01]`→`0001000a`.
5. **Mode** `[02]`→`00030010` — char `00030011` flips `01`→`04` (shoot-mode active).
6. **Shutter** `[00 01]` press / `[00 02]` release →`00030030`. **Fires.**

**RP smartphone-mode GATT** (16+ chars): `00010000` identity (`0001000a`,
`00010006`, `00010005`, `0001000b`) · `00020000` (`00020001`-`6`) · **`00030000`
control** (`00030001/02` status-notify, `00030010` mode, `00030011`
mode-state-notify, `00030020/21`, `00030030` shutter, `00030031`) · `00040000`
geo (`00040001/02/03`). **The RP HAS `00030000`; the EOS R does not** (next
subsection) — confirming furble's `00030000` UUIDs are correct for the RP and
that the 2018 EOS R is the divergent body.

### Empirical: EOS R smartphone-mode GATT (confirmed 2026-05-28, body DC:FE:23:40:0C:02)

Setting the EOS R to "Connect to smartphone / register a device" makes it
advertise **`00010000`** (BR-E1 `00050000` gone). `--dump-only` returned:

| Service | Chars (handle, props) | vs furble |
|---|---|---|
| `00010000` identity | `00010005`[0x14 read] · `0001000a`[0x16 w,wnr] · `0001000b`[0x18 read] · `00010006`[0x1a w,wnr,**ind**] | matches (`0001000a`=IDEN, `00010006`=NAME+pairing-ind) ✓ |
| **`00020000`** | `00020001`[0x1e r] · `00020002`[0x20 **w,wnr,notify**] · `00020003`[0x23 r,ind] · `00020004/5/6`[r] | **furble has NO `00020000`** — R-specific |
| `00040000` geo | `00040001`[0x2d r] · `00040002`[0x2f w,wnr] · `00040003`[0x31 r,ind] | matches (`00040002`=geo write, `00040003`=geo ind) ✓ |

**Key difference:** furble (built for an EOS M6) puts mode on `00030010` and
shutter on `00030030` — **service `00030000`, which is absent on the EOS R.**
Instead the R exposes `00020000` (its `00020002` is write+notify — a
control-channel shape).

**Resolved 2026-05-28 (clean-bond retest):** even with a clean BlueZ bond and an
**accepted registration** (`◀ 00010006: 02`), the EOS R **still exposes no
`00030000` and did not fire.** The original 2018 EOS R genuinely lacks the RP's
control service.

**DEFINITIVE (2026-05-28): the EOS R has no BLE shutter at all.** Canon's own
**Camera Connect** app cannot shoot the EOS R over Bluetooth — it requires
**Wi-Fi** to fire (confirmed on the actual body). The **RP shoots over BLE with
no Wi-Fi.** So the EOS R uses BLE only for pairing / wakeup / geo and hands off
to Wi-Fi for control; its `00020000` service is *not* a shutter channel. There
is **nothing to capture** — a BLE shutter for the R simply doesn't exist. The
R's wireless path is Wi-Fi (CCAPI — which won't activate on this body) or USB
PTP (works in Pulsar). **Conclusion: Canon-BLE shutter is RP / R5 / R6 / newer
(bodies exposing `00030000`); the 2018 EOS R is out of scope for BLE.**

Design consequence: auto-detect treats **smartphone-mode + no `00030000`** as
*unsupported* and tells the user to use USB/Wi-Fi (the EOS R case).

### Why this is the promising path

- It's a **documented, complete** handshake — not blind probing.
- It is **confirmed firing an EOS RP** (and R6 II) in furble. Our bodies are an
  EOS R + RP, so the odds are good.
- It needs the camera in **"Connect to smartphone" / register-a-device** mode
  (advertising `00010000`), **not** "Remote" mode. Confirm with
  `tools/canon_ble_test.py --dump-only` while in that mode — we expect the
  `00010000` service to appear (and the `00050000` BR-E1 service to be absent).

### Open questions before porting into Pulsar

1. Does the EOS R/RP, in smartphone-register mode, actually advertise
   `00010000`? (Test: `--dump-only`.)
2. The handshake needs an **encrypted/bonded** link (`secureConnection`).
   bleak on Linux doesn't bond by itself — may need `bluetoothctl pair` first,
   or BlueZ on-demand pairing. Android (Nordic BLE) bonds natively, so if the
   protocol works it ports cleanly to `CanonBleClient`.
3. The generated **128-bit device UUID** must be **persisted** so re-connects
   reuse the same identity (else the camera re-prompts to pair every time).

---

## 7b. BLE mechanism map (by family, living)

Mechanisms are **per family, not per body** — bodies of the same generation
share Canon's BLE firmware, so one mapping covers the whole family (add a body
to a known family for free). Two-level selection:

1. **Protocol** — auto-detected from the GATT on connect: BR-E1 (`00050000`),
   smartphone-with-control (`00010000`+`00030000`), or smartphone-no-shutter
   (`00010000`, no `00030000`). Robust and body-agnostic.
2. **Shutter encoding** (within smartphone-with-control) — **NOT distinguishable
   from the GATT** (toggle and press/release both expose `00030000`/`00030030`).
   If it ever diverges, key the override on the **model family / generation**
   (Device Information `0x2a24` model, `0x2a26` firmware), in one spot:
   `CanonBleClient.smartShutter` / `smartShutterTap`. Pulsar's `[00 01]`
   toggle (bulb) and `[00 01]` press / `[00 02]` release (M) are both
   confirmed on the RP — no per-family override needed today.

| Path | Detection | Shutter encoding | Bodies |
|---|---|---|---|
| **BR-E1 remote** | adv/GATT `00050000` | `mode\|button` byte → `00050003` | M50, 200D, 77D, 800D, older DSLRs (NOT R-series) |
| **Smartphone — bulb-state toggle** | `00010000` + `00030000`; sent by `smartShutter` (used by `startBulb` / `stopBulb`) | `[00 01]` toggles open ↔ closed → `00030030` | EOS RP ✅ confirmed (v0.272); R5/R6/R6 II expected (untested) |
| **Smartphone — press / release events** | `00010000` + `00030000`; sent by `smartShutterTap` (used by `fireShutter`) | press `[00 01]` / release `[00 02]` → `00030030` | EOS RP ✅ confirmed in M (v0.290); M6 documented in gkoh/furble (untested) |
| **Smartphone — no shutter** | `00010000`, **no `00030000`** | none (Camera Connect uses Wi-Fi) | EOS R (2018) — use USB/Wi-Fi |

How to add to the map: connect the body, run Tools → Collect diagnostics (or
`tools/canon_ble_test.py --dump-only`), note the GATT services + the Device-Info
model string, and which shutter bytes actually fire it — then slot it under the
matching mechanism family (or open a new one if it's genuinely different).

## 8. Licensing note

Reference licenses: `cbremote` Apache-2.0; `BR-M5`, `cannon-bluetooth-remote`
MIT; `eos-remote-web` MIT; the blog posts are pedagogical. All compatible with
Pulsar's GPL-3.0-or-later. Pulsar's transport is a clean-room re-expression of
the *protocol facts* (UUIDs, byte layout), not a copy of any project's code.

See also: [canon-ble.md](canon-ble.md) (the shipped transport design).
