/*
 * Arduino stub implementation for native tests.
 */

#include "Arduino.h"

uint32_t _stub_millis = 0;

void stub_set_millis(uint32_t ms) {
    _stub_millis = ms;
}

SerialStub Serial;
