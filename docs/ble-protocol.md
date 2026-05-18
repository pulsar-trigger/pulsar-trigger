# Pulsar BLE Protocol

> The BLE wire format used by current firmware and the Android app. Speaks
> protocol version `0x02`: a 1-byte opcode + 1-byte version + TLV payload.
> Older builds spoke a fixed-layout `[CMD][MODE][18 bytes]` framing; that
> revision is no longer supported on either side.

## Why TLV

The old fixed frame ran out of room every time a new parameter was added,
and mode `0x01` was overloaded (Intervalometer and Astro shared the wire id,
distinguished only by app-side state). The current frame is **self-describing
TLV** behind a 1-byte opcode + 1-byte version — new fields don't break old
clients; deprecated fields can be retired without rebuilding everyone.

## Frame envelope (both directions)

```
+--------+--------+--------+-----------------------+
| opcode | ver    | len    | TLV bytes (len bytes) |
| u8     | u8     | u8     |                       |
+--------+--------+--------+-----------------------+
```

- `opcode` — message type. Identifies a *single* operation; eliminates the
  CMD + MODE pairing.
- `ver` — protocol minor version. Receiver ignores unknown TLV tags within
  the same major version; rejects on major-version mismatch.
- `len` — number of TLV bytes that follow.
- TLV bytes — zero or more `[tag:1][tag-len:1][value:tag-len]` triples.

The current wire version is **`0x02`**.

### Why opcode + TLV rather than CMD + MODE

The old `SET_MODE(MODE_ASTRO)` / `SET_MODE(MODE_INTERVALOMETER)` pair lived
behind one CMD byte that branched on a second MODE byte. Folding into a
single opcode (`OP_SET_ASTRO`, `OP_SET_INTERVALOMETER`) gives the receiver
one switch instead of nested branches, and each mode gets its own opcode —
no more `0x01` overloading.

## Opcodes (app → firmware)

`0x01–0x0F` is reserved for the original CMD byte range to make pre-v2 and
v2 frames unambiguous on the wire (a discriminator the receiver no longer
needs, but kept for forensics).

| Range       | Use                                  |
| ----------- | ------------------------------------ |
| `0x01–0x0F` | Reserved (was: pre-v2 CMD bytes)     |
| `0x10–0x4F` | Setters (mode + params)              |
| `0x50–0x7F` | Commands (start/stop/etc.)           |

Setter opcodes:

| Opcode | Operation               | TLVs accepted                                                     |
| ------ | ----------------------- | ----------------------------------------------------------------- |
| `0x10` | `SET_INTERVALOMETER`    | INTERVAL_MS, EXPOSURE_MS, SHOT_COUNT, DELAY_MS                    |
| `0x11` | `SET_ASTRO`             | INTERVAL_MS, EXPOSURE_MS, SHOT_COUNT, DELAY_MS, (+ optical TLVs)  |
| `0x12` | `SET_DARK_FRAME`        | INTERVAL_MS, EXPOSURE_MS, SHOT_COUNT, DELAY_MS                    |
| `0x13` | `SET_RAMP`              | RAMP_START_MS, RAMP_END_MS, RAMP_STEPS, INTERVAL_MS               |
| `0x14` | `SET_PRESS_HOLD`        | (none)                                                            |
| `0x15` | `SET_PRESS_LOCK`        | (none)                                                            |
| `0x16` | `SET_TRACKER`           | (none)                                                            |
| `0x20` | `SET_FOCUS`             | FOCUS_MS                                                          |
| `0x21` | `SET_PINS`              | SHUTTER_PIN, FOCUS_PIN                                            |
| `0x22` | `SET_AUTO_OFF`          | AUTO_OFF_MIN                                                      |
| `0x23` | `SET_NAME`              | NAME_UTF8                                                         |

Control opcodes:

| Opcode | Operation       |
| ------ | --------------- |
| `0x50` | `START`         |
| `0x51` | `STOP`          |
| `0x52` | `SHUTTER`       |
| `0x53` | `STATUS_REQ`    |
| `0x54` | `DEVICE_INFO_REQ` |

## Opcodes (firmware → app, notify channel)

| Range       | Use                                            |
| ----------- | ---------------------------------------------- |
| `0x00–0x03` | Reserved (was: pre-v2 STATE bytes)             |
| `0xFF`      | Reserved (was: pre-v2 DeviceInfo marker)       |
| `0x80–0xBF` | Notifications                                  |

| Opcode | Notification        | TLVs                                                                          |
| ------ | ------------------- | ----------------------------------------------------------------------------- |
| `0x80` | `STATUS`            | STATE, MODE, SHOTS_TAKEN, TIME_REMAIN_MS, BATTERY_PCT, ERROR_CODE, FW_VERSION |
| `0x81` | `DEVICE_INFO`       | CHIP_MODEL, CHIP_REVISION, CPU_FREQ_MHZ, FLASH_SIZE_KB, FREE_HEAP_KB, PSRAM_KB, GPIO_COUNT, SAFE_OUT_COUNT, UPTIME_MIN |
| `0x82` | `ACK` *(optional)*  | OPCODE (the opcode being ack'd), ERROR_CODE                                   |

`ACK` is optional. Senders that don't need confirmation can ignore the
notify; useful when a command would otherwise silently no-op.

## TLV tag registry

Tags are partitioned by intent to keep the registry readable. Within the
current major version, unknown tags are ignored — clients can write tags
the other side doesn't know about and the operation still completes with
whatever the receiver did understand.

### Capture parameters (`0x01–0x0F`)

| Tag   | Name              | Type     | Notes                                  |
| ----- | ----------------- | -------- | -------------------------------------- |
| `0x01` | `INTERVAL_MS`    | u32 LE   | ms between exposures (or gap)          |
| `0x02` | `EXPOSURE_MS`    | u32 LE   | shutter-open duration                  |
| `0x03` | `SHOT_COUNT`     | u16 LE   | 0 = infinite                           |
| `0x04` | `DELAY_MS`       | u32 LE   | pre-flow start delay                   |

### Optical / Astro (`0x10–0x1F`)

| Tag   | Name              | Type     | Notes                                  |
| ----- | ----------------- | -------- | -------------------------------------- |
| `0x10` | `FOCAL_LENGTH`   | u16 LE   | mm                                     |
| `0x11` | `CROP_FACTOR`    | u16 LE   | × 1000 (i.e. 1.5 → 1500)               |
| `0x12` | `RULE_DIVISOR`   | u16 LE   | 0 = NPF rule                           |

### Ramp (`0x20–0x2F`)

| Tag   | Name                | Type   |
| ----- | ------------------- | ------ |
| `0x20` | `RAMP_START_MS`    | u32 LE |
| `0x21` | `RAMP_END_MS`      | u32 LE |
| `0x22` | `RAMP_STEPS`       | u16 LE |

### Hardware / device (`0x30–0x4F`)

| Tag   | Name                | Type      | Notes                                  |
| ----- | ------------------- | --------- | -------------------------------------- |
| `0x30` | `FOCUS_MS`         | u16 LE    | pre-shutter focus pulse                |
| `0x31` | `SHUTTER_PIN`      | u8        | safe GPIO from device-info range       |
| `0x32` | `FOCUS_PIN`        | u8        | safe GPIO from device-info range       |
| `0x33` | `AUTO_OFF_MIN`     | u16 LE    | 0 = disabled                           |
| `0x34` | `NAME_UTF8`        | bytes     | up to 16 bytes UTF-8 (verify on fw)    |

### Status (`0x50–0x6F`)

| Tag   | Name                | Type      | Notes                                  |
| ----- | ------------------- | --------- | -------------------------------------- |
| `0x50` | `STATE`            | u8        | 0=IDLE, 1=RUNNING, 2=WAITING, 3=ERROR  |
| `0x51` | `MODE`             | u8        | matches the SET_* opcode space         |
| `0x52` | `SHOTS_TAKEN`      | u16 LE    |                                        |
| `0x53` | `TIME_REMAIN_MS`   | u32 LE    |                                        |
| `0x54` | `BATTERY_PCT`      | u8        | 0–100                                  |
| `0x55` | `ERROR_CODE`       | u8        | 0 = none                               |
| `0x56` | `FW_VERSION`       | 3 bytes   | major, minor, patch                    |
| `0x57` | `OPCODE`           | u8        | (in ACK frames)                        |

### Device info (`0x70–0x8F`)

| Tag   | Name                | Type      |
| ----- | ------------------- | --------- |
| `0x70` | `CHIP_MODEL`       | u8        |
| `0x71` | `CHIP_REVISION`    | u8        |
| `0x72` | `CPU_FREQ_MHZ`     | u8        |
| `0x73` | `FLASH_SIZE_KB`    | u32 LE    |
| `0x74` | `FREE_HEAP_KB`     | u32 LE    |
| `0x75` | `PSRAM_KB`         | u16 LE    |
| `0x76` | `GPIO_COUNT`       | u8        |
| `0x77` | `SAFE_OUT_COUNT`   | u8        |
| `0x78` | `UPTIME_MIN`       | u16 LE    |

## Version-mismatch policy

- **Major mismatch** (`ver` byte in an unsupported major range) — refuse to
  operate, surface a clear message. Major versions are reserved for envelope
  changes, never bumped lightly.
- **Minor mismatch** — both sides operate at the lower minor. Unknown TLVs
  are ignored, so newer clients can send new tags to older firmware and the
  operation still completes with whatever the receiver understood. New
  opcodes that the lower-version side doesn't recognise are silently
  no-ops; if `ACK` is supported, the receiver returns `ERROR_CODE =
  UNSUPPORTED_OPCODE`.

## Worked example

App sends `SET_ASTRO` with NPF rule, 60s exposure, 30 shots, 4s gap,
focal length 14mm, full-frame:

```
opcode = 0x11 (SET_ASTRO)
ver    = 0x02
len    = 27

TLV bytes:
  01 04 a0 8c 00 00          // INTERVAL_MS = 36 000 = ... wait, simpler: 4000 ms
  01 04 a0 0f 00 00          // INTERVAL_MS = 4000  (u32 LE)
  02 04 60 ea 00 00          // EXPOSURE_MS = 60000 (u32 LE)
  03 02 1e 00                // SHOT_COUNT  = 30    (u16 LE)
  04 04 00 00 00 00          // DELAY_MS    = 0
  10 02 0e 00                // FOCAL_LENGTH = 14
  11 02 e8 03                // CROP_FACTOR  = 1000 (=1.0×)
  12 02 00 00                // RULE_DIVISOR = 0 (NPF)
```

Total: 3 (envelope) + 27 (TLV) = 30 bytes. Negotiated MTU at startup is
typically well above this; OTA pulls it to 517 so we have headroom.

Status frame on a running shot:

```
opcode = 0x80 (STATUS)
ver    = 0x02
len    = 16

TLV bytes:
  50 01 01                   // STATE = RUNNING
  51 01 11                   // MODE  = SET_ASTRO
  52 02 05 00                // SHOTS_TAKEN = 5
  53 04 a0 8c 00 00          // TIME_REMAIN_MS = 36 000
  54 01 5a                   // BATTERY_PCT = 90
  55 01 00                   // ERROR_CODE = 0
  56 03 00 1c 00             // FW_VERSION = 0.28.0
```

## MTU

The Nordic BLE client requests an extended MTU at connection time so
typical frames (30-byte command bursts, status notifications, long
`SET_NAME` payloads) fit comfortably. OTA bumps the MTU further (517) to
push firmware chunks. The default 23-byte ATT MTU is never relied on.

4. **Major-version bumping policy** — when does v3 happen? My take:
   never, unless we need a fundamentally different envelope (e.g. CBOR,
   COBS framing). The whole point of TLV is that minor v2.1, v2.2, etc.
   can accommodate everything we'd plausibly add.

5. **Astro params on the wire vs computed app-side** — Astro today
   computes exposure from focal/crop/rule app-side and sends a plain
   exposure_ms. The TLV schema above forwards the optics, leaving the
   computation to firmware. **Decision needed.** I lean **keep computing
   app-side** for now — the firmware doesn't need that knowledge, and
   leaving it on the app keeps the firmware simpler. The optical TLVs in
   the schema are a future hook, not v0.180 requirements.

## What changes after sign-off

This doc is the design. Implementation lands in this order:

1. **Firmware** — protocol parser, opcode dispatcher, TLV reader, status
   notify writer. Roughly 200–300 LOC change, mostly new files
   (`protocol_v2.cpp` alongside existing `protocol.h`); existing v1 path
   stays for the compat window.
2. **App** — `CommandBuilder` writes v2; `StatusFrame.parse` handles both
   v1 and v2 by sniffing the first byte (per the discriminator table).
3. **Tests** — round-trip encode/decode on both sides, plus a v1 frame
   parser test in the app so legacy fw still talks.
4. **Real-hardware validation** — fresh fw + fresh app, plus the matrix
   from the compat-window decision (b means only fresh+fresh works; (a)
   would test the cross too).

Estimated effort if (b): 2 days end to end. If (a): 4 days.
