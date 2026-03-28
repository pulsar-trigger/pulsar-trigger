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
    // Blink LED while connected
    static uint32_t led_timer = 0;
    if (ble_connected()) {
        if (millis() - led_timer > 1000) {
            digitalWrite(PIN_LED, !digitalRead(PIN_LED));
            led_timer = millis();
        }
    } else {
        // Fast blink while advertising
        if (millis() - led_timer > 200) {
            digitalWrite(PIN_LED, !digitalRead(PIN_LED));
            led_timer = millis();
        }
    }

    // Run active trigger mode
    triggers_tick();

    // Small yield to avoid watchdog
    delay(1);
}
