/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "triggers.h"
#include "camera.h"
#include "status.h"
#include "config.h"
#include <Arduino.h>

// ── State ────────────────────────────────────────────────────────────────────
static Mode  _mode  = MODE_NONE;
static State _state = STATE_IDLE;

static IntervalParams  _interval = {};

static uint16_t _shots_taken   = 0;
static uint32_t _next_fire_ms  = 0;
static uint32_t _focus_ms      = DEFAULT_FOCUS_MS;
static bool     _lock_active   = false;
static uint32_t _debounce_until = 0;  // non-blocking debounce timestamp
static uint32_t _last_remaining_ms = 0;  // cached for display getter

// ── Helpers ──────────────────────────────────────────────────────────────────
static uint32_t clamp_u32(uint32_t value, uint32_t lower, uint32_t upper) {
    if (value < lower) return lower;
    if (value > upper) return upper;
    return value;
}

static uint16_t clamp_u16(uint16_t value, uint16_t lower, uint16_t upper) {
    if (value < lower) return lower;
    if (value > upper) return upper;
    return value;
}

static uint32_t read_u32_le(const uint8_t* data) {
    return (uint32_t)data[0] |
           ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16) |
           ((uint32_t)data[3] << 24);
}

static uint16_t read_u16_le(const uint8_t* data) {
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8);
}

static void fire_and_count(uint32_t exposure_ms) {
    camera_shutter(exposure_ms, _focus_ms);
    _shots_taken++;
    status_send(_state, _mode, _shots_taken, 0);
}

// ── Public API ───────────────────────────────────────────────────────────────
void triggers_init() {
    _mode = MODE_NONE;
    _state = STATE_IDLE;
}

bool triggers_set_focus(uint16_t ms) {
    _focus_ms = clamp_u32(ms, MIN_FOCUS_MS, MAX_FOCUS_MS);
    return true;
}

bool triggers_set_mode(Mode mode, const uint8_t* payload, size_t len) {
    switch (mode) {
        case MODE_INTERVALOMETER: {
            if (len < sizeof(IntervalParams)) return false;
            _interval.interval_ms = clamp_u32(read_u32_le(payload), MIN_INTERVAL_MS, MAX_INTERVAL_MS);
            _interval.exposure_ms = clamp_u32(read_u32_le(payload + 4), MIN_EXPOSURE_MS, MAX_EXPOSURE_MS);
            _interval.count = clamp_u16(read_u16_le(payload + 8), MIN_SHOT_COUNT, MAX_SHOT_COUNT);
            _interval.delay_ms = clamp_u32(read_u32_le(payload + 10), MIN_DELAY_MS, MAX_DELAY_MS);
            _mode = mode;
            Serial.printf("[INTV] config: interval=%lu exposure=%lu count=%u delay=%lu\n",
                          _interval.interval_ms, _interval.exposure_ms,
                          _interval.count, _interval.delay_ms);
            return true;
        }
        case MODE_PRESS_HOLD:
        case MODE_PRESS_LOCK:
            _mode = mode;
            return true;
        default:
            return false;
    }

    return false;
}

void triggers_start() {
    _shots_taken = 0;
    _debounce_until = 0;
    _state = STATE_RUNNING;

    if (_mode == MODE_INTERVALOMETER) {
        _next_fire_ms = millis() + _interval.delay_ms;
        _state = STATE_WAITING;
    } else if (_mode == MODE_PRESS_HOLD || _mode == MODE_PRESS_LOCK) {
        _lock_active = true;
        camera_focus(true);
        camera_shutter_set(true);
    }

    _last_remaining_ms = 0;
    status_send(_state, _mode, _shots_taken, 0);
}

void triggers_stop() {
    _state = STATE_IDLE;

    if (_mode == MODE_PRESS_HOLD || _mode == MODE_PRESS_LOCK) {
        camera_shutter_set(false);
        camera_focus(false);
        _lock_active = false;
    }

    _last_remaining_ms = 0;
    status_send(_state, _mode, _shots_taken, 0);
}

void triggers_single_shot() {
    camera_shutter(200, _focus_ms);  // 200 ms default single shot
}

Mode  triggers_current_mode()  { return _mode; }
State triggers_current_state() { return _state; }
uint16_t triggers_shots_taken() { return _shots_taken; }
const IntervalParams& triggers_interval_params() { return _interval; }
uint32_t triggers_time_remaining_ms() { return _last_remaining_ms; }

// ── Tick (called from loop) ──────────────────────────────────────────────────
void triggers_tick() {
    if (_state != STATE_RUNNING && _state != STATE_WAITING) return;

    uint32_t now = millis();

    // Non-blocking debounce guard for sensor triggers
    if (_debounce_until != 0 && (now - _debounce_until) < DEBOUNCE_MS) return;
    _debounce_until = 0;

    switch (_mode) {
        // ── Intervalometer ───────────────────────────────────────────────
        case MODE_INTERVALOMETER: {
            if ((now - _next_fire_ms) < 0x80000000UL) {  // wraparound-safe: now >= _next_fire_ms
                _state = STATE_RUNNING;
                Serial.printf("[INTV] shot %u firing at %lu ms\n", _shots_taken + 1, millis());
                fire_and_count(_interval.exposure_ms);

                if (_interval.count > 0 && _shots_taken >= _interval.count) {
                    _state = STATE_IDLE;
                    Serial.printf("[INTV] sequence complete: %u shots\n", _shots_taken);
                    _last_remaining_ms = 0;
                    status_send(_state, _mode, _shots_taken, 0);
                    return;
                }
                // Gap semantics: wait interval_ms AFTER the exposure ends,
                // then fire the next shot.
                _next_fire_ms = millis() + _interval.interval_ms;
                Serial.printf("[INTV] gap %lu ms, next fire at %lu ms\n",
                              _interval.interval_ms, _next_fire_ms);
                _state = STATE_WAITING;

                // Compute total remaining time for the job
                uint32_t remaining;
                if (_interval.count > 0) {
                    uint16_t shots_left = _interval.count - _shots_taken;
                    // remaining = gap + (shots_left-1) * (exposure + gap) + exposure
                    //           = shots_left * (exposure + gap)
                    uint64_t cycle = (uint64_t)_interval.exposure_ms + _interval.interval_ms;
                    uint64_t total = (uint64_t)shots_left * cycle;
                    remaining = (total > UINT32_MAX) ? UINT32_MAX : (uint32_t)total;
                } else {
                    remaining = _interval.interval_ms;  // infinite: gap countdown
                }
                _last_remaining_ms = remaining;
                status_send(_state, _mode, _shots_taken, remaining);
            }
            break;
        }

        // ── Press & Hold — handled via START/STOP, nothing to tick ───────
        case MODE_PRESS_HOLD:
        case MODE_PRESS_LOCK:
            break;

        default:
            break;
    }
}
