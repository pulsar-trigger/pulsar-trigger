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

/// Send pitch (degrees) on the Pitch characteristic (notify) — tracker mode.
void ble_notify_pitch(float pitch);

/// Returns true when a BLE central is connected.
bool ble_connected();

/// Call from loop() — performs deferred BLE reinit after a name change.
void ble_handle_reinit();
