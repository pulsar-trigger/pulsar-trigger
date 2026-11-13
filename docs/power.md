<!--
SPDX-License-Identifier: GPL-3.0-or-later
Copyright (C) 2026 Pulsar Trigger contributors
-->

# Firmware power

The M5StickS3 has a 250 mAh battery, and before this work it did not last a long
session even with the LCD off. This page says what the firmware does about power, what
was measured, and how to measure it again.

## What the firmware does

| Change | Where | Effect |
|---|---|---|
| The StickS3 IMU is not initialised, so it stays in its power-on suspend state | `main.cpp` (`cfg.internal_imu = false`, StickS3 only) | the IMU is never read |
| The PM1 5 V output boost (Hat and Grove 5 V pins) is switched off | `main.cpp`, boot line `[PM] 5V output on -> off` | it was **on** at boot (`PM1 0x06 = 0x1F`); no Pulsar hat uses 5 V |
| A slow BLE link when nobody is waiting on it | `link_policy.h`, `ble_server.cpp` | 400–800 ms, slave latency 2, 6 s timeout when idle-connected and during a run; 15–30 ms for 10 s after a command or a run's end, and during OTA |
| `loop()` blocks until the next shot instead of waking every 1 ms | `wake.h`, `wake_plan.h`, `triggers_ms_until_next_event()` | BLE commands wake it at once; the last 2 ms before a shot are spun, so timing is unchanged |
| Real light sleep: power management, tickless idle, BLE modem sleep | the **`m5sticks3_pm`** env, `sdkconfig.defaults` | experimental; see below |

The speaker amp, microphone and LCD were already off.

### Light sleep needs its own build

The stock Arduino 2.0.17 libraries are compiled without `CONFIG_PM_ENABLE`,
`CONFIG_FREERTOS_USE_TICKLESS_IDLE` or BLE controller sleep (`CONFIG_BT_CTRL_SLEEP_MODE_EFF 0`
on the S3). So in the `esp32`, `m5sticks3` and `m5core2` builds the chip runs at a fixed
80 MHz and never sleeps; no code can change that. The boot log says so:

```text
[PM] CONFIG_PM_ENABLE not built in: no light sleep; CPU fixed at 80 MHz
```

`m5sticks3_pm` builds the same firmware as Arduino on ESP-IDF 4.4.7 — the same Arduino
API — with those options turned on. Its boot log:

```text
[PM] esp_pm_configure OK: max 80 / min 10 MHz, light sleep on (tickless idle built in, BLE modem sleep on); CPU now 240 MHz
```

(The 240 MHz is read straight after the call; once power management takes over, the `i`
hook reports 80 MHz.)

Flash it with:

```sh
cd firmware
~/.platformio/penv/bin/pio run -e m5sticks3_pm -t upload
```

🔴 **Two traps, both in `sdkconfig.defaults`' header.** Without `CONFIG_SPIRAM_BOOT_INIT`,
Arduino starts the octal PSRAM late and NVS cannot write ("WREN flag has not been set"), so
every setting — name, trigger pins, auto-off — silently falls back to its default. And the
stock sdkconfig's deprecated `CONFIG_FLASHMODE_*` aliases override
`CONFIG_ESPTOOLPY_FLASHMODE_*`.

The BLE low-power clock is the main crystal (`CONFIG_BT_CTRL_LPCLK_SEL_MAIN_XTAL`), because
no 32.768 kHz crystal is assumed on the StickS3. That keeps the crystal running in light
sleep, which costs some current; an external 32 kHz crystal, if the board has one, would
be cheaper. Not checked.

## Verified on a StickS3 (2026-11-10)

| Check | Stock build (`m5sticks3`) | Light sleep (`m5sticks3_pm`) |
|---|---|---|
| Boot `[PM]` line | `CONFIG_PM_ENABLE not built in … 80 MHz` | `esp_pm_configure OK … tickless idle built in, BLE modem sleep on` |
| Settings load (`Loaded device name: 'Pulsar-S3'`) | yes | yes |
| Single shot (`o`) | 400 ms (200 focus + 200 shot) | 400 ms |
| 2 s / 1 s / 5-shot run (`t`) | every shot 0 ms late, 2200 ms (first: due at start, 2 ms late) | the same |
| BLE fast link granted | 30 ms | 30 ms |
| BLE slow link granted | — (central dropped first) | 795 ms, latency 2, 6 s |
| USB serial while sleeping | — | keeps working (11-10: light sleep was not yet engaging - see below) |

## Serial hooks

Single keys on the USB serial console (115200), no app needed:

| Key | Does |
|---|---|
| `r` | the measurement run: intervalometer, 30 s exposure, 5 s gap, 20 shots |
| `t` | a short timing run: 2 s exposure, 1 s gap, 5 shots |
| `o` | one single shot |
| `s` | stop the run |
| `i` | CPU MHz, PM, BLE state, run state |
| `p` | the flash partitions the firmware sees |
| `?` | this list |

## Measuring current

🔴 **On USB power the battery charges, and a USB-C meter in line measures the charging too.**
Measure with the battery full, or on battery with a meter in the battery lead.
🔴 **The firmware switches itself off after 5 minutes idle and unconnected** (Auto-off,
settable in the app). A measurement of state (a) longer than that needs Auto-off set to 0
first. Take any LED off the trigger pins, too.

Flash the build to test (`m5sticks3` for "before", `m5sticks3_pm` for "after"), then:

- **(a) Advertising / idle:** nothing connected, no run. Read for at least 30 s.
- **(b) Connected / idle:** connect the app, then leave it 10 s untouched, which lets the link
  go slow. Check the log for `Link now: interval 795…` (or whatever the phone grants).
- **(c) Running:** with the app connected, press `r` on the console, or start the same job in
  the app: 30 s exposure, 5 s gap, 20 shots. Read over a few full cycles.

Lines to watch for timing during (c), one pair per shot:

```text
[INTV] shot 3 firing at 17432 ms (due 17432, late 0 ms)
[INTV] shot 3 took 30200 ms (asked 30200)
```

"late" must stay at 0–2 ms and "took" equal "asked". Any drift means a power change touched
the timing.

| State | `m5sticks3` (before) mA | `m5sticks3_pm` (after) mA | Notes |
|---|---|---|---|
| (a) advertising / idle | | | |
| (b) connected / idle | | | |
| (c) running 30 s / 5 s / 20 | | | |

Before = the branch's own `m5sticks3` build, which already has fixes 2–4. For a true baseline,
also flash `master` once.

## Firmware updates (OTA) and the link - 2026-11-11/12

Since `CONFIG_BT_CTRL_MAIN_XTAL_PU_DURING_LIGHT_SLEEP` (0.33) light sleep really engages, and
three things followed.

**The upload crawled at ~7 writes/s - a link race, every build.** The stick asks for the slow
link 10 s after the last command (`LINK_INTERACTIVE_MS`). If an OTA started while that request
was still in flight, it landed AFTER the OTA's fast request, the link stayed at 795 ms, and the
firmware - which tracked what it had *asked* for - never asked again. `LinkTracker`
(`link_policy.h`) now decides from the interval the controller *reports*, one request at a
time, up to 3 per mode change. On the air (btmon), reproduced by idling 14 s before `BEGIN`:
30 ms -> 795 ms -> 30 ms, the upload completing.

**The power-saving build went silent during uploads - fixed by staying awake.** With the link
fast, light sleep while flash was being erased and written stopped the radio (the central saw a
supervision timeout) three times out of three. `ota.cpp`'s `set_active()` now holds
`ESP_PM_NO_LIGHT_SLEEP` + `ESP_PM_CPU_FREQ_MAX` and turns BLE modem sleep off for the length of
an upload.

| Build | OTA runs | Result |
|---|---|---|
| `m5sticks3` (stock, what ships) | 3 + 3, plain and under the race | all complete, 37-45 s, 64-79 writes/s |
| `m5sticks3_pm` | 2 under the race | complete, 46-52 s |
| `m5sticks3_pm` | 2 started on the fast link | 🔴 failed: link lost 4 s in; data stalled at 60 % (the stick logged both and kept running) |

🔴 **Still open on `m5sticks3_pm`:** an upload that starts on the fast link (4 s supervision
timeout) can lose the link while flash is busy; one that starts on the slow link (6 s,
latency 2) survives. Not seen on the stock build, so it is in this build's ESP-IDF
configuration (octal PSRAM started at boot, SPI flash settings), not in the link logic.
Candidates: `esp_ota_begin` with `OTA_WITH_SEQUENTIAL_WRITES` (erase per sector instead of
the whole image up front), a longer fast-link supervision timeout, the flash-erase yield
settings. Keep `m5sticks3_pm` experimental until it passes.

🔴 **USB on the power-saving build is dead while it sleeps - from about a second after boot.**
The port stays listed but answers nothing (`OSError: [Errno 71] Protocol error` on the first
control request). Flash it in **download mode**: G0 to GND while powering on, flash, remove
the wire, power-cycle. Or OTA it.

Test harness: `tools/ota_ble_test.py <image.bin> <address> [idle-seconds]`, with `sudo btmon`
alongside for the link intervals.

## Battery rundown, stock vs light sleep - 2026-11-12

There is no ammeter, so the two builds were compared by how fast the stick's own battery
percentage falls doing the same job: `tools/battery_log.py`. Each run: full charge, USB
unplugged, an LED + resistor on the trigger pin, the logger connected and only listening,
an intervalometer of one 200 ms shot every 30 s, unlimited, for 2 h. Auto-off is disabled
during the run.

| Minute | `m5sticks3` (stock) | `m5sticks3_pm` (light sleep) |
|---|---|---|
| 1 | 99 % | 100 % |
| 15 | 87 % | 100 % |
| 30 | 76 % | 100 % |
| 45 | 64 % | 100 % |
| 60 | 54 % | 100 % |
| 75 | 45 % | 98 % |
| 90 | 39 % | 97 % |
| 105 | 36 % | 95 % |
| 120 | 32 % | 93 % |
| **Drop in 2 h** | **67 points** | **7 points** |
| Shots | 237 | 237, none late, no restart (uptime 7389 s) |

Over the same stretch of the gauge, 99/100 % down to 93 %, the stock build took **7 minutes**
and the light-sleep build took **2 hours**.

⚠️ **This is a ratio, not milliamps, and not a precise one.** The M5PM1 gauge works from
voltage. A full cell sits on a plateau near 100 %, and a heavier load pulls the voltage
down, so it reads lower. Both effects make the stock build look worse than its current
alone would. What the run does show: the light-sleep build drains the battery several times
more slowly, roughly an order of magnitude on this job. An ammeter in the battery lead would
give the true factor (see "Measuring current").

Raw data: `~/pulsar-power/run1-m5sticks3.csv` and `run2-m5sticks3_pm.csv` on the test
machine, not in the repo.

## Reboots on the power-saving build, and how they were found - 2026-11-12

The first attempt at the `m5sticks3_pm` run restarted the stick every few minutes.
There was no USB serial: USB is dead on this build while it sleeps. So the firmware now
reports its own crashes over BLE.

**Crash report over BLE.** The pm build keeps an ELF core dump in flash. At boot, the
firmware reads the dump's summary. Device info then carries the reset reason
(`esp_reset_reason()`), the uptime and a tick-clamp counter. After the device-info frame,
the firmware sends a crash-info notification: task, PC, exception cause, backtrace and the
image's ELF SHA. See [ble-protocol.md](ble-protocol.md) (`0x55` / `0x83`).
`xtensa-esp32s3-elf-addr2line -pfiaC -e firmware.elf` decodes the backtrace, so keep each
build's `firmware.elf`.

🔴 **Build the crash frame in `loop()`, never in the GATT callback.** The callback runs on
Bluedroid's BTC task. Arduino's `BLECharacteristic::notify()` hex-dumps every value it sends,
even with logging off, and that alone overflowed the BTC stack.

Three faults, in the order they surfaced:

1. **Idle task stack overflow.** With light sleep, the idle task runs the sleep code, and
   1 KB was not enough. Raised to 4 KB (`CONFIG_FREERTOS_IDLE_TASK_STACKSIZE`).
2. **`vTaskStepTick` assert after a late wake.** Light sleep can overshoot the next FreeRTOS
   deadline. `vApplicationSleep` then steps the tick count past `xNextTaskUnblockTime`, and
   FreeRTOS asserts. This is ESP-IDF issue #9797, with no fix upstream for 4.4. Switching
   off CPU power-down only made it rarer. The fix is a patched copy of ESP-IDF 4.4.7's
   `esp_pm` in `firmware/components/esp_pm/`, which replaces the framework's component of
   the same name. It clamps the stepped ticks to the expected idle time, which is safe
   because the idle task holds the critical section there. Each clamp is counted and
   reported in device info: 66 in the 2 h rundown, every one a reboot before. Shot timing is
   unaffected, because the run loop uses `millis()`, which comes from `esp_timer`, not the
   tick count. See the component's README.
3. **BTC task stack overflow** answering a status request right after boot. Raised from 3 KB
   to 6 KB (`CONFIG_BT_BTC_TASK_STACK_SIZE`).

🔴 Every one of these settings must be in both `sdkconfig.defaults` and the generated,
untracked `sdkconfig.m5sticks3_pm`, because an existing `sdkconfig.<env>` wins over the
defaults.

Verified: 30 back-to-back connect-and-status cycles, a 15-minute run with 0 reboots, then the
2 h rundown above with none.

## Follow-ups (not in this work)

- Arduino's `notify()` hex-dumps every value (three 80-byte buffers and `sprintf`) on every
  status notification. Sending through `esp_ble_gatts_send_indicate` directly would save
  CPU time and stack on every shot.

- Bluedroid → NimBLE: smaller and lighter, a bigger change.
- A light-sleep build for the Core2 and the generic ESP32, the same way, after the StickS3
  numbers say it is worth it.
- Make `m5sticks3_pm` the default StickS3 build once it has run a few real sessions (one 2 h rundown so far, 2026-11-12) and the fast-link OTA issue above is fixed.
- Advertising interval (152–318 ms today) could be longer when idle.
- The StickS3 trigger pins are still the prototype's G0/G1 in saved settings; the jack hat
  uses G5/G6 (separate change).
