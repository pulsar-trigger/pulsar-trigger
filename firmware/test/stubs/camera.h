/*
 * Camera stub for native unit tests.
 * Records calls so tests can verify behavior.
 */

#pragma once
#include <cstdint>

// Stub state — test code can inspect these
extern bool stub_shutter_on;
extern bool stub_focus_on;
extern uint32_t stub_last_shutter_duration_ms;
extern uint32_t stub_last_focus_ms;
extern int stub_shutter_fire_count;

void camera_init();
void camera_init_pins(uint8_t shutter_pin, uint8_t focus_pin);
void camera_focus(bool on);
void camera_shutter_set(bool on);
void camera_shutter(uint32_t duration_ms, uint32_t focus_ms);

// Reset all stub state
void stub_camera_reset();
