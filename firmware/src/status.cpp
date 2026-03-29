/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "status.h"
#include "ble_server.h"
#include "config.h"

uint8_t battery_read_pct() {
    uint32_t raw = analogRead(PIN_BATTERY);
    // ESP32 ADC: 12-bit (0-4095), 3.3 V reference
    float voltage = (raw / 4095.0f) * 3.3f * BATTERY_DIVIDER_RATIO;
    uint32_t mv = (uint32_t)(voltage * 1000.0f);

    if (mv >= BATTERY_FULL_MV) return 100;
    if (mv <= BATTERY_EMPTY_MV) return 0;
    return (uint8_t)((mv - BATTERY_EMPTY_MV) * 100 / (BATTERY_FULL_MV - BATTERY_EMPTY_MV));
}

void status_send(State state, Mode mode, uint16_t shots, uint32_t time_ms, uint8_t error) {
    StatusFrame f = {};
    f.state = state;
    f.mode = mode;
    f.shots_taken = shots;
    f.time_remaining_ms = time_ms;
    f.battery_pct = battery_read_pct();
    f.error_code = error;
    ble_notify(reinterpret_cast<const uint8_t*>(&f), sizeof(f));
}
