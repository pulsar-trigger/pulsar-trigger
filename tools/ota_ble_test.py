#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""OTA a Pulsar image over BLE and measure it (Linux/BlueZ; pip install bleak).

usage: ota_ble_test.py <image.bin> <address> [idle-seconds-before-BEGIN]

An idle of ~14 s reproduces the 2026-11-11 link race: the stick asks for the slow
link after 10 s, and BEGIN arrives while that request is still in flight. Run
`sudo btmon` alongside to see the intervals the link really ran at (docs/power.md).

Pass  = BEGIN -> READY, every chunk written, END -> COMPLETE.
Speed = image bytes / (first chunk -> COMPLETE). The normal build did ~90
writes/s; the power-saving build crawled at ~7 because a stale Slow link
request landed after the OTA's Fast one (fixed by LinkTracker).
"""
import asyncio, struct, sys, time
from bleak import BleakClient, BleakScanner

CTRL = "0000ff11-0000-1000-8000-00805f9b34fb"
DATA = "0000ff12-0000-1000-8000-00805f9b34fb"
NAMES = {0x00: "OK", 0x01: "ERR_BEGIN", 0x02: "ERR_WRITE", 0x03: "ERR_VALIDATE",
         0x04: "ERR_SIZE", 0x05: "ERR_BOARD", 0x06: "ERR_BUSY",
         0x10: "READY", 0x11: "COMPLETE"}


async def main(path, address, idle=0):
    image = open(path, "rb").read()
    print(f"image {path}: {len(image)} bytes")
    dev = await BleakScanner.find_device_by_address(address, timeout=30)
    if not dev:
        sys.exit(f"FAIL: {address} not advertising (is the stick on?)")
    status = asyncio.Queue()
    async with BleakClient(dev, timeout=60, pair=True) as c:
        if hasattr(c._backend, "_acquire_mtu"):
            await c._backend._acquire_mtu()
        chunk = min(c.mtu_size - 3, 512)
        print(f"connected, MTU {c.mtu_size} -> {chunk}-byte chunks")
        tb = [time.monotonic()]
        def on_status(_, d):
            d = bytes(d)
            print(f"  [{time.monotonic() - tb[0]:7.1f} s] status {NAMES.get(d[0], hex(d[0]))} {d.hex()}", flush=True)
            status.put_nowait(d)
        await c.start_notify(CTRL, on_status)

        async def expect(want, secs):
            d = await asyncio.wait_for(status.get(), secs)
            name = NAMES.get(d[0], f"0x{d[0]:02x}")
            if d[0] != want:
                sys.exit(f"FAIL: expected {NAMES[want]}, got {name} ({d.hex()})")
            return d

        if idle:
            # Reproduce the bench race: after LINK_INTERACTIVE_MS (10 s) idle the
            # stick asks for the Slow link; BEGIN while that is in flight.
            print(f"idle {idle} s before BEGIN (lets the stick request the slow link)")
            await asyncio.sleep(idle)
        tb[0] = time.monotonic()
        await c.write_gatt_char(CTRL, struct.pack("<BI", 0x01, len(image)), response=True)
        d = await expect(0x10, 20)
        print(f"READY (chip {d[1] if len(d) > 1 else '?'})")

        n = (len(image) + chunk - 1) // chunk
        t0 = time.monotonic()
        for i in range(n):
            await c.write_gatt_char(DATA, image[i * chunk:(i + 1) * chunk], response=False)
            if i % 200 == 0:
                print(f"  queued {i}/{n}  {time.monotonic() - t0:6.1f} s", flush=True)
        t_sent = time.monotonic() - t0
        print(f"all {n} chunks handed to BlueZ in {t_sent:.1f} s; END ...")
        # END waits behind every queued chunk, so its answer marks the end of delivery.
        try:
            await asyncio.wait_for(c.write_gatt_char(CTRL, bytes([0x02]), response=True), 900)
        except Exception as e:
            sys.exit(f"FAIL: END after {time.monotonic() - t0:.1f} s, connected={c.is_connected}: {e}")
        await expect(0x11, 120)
        dt = time.monotonic() - t0
        print(f"COMPLETE after {dt:.1f} s: {len(image) / dt / 1024:.1f} KiB/s, "
              f"{n / dt:.1f} writes/s")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    asyncio.run(main(sys.argv[1], sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 0))
