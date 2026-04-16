/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include <Arduino.h>
#include <esp_ota_ops.h>
#include <esp_partition.h>
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
    // Mark this firmware as valid so the bootloader won't roll back after OTA
    esp_ota_mark_app_valid_cancel_rollback();

#ifdef HAS_M5DISPLAY
    auto cfg = M5.config();
    // Disable peripherals we don't use to save power
    // Note: IMU left enabled — disabling it on M5StickC Plus2 can
    // disrupt the shared I2C bus and prevent PMIC init.
    cfg.internal_spk = false;
    cfg.internal_mic = false;
    cfg.led_brightness = 0;
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

    // Boot partition diagnostics — helps debug OTA issues
    const esp_partition_t* running = esp_ota_get_running_partition();
    if (running) {
        log_i("[BOOT] Running from '%s' @ 0x%06X", running->label, running->address);
    }
    const esp_partition_t* next = esp_ota_get_next_update_partition(NULL);
    if (next) {
        log_i("[BOOT] Next OTA target: '%s' @ 0x%06X", next->label, next->address);
    }
    esp_ota_img_states_t img_state;
    if (running && esp_ota_get_state_partition(running, &img_state) == ESP_OK) {
        const char* state_str = "UNKNOWN";
        switch (img_state) {
            case ESP_OTA_IMG_NEW:              state_str = "NEW"; break;
            case ESP_OTA_IMG_PENDING_VERIFY:   state_str = "PENDING_VERIFY"; break;
            case ESP_OTA_IMG_VALID:            state_str = "VALID"; break;
            case ESP_OTA_IMG_INVALID:          state_str = "INVALID"; break;
            case ESP_OTA_IMG_ABORTED:          state_str = "ABORTED"; break;
            case ESP_OTA_IMG_UNDEFINED:        state_str = "UNDEFINED"; break;
            default: break;
        }
        log_i("[BOOT] Partition state: %s (%d)", state_str, (int)img_state);
    }

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
