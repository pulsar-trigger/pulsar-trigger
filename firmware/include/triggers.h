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

/// Get number of shots taken in current sequence.
uint16_t triggers_shots_taken();

/// Get configured interval parameters (valid after SET_MODE).
const IntervalParams& triggers_interval_params();

/// Get time remaining for the current sequence (ms).
uint32_t triggers_time_remaining_ms();

/// Milliseconds until the next scheduled shot (0 when due or overdue), or
/// WAKE_NO_EVENT when nothing is scheduled. loop() blocks this long - minus
/// WAKE_EARLY_MS - instead of polling, so the chip can idle between shots.
uint32_t triggers_ms_until_next_event();

/// Get current filtered pitch in degrees (tracker alignment mode).
float triggers_tracker_pitch();
