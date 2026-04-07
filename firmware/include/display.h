/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once

/// Initialize the LCD display (sprite, brightness, initial screen).
void display_init();

/// Redraw the display (throttled internally to ~5 Hz). Call from loop().
void display_update();
