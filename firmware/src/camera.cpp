/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "camera.h"
#include "triggers.h"

static uint32_t _focus_ms = DEFAULT_FOCUS_MS;

void camera_init() {
    pinMode(PIN_SHUTTER, OUTPUT);
    pinMode(PIN_FOCUS, OUTPUT);
    digitalWrite(PIN_SHUTTER, LOW);
    digitalWrite(PIN_FOCUS, LOW);
}

void camera_focus(bool on) {
    digitalWrite(PIN_FOCUS, on ? HIGH : LOW);
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
    uint32_t f = focus_ms ? focus_ms : _focus_ms;

    // Pre-focus
    digitalWrite(PIN_FOCUS, HIGH);
    if (!interruptible_delay(f)) {
        digitalWrite(PIN_FOCUS, LOW);
        return;
    }

    // Open shutter
    digitalWrite(PIN_SHUTTER, HIGH);
    if (!interruptible_delay(duration_ms)) {
        // Aborted mid-exposure — release immediately
        digitalWrite(PIN_SHUTTER, LOW);
        digitalWrite(PIN_FOCUS, LOW);
        return;
    }

    // Release both
    digitalWrite(PIN_SHUTTER, LOW);
    digitalWrite(PIN_FOCUS, LOW);
}
