/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#include "ble_server.h"
#include "config.h"
#include "protocol.h"
#include "triggers.h"
#include "status.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

static BLECharacteristic* _cmdChar   = nullptr;
static BLECharacteristic* _statusChar = nullptr;
static bool _connected = false;

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
        // Do NOT stop triggers: the running job survives disconnection
        BLEDevice::startAdvertising();
    }
};

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
                    // u16 LE at bytes 1-2
                    // (focus_ms stored globally — for simplicity, just log)
                    uint16_t ms = data[1] | (data[2] << 8);
                    Serial.printf("[BLE] SET_FOCUS %u ms\n", ms);
                }
                break;
            }
            default:
                Serial.printf("[BLE] Unknown CMD %02X\n", cmd);
                break;
        }
    }
};

// ── Public API ───────────────────────────────────────────────────────────────
void ble_init() {
    BLEDevice::init(BLE_DEVICE_NAME);

    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new PulsarServerCB());

    BLEService* svc = server->createService(SERVICE_UUID);

    // Command characteristic (write)
    _cmdChar = svc->createCharacteristic(
        CHAR_COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    _cmdChar->setCallbacks(new CmdCharCB());

    // Status characteristic (notify)
    _statusChar = svc->createCharacteristic(
        CHAR_STATUS_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    _statusChar->addDescriptor(new BLE2902());

    svc->start();

    BLEAdvertising* adv = BLEDevice::getAdvertising();
    adv->addServiceUUID(SERVICE_UUID);
    adv->setScanResponse(true);
    adv->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.println("[BLE] Advertising as " BLE_DEVICE_NAME);
}

void ble_notify(const uint8_t* data, size_t len) {
    if (_statusChar && _connected) {
        _statusChar->setValue(const_cast<uint8_t*>(data), len);
        _statusChar->notify();
    }
}

bool ble_connected() {
    return _connected;
}
