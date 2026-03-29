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

void setup() {
    Serial.begin(115200);
    Serial.println("\n=== Pulsar Intervalometer ===");

    camera_init();
    triggers_init();
    ble_init();

    pinMode(PIN_LED, OUTPUT);
    digitalWrite(PIN_LED, LOW);
}

void loop() {
    // LED status indicator:
    //   Connected:                  slow blink (1 s)
    //   Disconnected, job running:  solid ON
    //   Disconnected, idle:         fast blink (200 ms)
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

    // Handle deferred BLE reinit (e.g. after name change)
    ble_handle_reinit();

    // Run active trigger mode
    triggers_tick();

    // Small yield to avoid watchdog
    delay(1);
}
