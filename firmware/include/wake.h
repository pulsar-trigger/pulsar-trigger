/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>
#include "wake_plan.h"

/// Call once from setup(): the task that calls it (Arduino's loop task) is
/// the one wake_kick() wakes.
void wake_init();

/// Block the calling task for up to `ms`. Returns early, with true, when
/// wake_kick() is called meanwhile (the loop task only).
bool wake_wait_ms(uint32_t ms);

/// End the loop task's current wait now. Safe from any task, including the
/// BLE callbacks, so STOP and other commands are handled at once.
void wake_kick();

/// Busy-wait until millis() reaches `target_ms` (wraparound-safe). Used only
/// for the last WAKE_EARLY_MS before an event, where a wake from sleep might
/// be late.
void wake_spin_until_ms(uint32_t target_ms);
