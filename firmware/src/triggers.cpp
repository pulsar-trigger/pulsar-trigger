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

void triggers_set_mode(Mode mode, const uint8_t* payload, size_t len) {
    _mode = mode;
    switch (mode) {
        case MODE_INTERVALOMETER:
            if (len >= sizeof(IntervalParams))
                memcpy(&_interval, payload, sizeof(IntervalParams));
            break;
        case MODE_SOUND:
            if (len >= sizeof(SoundParams))
                memcpy(&_sound, payload, sizeof(SoundParams));
            break;
        case MODE_LIGHTNING:
            if (len >= sizeof(LightningParams))
                memcpy(&_lightning, payload, sizeof(LightningParams));
            break;
        case MODE_LASER:
            if (len >= sizeof(LaserParams))
                memcpy(&_laser, payload, sizeof(LaserParams));
            break;
        case MODE_HDR:
            if (len >= sizeof(uint8_t)) {
                _hdr.count = payload[0];
                if (_hdr.count > 5) _hdr.count = 5;
                size_t needed = 1 + _hdr.count * sizeof(uint32_t);
                if (len >= needed)
                    memcpy(_hdr.exposures, payload + 1, _hdr.count * sizeof(uint32_t));
            }
            break;
        default:
            break;
    }
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
                _next_fire_ms = now + _interval.interval_ms;
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
