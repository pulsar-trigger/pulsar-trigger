/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <Arduino.h>
#include "config.h"

/// Fire the shutter optocoupler for `duration_ms`, with optional pre-focus.
void camera_init();
void camera_init_pins(uint8_t shutter_pin, uint8_t focus_pin);
void camera_focus(bool on);
void camera_shutter_set(bool on);
void camera_shutter(uint32_t duration_ms, uint32_t focus_ms);
