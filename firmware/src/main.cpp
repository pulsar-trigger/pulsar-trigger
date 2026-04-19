/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include <Arduino.h>
#include <WiFi.h>
#include <esp_ota_ops.h>
#include <esp_partition.h>
#include <esp_pm.h>
#include "config.h"
#include "camera.h"
#include "ble_server.h"
#include "triggers.h"
#include "status.h"
#include "ota.h"
#include <BLEDevice.h>

#ifdef HAS_M5DISPLAY
#include <M5Unified.h>
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
    Serial.println("\n=== Pulsar Intervalometer (M5) ===");
#else
    Serial.begin(115200);
    Serial.println("\n=== Pulsar Intervalometer ===");
    pinMode(PIN_LED, OUTPUT);
    digitalWrite(PIN_LED, LOW);
#endif

    // ── Power management ───────────────────────────────────────────────
    // Explicitly shut down WiFi radio — we only use BLE
    WiFi.mode(WIFI_OFF);

    // Reduce CPU clock
#if CONFIG_PM_ENABLE
#ifdef ESP32S3
    esp_pm_config_esp32s3_t pm_config = {
        .max_freq_mhz = 80,
        .min_freq_mhz = 10,
        .light_sleep_enable = true,
    };
#else
    esp_pm_config_esp32_t pm_config = {
        .max_freq_mhz = 80,
        .min_freq_mhz = 10,
        .light_sleep_enable = true,
    };
#endif
    esp_err_t pm_err = esp_pm_configure(&pm_config);
    if (pm_err == ESP_OK) {
        log_i("[PM] CPU max 80 MHz, light sleep enabled");
    } else {
        log_w("[PM] esp_pm_configure failed: %s (running at %d MHz)",
              esp_err_to_name(pm_err), getCpuFrequencyMhz());
    }
#else
    // CONFIG_PM_ENABLE not set — just lower CPU frequency directly
    setCpuFrequencyMhz(80);
    log_i("[PM] CPU set to 80 MHz");
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
    // Keep LCD off at all times — saves power and avoids unwanted wake-ups
    M5.Display.setBrightness(0);
    M5.Display.sleep();
#endif
}

void loop() {
#ifdef HAS_M5DISPLAY
    M5.update();
#endif

    // LED status indicator (generic ESP32 only)
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

    // Finalize OTA if pending (deferred from BLE callback to avoid stack overflow)
    ota_poll();

    // Run active trigger mode
    triggers_tick();

    // ── Auto-shutdown: power off after idle timeout ─────────────────────
    {
        static uint32_t last_active_ms = 0;
        bool busy = (triggers_current_state() == STATE_RUNNING ||
                     triggers_current_state() == STATE_WAITING ||
                     ble_connected() || ota_in_progress());
        if (busy) {
            last_active_ms = millis();
        } else {
            uint16_t timeout = ble_auto_off_minutes();
            if (timeout > 0) {
                uint32_t idle_ms = millis() - last_active_ms;
                if (idle_ms > (uint32_t)timeout * 60000UL) {
                    log_i("[PM] Auto-shutdown after %u min idle", timeout);
#ifdef HAS_M5DISPLAY
                    camera_release_pins();
                    BLEDevice::deinit(true);
                    delay(100);
                    M5.Power.powerOff();
#else
                    camera_release_pins();
                    BLEDevice::deinit(true);
                    esp_deep_sleep_start();
#endif
                }
            }
        }
    }

    // Yield to FreeRTOS — longer sleep when idle to save power
    bool active = (triggers_current_state() == STATE_RUNNING ||
                   triggers_current_state() == STATE_WAITING);
    delay(active ? 1 : 20);
}
