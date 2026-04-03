/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "camera.h"
#include "triggers.h"

static uint8_t _pin_shutter = DEFAULT_PIN_SHUTTER;
static uint8_t _pin_focus   = DEFAULT_PIN_FOCUS;

static void setup_pins() {
    pinMode(_pin_shutter, OUTPUT);
    pinMode(_pin_focus, OUTPUT);
    digitalWrite(_pin_shutter, LOW);
    digitalWrite(_pin_focus, LOW);
}

void camera_init() {
    setup_pins();
}

void camera_init_pins(uint8_t shutter_pin, uint8_t focus_pin) {
    // Release old pins
    digitalWrite(_pin_shutter, LOW);
    digitalWrite(_pin_focus, LOW);
    // Apply new pins
    _pin_shutter = shutter_pin;
    _pin_focus   = focus_pin;
    setup_pins();
}

void camera_focus(bool on) {
    digitalWrite(_pin_focus, on ? HIGH : LOW);
}

void camera_shutter_set(bool on) {
    digitalWrite(_pin_shutter, on ? HIGH : LOW);
}

/// Non-blocking delay that yields to BLE stack and checks for stop.
/// Returns true if the full duration elapsed, false if aborted.
static bool interruptible_delay(uint32_t ms) {
    uint32_t start = millis();
    while (millis() - start < ms) {
        delay(10);  // yield to FreeRTOS / BLE stack
        if (triggers_current_state() == STATE_IDLE) return false;
    }
    return true;
}

void camera_shutter(uint32_t duration_ms, uint32_t focus_ms) {
    // Pre-focus
    digitalWrite(_pin_focus, HIGH);
    if (!interruptible_delay(focus_ms)) {
        digitalWrite(_pin_focus, LOW);
        return;
    }

    // Open shutter
    digitalWrite(_pin_shutter, HIGH);
    if (!interruptible_delay(duration_ms)) {
        // Aborted mid-exposure — release immediately
        digitalWrite(_pin_shutter, LOW);
        digitalWrite(_pin_focus, LOW);
        return;
    }

    // Release both
    digitalWrite(_pin_shutter, LOW);
    digitalWrite(_pin_focus, LOW);
}
