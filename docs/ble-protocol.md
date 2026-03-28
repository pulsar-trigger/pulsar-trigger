# Pulsar BLE Protocol v1

## Service & Characteristics

| Item | UUID |
|------|------|
| **Service** | `0000ff00-0000-1000-8000-00805f9b34fb` |
| Command (Write) | `0000ff01-...` |
| Status  (Notify) | `0000ff02-...` |

## Command Frame (Write to FF01)

All commands are a fixed **20-byte** BLE packet (ATT MTU friendly).

```
Byte 0      : CMD  (command id)
Byte 1      : MODE (trigger mode)
Bytes 2-19  : Payload (mode-specific, zero-padded)
```

### CMD values

| CMD | Name | Description |
|-----|------|-------------|
| 0x01 | SET_MODE | Configure a trigger mode and its parameters |
| 0x02 | START | Begin current mode |
| 0x03 | STOP | Stop / abort |
| 0x04 | SHUTTER | Single manual shutter fire |
| 0x05 | STATUS_REQ | Request a status update |
| 0x06 | SET_FOCUS_MS | Set pre-focus time (ms) in payload u16 LE |

### MODE values (used with SET_MODE)

| MODE | Name | Payload layout |
|------|------|----------------|
| 0x01 | INTERVALOMETER | interval_ms(u32 LE), exposure_ms(u32 LE), count(u16 LE), delay_ms(u32 LE) |
| 0x02 | SOUND | threshold(u16 LE), exposure_ms(u32 LE) |
| 0x03 | LIGHTNING | sensitivity(u8), exposure_ms(u32 LE) |
| 0x04 | LASER | exposure_ms(u32 LE) |
| 0x05 | HDR | exposures[] array: count(u8), then up to 5x u32 LE ms values |
| 0x06 | PRESS_HOLD | (no params — trigger while button held via START/STOP) |
| 0x07 | PRESS_LOCK | (no params — toggle on START, off on STOP) |

## Status Frame (Notify on FF02)

```
Byte 0      : STATE  (0=IDLE, 1=RUNNING, 2=WAITING, 3=ERROR)
Byte 1      : MODE   (current mode)
Bytes 2-3   : shots_taken (u16 LE)
Bytes 4-7   : time_remaining_ms (u32 LE)  — next shot or total
Byte 8      : battery_pct (0-100)
Byte 9      : error_code (0=none)
Bytes 10-19 : reserved
```

Status is sent:
- On every shot fired
- Every 1 s while running
- On state change (start / stop / error)
