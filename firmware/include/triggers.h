/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include "protocol.h"

/// Initialize all trigger mode state machines.
void triggers_init();

/// Call from loop() — runs the active mode's tick.
void triggers_tick();

/// Apply a SET_MODE command payload.
bool triggers_set_mode(Mode mode, const uint8_t* payload, size_t len);

/// Update pre-focus time in milliseconds.
bool triggers_set_focus(uint16_t ms);

/// Start the currently configured mode.
void triggers_start();

/// Stop / abort the active mode.
void triggers_stop();

/// Fire a single manual shutter.
void triggers_single_shot();

/// Get the currently active mode.
Mode triggers_current_mode();

/// Get the current state.
State triggers_current_state();
