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
static SoundParams     _sound    = {};
static LightningParams _lightning = {};
static LaserParams     _laser    = {};
static HdrParams       _hdr      = {};

static uint16_t _shots_taken   = 0;
static uint32_t _next_fire_ms  = 0;
static uint32_t _focus_ms      = DEFAULT_FOCUS_MS;
static bool     _lock_active   = false;

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
    pinMode(PIN_SOUND, INPUT);
    pinMode(PIN_LIGHT, INPUT);
    pinMode(PIN_LASER_RX, INPUT);
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
            return true;
        }
        case MODE_SOUND: {
            if (len < sizeof(SoundParams)) return false;
            _sound.threshold = clamp_u16(read_u16_le(payload), MIN_SOUND_THRESHOLD, MAX_SOUND_THRESHOLD);
            _sound.exposure_ms = clamp_u32(read_u32_le(payload + 2), MIN_EXPOSURE_MS, MAX_EXPOSURE_MS);
            _mode = mode;
            return true;
        }
        case MODE_LIGHTNING: {
            if (len < sizeof(LightningParams)) return false;
            _lightning.sensitivity = clamp_u32(payload[0], MIN_LIGHTNING_SENS, MAX_LIGHTNING_SENS);
            _lightning.exposure_ms = clamp_u32(read_u32_le(payload + 1), MIN_EXPOSURE_MS, MAX_EXPOSURE_MS);
            _mode = mode;
            return true;
        }
        case MODE_LASER: {
            if (len < sizeof(LaserParams)) return false;
            _laser.exposure_ms = clamp_u32(read_u32_le(payload), MIN_EXPOSURE_MS, MAX_EXPOSURE_MS);
            _mode = mode;
            return true;
        }
        case MODE_HDR: {
            if (len < sizeof(uint8_t)) return false;
            uint8_t requested = payload[0];
            if (requested < MIN_HDR_COUNT || requested > MAX_HDR_COUNT) return false;
            size_t needed = 1 + requested * sizeof(uint32_t);
            if (len < needed) return false;
            _hdr.count = requested;
            for (uint8_t i = 0; i < _hdr.count; i++) {
                _hdr.exposures[i] = clamp_u32(
                    read_u32_le(payload + 1 + (i * sizeof(uint32_t))),
                    MIN_EXPOSURE_MS,
                    MAX_EXPOSURE_MS
                );
            }
            for (uint8_t i = _hdr.count; i < MAX_HDR_COUNT; i++) {
                _hdr.exposures[i] = 0;
            }
            _mode = mode;
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
    _state = STATE_RUNNING;

    if (_mode == MODE_INTERVALOMETER) {
        _next_fire_ms = millis() + _interval.delay_ms;
        _state = STATE_WAITING;
    } else if (_mode == MODE_PRESS_HOLD || _mode == MODE_PRESS_LOCK) {
        _lock_active = true;
        camera_focus(true);
        digitalWrite(PIN_SHUTTER, HIGH);
    }

    status_send(_state, _mode, _shots_taken, 0);
}

void triggers_stop() {
    _state = STATE_IDLE;

    if (_mode == MODE_PRESS_HOLD || _mode == MODE_PRESS_LOCK) {
        digitalWrite(PIN_SHUTTER, LOW);
        camera_focus(false);
        _lock_active = false;
    }

    status_send(_state, _mode, _shots_taken, 0);
}

void triggers_single_shot() {
    camera_shutter(200, _focus_ms);  // 200 ms default single shot
}

Mode  triggers_current_mode()  { return _mode; }
State triggers_current_state() { return _state; }

// ── Tick (called from loop) ──────────────────────────────────────────────────
void triggers_tick() {
    if (_state != STATE_RUNNING && _state != STATE_WAITING) return;

    uint32_t now = millis();

    switch (_mode) {
        // ── Intervalometer ───────────────────────────────────────────────
        case MODE_INTERVALOMETER: {
            if (now >= _next_fire_ms) {
                _state = STATE_RUNNING;
                fire_and_count(_interval.exposure_ms);

                if (_interval.count > 0 && _shots_taken >= _interval.count) {
                    _state = STATE_IDLE;
                    status_send(_state, _mode, _shots_taken, 0);
                    return;
                }
                _next_fire_ms = millis() + _interval.interval_ms;
                _state = STATE_WAITING;

                uint32_t remaining = _next_fire_ms - millis();
                status_send(_state, _mode, _shots_taken, remaining);
            }
            break;
        }

        // ── Sound trigger ────────────────────────────────────────────────
        case MODE_SOUND: {
            uint16_t val = analogRead(PIN_SOUND);
            if (val > _sound.threshold) {
                fire_and_count(_sound.exposure_ms);
                delay(DEBOUNCE_MS);
            }
            break;
        }

        // ── Lightning trigger ────────────────────────────────────────────
        case MODE_LIGHTNING: {
            // Sensitivity maps to threshold: higher sensitivity = lower threshold
            uint16_t thresh = 4095 - (_lightning.sensitivity * 800);
            uint16_t val = analogRead(PIN_LIGHT);
            if (val > thresh) {
                fire_and_count(_lightning.exposure_ms);
                delay(DEBOUNCE_MS);
            }
            break;
        }

        // ── Laser break-beam ─────────────────────────────────────────────
        case MODE_LASER: {
            uint16_t val = analogRead(PIN_LASER_RX);
            if (val < LASER_BREAK_THRESH) {  // beam broken = low reading
                fire_and_count(_laser.exposure_ms);
                delay(DEBOUNCE_MS);
            }
            break;
        }

        // ── HDR bracket ──────────────────────────────────────────────────
        case MODE_HDR: {
            for (uint8_t i = 0; i < _hdr.count; i++) {
                camera_shutter(_hdr.exposures[i], _focus_ms);
                _shots_taken++;
                delay(500);  // gap between brackets
            }
            _state = STATE_IDLE;
            status_send(_state, _mode, _shots_taken, 0);
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
