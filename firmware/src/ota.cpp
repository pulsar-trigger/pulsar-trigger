/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "ota.h"
#include "config.h"
#include "protocol.h"
#include "board_tag.h"
#include "triggers.h"

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
static volatile uint32_t _ota_total_size = 0;
static volatile uint32_t _ota_written = 0;
static volatile bool _ota_active = false;
static volatile bool _ota_finalize_pending = false;
static volatile uint32_t _ota_last_rx_ms = 0;
static BoardTagScanner _board_tag;

// An upload with no data for this long is abandoned (ota_poll).
static constexpr uint32_t OTA_IDLE_TIMEOUT_MS = 30000;

// Keep the chip fully awake for the whole upload (power-saving build).
// 2026-11-11, M5StickS3: with light sleep allowed, an upload over a FAST link
// stopped the radio dead - the central saw a supervision timeout - three runs
// out of three, at BEGIN or ~27 s in, while flash was being erased/written.
// The one run that completed was on a build holding exactly these three for
// the upload: no light sleep, CPU at max, BLE modem sleep off. (Before the
// link fix the upload crawled on a 795 ms link, the radio was nearly idle, and
// it survived - slowly. Speed and stability are separate fixes; both are needed.)
#if CONFIG_PM_ENABLE
#include <esp_pm.h>
#include <esp_bt.h>
static esp_pm_lock_handle_t _ota_no_sleep = nullptr;
static esp_pm_lock_handle_t _ota_cpu_max = nullptr;
#endif

/// The only place _ota_active changes after init: entering and leaving an
/// upload takes and releases the locks, each exactly once.
static void set_active(bool on) {
    if (_ota_active == on) return;
    _ota_active = on;
#if CONFIG_PM_ENABLE
    if (!_ota_no_sleep) esp_pm_lock_create(ESP_PM_NO_LIGHT_SLEEP, 0, "ota", &_ota_no_sleep);
    if (!_ota_cpu_max) esp_pm_lock_create(ESP_PM_CPU_FREQ_MAX, 0, "ota-cpu", &_ota_cpu_max);
    if (on) {
        if (_ota_no_sleep) esp_pm_lock_acquire(_ota_no_sleep);
        if (_ota_cpu_max) esp_pm_lock_acquire(_ota_cpu_max);
#if CONFIG_BT_CTRL_MODEM_SLEEP
        esp_bt_sleep_disable();
#endif
    } else {
#if CONFIG_BT_CTRL_MODEM_SLEEP
        esp_bt_sleep_enable();
#endif
        if (_ota_cpu_max) esp_pm_lock_release(_ota_cpu_max);
        if (_ota_no_sleep) esp_pm_lock_release(_ota_no_sleep);
    }
#endif
}

/// Drop an unfinished upload. Safe from any task; a finalize already queued
/// for the main loop is left to finish.
static void ota_abandon(const char* why) {
    if (!_ota_active || _ota_finalize_pending) return;
    esp_ota_abort(_ota_handle);
    set_active(false);
    _ota_written = 0;
    ESP_LOGW(TAG, "Abandoned: %s", why);
}

void ota_on_disconnect() {
    ota_abandon("client disconnected");
}

void ota_init() {
    set_active(false);
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

            // Never during a run: erasing flash stalls the CPU and the update
            // would reboot the board mid-sequence.
            if (triggers_current_state() != STATE_IDLE) {
                ESP_LOGW(TAG, "BEGIN refused: a run is in progress");
                send_ota_status(OTA_ERR_BUSY);
                return;
            }
            // A second BEGIN restarts the upload rather than leaking the handle.
            if (_ota_active) ota_abandon("restarted by a new BEGIN");

            _ota_total_size = (uint32_t)data[1] | ((uint32_t)data[2] << 8) |
                              ((uint32_t)data[3] << 16) | ((uint32_t)data[4] << 24);
            _ota_written = 0;
            _board_tag.reset();

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

            set_active(true);
            _ota_last_rx_ms = millis();
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
                set_active(false);
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
        set_active(false);
        send_ota_status(OTA_ERR_WRITE);
        return;
    }

    _ota_written += len;
    _ota_last_rx_ms = millis();
    _board_tag.feed(data, len);

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
    if (_ota_active && !_ota_finalize_pending &&
        millis() - _ota_last_rx_ms > OTA_IDLE_TIMEOUT_MS) {
        ota_abandon("no data for 30 s");
        send_ota_status(OTA_ERR_WRITE);
        return;
    }
    if (!_ota_finalize_pending) return;
    _ota_finalize_pending = false;

    ESP_LOGI(TAG, "Finalizing OTA on main task...");

    // The image must say it was built for THIS board (board_tag.h). The chip
    // check alone let a generic ESP32 image onto an M5Core2.
    if (_board_tag.found != PULSAR_BOARD_ID) {
        ESP_LOGE(TAG, "Image is for board %d, this is board %d - refused",
                 _board_tag.found, PULSAR_BOARD_ID);
        esp_ota_abort(_ota_handle);
        set_active(false);
        send_ota_status(OTA_ERR_BOARD);
        return;
    }

    esp_err_t err = esp_ota_end(_ota_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_end failed: %s", esp_err_to_name(err));
        set_active(false);
        send_ota_status(OTA_ERR_VALIDATE);
        return;
    }

    err = esp_ota_set_boot_partition(_ota_partition);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "set_boot_partition failed: %s", esp_err_to_name(err));
        set_active(false);
        send_ota_status(OTA_ERR_VALIDATE);
        return;
    }

    ESP_LOGI(TAG, "COMPLETE — %u bytes written to '%s', rebooting...",
             _ota_written, _ota_partition->label);
    send_ota_status(OTA_COMPLETE);
    delay(500);
    esp_restart();
}
