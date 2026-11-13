# esp_pm - ESP-IDF 4.4.7's power-management component, patched

A copy of `components/esp_pm` from ESP-IDF 4.4.7 (PlatformIO `framework-espidf`),
used only by the `m5sticks3_pm` env. ESP-IDF builds a project's `components/<name>`
in place of its own component of that name; the Arduino-only envs never read it.
Apache-2.0, as upstream (the SPDX headers are unchanged).

**The one change** is in `pm_impl.c`, `vApplicationSleep()`, marked `PULSAR PATCH`:
the tick step after a light sleep is clamped to `xExpectedIdleTime`, and
`pulsar_pm_tick_clamps` counts how often (DEVICE_INFO tag `0x82`).

**Why.** With tickless idle, BLE modem sleep and light sleep, a sleep sometimes ends
after the next task's unblock time. Stepping the whole sleep then fails
`configASSERT((xTickCount + xTicksToJump) <= xNextTaskUnblockTime)` in
`vTaskStepTick` and the board reboots - every few minutes on the StickS3
(2026-11-12, read from its core dump over BLE). ESP-IDF #9797 reports the same
assert from 4.4.2 to 5.1.4; it was closed by a commit that only touches the
`CONFIG_PM_TRACE` path, so no upstream fix exists for it.

**Why the clamp is safe.** The idle task calls `vApplicationSleep` inside
`taskENTER_CRITICAL()` (the kernel lock), so neither `xTickCount` nor
`xNextTaskUnblockTime` can change, and `xExpectedIdleTime` is exactly their
difference (`prvGetExpectedIdleTime`). The tick interrupt `vApplicationSleep`
already triggers then unblocks the due task. The cost: the FreeRTOS tick count
falls behind real time by the overrun, about one tick each time. The firmware
times shots with `millis()` = `esp_timer_get_time() / 1000`, which is not affected.

**When upgrading ESP-IDF**, re-copy the component and re-apply the marked hunk -
or drop this directory if upstream has fixed the assert.
