/*
 * Unit tests for protocol.h — struct sizes, enum values, packed layout.
 */

#include <unity.h>
#include "protocol.h"
#include <cstring>

// ── Enum values ──────────────────────────────────────────────────────────────

void test_cmd_enum_values(void) {
    TEST_ASSERT_EQUAL_UINT8(0x01, CMD_SET_MODE);
    TEST_ASSERT_EQUAL_UINT8(0x02, CMD_START);
    TEST_ASSERT_EQUAL_UINT8(0x03, CMD_STOP);
    TEST_ASSERT_EQUAL_UINT8(0x04, CMD_SHUTTER);
    TEST_ASSERT_EQUAL_UINT8(0x05, CMD_STATUS_REQ);
    TEST_ASSERT_EQUAL_UINT8(0x06, CMD_SET_FOCUS);
    TEST_ASSERT_EQUAL_UINT8(0x08, CMD_SET_NAME);
    TEST_ASSERT_EQUAL_UINT8(0x09, CMD_SET_PINS);
    TEST_ASSERT_EQUAL_UINT8(0x0A, CMD_DEVICE_INFO);
}

void test_mode_enum_values(void) {
    TEST_ASSERT_EQUAL_UINT8(0x00, MODE_NONE);
    TEST_ASSERT_EQUAL_UINT8(0x01, MODE_INTERVALOMETER);
    TEST_ASSERT_EQUAL_UINT8(0x06, MODE_PRESS_HOLD);
    TEST_ASSERT_EQUAL_UINT8(0x07, MODE_PRESS_LOCK);
}

void test_state_enum_values(void) {
    TEST_ASSERT_EQUAL_UINT8(0x00, STATE_IDLE);
    TEST_ASSERT_EQUAL_UINT8(0x01, STATE_RUNNING);
    TEST_ASSERT_EQUAL_UINT8(0x02, STATE_WAITING);
    TEST_ASSERT_EQUAL_UINT8(0x03, STATE_ERROR);
}

void test_ota_cmd_values(void) {
    TEST_ASSERT_EQUAL_UINT8(0x01, OTA_BEGIN);
    TEST_ASSERT_EQUAL_UINT8(0x02, OTA_END);
    TEST_ASSERT_EQUAL_UINT8(0x03, OTA_ABORT);
}

void test_ota_status_values(void) {
    TEST_ASSERT_EQUAL_UINT8(0x00, OTA_OK);
    TEST_ASSERT_EQUAL_UINT8(0x01, OTA_ERR_BEGIN);
    TEST_ASSERT_EQUAL_UINT8(0x02, OTA_ERR_WRITE);
    TEST_ASSERT_EQUAL_UINT8(0x03, OTA_ERR_VALIDATE);
    TEST_ASSERT_EQUAL_UINT8(0x04, OTA_ERR_SIZE);
    TEST_ASSERT_EQUAL_UINT8(0x10, OTA_READY);
    TEST_ASSERT_EQUAL_UINT8(0x11, OTA_COMPLETE);
}

// ── Struct sizes ─────────────────────────────────────────────────────────────

void test_status_frame_is_20_bytes(void) {
    TEST_ASSERT_EQUAL(20, sizeof(StatusFrame));
}

void test_device_info_frame_is_18_bytes(void) {
    TEST_ASSERT_EQUAL(18, sizeof(DeviceInfoFrame));
}

void test_interval_params_is_14_bytes(void) {
    TEST_ASSERT_EQUAL(14, sizeof(IntervalParams));
}

// ── StatusFrame packed layout ────────────────────────────────────────────────

void test_status_frame_field_packing(void) {
    StatusFrame sf;
    memset(&sf, 0, sizeof(sf));

    sf.state = STATE_RUNNING;
    sf.mode = MODE_INTERVALOMETER;
    sf.shots_taken = 0x0005;      // 5
    sf.time_remaining_ms = 0x00001388; // 5000ms
    sf.battery_pct = 75;
    sf.error_code = 0;
    sf.fw_major = 0;
    sf.fw_minor = 7;
    sf.fw_patch = 1;

    uint8_t* raw = reinterpret_cast<uint8_t*>(&sf);
    TEST_ASSERT_EQUAL_UINT8(STATE_RUNNING, raw[0]);
    TEST_ASSERT_EQUAL_UINT8(MODE_INTERVALOMETER, raw[1]);
    // shots_taken LE: 0x05, 0x00
    TEST_ASSERT_EQUAL_UINT8(0x05, raw[2]);
    TEST_ASSERT_EQUAL_UINT8(0x00, raw[3]);
    // time_remaining_ms LE: 0x88, 0x13, 0x00, 0x00
    TEST_ASSERT_EQUAL_UINT8(0x88, raw[4]);
    TEST_ASSERT_EQUAL_UINT8(0x13, raw[5]);
    TEST_ASSERT_EQUAL_UINT8(0x00, raw[6]);
    TEST_ASSERT_EQUAL_UINT8(0x00, raw[7]);
    // battery_pct
    TEST_ASSERT_EQUAL_UINT8(75, raw[8]);
    // error_code
    TEST_ASSERT_EQUAL_UINT8(0, raw[9]);
    // fw version
    TEST_ASSERT_EQUAL_UINT8(0, raw[10]);
    TEST_ASSERT_EQUAL_UINT8(7, raw[11]);
    TEST_ASSERT_EQUAL_UINT8(1, raw[12]);
}

// ── DeviceInfoFrame packed layout ────────────────────────────────────────────

void test_device_info_frame_marker(void) {
    DeviceInfoFrame dif;
    memset(&dif, 0, sizeof(dif));
    dif.marker = 0xFF;
    dif.chip_model = 1;  // ESP32
    dif.cpu_freq_mhz = 240;
    dif.flash_size_kb = 4096;
    dif.gpio_count = 34;
    dif.safe_output_count = 13;

    uint8_t* raw = reinterpret_cast<uint8_t*>(&dif);
    TEST_ASSERT_EQUAL_UINT8(0xFF, raw[0]);  // marker
    TEST_ASSERT_EQUAL_UINT8(1, raw[1]);     // chip_model
    TEST_ASSERT_EQUAL_UINT8(240, raw[3]);   // cpu_freq_mhz
    TEST_ASSERT_EQUAL_UINT8(34, raw[14]);   // gpio_count
    TEST_ASSERT_EQUAL_UINT8(13, raw[15]);   // safe_output_count
}

// ── IntervalParams packed layout ─────────────────────────────────────────────

void test_interval_params_layout(void) {
    IntervalParams ip;
    memset(&ip, 0, sizeof(ip));
    ip.interval_ms = 5000;
    ip.exposure_ms = 2000;
    ip.count = 100;
    ip.delay_ms = 1000;

    uint8_t* raw = reinterpret_cast<uint8_t*>(&ip);
    // interval_ms LE at offset 0: 5000 = 0x1388
    TEST_ASSERT_EQUAL_UINT8(0x88, raw[0]);
    TEST_ASSERT_EQUAL_UINT8(0x13, raw[1]);
    // exposure_ms LE at offset 4: 2000 = 0x07D0
    TEST_ASSERT_EQUAL_UINT8(0xD0, raw[4]);
    TEST_ASSERT_EQUAL_UINT8(0x07, raw[5]);
    // count LE at offset 8: 100 = 0x0064
    TEST_ASSERT_EQUAL_UINT8(0x64, raw[8]);
    TEST_ASSERT_EQUAL_UINT8(0x00, raw[9]);
    // delay_ms LE at offset 10: 1000 = 0x03E8
    TEST_ASSERT_EQUAL_UINT8(0xE8, raw[10]);
    TEST_ASSERT_EQUAL_UINT8(0x03, raw[11]);
}

// ── Runner ───────────────────────────────────────────────────────────────────

int main(int argc, char** argv) {
    UNITY_BEGIN();

    RUN_TEST(test_cmd_enum_values);
    RUN_TEST(test_mode_enum_values);
    RUN_TEST(test_state_enum_values);
    RUN_TEST(test_ota_cmd_values);
    RUN_TEST(test_ota_status_values);
    RUN_TEST(test_status_frame_is_20_bytes);
    RUN_TEST(test_device_info_frame_is_18_bytes);
    RUN_TEST(test_interval_params_is_14_bytes);
    RUN_TEST(test_status_frame_field_packing);
    RUN_TEST(test_device_info_frame_marker);
    RUN_TEST(test_interval_params_layout);

    return UNITY_END();
}
