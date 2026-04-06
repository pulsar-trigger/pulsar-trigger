/*
 * Unit tests for triggers.cpp — state machine transitions, mode configuration,
 * parameter clamping, and tick behavior.
 */

#include <unity.h>
#include <cstring>

// Include stubs and source directly so everything links in a single compilation unit
#include "../../test/stubs/arduino_stub.cpp"
#include "../../test/stubs/camera_stub.cpp"
#include "../../test/stubs/status_stub.cpp"
#include "../../src/triggers.cpp"

// Stub accessors (already linked via includes above)

static void reset_all() {
    triggers_init();
    stub_camera_reset();
    stub_status_reset();
    stub_set_millis(0);
}

// ── Init ─────────────────────────────────────────────────────────────────────

void test_init_sets_idle(void) {
    reset_all();
    TEST_ASSERT_EQUAL(STATE_IDLE, triggers_current_state());
    TEST_ASSERT_EQUAL(MODE_NONE, triggers_current_mode());
}

// ── set_mode ─────────────────────────────────────────────────────────────────

void test_set_mode_intervalometer(void) {
    reset_all();
    // Build a valid IntervalParams payload (14 bytes)
    uint8_t payload[14];
    memset(payload, 0, sizeof(payload));
    // interval_ms = 5000 LE
    payload[0] = 0x88; payload[1] = 0x13; payload[2] = 0x00; payload[3] = 0x00;
    // exposure_ms = 2000 LE
    payload[4] = 0xD0; payload[5] = 0x07; payload[6] = 0x00; payload[7] = 0x00;
    // count = 10 LE
    payload[8] = 0x0A; payload[9] = 0x00;
    // delay_ms = 1000 LE
    payload[10] = 0xE8; payload[11] = 0x03; payload[12] = 0x00; payload[13] = 0x00;

    bool ok = triggers_set_mode(MODE_INTERVALOMETER, payload, sizeof(payload));
    TEST_ASSERT_TRUE(ok);
    TEST_ASSERT_EQUAL(MODE_INTERVALOMETER, triggers_current_mode());
}

void test_set_mode_intervalometer_too_short(void) {
    reset_all();
    uint8_t payload[5] = {};
    bool ok = triggers_set_mode(MODE_INTERVALOMETER, payload, sizeof(payload));
    TEST_ASSERT_FALSE(ok);
}

void test_set_mode_press_hold(void) {
    reset_all();
    bool ok = triggers_set_mode(MODE_PRESS_HOLD, nullptr, 0);
    TEST_ASSERT_TRUE(ok);
    TEST_ASSERT_EQUAL(MODE_PRESS_HOLD, triggers_current_mode());
}

void test_set_mode_press_lock(void) {
    reset_all();
    bool ok = triggers_set_mode(MODE_PRESS_LOCK, nullptr, 0);
    TEST_ASSERT_TRUE(ok);
    TEST_ASSERT_EQUAL(MODE_PRESS_LOCK, triggers_current_mode());
}

void test_set_mode_invalid(void) {
    reset_all();
    bool ok = triggers_set_mode(MODE_NONE, nullptr, 0);
    TEST_ASSERT_FALSE(ok);
}

// ── set_focus clamping ───────────────────────────────────────────────────────

void test_set_focus_normal(void) {
    reset_all();
    bool ok = triggers_set_focus(500);
    TEST_ASSERT_TRUE(ok);
}

void test_set_focus_below_min(void) {
    reset_all();
    // 10 < MIN_FOCUS_MS(50) — should clamp to 50
    bool ok = triggers_set_focus(10);
    TEST_ASSERT_TRUE(ok);
}

void test_set_focus_above_max(void) {
    reset_all();
    // 60000 > MAX_FOCUS_MS(5000) — should clamp to 5000
    bool ok = triggers_set_focus(60000);
    TEST_ASSERT_TRUE(ok);
}

// ── Intervalometer parameter clamping ────────────────────────────────────────

void test_intervalometer_clamps_interval(void) {
    reset_all();
    uint8_t payload[14];
    memset(payload, 0, sizeof(payload));
    // interval_ms = 100 (below MIN_INTERVAL_MS=500)
    payload[0] = 0x64; payload[1] = 0x00; payload[2] = 0x00; payload[3] = 0x00;
    // exposure_ms = 1000
    payload[4] = 0xE8; payload[5] = 0x03; payload[6] = 0x00; payload[7] = 0x00;
    payload[8] = 0x01; payload[9] = 0x00;  // count=1
    payload[10] = 0x00; payload[11] = 0x00; payload[12] = 0x00; payload[13] = 0x00;

    bool ok = triggers_set_mode(MODE_INTERVALOMETER, payload, sizeof(payload));
    TEST_ASSERT_TRUE(ok);
    // The mode should be accepted (clamped internally)
    TEST_ASSERT_EQUAL(MODE_INTERVALOMETER, triggers_current_mode());
}

// ── Start / Stop ─────────────────────────────────────────────────────────────

void test_start_intervalometer(void) {
    reset_all();
    uint8_t payload[14];
    memset(payload, 0, sizeof(payload));
    payload[0] = 0x88; payload[1] = 0x13; payload[2] = 0x00; payload[3] = 0x00; // 5000ms
    payload[4] = 0xD0; payload[5] = 0x07; payload[6] = 0x00; payload[7] = 0x00; // 2000ms
    payload[8] = 0x05; payload[9] = 0x00;  // 5 shots
    payload[10] = 0x00; payload[11] = 0x00; payload[12] = 0x00; payload[13] = 0x00; // 0 delay

    triggers_set_mode(MODE_INTERVALOMETER, payload, sizeof(payload));
    triggers_start();
    TEST_ASSERT_EQUAL(STATE_WAITING, triggers_current_state());
}

void test_start_press_hold(void) {
    reset_all();
    triggers_set_mode(MODE_PRESS_HOLD, nullptr, 0);
    triggers_start();
    TEST_ASSERT_EQUAL(STATE_RUNNING, triggers_current_state());
    TEST_ASSERT_TRUE(stub_shutter_on);
    TEST_ASSERT_TRUE(stub_focus_on);
}

void test_stop_resets_to_idle(void) {
    reset_all();
    triggers_set_mode(MODE_PRESS_HOLD, nullptr, 0);
    triggers_start();
    triggers_stop();
    TEST_ASSERT_EQUAL(STATE_IDLE, triggers_current_state());
    TEST_ASSERT_FALSE(stub_shutter_on);
    TEST_ASSERT_FALSE(stub_focus_on);
}

void test_stop_sends_status(void) {
    reset_all();
    triggers_set_mode(MODE_PRESS_HOLD, nullptr, 0);
    triggers_start();
    int count_before = stub_status_send_count;
    triggers_stop();
    TEST_ASSERT_GREATER_THAN(count_before, stub_status_send_count);
    TEST_ASSERT_EQUAL(STATE_IDLE, stub_last_state);
}

// ── Tick: intervalometer fires and completes ─────────────────────────────────

void test_intervalometer_tick_fires_shot(void) {
    reset_all();
    uint8_t payload[14];
    memset(payload, 0, sizeof(payload));
    // interval = 1000ms, exposure = 100ms, count = 2, delay = 0
    payload[0] = 0xE8; payload[1] = 0x03; payload[2] = 0x00; payload[3] = 0x00;
    payload[4] = 0x64; payload[5] = 0x00; payload[6] = 0x00; payload[7] = 0x00;
    payload[8] = 0x02; payload[9] = 0x00;
    payload[10] = 0x00; payload[11] = 0x00; payload[12] = 0x00; payload[13] = 0x00;

    triggers_set_mode(MODE_INTERVALOMETER, payload, sizeof(payload));
    stub_set_millis(0);
    triggers_start();
    TEST_ASSERT_EQUAL(STATE_WAITING, triggers_current_state());

    // Advance time to trigger first shot
    stub_set_millis(1);
    triggers_tick();
    TEST_ASSERT_EQUAL(1, stub_shutter_fire_count);

    // Advance time for second shot (after interval)
    stub_set_millis(1 + 1000 + 1);
    triggers_tick();
    TEST_ASSERT_EQUAL(2, stub_shutter_fire_count);

    // After count reached, should be idle
    TEST_ASSERT_EQUAL(STATE_IDLE, triggers_current_state());
}

void test_tick_does_nothing_when_idle(void) {
    reset_all();
    stub_set_millis(10000);
    triggers_tick();
    TEST_ASSERT_EQUAL(0, stub_shutter_fire_count);
    TEST_ASSERT_EQUAL(STATE_IDLE, triggers_current_state());
}

// ── Runner ───────────────────────────────────────────────────────────────────

int main(int argc, char** argv) {
    UNITY_BEGIN();

    RUN_TEST(test_init_sets_idle);
    RUN_TEST(test_set_mode_intervalometer);
    RUN_TEST(test_set_mode_intervalometer_too_short);
    RUN_TEST(test_set_mode_press_hold);
    RUN_TEST(test_set_mode_press_lock);
    RUN_TEST(test_set_mode_invalid);
    RUN_TEST(test_set_focus_normal);
    RUN_TEST(test_set_focus_below_min);
    RUN_TEST(test_set_focus_above_max);
    RUN_TEST(test_intervalometer_clamps_interval);
    RUN_TEST(test_start_intervalometer);
    RUN_TEST(test_start_press_hold);
    RUN_TEST(test_stop_resets_to_idle);
    RUN_TEST(test_stop_sends_status);
    RUN_TEST(test_intervalometer_tick_fires_shot);
    RUN_TEST(test_tick_does_nothing_when_idle);

    return UNITY_END();
}
