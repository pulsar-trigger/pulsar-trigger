/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "status.h"
#include "ble_server.h"
#include "config.h"
#include "protocol_v2.h"

#ifdef HAS_M5DISPLAY
#include <M5Unified.h>
#endif

static uint8_t  _cached_battery_pct = 0;
static uint32_t _last_battery_read  = 0;
static const uint32_t BATTERY_CACHE_MS = 5000;  // refresh at most every 5 s

uint8_t battery_read_pct() {
    uint32_t now = millis();
    if (now - _last_battery_read < BATTERY_CACHE_MS && _last_battery_read != 0) {
        return _cached_battery_pct;
    }
    _last_battery_read = now;

#ifdef HAS_M5DISPLAY
    // M5 boards: read battery level via M5Unified Power API
    int32_t level = M5.Power.getBatteryLevel();
    _cached_battery_pct = (level < 0) ? 0 : (level > 100) ? 100 : (uint8_t)level;
#else
    // Generic ESP32: read battery via ADC + resistor divider
    uint32_t raw = analogRead(PIN_BATTERY);
    // ESP32 ADC: 12-bit (0-4095), 3.3 V reference
    float voltage = (raw / 4095.0f) * 3.3f * BATTERY_DIVIDER_RATIO;
    uint32_t mv = (uint32_t)(voltage * 1000.0f);

    if (mv >= BATTERY_FULL_MV) _cached_battery_pct = 100;
    else if (mv <= BATTERY_EMPTY_MV) _cached_battery_pct = 0;
    else _cached_battery_pct = (uint8_t)((mv - BATTERY_EMPTY_MV) * 100 / (BATTERY_FULL_MV - BATTERY_EMPTY_MV));
#endif

    return _cached_battery_pct;
}

// Map legacy Mode enum → v2 opcode space so the TAG_MODE byte matches the
// SET_* opcode the app sent.
static uint8_t mode_to_v2_opcode(Mode m) {
    switch (m) {
        case MODE_INTERVALOMETER: return v2::OP_SET_INTERVALOMETER;
        case MODE_ASTRO:          return v2::OP_SET_ASTRO;
        case MODE_DARK_FRAME:     return v2::OP_SET_DARK_FRAME;
        case MODE_RAMP:           return v2::OP_SET_RAMP;
        case MODE_PRESS_HOLD:     return v2::OP_SET_PRESS_HOLD;
        case MODE_PRESS_LOCK:     return v2::OP_SET_PRESS_LOCK;
        case MODE_TRACKER:        return v2::OP_SET_TRACKER;
        default:                  return 0x00;  // MODE_NONE
    }
}

void status_send(State state, Mode mode, uint16_t shots, uint32_t time_ms, uint8_t error) {
    uint8_t buf[40];
    v2::FrameWriter w(buf, sizeof(buf));
    if (!w.begin(v2::NOTIFY_STATUS)) return;

    w.putU8 (v2::TAG_STATE,          (uint8_t)state);
    w.putU8 (v2::TAG_MODE,           mode_to_v2_opcode(mode));
    w.putU16(v2::TAG_SHOTS_TAKEN,    shots);
    w.putU32(v2::TAG_TIME_REMAIN_MS, time_ms);
    w.putU8 (v2::TAG_BATTERY_PCT,    battery_read_pct());
    w.putU8 (v2::TAG_ERROR_CODE,     error);

    uint8_t fw[3] = { FW_VERSION_MAJOR, FW_VERSION_MINOR, FW_VERSION_PATCH };
    w.putBytes(v2::TAG_FW_VERSION, fw, sizeof(fw));

    size_t n = w.finish();
    if (n > 0) ble_notify(buf, n);
}
