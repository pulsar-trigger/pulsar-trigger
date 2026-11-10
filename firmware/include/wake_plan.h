/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>

// How long the firmware may block before its next scheduled event.
//
// Blocking (instead of waking every 1 ms to look at the clock) lets the chip
// idle between events, and with tickless idle (light sleep) actually sleep.
// A wake from sleep can be late by a tick or so, so the block ends
// WAKE_EARLY_MS before the event and the last stretch is a short precise
// wait (wake_spin_until_ms). An event never fires later than it did with
// the 1 ms poll.

// "No event scheduled" - only a command (which kicks the wait) or the cap
// can end the block.
constexpr uint32_t WAKE_NO_EVENT = UINT32_MAX;

// Woken this long before an event; the rest is spun.
constexpr uint32_t WAKE_EARLY_MS = 2;

// During OTA the loop keeps its old 20 ms pace: ota_poll() finalises there.
constexpr uint32_t WAKE_OTA_CAP_MS = 20;

// Milliseconds to block now, for an event due in `until_ms` (WAKE_NO_EVENT if
// none). 0 means "do not block": the event is due within WAKE_EARLY_MS, so
// spin to it, or it is already due.
constexpr uint32_t wake_sleep_ms(uint32_t until_ms, uint32_t cap_ms) {
    return until_ms <= WAKE_EARLY_MS ? 0
         : (until_ms - WAKE_EARLY_MS < cap_ms ? until_ms - WAKE_EARLY_MS : cap_ms);
}

// Milliseconds until `target_ms`, wraparound-safe; 0 when it has passed.
constexpr uint32_t wake_ms_until(uint32_t now_ms, uint32_t target_ms) {
    return (target_ms - now_ms) < 0x80000000UL ? target_ms - now_ms : 0;
}
