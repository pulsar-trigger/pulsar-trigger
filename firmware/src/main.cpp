/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include <Arduino.h>
#include "config.h"
#include "camera.h"
#include "ble_server.h"
#include "triggers.h"
#include "status.h"

#ifdef HAS_M5DISPLAY
#include <M5Unified.h>
#include "display.h"
#endif

void setup() {
#ifdef HAS_M5DISPLAY
    auto cfg = M5.config();
    M5.begin(cfg);
#ifdef BOARD_M5STICKS3
    // Enable 5V output on Grove / Hat2 bus (needed for optocoupler power)
    M5.Power.setExtOutput(true);
#endif
    Serial.println("\n=== Pulsar Intervalometer (M5) ===");
#else
    Serial.begin(115200);
    Serial.println("\n=== Pulsar Intervalometer ===");
    pinMode(PIN_LED, OUTPUT);
    digitalWrite(PIN_LED, LOW);
#endif

    camera_init();
    triggers_init();
    ble_init();

#ifdef HAS_M5DISPLAY
    display_init();
#endif
}

void loop() {
#ifdef HAS_M5DISPLAY
    M5.update();
#endif

    // LED status indicator (generic ESP32 only — M5 boards use display)
#ifndef HAS_M5DISPLAY
    static uint32_t led_timer = 0;
    bool running = (triggers_current_state() == STATE_RUNNING ||
                    triggers_current_state() == STATE_WAITING);

    if (ble_connected()) {
        if (millis() - led_timer > 1000) {
            digitalWrite(PIN_LED, !digitalRead(PIN_LED));
            led_timer = millis();
        }
    } else if (running) {
        digitalWrite(PIN_LED, HIGH);
    } else {
        if (millis() - led_timer > 200) {
            digitalWrite(PIN_LED, !digitalRead(PIN_LED));
            led_timer = millis();
        }
    }
#endif

    // Handle deferred BLE reinit (e.g. after name change)
    ble_handle_reinit();

    // Run active trigger mode
    triggers_tick();

#ifdef HAS_M5DISPLAY
    // Update LCD display
    display_update();
#endif

    // Small yield to avoid watchdog
    delay(1);
}
