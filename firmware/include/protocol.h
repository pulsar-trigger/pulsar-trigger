/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>
#include <cstddef>

// ── Command IDs (byte 0 of BLE write) ───────────────────────────────────────
enum Cmd : uint8_t {
    CMD_SET_MODE   = 0x01,
    CMD_START      = 0x02,
    CMD_STOP       = 0x03,
    CMD_SHUTTER    = 0x04,
    CMD_STATUS_REQ = 0x05,
    CMD_SET_FOCUS  = 0x06,
    CMD_SET_NAME  = 0x08,
    CMD_SET_PINS  = 0x09,
};

// ── Trigger Modes (byte 1) ──────────────────────────────────────────────────
enum Mode : uint8_t {
    MODE_NONE          = 0x00,
    MODE_INTERVALOMETER = 0x01,
    MODE_SOUND         = 0x02,
    MODE_LIGHTNING     = 0x03,
    MODE_LASER         = 0x04,
    MODE_HDR           = 0x05,
    MODE_PRESS_HOLD    = 0x06,
    MODE_PRESS_LOCK    = 0x07,
};

// ── Device State (byte 0 of status notify) ──────────────────────────────────
enum State : uint8_t {
    STATE_IDLE    = 0x00,
    STATE_RUNNING = 0x01,
    STATE_WAITING = 0x02,
    STATE_ERROR   = 0x03,
};

// ── SET_MODE payload structs ────────────────────────────────────────────────
struct __attribute__((packed)) IntervalParams {
    uint32_t interval_ms;
    uint32_t exposure_ms;
    uint16_t count;        // 0 = infinite
    uint32_t delay_ms;     // initial delay before first shot
};

struct __attribute__((packed)) SoundParams {
    uint16_t threshold;
    uint32_t exposure_ms;
};

struct __attribute__((packed)) LightningParams {
    uint8_t  sensitivity;  // 1-5
    uint32_t exposure_ms;
};

struct __attribute__((packed)) LaserParams {
    uint32_t exposure_ms;
};

struct __attribute__((packed)) HdrParams {
    uint8_t  count;        // number of exposures (max 5)
    uint32_t exposures[5]; // ms per bracket
};

// ── Status frame (20 bytes, sent via notify) ────────────────────────────────
struct __attribute__((packed)) StatusFrame {
    uint8_t  state;
    uint8_t  mode;
    uint16_t shots_taken;
    uint32_t time_remaining_ms;
    uint8_t  battery_pct;
    uint8_t  error_code;
    uint8_t  reserved[10];
};
