/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

#pragma once
#include <cstddef>
#include <cstdint>

// Every firmware image carries the board it was built for, as the text
// "PULSAR-BOARD-ID=<n>;" in its read-only data (main.cpp, PULSAR_BOARD_TAG).
// An OTA streams the new image through BoardTagScanner and refuses to boot
// it unless the tag names this board: three boards share the ESP32 family,
// and the chip check alone let a generic DevKit image onto an M5Core2.
//
// Images built before 0.33.0 carry no tag, so an OTA back to them is refused
// (they can still be flashed over USB).

#define PULSAR_BOARD_TAG_PREFIX "PULSAR-BOARD-ID="

struct BoardTagScanner {
    static constexpr const char* kPrefix = PULSAR_BOARD_TAG_PREFIX;
    static constexpr size_t kPrefixLen = sizeof(PULSAR_BOARD_TAG_PREFIX) - 1;

    int found = -1;       // board id of the first complete tag, -1 = none yet
    size_t matched = 0;   // prefix characters matched so far
    bool inDigits = false;
    int value = 0;
    int digits = 0;

    void reset() { *this = BoardTagScanner{}; }

    // Feed the next chunk of the image. Tags split across chunks are found:
    // the state carries over between calls.
    void feed(const uint8_t* data, size_t len) {
        for (size_t i = 0; i < len && found < 0; i++) step(static_cast<char>(data[i]));
    }

private:
    void step(char c) {
        if (inDigits) {
            if (c >= '0' && c <= '9' && digits < 3) {
                value = value * 10 + (c - '0');
                digits++;
                return;
            }
            if (c == ';' && digits > 0) {
                found = value;
                return;
            }
            inDigits = false;  // not a well-formed tag: keep looking
            matched = 0;
        }
        if (c == kPrefix[matched]) {
            if (++matched == kPrefixLen) {
                inDigits = true;
                value = 0;
                digits = 0;
                matched = 0;
            }
        } else {
            // 'P' occurs only at the start of the prefix, so a mismatch can
            // only restart the match at this character.
            matched = (c == kPrefix[0]) ? 1 : 0;
        }
    }
};
