/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 *
 * Protocol v2: TLV payloads behind a 1-byte opcode + 1-byte version.
 * See docs/ble-protocol-v2.md for the design rationale.
 */

#pragma once
#include <cstdint>
#include <cstddef>
#include <cstring>

namespace v2 {

constexpr uint8_t PROTO_VERSION = 0x02;

// ── Opcodes (app → firmware) ─────────────────────────────────────────────
// 0x01–0x0F reserved for v1 CMD bytes (discriminator).
enum Op : uint8_t {
    OP_SET_INTERVALOMETER = 0x10,
    OP_SET_ASTRO          = 0x11,
    OP_SET_DARK_FRAME     = 0x12,
    OP_SET_RAMP           = 0x13,
    OP_SET_PRESS_HOLD     = 0x14,
    OP_SET_PRESS_LOCK     = 0x15,
    OP_SET_TRACKER        = 0x16,

    OP_SET_FOCUS          = 0x20,
    OP_SET_PINS           = 0x21,
    OP_SET_AUTO_OFF       = 0x22,
    OP_SET_NAME           = 0x23,

    OP_START              = 0x50,
    OP_STOP               = 0x51,
    OP_SHUTTER            = 0x52,
    OP_STATUS_REQ         = 0x53,
    OP_DEVICE_INFO_REQ    = 0x54,
};

// ── Notification opcodes (firmware → app) ─────────────────────────────────
// 0x00–0x03 reserved for v1 STATE bytes, 0xFF for v1 DeviceInfo marker.
enum NotifyOp : uint8_t {
    NOTIFY_STATUS      = 0x80,
    NOTIFY_DEVICE_INFO = 0x81,
    NOTIFY_ACK         = 0x82,
};

// ── TLV tag registry ──────────────────────────────────────────────────────
enum Tag : uint8_t {
    // Capture parameters (0x01–0x0F)
    TAG_INTERVAL_MS    = 0x01,  // u32 LE
    TAG_EXPOSURE_MS    = 0x02,  // u32 LE
    TAG_SHOT_COUNT     = 0x03,  // u16 LE
    TAG_DELAY_MS       = 0x04,  // u32 LE

    // Optical / Astro (0x10–0x1F) — reserved hooks; not used by firmware yet.
    TAG_FOCAL_LENGTH   = 0x10,  // u16 LE
    TAG_CROP_FACTOR    = 0x11,  // u16 LE (× 1000)
    TAG_RULE_DIVISOR   = 0x12,  // u16 LE (0 = NPF)

    // Ramp (0x20–0x2F)
    TAG_RAMP_START_MS  = 0x20,  // u32 LE
    TAG_RAMP_END_MS    = 0x21,  // u32 LE
    TAG_RAMP_STEPS     = 0x22,  // u16 LE

    // Hardware / device (0x30–0x4F)
    TAG_FOCUS_MS       = 0x30,  // u16 LE
    TAG_SHUTTER_PIN    = 0x31,  // u8
    TAG_FOCUS_PIN      = 0x32,  // u8
    TAG_AUTO_OFF_MIN   = 0x33,  // u16 LE
    TAG_NAME_UTF8      = 0x34,  // raw bytes

    // Status (0x50–0x6F)
    TAG_STATE          = 0x50,  // u8
    TAG_MODE           = 0x51,  // u8 (matches OP_SET_* opcode space)
    TAG_SHOTS_TAKEN    = 0x52,  // u16 LE
    TAG_TIME_REMAIN_MS = 0x53,  // u32 LE
    TAG_BATTERY_PCT    = 0x54,  // u8
    TAG_ERROR_CODE     = 0x55,  // u8
    TAG_FW_VERSION     = 0x56,  // 3 bytes (major, minor, patch)
    TAG_OPCODE         = 0x57,  // u8 (in ACK frames)

    // Device info (0x70–0x8F)
    TAG_CHIP_MODEL     = 0x70,  // u8
    TAG_CHIP_REVISION  = 0x71,  // u8
    TAG_CPU_FREQ_MHZ   = 0x72,  // u8
    TAG_FLASH_SIZE_KB  = 0x73,  // u32 LE
    TAG_FREE_HEAP_KB   = 0x74,  // u32 LE
    TAG_PSRAM_KB       = 0x75,  // u16 LE
    TAG_GPIO_COUNT     = 0x76,  // u8
    TAG_SAFE_OUT_COUNT = 0x77,  // u8
    TAG_UPTIME_MIN     = 0x78,  // u16 LE
};

// ── TLV writer ────────────────────────────────────────────────────────────
// Builds frames into a caller-owned buffer. Returns total bytes written
// (envelope + payload) or 0 if the buffer is too small.
class FrameWriter {
public:
    FrameWriter(uint8_t* buf, size_t cap) : _buf(buf), _cap(cap), _len(0) {}

    // Initialise the envelope. Reserves bytes 0/1/2 for opcode/ver/len;
    // returns false if the buffer can't even hold the header.
    bool begin(uint8_t opcode) {
        if (_cap < 3) return false;
        _buf[0] = opcode;
        _buf[1] = PROTO_VERSION;
        _buf[2] = 0;  // payload len, patched at finish()
        _len = 3;
        return true;
    }

    bool putU8(uint8_t tag, uint8_t v) {
        if (_len + 3 > _cap) return false;
        _buf[_len++] = tag;
        _buf[_len++] = 1;
        _buf[_len++] = v;
        return true;
    }

    bool putU16(uint8_t tag, uint16_t v) {
        if (_len + 4 > _cap) return false;
        _buf[_len++] = tag;
        _buf[_len++] = 2;
        _buf[_len++] = (uint8_t)(v & 0xFF);
        _buf[_len++] = (uint8_t)((v >> 8) & 0xFF);
        return true;
    }

    bool putU32(uint8_t tag, uint32_t v) {
        if (_len + 6 > _cap) return false;
        _buf[_len++] = tag;
        _buf[_len++] = 4;
        _buf[_len++] = (uint8_t)(v & 0xFF);
        _buf[_len++] = (uint8_t)((v >> 8) & 0xFF);
        _buf[_len++] = (uint8_t)((v >> 16) & 0xFF);
        _buf[_len++] = (uint8_t)((v >> 24) & 0xFF);
        return true;
    }

    bool putBytes(uint8_t tag, const uint8_t* data, uint8_t len) {
        if (_len + 2 + len > _cap) return false;
        _buf[_len++] = tag;
        _buf[_len++] = len;
        memcpy(_buf + _len, data, len);
        _len += len;
        return true;
    }

    // Finalise: patches the payload-length byte, returns total frame size.
    size_t finish() {
        if (_len < 3) return 0;
        size_t payload = _len - 3;
        if (payload > 0xFF) return 0;  // single-byte length field
        _buf[2] = (uint8_t)payload;
        return _len;
    }

    size_t bytesWritten() const { return _len; }

private:
    uint8_t* _buf;
    size_t   _cap;
    size_t   _len;
};

// ── TLV reader ────────────────────────────────────────────────────────────
// Walks a frame's TLV bytes, calling user code for each tag found. Caller
// is responsible for length validation against the declared envelope `len`.
class FrameReader {
public:
    FrameReader(const uint8_t* data, size_t len) : _data(data), _len(len), _pos(0) {}

    // Parses the envelope; returns false if the buffer is too short or the
    // version mismatches. On success, _pos sits at the first TLV byte.
    bool parseEnvelope(uint8_t& opcode_out, uint8_t& payload_len_out) {
        if (_len < 3) return false;
        opcode_out      = _data[0];
        uint8_t ver     = _data[1];
        payload_len_out = _data[2];
        if (ver != PROTO_VERSION) return false;
        if (3u + payload_len_out > _len) return false;
        _pos = 3;
        return true;
    }

    // Returns true and advances if a TLV is available; false at end.
    bool next(uint8_t& tag, uint8_t& tlen, const uint8_t*& vptr) {
        if (_pos + 2 > _len) return false;
        tag  = _data[_pos];
        tlen = _data[_pos + 1];
        if (_pos + 2 + tlen > _len) return false;
        vptr = _data + _pos + 2;
        _pos += 2 + tlen;
        return true;
    }

    // Convenience extractors. Callers iterate via next() and dispatch on tag.
    static uint8_t  readU8 (const uint8_t* v) { return v[0]; }
    static uint16_t readU16(const uint8_t* v) {
        return (uint16_t)v[0] | ((uint16_t)v[1] << 8);
    }
    static uint32_t readU32(const uint8_t* v) {
        return (uint32_t)v[0]        | ((uint32_t)v[1] << 8) |
               ((uint32_t)v[2] << 16) | ((uint32_t)v[3] << 24);
    }

private:
    const uint8_t* _data;
    size_t         _len;
    size_t         _pos;
};

}  // namespace v2
