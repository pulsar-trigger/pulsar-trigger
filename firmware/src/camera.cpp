#include "camera.h"

static uint32_t _focus_ms = DEFAULT_FOCUS_MS;

void camera_init() {
    pinMode(PIN_SHUTTER, OUTPUT);
    pinMode(PIN_FOCUS, OUTPUT);
    digitalWrite(PIN_SHUTTER, LOW);
    digitalWrite(PIN_FOCUS, LOW);
}

void camera_focus(bool on) {
    digitalWrite(PIN_FOCUS, on ? HIGH : LOW);
}

void camera_shutter(uint32_t duration_ms, uint32_t focus_ms) {
    uint32_t f = focus_ms ? focus_ms : _focus_ms;

    // Pre-focus
    digitalWrite(PIN_FOCUS, HIGH);
    delay(f);

    // Open shutter
    digitalWrite(PIN_SHUTTER, HIGH);
    delay(duration_ms);

    // Release both
    digitalWrite(PIN_SHUTTER, LOW);
    digitalWrite(PIN_FOCUS, LOW);
}
