/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "ble_server.h"
#include "config.h"
#include "protocol.h"
#include "triggers.h"
#include "status.h"
#include "camera.h"
#include "ota.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <esp_gap_ble_api.h>
#include <esp_bt_main.h>
#include <esp_chip_info.h>
#include <esp_system.h>
#include <esp_spi_flash.h>

static Preferences _prefs;
static char _deviceName[7 + BLE_NAME_SUFFIX_MAX + 1]; // "Pulsar-" + suffix + NUL
static BLECharacteristic* _cmdChar     = nullptr;
static BLECharacteristic* _statusChar  = nullptr;
static BLECharacteristic* _pitchChar   = nullptr;
static BLECharacteristic* _otaCtrlChar = nullptr;
static BLECharacteristic* _otaDataChar = nullptr;
static bool _connected = false;
static volatile bool _pendingReinit = false;
static bool _advConfigured = false;

// ── Connection callbacks ─────────────────────────────────────────────────────
class PulsarServerCB : public BLEServerCallbacks {
    void onConnect(BLEServer* s) override {
        _connected = true;
        Serial.printf("[BLE] Client connected (state=%d, mode=%d)\n",
                      triggers_current_state(), triggers_current_mode());
    }
    void onDisconnect(BLEServer* s) override {
        _connected = false;
        Serial.println("[BLE] Client disconnected — job continues");
        // Skip re-advertising if a reinit is pending (deinit is tearing down the stack)
        if (!_pendingReinit) {
            BLEDevice::startAdvertising();
        }
    }
};
// ── NVS name helpers ─────────────────────────────────────────────────────────
static bool is_safe_output_pin(uint8_t pin) {
    for (size_t i = 0; i < SAFE_OUTPUT_PIN_COUNT; i++) {
        if (SAFE_OUTPUT_PINS[i] == pin) return true;
    }
    return false;
}

static void load_device_name() {
    _prefs.begin("pulsar", true);  // read-only
    String suffix = _prefs.getString("name", "");
    _prefs.end();

    if (suffix.length() > 0) {
        snprintf(_deviceName, sizeof(_deviceName), "%s%s", BLE_NAME_PREFIX, suffix.c_str());
    } else {
        strncpy(_deviceName, BLE_DEVICE_NAME, sizeof(_deviceName));
    }
}

static void save_device_name(const char* suffix) {
    _prefs.begin("pulsar", false);  // read-write
    _prefs.putString("name", suffix);
    _prefs.end();
}

// ── Command characteristic callback ─────────────────────────────────────────
class CmdCharCB : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        const uint8_t* data = c->getData();
        size_t len = c->getLength();
        if (len < 1) return;

        uint8_t cmd = data[0];

        switch (cmd) {
            case CMD_SET_MODE: {
                if (len < 2) return;
                Mode mode = static_cast<Mode>(data[1]);
                triggers_set_mode(mode, data + 2, len - 2);
                Serial.printf("[BLE] SET_MODE %02X\n", mode);
                break;
            }
            case CMD_START:
                triggers_start();
                Serial.println("[BLE] START");
                break;
            case CMD_STOP:
                triggers_stop();
                Serial.println("[BLE] STOP");
                break;
            case CMD_SHUTTER:
                triggers_single_shot();
                Serial.println("[BLE] SHUTTER");
                break;
            case CMD_STATUS_REQ:
                status_send(triggers_current_state(), triggers_current_mode(), 0, 0);
                break;
            case CMD_SET_FOCUS: {
                if (len >= 3) {
                    uint16_t ms = data[1] | (data[2] << 8);
                    triggers_set_focus(ms);
                    Serial.printf("[BLE] SET_FOCUS %u ms\n", ms);
                }
                break;
            }
            case CMD_SET_PINS: {
                if (len < 3) return;
                uint8_t shutter = data[1];
                uint8_t focus   = data[2];
                if (!is_safe_output_pin(shutter) || !is_safe_output_pin(focus)) {
                    Serial.printf("[BLE] SET_PINS rejected: shutter=%u focus=%u\n", shutter, focus);
                    return;
                }
                if (shutter == focus) {
                    Serial.println("[BLE] SET_PINS rejected: shutter == focus");
                    return;
                }
                _prefs.begin("pulsar", false);
                _prefs.putUChar("pin_shutter", shutter);
                _prefs.putUChar("pin_focus", focus);
                _prefs.end();
                camera_init_pins(shutter, focus);
                Serial.printf("[BLE] SET_PINS shutter=%u focus=%u\n", shutter, focus);
                break;
            }
            case CMD_SET_NAME: {
                if (len < 2) return;
                // Bytes 1..N are the UTF-8 suffix (no "Pulsar-" prefix)
                size_t suffixLen = len - 1;
                if (suffixLen > BLE_NAME_SUFFIX_MAX) suffixLen = BLE_NAME_SUFFIX_MAX;
                char suffix[BLE_NAME_SUFFIX_MAX + 1] = {};
                memcpy(suffix, data + 1, suffixLen);
                suffix[suffixLen] = '\0';

                // Sanitise: keep only printable ASCII (0x20-0x7E)
                size_t clean = 0;
                for (size_t i = 0; i < suffixLen; i++) {
                    if (suffix[i] >= 0x20 && suffix[i] <= 0x7E) {
                        suffix[clean++] = suffix[i];
                    }
                }
                suffix[clean] = '\0';
                suffixLen = clean;

                if (suffixLen == 0) {
                    // Empty suffix → reset to default "Pulsar"
                    save_device_name("");
                    strncpy(_deviceName, BLE_DEVICE_NAME, sizeof(_deviceName));
                } else {
                    save_device_name(suffix);
                    snprintf(_deviceName, sizeof(_deviceName), "%s%s", BLE_NAME_PREFIX, suffix);
                }

                // Defer BLE reinit to main loop (unsafe from callback context)
                _pendingReinit = true;
                Serial.printf("[BLE] SET_NAME → %s (reinit pending)\n", _deviceName);
                break;
            }
            case CMD_DEVICE_INFO: {
                DeviceInfoFrame info = {};
                info.marker = 0xFF;

                esp_chip_info_t chip;
                esp_chip_info(&chip);
                switch (chip.model) {
                    case CHIP_ESP32:   info.chip_model = 1; break;
                    case CHIP_ESP32S2: info.chip_model = 2; break;
                    case CHIP_ESP32S3: info.chip_model = 3; break;
                    case CHIP_ESP32C3: info.chip_model = 4; break;
                    default:           info.chip_model = 0; break;
                }
                info.chip_revision = chip.revision;
                info.cpu_freq_mhz = (uint8_t)(getCpuFrequencyMhz());
                info.flash_size_kb = (uint32_t)(spi_flash_get_chip_size() / 1024);
                info.free_heap_kb = (uint32_t)(esp_get_free_heap_size() / 1024);
                #if CONFIG_SPIRAM
                info.psram_kb = (uint16_t)(esp_spiram_get_size() / 1024);
                #else
                info.psram_kb = 0;
                #endif
                info.gpio_count = (uint8_t)GPIO_NUM_MAX;
                info.safe_output_count = SAFE_OUTPUT_PIN_COUNT;
                info.uptime_minutes = (uint16_t)(millis() / 60000UL);

                ble_notify(reinterpret_cast<const uint8_t*>(&info), sizeof(info));
                Serial.println("[BLE] DEVICE_INFO sent");
                break;
            }
            default:
                Serial.printf("[BLE] Unknown CMD %02X\n", cmd);
                break;
        }
    }
};

// ── OTA characteristic callbacks ─────────────────────────────────────────────
class OtaCtrlCB : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        ota_handle_control(c->getData(), c->getLength());
    }
};

class OtaDataCB : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        ota_handle_data(c->getData(), c->getLength());
    }
};

// ── Public: OTA notify (called from ota.cpp) ─────────────────────────────────
void ble_ota_notify(const uint8_t* data, size_t len) {
    if (_otaCtrlChar && _connected) {
        _otaCtrlChar->setValue(const_cast<uint8_t*>(data), len);
        _otaCtrlChar->notify();
    }
}

// ── Public API ───────────────────────────────────────────────────────────────
void ble_init() {
    load_device_name();
    ota_init();

    // Load saved GPIO pins and apply them
    _prefs.begin("pulsar", true);
    uint8_t shutter = _prefs.getUChar("pin_shutter", DEFAULT_PIN_SHUTTER);
    uint8_t focus   = _prefs.getUChar("pin_focus",   DEFAULT_PIN_FOCUS);
    _prefs.end();
    if (is_safe_output_pin(shutter) && is_safe_output_pin(focus) && shutter != focus) {
        camera_init_pins(shutter, focus);
        Serial.printf("[BLE] Loaded pins: shutter=%u focus=%u\n", shutter, focus);
    }

    Serial.printf("[BLE] Initializing as '%s' ...\n", _deviceName);
    BLEDevice::init(_deviceName);
    Serial.println("[BLE] Stack initialized");

    // Enable BLE security — bonding with encryption (Just Works, no MITM)
    BLEDevice::setEncryptionLevel(ESP_BLE_SEC_ENCRYPT);
    BLESecurity* security = new BLESecurity();
    security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
    security->setCapability(ESP_IO_CAP_NONE);  // Just Works pairing
    security->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new PulsarServerCB());

    BLEService* svc = server->createService(SERVICE_UUID);

    // Command characteristic (write, requires encryption)
    _cmdChar = svc->createCharacteristic(
        CHAR_COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    _cmdChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
    _cmdChar->setCallbacks(new CmdCharCB());

    // Status characteristic (notify)
    _statusChar = svc->createCharacteristic(
        CHAR_STATUS_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    _statusChar->addDescriptor(new BLE2902());

    // Pitch characteristic (notify — tracker alignment mode)
    _pitchChar = svc->createCharacteristic(
        CHAR_PITCH_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    _pitchChar->addDescriptor(new BLE2902());

    svc->start();

    // ── OTA service (separate service UUID) ──────────────────────────────
    BLEService* otaSvc = server->createService(OTA_SERVICE_UUID);

    // OTA control characteristic (write + notify for status feedback)
    _otaCtrlChar = otaSvc->createCharacteristic(
        OTA_CONTROL_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY
    );
    _otaCtrlChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
    _otaCtrlChar->setCallbacks(new OtaCtrlCB());
    _otaCtrlChar->addDescriptor(new BLE2902());

    // OTA data characteristic (write-no-response for fast bulk transfer)
    _otaDataChar = otaSvc->createCharacteristic(
        OTA_DATA_UUID,
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    _otaDataChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
    _otaDataChar->setCallbacks(new OtaDataCB());

    otaSvc->start();

    // ── Advertising ──────────────────────────────────────────────────────
    BLEAdvertising* adv = BLEDevice::getAdvertising();
    if (!_advConfigured) {
        adv->addServiceUUID(SERVICE_UUID);
        _advConfigured = true;
    }
    adv->setScanResponse(true);
    adv->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.printf("[BLE] Advertising as %s\n", _deviceName);
}

void ble_notify(const uint8_t* data, size_t len) {
    if (_statusChar && _connected) {
        _statusChar->setValue(const_cast<uint8_t*>(data), len);
        _statusChar->notify();
    }
}

void ble_notify_pitch(float pitch) {
    if (_pitchChar && _connected) {
        _pitchChar->setValue(reinterpret_cast<uint8_t*>(&pitch), sizeof(float));
        _pitchChar->notify();
    }
}

bool ble_connected() {
    return _connected;
}

void ble_handle_reinit() {
    if (!_pendingReinit) return;
    _pendingReinit = false;

    Serial.printf("[BLE] Reinit for rename → %s\n", _deviceName);

    // Full deinit/reinit — the only reliable way to update the advertised
    // name across all ESP32 variants.  deinit(false) releases BLE resources
    // but keeps the Bluetooth controller active so reinit is fast.
    BLEDevice::deinit(false);
    _connected = false;
    _advConfigured = false;
    _cmdChar = nullptr;
    _statusChar = nullptr;
    _pitchChar = nullptr;
    _otaCtrlChar = nullptr;
    _otaDataChar = nullptr;
    delay(100);
    ble_init();

    Serial.printf("[BLE] Now advertising as %s\n", _deviceName);
}
