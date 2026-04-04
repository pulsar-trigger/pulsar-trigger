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
    CMD_DEVICE_INFO = 0x0A,
};

// ── Trigger Modes (byte 1) ──────────────────────────────────────────────────
enum Mode : uint8_t {
    MODE_NONE          = 0x00,
    MODE_INTERVALOMETER = 0x01,
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

// ── OTA control commands ─────────────────────────────────────────────────────
enum OtaCmd : uint8_t {
    OTA_BEGIN  = 0x01,  // payload: u32LE total_size
    OTA_END    = 0x02,  // no payload — validate & reboot
    OTA_ABORT  = 0x03,  // no payload — cancel OTA
};

// OTA status codes sent back via OTA control characteristic (notify)
enum OtaStatus : uint8_t {
    OTA_OK          = 0x00,
    OTA_ERR_BEGIN   = 0x01,
    OTA_ERR_WRITE   = 0x02,
    OTA_ERR_VALIDATE = 0x03,
    OTA_ERR_SIZE    = 0x04,
    OTA_READY       = 0x10,  // sent after OTA_BEGIN accepted
    OTA_COMPLETE    = 0x11,  // sent after OTA_END validated (rebooting)
};

// ── Status frame (20 bytes, sent via notify) ────────────────────────────────
struct __attribute__((packed)) StatusFrame {
    uint8_t  state;
    uint8_t  mode;
    uint16_t shots_taken;
    uint32_t time_remaining_ms;
    uint8_t  battery_pct;
    uint8_t  error_code;
    uint8_t  fw_major;
    uint8_t  fw_minor;
    uint8_t  fw_patch;
    uint8_t  reserved[7];
};

// ── Device info frame (20 bytes, sent in response to CMD_DEVICE_INFO) ───────
// Byte 0     = 0xFF marker (distinguishes from StatusFrame whose byte 0 is 0x00–0x03)
// Bytes 1–19 = hardware info fields
struct __attribute__((packed)) DeviceInfoFrame {
    uint8_t  marker;             // always 0xFF
    uint8_t  chip_model;         // 1=ESP32, 2=ESP32-S2, 3=ESP32-S3, 4=ESP32-C3
    uint8_t  chip_revision;      // silicon revision
    uint8_t  cpu_freq_mhz;      // CPU freq / 1 (e.g. 240)
    uint32_t flash_size_kb;      // flash size in KB
    uint32_t free_heap_kb;       // available heap in KB
    uint16_t psram_kb;           // PSRAM in KB (0 if none)
    uint8_t  gpio_count;         // total GPIO pins
    uint8_t  safe_output_count;  // configurable output pins
    uint16_t uptime_minutes;     // device uptime in minutes
};
