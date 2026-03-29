/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>
#include <cstddef>

/// Set up BLE service, characteristics, and start advertising.
void ble_init();

/// Send raw bytes on the Status characteristic (notify).
void ble_notify(const uint8_t* data, size_t len);

/// Returns true when a BLE central is connected.
bool ble_connected();
