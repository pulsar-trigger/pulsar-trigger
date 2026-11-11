/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>
#include <cstddef>

void ota_init();
void ota_handle_control(const uint8_t* data, size_t len);
void ota_handle_data(const uint8_t* data, size_t len);
bool ota_in_progress();

/// The BLE client went away: abandon an unfinished upload, so the device does
/// not stay awake (no auto-off, fast link) until it is power-cycled.
void ota_on_disconnect();
void ota_poll();
