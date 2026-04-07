/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "ota.h"
#include "config.h"
#include "protocol.h"

#include <esp_ota_ops.h>
#include <esp_partition.h>
#include <esp_chip_info.h>
#include <Arduino.h>

// Forward declaration — implemented in ble_server.cpp
extern void ble_ota_notify(const uint8_t* data, size_t len);

static esp_ota_handle_t _ota_handle = 0;
static const esp_partition_t* _ota_partition = nullptr;
static uint32_t _ota_total_size = 0;
static uint32_t _ota_written = 0;
static bool _ota_active = false;

void ota_init() {
    _ota_active = false;
    _ota_handle = 0;
    _ota_partition = nullptr;
    _ota_total_size = 0;
    _ota_written = 0;
}

bool ota_in_progress() {
    return _ota_active;
}

static void send_ota_status(OtaStatus status) {
    uint8_t buf[1] = { status };
    ble_ota_notify(buf, 1);
}

/// Return the chip model byte matching DeviceInfoFrame encoding:
/// 1=ESP32, 2=ESP32-S2, 3=ESP32-S3, 4=ESP32-C3
static uint8_t get_chip_model_id() {
    esp_chip_info_t chip;
    esp_chip_info(&chip);
    switch (chip.model) {
        case CHIP_ESP32:   return 1;
        case CHIP_ESP32S2: return 2;
        case CHIP_ESP32S3: return 3;
        case CHIP_ESP32C3: return 4;
        default:           return 0;
    }
}

/// Send OTA_READY with chip model byte so the client can verify the binary matches.
static void send_ota_ready() {
    uint8_t buf[2] = { OTA_READY, get_chip_model_id() };
    ble_ota_notify(buf, sizeof(buf));
}

void ota_handle_control(const uint8_t* data, size_t len) {
    if (len < 1) return;

    OtaCmd cmd = static_cast<OtaCmd>(data[0]);

    switch (cmd) {
        case OTA_BEGIN: {
            if (len < 5) {
                send_ota_status(OTA_ERR_SIZE);
                return;
            }

            _ota_total_size = data[1] | (data[2] << 8) | (data[3] << 16) | (data[4] << 24);
            _ota_written = 0;

            _ota_partition = esp_ota_get_next_update_partition(NULL);
            if (_ota_partition == nullptr) {
                Serial.println("[OTA] No OTA partition available");
                send_ota_status(OTA_ERR_BEGIN);
                return;
            }

            esp_err_t err = esp_ota_begin(_ota_partition, _ota_total_size, &_ota_handle);
            if (err != ESP_OK) {
                Serial.printf("[OTA] esp_ota_begin failed: %s\n", esp_err_to_name(err));
                send_ota_status(OTA_ERR_BEGIN);
                return;
            }

            _ota_active = true;
            Serial.printf("[OTA] BEGIN — %u bytes → partition '%s'\n",
                          _ota_total_size, _ota_partition->label);
            send_ota_ready();
            break;
        }

        case OTA_END: {
            if (!_ota_active) return;

            esp_err_t err = esp_ota_end(_ota_handle);
            if (err != ESP_OK) {
                Serial.printf("[OTA] esp_ota_end failed: %s\n", esp_err_to_name(err));
                _ota_active = false;
                send_ota_status(OTA_ERR_VALIDATE);
                return;
            }

            err = esp_ota_set_boot_partition(_ota_partition);
            if (err != ESP_OK) {
                Serial.printf("[OTA] set_boot_partition failed: %s\n", esp_err_to_name(err));
                _ota_active = false;
                send_ota_status(OTA_ERR_VALIDATE);
                return;
            }

            Serial.printf("[OTA] COMPLETE — %u bytes written, rebooting...\n", _ota_written);
            send_ota_status(OTA_COMPLETE);
            delay(500);
            esp_restart();
            break;
        }

        case OTA_ABORT: {
            if (_ota_active) {
                esp_ota_abort(_ota_handle);
                _ota_active = false;
                _ota_written = 0;
                Serial.println("[OTA] ABORTED");
            }
            send_ota_status(OTA_OK);
            break;
        }

        default:
            Serial.printf("[OTA] Unknown control cmd %02X\n", data[0]);
            break;
    }
}

void ota_handle_data(const uint8_t* data, size_t len) {
    if (!_ota_active || len == 0) return;

    esp_err_t err = esp_ota_write(_ota_handle, data, len);
    if (err != ESP_OK) {
        Serial.printf("[OTA] Write error at offset %u: %s\n", _ota_written, esp_err_to_name(err));
        esp_ota_abort(_ota_handle);
        _ota_active = false;
        send_ota_status(OTA_ERR_WRITE);
        return;
    }

    _ota_written += len;
}
