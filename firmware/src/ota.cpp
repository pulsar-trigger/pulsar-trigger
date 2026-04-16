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
#include <esp_log.h>
#include <Arduino.h>

static const char* TAG = "OTA";

// Forward declaration — implemented in ble_server.cpp
extern void ble_ota_notify(const uint8_t* data, size_t len);

static esp_ota_handle_t _ota_handle = 0;
static const esp_partition_t* _ota_partition = nullptr;
static uint32_t _ota_total_size = 0;
static uint32_t _ota_written = 0;
static bool _ota_active = false;
static volatile bool _ota_finalize_pending = false;

void ota_init() {
    _ota_active = false;
    _ota_handle = 0;
    _ota_partition = nullptr;
    _ota_total_size = 0;
    _ota_written = 0;
    _ota_finalize_pending = false;
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

            // Diagnostic: show current boot partition and OTA state
            const esp_partition_t* running = esp_ota_get_running_partition();
            if (running) {
                ESP_LOGI(TAG, "Running from partition '%s' @ 0x%06X (%u bytes)",
                         running->label, running->address, running->size);
            }
            esp_ota_img_states_t ota_state;
            if (running && esp_ota_get_state_partition(running, &ota_state) == ESP_OK) {
                ESP_LOGI(TAG, "Current partition state: %d", (int)ota_state);
            }

            _ota_partition = esp_ota_get_next_update_partition(NULL);
            if (_ota_partition == nullptr) {
                ESP_LOGE(TAG, "No OTA partition available");
                send_ota_status(OTA_ERR_BEGIN);
                return;
            }

            ESP_LOGI(TAG, "Target partition '%s' @ 0x%06X (%u bytes)",
                     _ota_partition->label, _ota_partition->address, _ota_partition->size);

            if (_ota_total_size > _ota_partition->size) {
                ESP_LOGE(TAG, "Firmware too large: %u > %u", _ota_total_size, _ota_partition->size);
                send_ota_status(OTA_ERR_SIZE);
                return;
            }

            esp_err_t err = esp_ota_begin(_ota_partition, _ota_total_size, &_ota_handle);
            if (err != ESP_OK) {
                ESP_LOGE(TAG, "esp_ota_begin failed: %s", esp_err_to_name(err));
                send_ota_status(OTA_ERR_BEGIN);
                return;
            }

            _ota_active = true;
            ESP_LOGI(TAG, "BEGIN — %u bytes → partition '%s'",
                     _ota_total_size, _ota_partition->label);
            send_ota_ready();
            break;
        }

        case OTA_END: {
            if (!_ota_active) return;

            ESP_LOGI(TAG, "END — received %u of %u bytes", _ota_written, _ota_total_size);

            if (_ota_written != _ota_total_size) {
                ESP_LOGW(TAG, "Size mismatch! Expected %u, got %u",
                         _ota_total_size, _ota_written);
            }

            // Defer finalization to main loop — esp_ota_end() validates the
            // entire partition image (hash check) which requires more stack
            // than the BLE callback task (BTC_TASK) provides on ESP32-S3.
            _ota_finalize_pending = true;
            ESP_LOGI(TAG, "Finalization deferred to main loop");
            break;
        }

        case OTA_ABORT: {
            if (_ota_active) {
                esp_ota_abort(_ota_handle);
                _ota_active = false;
                _ota_written = 0;
                ESP_LOGI(TAG, "ABORTED");
            }
            send_ota_status(OTA_OK);
            break;
        }

        default:
            ESP_LOGW(TAG, "Unknown control cmd %02X", data[0]);
            break;
    }
}

void ota_handle_data(const uint8_t* data, size_t len) {
    if (!_ota_active || len == 0) return;

    esp_err_t err = esp_ota_write(_ota_handle, data, len);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Write error at offset %u: %s", _ota_written, esp_err_to_name(err));
        esp_ota_abort(_ota_handle);
        _ota_active = false;
        send_ota_status(OTA_ERR_WRITE);
        return;
    }

    _ota_written += len;

    // Log progress every ~10%
    if (_ota_total_size > 0) {
        uint32_t pct = (_ota_written * 100) / _ota_total_size;
        uint32_t prev_pct = ((_ota_written - len) * 100) / _ota_total_size;
        if (pct / 10 != prev_pct / 10) {
            ESP_LOGI(TAG, "Progress: %u/%u bytes (%u%%)", _ota_written, _ota_total_size, pct);
        }
    }
}

void ota_poll() {
    if (!_ota_finalize_pending) return;
    _ota_finalize_pending = false;

    ESP_LOGI(TAG, "Finalizing OTA on main task...");

    esp_err_t err = esp_ota_end(_ota_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_end failed: %s", esp_err_to_name(err));
        _ota_active = false;
        send_ota_status(OTA_ERR_VALIDATE);
        return;
    }

    err = esp_ota_set_boot_partition(_ota_partition);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "set_boot_partition failed: %s", esp_err_to_name(err));
        _ota_active = false;
        send_ota_status(OTA_ERR_VALIDATE);
        return;
    }

    ESP_LOGI(TAG, "COMPLETE — %u bytes written to '%s', rebooting...",
             _ota_written, _ota_partition->label);
    send_ota_status(OTA_COMPLETE);
    delay(500);
    esp_restart();
}
