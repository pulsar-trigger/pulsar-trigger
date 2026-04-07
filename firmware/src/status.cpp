/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "status.h"
#include "ble_server.h"
#include "config.h"

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

void status_send(State state, Mode mode, uint16_t shots, uint32_t time_ms, uint8_t error) {
    StatusFrame f = {};
    f.state = state;
    f.mode = mode;
    f.shots_taken = shots;
    f.time_remaining_ms = time_ms;
    f.battery_pct = battery_read_pct();
    f.error_code = error;
    f.fw_major = FW_VERSION_MAJOR;
    f.fw_minor = FW_VERSION_MINOR;
    f.fw_patch = FW_VERSION_PATCH;
    ble_notify(reinterpret_cast<const uint8_t*>(&f), sizeof(f));
}
