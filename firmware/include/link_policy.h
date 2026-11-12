/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstdint>

// Which BLE connection parameters the firmware asks the central for.
//
// A connected link costs radio time at every connection event, so the
// interval decides most of the idle-connected and running current. Fast is
// for when a person is waiting on the other end (configuring, OTA); Slow is
// for everything else. Notifications still go out under Slow - they wait for
// the next connection event, at most one interval (slave latency is skipped
// only when the peripheral has nothing to send).
enum class LinkMode : uint8_t { None, Fast, Slow };

// Units as the BLE spec and esp_ble_gap_update_conn_params take them:
// interval in 1.25 ms, timeout in 10 ms.
struct LinkParams {
    uint16_t min_interval;
    uint16_t max_interval;
    uint16_t latency;
    uint16_t timeout;
};

// 15-30 ms, no latency, 4 s supervision timeout.
constexpr LinkParams LINK_FAST = {12, 24, 0, 400};
// 400-800 ms, slave latency 2, 6 s supervision timeout. The spec requires
// timeout > (1 + latency) * max_interval * 2 = 3 * 800 ms * 2 = 4.8 s.
constexpr LinkParams LINK_SLOW = {320, 640, 2, 600};

// After the last command (or the end of a run), stay Fast this long, so a
// person working through the app is not waiting on an 800 ms link.
constexpr uint32_t LINK_INTERACTIVE_MS = 10000;

// The supervision-timeout rule above, so a change to the constants that
// breaks it fails the build rather than dropping links in the field.
constexpr bool link_params_valid(const LinkParams& p) {
    return p.min_interval >= 6 && p.min_interval <= p.max_interval &&
           p.max_interval <= 3200 && p.latency <= 499 &&
           p.timeout >= 10 && p.timeout <= 3200 &&
           (uint32_t)p.timeout * 10 >
               (1u + p.latency) * ((uint32_t)p.max_interval * 125 / 100) * 2;
}
static_assert(link_params_valid(LINK_FAST), "LINK_FAST breaks the BLE timeout rule");
static_assert(link_params_valid(LINK_SLOW), "LINK_SLOW breaks the BLE timeout rule");

// The mode the link should be in now. `since_interaction_ms` is the time
// since the last command write or the end of a run.
constexpr LinkMode link_wanted(bool connected, bool ota, bool run_active,
                               uint32_t since_interaction_ms) {
    return !connected ? LinkMode::None
         : ota        ? LinkMode::Fast
         : run_active ? LinkMode::Slow
         : since_interaction_ms < LINK_INTERACTIVE_MS ? LinkMode::Fast
                                                      : LinkMode::Slow;
}

// Whether a link running at `interval` (1.25 ms units, 0 = not known yet)
// already serves mode `m`. Fast is served by anything up to Fast's maximum,
// Slow by anything from Slow's minimum: the central picks inside the range.
constexpr bool link_serves(LinkMode m, uint16_t interval) {
    return m == LinkMode::None ||
           (interval != 0 &&
            (m == LinkMode::Fast ? interval <= LINK_FAST.max_interval
                                 : interval >= LINK_SLOW.min_interval));
}

// How long to wait for the central to answer a request before asking again.
constexpr uint32_t LINK_ANSWER_MS = 10000;
// Requests per mode change. A central that answers with something else (a
// phone that will not go below 50 ms, say) is left alone after this many.
constexpr uint8_t LINK_MAX_TRIES = 3;

// Decides when to ask the central for new parameters, from what the link
// actually runs at rather than from what was last asked for.
//
// Asking and getting are not the same thing: a request can be refused, and
// one still in flight can land after a newer one. Both happened on the
// bench - a Slow request sent before an OTA landed after the OTA's Fast one,
// and the upload crawled at the 795 ms interval while the firmware believed
// the link was fast. So: one request in flight at a time, and the interval
// the controller reports is the truth to compare against.
struct LinkTracker {
    LinkMode want = LinkMode::None;  // the mode decided last
    uint16_t interval = 0;           // in force now, from the controller (0 = unknown)
    uint8_t tries = 0;               // requests sent for `want`
    bool waiting = false;            // a request is out, no answer yet
    uint32_t asked_ms = 0;

    // A new connection, with the interval the central opened it at.
    void connected(uint16_t interval_now) {
        *this = LinkTracker{};
        interval = interval_now;
    }

    // The controller's report of a parameter update. A failed one changes
    // nothing on the link, but it does answer the request.
    void updated(uint16_t interval_now, bool ok) {
        waiting = false;
        if (ok) interval = interval_now;
    }

    // Whether to send a request for `w` now. Call it every loop.
    bool should_ask(LinkMode w, uint32_t now_ms) {
        if (w != want) {
            want = w;
            tries = 0;
        }
        if (link_serves(w, interval)) return false;
        if (waiting && now_ms - asked_ms < LINK_ANSWER_MS) return false;
        if (tries >= LINK_MAX_TRIES) return false;
        tries++;
        waiting = true;
        asked_ms = now_ms;
        return true;
    }
};
