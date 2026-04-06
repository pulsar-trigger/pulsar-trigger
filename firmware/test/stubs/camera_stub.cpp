/*
 * Camera stub implementation for native tests.
 */

#include "camera.h"

bool stub_shutter_on = false;
bool stub_focus_on = false;
uint32_t stub_last_shutter_duration_ms = 0;
uint32_t stub_last_focus_ms = 0;
int stub_shutter_fire_count = 0;

void camera_init() {}
void camera_init_pins(uint8_t, uint8_t) {}

void camera_focus(bool on) {
    stub_focus_on = on;
}

void camera_shutter_set(bool on) {
    stub_shutter_on = on;
}

void camera_shutter(uint32_t duration_ms, uint32_t focus_ms) {
    stub_last_shutter_duration_ms = duration_ms;
    stub_last_focus_ms = focus_ms;
    stub_shutter_fire_count++;
}

void stub_camera_reset() {
    stub_shutter_on = false;
    stub_focus_on = false;
    stub_last_shutter_duration_ms = 0;
    stub_last_focus_ms = 0;
    stub_shutter_fire_count = 0;
}
