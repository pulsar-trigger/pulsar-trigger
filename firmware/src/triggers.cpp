/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "triggers.h"
#include "camera.h"
#include "status.h"
#include "ble_server.h"
#include "config.h"
#include <Arduino.h>

// ── State ────────────────────────────────────────────────────────────────────
static Mode  _mode  = MODE_NONE;
static volatile State _state = STATE_IDLE;

static IntervalParams  _interval = {};

static volatile uint16_t _shots_taken   = 0;
static volatile uint32_t _next_fire_ms  = 0;
static volatile uint32_t _focus_ms      = DEFAULT_FOCUS_MS;
static volatile bool     _lock_active   = false;
static volatile uint32_t _debounce_until = 0;  // non-blocking debounce timestamp
static volatile uint32_t _last_remaining_ms = 0;  // cached for status getter


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

/// Compute remaining time for an intervalometer job.
/// @param shots_left  number of shots still to fire (including current if pre-fire)
/// @param include_gap whether to include the trailing gap (false = pre-fire, true = post-fire)
static uint32_t calc_remaining_ms(uint16_t shots_left, bool include_gap) {
    if (_interval.count > 0 && shots_left > 0) {
        uint64_t cycle = (uint64_t)_interval.exposure_ms + _interval.interval_ms;
        uint64_t total = (uint64_t)shots_left * cycle;
        if (!include_gap) total -= _interval.interval_ms;
        return (total > UINT32_MAX) ? UINT32_MAX : (uint32_t)total;
    }
    return include_gap ? _interval.interval_ms : _interval.exposure_ms;
}

static void fire_and_count(uint32_t exposure_ms) {
    camera_shutter(exposure_ms, _focus_ms);
    _shots_taken++;
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
        case MODE_INTERVALOMETER:
        case MODE_ASTRO:
        case MODE_DARK_FRAME:
        case MODE_RAMP: {
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
        case MODE_TRACKER:
            _mode = mode;
            return true;
        default:
            return false;
    }
}

void triggers_start() {
    _shots_taken = 0;
    _debounce_until = 0;
    _state = STATE_RUNNING;

    if (_mode == MODE_INTERVALOMETER || _mode == MODE_ASTRO ||
        _mode == MODE_DARK_FRAME    || _mode == MODE_RAMP) {
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
    camera_shutter(SINGLE_SHOT_MS, _focus_ms);
}

Mode  triggers_current_mode()  { return _mode; }
State triggers_current_state() { return _state; }
uint16_t triggers_shots_taken() { return _shots_taken; }
const IntervalParams& triggers_interval_params() { return _interval; }
uint32_t triggers_time_remaining_ms() { return _last_remaining_ms; }
float triggers_tracker_pitch() { return 0.0f; }

// ── Tick (called from loop) ──────────────────────────────────────────────────
void triggers_tick() {
    if (_state != STATE_RUNNING && _state != STATE_WAITING) return;

    uint32_t now = millis();

    // Non-blocking debounce guard for sensor triggers
    if (_debounce_until != 0 && (now - _debounce_until) < DEBOUNCE_MS) return;
    _debounce_until = 0;

    switch (_mode) {
        // ── Intervalometer / Astro / Dark Frame / Ramp ──────────────────
        case MODE_INTERVALOMETER:
        case MODE_ASTRO:
        case MODE_DARK_FRAME:
        case MODE_RAMP: {
            if ((now - _next_fire_ms) < 0x80000000UL) {  // wraparound-safe: now >= _next_fire_ms
                _state = STATE_RUNNING;
                Serial.printf("[INTV] shot %u firing at %lu ms\n", _shots_taken + 1, millis());

                // Notify app BEFORE the blocking exposure so it can show EXPOSING state
                {
                    uint16_t shots_left = (_interval.count > 0) ? _interval.count - _shots_taken : 0;
                    uint32_t pre_remaining = calc_remaining_ms(shots_left, false);
                    status_send(_state, _mode, _shots_taken, pre_remaining);
                }

                fire_and_count(_interval.exposure_ms);

                // Stop requested during exposure — bail out immediately
                if (_state == STATE_IDLE) return;

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
                uint16_t shots_left = (_interval.count > 0) ? _interval.count - _shots_taken : 0;
                uint32_t remaining = calc_remaining_ms(shots_left, true);
                _last_remaining_ms = remaining;
                status_send(_state, _mode, _shots_taken, remaining);
            }
            break;
        }

        // ── Press & Hold — handled via START/STOP, nothing to tick ───────
        case MODE_PRESS_HOLD:
        case MODE_PRESS_LOCK:
            break;

        // ── Tracker — alignment handled app-side, nothing to tick ────────
        case MODE_TRACKER:
            break;

        default:
            break;
    }
}
