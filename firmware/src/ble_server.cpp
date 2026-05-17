/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "ble_server.h"
#include "config.h"
#include "protocol.h"
#include "protocol_v2.h"
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
#include <esp_log.h>

static const char* TAG = "BLE";

static Preferences _prefs;
static char _deviceName[7 + BLE_NAME_SUFFIX_MAX + 1]; // "Pulsar-" + suffix + NUL
static BLECharacteristic* _cmdChar     = nullptr;
static BLECharacteristic* _statusChar  = nullptr;
static BLECharacteristic* _pitchChar   = nullptr;
static BLECharacteristic* _otaCtrlChar = nullptr;
static BLECharacteristic* _otaDataChar = nullptr;
static volatile bool _connected = false;
static volatile bool _pendingReinit = false;
static bool _advConfigured = false;
static volatile uint16_t _auto_off_minutes = 5;  // default: 5 min (0 = disabled)

// ── Connection callbacks ─────────────────────────────────────────────────────
class PulsarServerCB : public BLEServerCallbacks {
    void onConnect(BLEServer* s) override {
        _connected = true;
        ESP_LOGI(TAG, "Client connected (state=%d, mode=%d)",
                 triggers_current_state(), triggers_current_mode());
    }
    void onDisconnect(BLEServer* s) override {
        _connected = false;
        ESP_LOGI(TAG, "Client disconnected — job continues");
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
    ESP_LOGI(TAG, "Loaded device name: '%s'", _deviceName);
}

static bool save_device_name(const char* suffix) {
    _prefs.begin("pulsar", false);  // read-write
    size_t written = _prefs.putString("name", suffix);
    _prefs.end();

    // Verify: read back and compare
    _prefs.begin("pulsar", true);
    String verify = _prefs.getString("name", "");
    _prefs.end();
    bool ok = (verify == suffix);
    if (!ok) {
        ESP_LOGE(TAG, "NVS verify failed: wrote '%s', read back '%s'", suffix, verify.c_str());
    } else {
        ESP_LOGI(TAG, "NVS saved name suffix: '%s' (%u bytes)", suffix, written);
    }
    return ok;
}

// Forward decl — v2 device-info sender, defined below.
static void send_device_info_v2();

// ── TLV → IntervalParams adapter ───────────────────────────────────────────
// Drains a TLV iterator into the legacy IntervalParams struct so we can keep
// calling triggers_set_mode() without changing its signature.
static void tlv_to_interval(v2::FrameReader& rdr, IntervalParams& out) {
    out = {};
    uint8_t tag, tlen;
    const uint8_t* v;
    while (rdr.next(tag, tlen, v)) {
        switch (tag) {
            case v2::TAG_INTERVAL_MS:
                if (tlen == 4) out.interval_ms = v2::FrameReader::readU32(v);
                break;
            case v2::TAG_EXPOSURE_MS:
                if (tlen == 4) out.exposure_ms = v2::FrameReader::readU32(v);
                break;
            case v2::TAG_SHOT_COUNT:
                if (tlen == 2) out.count = v2::FrameReader::readU16(v);
                break;
            case v2::TAG_DELAY_MS:
                if (tlen == 4) out.delay_ms = v2::FrameReader::readU32(v);
                break;
            default:
                // Unknown TLV — ignored per v2 protocol rules.
                break;
        }
    }
}

// ── ACK helper ─────────────────────────────────────────────────────────────
// Optional notify acknowledging a command. ERROR_CODE 0 = ok; non-zero is
// caller-defined (e.g. opcode unsupported, validation rejection).
static void send_ack(uint8_t opcode, uint8_t err) {
    uint8_t buf[12];
    v2::FrameWriter w(buf, sizeof(buf));
    if (!w.begin(v2::NOTIFY_ACK)) return;
    w.putU8(v2::TAG_OPCODE, opcode);
    w.putU8(v2::TAG_ERROR_CODE, err);
    size_t n = w.finish();
    if (n > 0) ble_notify(buf, n);
}

// ── Command characteristic callback (v2) ───────────────────────────────────
class CmdCharCB : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        const uint8_t* data = c->getData();
        size_t len = c->getLength();
        if (len < 1) return;

        // Reject v1 frames (CMD bytes 0x01–0x0F) — v0.180+ firmware is v2-only.
        // Clients still on v1 won't be parsed; the app surfaces a clear
        // "firmware too old / app too old" mismatch on connect.
        if (data[0] < 0x10) {
            ESP_LOGW(TAG, "Legacy v1 frame ignored (first byte=%02X)", data[0]);
            return;
        }

        v2::FrameReader rdr(data, len);
        uint8_t opcode, payload_len;
        if (!rdr.parseEnvelope(opcode, payload_len)) {
            ESP_LOGW(TAG, "Bad v2 envelope or version mismatch (byte0=%02X)", data[0]);
            return;
        }

        switch (opcode) {
            case v2::OP_SET_INTERVALOMETER:
            case v2::OP_SET_ASTRO:
            case v2::OP_SET_DARK_FRAME:
            case v2::OP_SET_RAMP: {
                IntervalParams p;
                tlv_to_interval(rdr, p);
                // Opcode → legacy Mode for triggers_set_mode().
                Mode mode = MODE_INTERVALOMETER;
                switch (opcode) {
                    case v2::OP_SET_ASTRO:      mode = MODE_ASTRO;      break;
                    case v2::OP_SET_DARK_FRAME: mode = MODE_DARK_FRAME; break;
                    case v2::OP_SET_RAMP:       mode = MODE_RAMP;       break;
                    default: break;
                }
                triggers_set_mode(mode, reinterpret_cast<const uint8_t*>(&p), sizeof(p));
                ESP_LOGI(TAG, "SET mode=%02X intv=%u exp=%u n=%u delay=%u",
                         mode, (unsigned)p.interval_ms, (unsigned)p.exposure_ms,
                         (unsigned)p.count, (unsigned)p.delay_ms);
                send_ack(opcode, 0);
                break;
            }
            case v2::OP_SET_PRESS_HOLD:
                triggers_set_mode(MODE_PRESS_HOLD, nullptr, 0);
                send_ack(opcode, 0);
                ESP_LOGI(TAG, "SET PRESS_HOLD");
                break;
            case v2::OP_SET_PRESS_LOCK:
                triggers_set_mode(MODE_PRESS_LOCK, nullptr, 0);
                send_ack(opcode, 0);
                ESP_LOGI(TAG, "SET PRESS_LOCK");
                break;
            case v2::OP_SET_TRACKER:
                triggers_set_mode(MODE_TRACKER, nullptr, 0);
                send_ack(opcode, 0);
                ESP_LOGI(TAG, "SET TRACKER");
                break;

            case v2::OP_START:
                triggers_start();
                ESP_LOGI(TAG, "START");
                send_ack(opcode, 0);
                break;
            case v2::OP_STOP:
                triggers_stop();
                ESP_LOGI(TAG, "STOP");
                send_ack(opcode, 0);
                break;
            case v2::OP_SHUTTER:
                triggers_single_shot();
                ESP_LOGI(TAG, "SHUTTER");
                send_ack(opcode, 0);
                break;
            case v2::OP_STATUS_REQ:
                status_send(triggers_current_state(), triggers_current_mode(), 0, 0);
                break;
            case v2::OP_DEVICE_INFO_REQ:
                send_device_info_v2();
                ESP_LOGI(TAG, "DEVICE_INFO sent");
                break;

            case v2::OP_SET_FOCUS: {
                uint8_t tag, tlen; const uint8_t* v;
                while (rdr.next(tag, tlen, v)) {
                    if (tag == v2::TAG_FOCUS_MS && tlen == 2) {
                        uint16_t ms = v2::FrameReader::readU16(v);
                        triggers_set_focus(ms);
                        ESP_LOGI(TAG, "SET_FOCUS %u ms", ms);
                    }
                }
                send_ack(opcode, 0);
                break;
            }
            case v2::OP_SET_PINS: {
                uint8_t shutter = 0, focus = 0;
                bool haveShutter = false, haveFocus = false;
                uint8_t tag, tlen; const uint8_t* v;
                while (rdr.next(tag, tlen, v)) {
                    if (tag == v2::TAG_SHUTTER_PIN && tlen == 1) { shutter = v[0]; haveShutter = true; }
                    if (tag == v2::TAG_FOCUS_PIN   && tlen == 1) { focus   = v[0]; haveFocus   = true; }
                }
                if (!haveShutter || !haveFocus) {
                    ESP_LOGW(TAG, "SET_PINS missing TLVs");
                    send_ack(opcode, 1);
                    return;
                }
                if (!is_safe_output_pin(shutter) || !is_safe_output_pin(focus) || shutter == focus) {
                    ESP_LOGW(TAG, "SET_PINS rejected: shutter=%u focus=%u", shutter, focus);
                    send_ack(opcode, 2);
                    return;
                }
                _prefs.begin("pulsar", false);
                _prefs.putUChar("pin_shutter", shutter);
                _prefs.putUChar("pin_focus", focus);
                _prefs.end();
                camera_init_pins(shutter, focus);
                ESP_LOGI(TAG, "SET_PINS shutter=%u focus=%u", shutter, focus);
                send_ack(opcode, 0);
                break;
            }
            case v2::OP_SET_AUTO_OFF: {
                uint8_t tag, tlen; const uint8_t* v;
                while (rdr.next(tag, tlen, v)) {
                    if (tag == v2::TAG_AUTO_OFF_MIN && tlen == 2) {
                        uint16_t minutes = v2::FrameReader::readU16(v);
                        _auto_off_minutes = minutes;
                        _prefs.begin("pulsar", false);
                        _prefs.putUShort("auto_off", minutes);
                        _prefs.end();
                        ESP_LOGI(TAG, "SET_AUTO_OFF %u min", minutes);
                    }
                }
                send_ack(opcode, 0);
                break;
            }
            case v2::OP_SET_NAME: {
                uint8_t tag, tlen; const uint8_t* v;
                const uint8_t* nameBytes = nullptr;
                uint8_t nameLen = 0;
                while (rdr.next(tag, tlen, v)) {
                    if (tag == v2::TAG_NAME_UTF8) { nameBytes = v; nameLen = tlen; }
                }
                if (!nameBytes) { send_ack(opcode, 1); return; }
                size_t suffixLen = nameLen;
                if (suffixLen > BLE_NAME_SUFFIX_MAX) suffixLen = BLE_NAME_SUFFIX_MAX;
                char suffix[BLE_NAME_SUFFIX_MAX + 1] = {};
                memcpy(suffix, nameBytes, suffixLen);
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
                    save_device_name("");
                    strncpy(_deviceName, BLE_DEVICE_NAME, sizeof(_deviceName));
                } else {
                    save_device_name(suffix);
                    snprintf(_deviceName, sizeof(_deviceName), "%s%s", BLE_NAME_PREFIX, suffix);
                }

                // Defer BLE reinit to main loop (unsafe from callback context)
                _pendingReinit = true;
                ESP_LOGI(TAG, "SET_NAME → '%s' (reinit pending)", _deviceName);
                send_ack(opcode, 0);
                break;
            }

            default:
                ESP_LOGW(TAG, "Unknown v2 opcode %02X", opcode);
                send_ack(opcode, 0xFF);  // UNSUPPORTED_OPCODE
                break;
        }
    }
};

// ── v2 device-info notify ──────────────────────────────────────────────────
static void send_device_info_v2() {
    uint8_t buf[64];
    v2::FrameWriter w(buf, sizeof(buf));
    if (!w.begin(v2::NOTIFY_DEVICE_INFO)) return;

    esp_chip_info_t chip;
    esp_chip_info(&chip);
    uint8_t chip_model = 0;
    switch (chip.model) {
        case CHIP_ESP32:   chip_model = 1; break;
        case CHIP_ESP32S2: chip_model = 2; break;
        case CHIP_ESP32S3: chip_model = 3; break;
        case CHIP_ESP32C3: chip_model = 4; break;
        default:           chip_model = 0; break;
    }
    uint32_t psram_kb = 0;
    #if CONFIG_SPIRAM
    psram_kb = (uint32_t)(esp_spiram_get_size() / 1024);
    #endif

    w.putU8 (v2::TAG_CHIP_MODEL,     chip_model);
    w.putU8 (v2::TAG_CHIP_REVISION,  (uint8_t)chip.revision);
    w.putU8 (v2::TAG_CPU_FREQ_MHZ,   (uint8_t)getCpuFrequencyMhz());
    w.putU32(v2::TAG_FLASH_SIZE_KB,  (uint32_t)(spi_flash_get_chip_size() / 1024));
    w.putU32(v2::TAG_FREE_HEAP_KB,   (uint32_t)(esp_get_free_heap_size() / 1024));
    w.putU16(v2::TAG_PSRAM_KB,       (uint16_t)psram_kb);
    w.putU8 (v2::TAG_GPIO_COUNT,     (uint8_t)GPIO_NUM_MAX);
    w.putU8 (v2::TAG_SAFE_OUT_COUNT, (uint8_t)SAFE_OUTPUT_PIN_COUNT);
    w.putU16(v2::TAG_UPTIME_MIN,     (uint16_t)(millis() / 60000UL));

    size_t n = w.finish();
    if (n > 0) ble_notify(buf, n);
}

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

    // Load saved settings from NVS
    _prefs.begin("pulsar", true);
    uint8_t shutter = _prefs.getUChar("pin_shutter", DEFAULT_PIN_SHUTTER);
    uint8_t focus   = _prefs.getUChar("pin_focus",   DEFAULT_PIN_FOCUS);
    _auto_off_minutes = _prefs.getUShort("auto_off", 5);
    _prefs.end();
    ESP_LOGI(TAG, "Auto-off: %u min", _auto_off_minutes);
    if (is_safe_output_pin(shutter) && is_safe_output_pin(focus) && shutter != focus) {
        camera_init_pins(shutter, focus);
        ESP_LOGI(TAG, "Loaded pins: shutter=%u focus=%u", shutter, focus);
    }

    ESP_LOGI(TAG, "Initializing as '%s' ...", _deviceName);
    BLEDevice::init(_deviceName);
    ESP_LOGI(TAG, "Stack initialized");

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
    // Advertising interval: 152.5ms–318.75ms (power-friendly while still
    // discoverable within a few seconds).  Units are 0.625ms.
    adv->setMinInterval(0x00F4);  // 152.5 ms
    adv->setMaxInterval(0x01FE);  // 318.75 ms
    adv->setMinPreferred(0x06);

    // Manufacturer-specific data in the scan response: lets the Android app
    // distinguish board variants (M5Core2 vs M5Stick S3 vs generic ESP32)
    // before connecting, so we can show the right device image.
    //   bytes 0-1: company ID 0xFFFF (unregistered / hobbyist)
    //   byte 2:    PULSAR_BOARD_ID  — 1=generic ESP32, 2=M5StickC S3, 3=M5Core2
    //   byte 3:    ESP chip model   — 1=ESP32, 2=S2, 3=S3, 4=C3
    //   byte 4-5:  firmware major / minor (for completeness)
    {
        esp_chip_info_t chip;
        esp_chip_info(&chip);
        uint8_t chip_model = 0;
        switch (chip.model) {
            case CHIP_ESP32:   chip_model = 1; break;
            case CHIP_ESP32S2: chip_model = 2; break;
            case CHIP_ESP32S3: chip_model = 3; break;
            case CHIP_ESP32C3: chip_model = 4; break;
            default:           chip_model = 0; break;
        }
        uint8_t mfg[6] = {
            0xFF, 0xFF,
            (uint8_t)PULSAR_BOARD_ID,
            chip_model,
            (uint8_t)FW_VERSION_MAJOR,
            (uint8_t)FW_VERSION_MINOR,
        };
        BLEAdvertisementData scanResp;
        scanResp.setManufacturerData(std::string((char*)mfg, sizeof(mfg)));
        adv->setScanResponseData(scanResp);
    }

    BLEDevice::startAdvertising();

    ESP_LOGI(TAG, "Advertising as '%s'", _deviceName);
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

    ESP_LOGI(TAG, "Reinit for rename → '%s'", _deviceName);

    // Full deinit/reinit — the only reliable way to update the advertised
    // name across all ESP32 variants.  deinit(true) fully resets the BT
    // controller which is needed on ESP32-S3 to clear the cached GAP name.
    BLEDevice::deinit(true);
    _connected = false;
    _advConfigured = false;
    _cmdChar = nullptr;
    _statusChar = nullptr;
    _pitchChar = nullptr;
    _otaCtrlChar = nullptr;
    _otaDataChar = nullptr;
    delay(300);
    ble_init();

    ESP_LOGI(TAG, "Now advertising as '%s'", _deviceName);
}

uint16_t ble_auto_off_minutes() {
    return _auto_off_minutes;
}
