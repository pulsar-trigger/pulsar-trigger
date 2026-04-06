/*
 * Status stub implementation for native tests.
 */

#include "status.h"

State stub_last_state = STATE_IDLE;
Mode stub_last_mode = MODE_NONE;
uint16_t stub_last_shots = 0;
uint32_t stub_last_time_ms = 0;
uint8_t stub_last_error = 0;
int stub_status_send_count = 0;

void status_send(State state, Mode mode, uint16_t shots, uint32_t time_ms, uint8_t error) {
    stub_last_state = state;
    stub_last_mode = mode;
    stub_last_shots = shots;
    stub_last_time_ms = time_ms;
    stub_last_error = error;
    stub_status_send_count++;
}

void stub_status_reset() {
    stub_last_state = STATE_IDLE;
    stub_last_mode = MODE_NONE;
    stub_last_shots = 0;
    stub_last_time_ms = 0;
    stub_last_error = 0;
    stub_status_send_count = 0;
}
