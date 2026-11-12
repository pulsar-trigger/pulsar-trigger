#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Battery rundown logger: compare two firmware builds without an ammeter.

usage: battery_log.py <address> <label> [--hours 3] [--interval-s 30] [--out file.csv]

The M5StickS3's power chip (M5PM1) reports voltages, not current, so this
compares builds by how fast the stick's own battery percentage falls doing
the same job. The ratio of %/hour between two builds is the ratio of their
average current; the battery-gauge curve is the same for both, so its error
largely cancels. It is a ratio, not milliamps.

The job is a night-style intervalometer: one shot every --interval-s,
200 ms exposure, unlimited shots, app connected - how the stick spends a
real shoot. Auto-off is disabled for the run and set back to 5 min after.

Run it on battery (USB unplugged: USB powers and charges the stick) and
start each build from the same charge. Linux/BlueZ; pip install bleak.
Keep the computer awake for the whole run, or it suspends and the link
drops (2026-11-12: the first run lost an hour that way):

    systemd-inhibit --what=sleep:idle:handle-lid-switch --why="Pulsar rundown" \
        python3 tools/battery_log.py <address> <label>

Elapsed time is wall-clock, and every row carries its timestamp, so a gap
shows as a gap.

🔴 The logger only LISTENS during the run. Any command counts as interaction
and puts the link on the fast interval for 10 s (link_policy.h), which would
cost power and bias the result. Status notifications arrive on every shot.
"""
import argparse, asyncio, csv, struct, sys, time
from bleak import BleakClient, BleakScanner

CMD = "0000ff01-0000-1000-8000-00805f9b34fb"
STATUS = "0000ff02-0000-1000-8000-00805f9b34fb"
VER = 0x02
OP_SET_INTERVALOMETER, OP_SET_AUTO_OFF, OP_START, OP_STOP = 0x10, 0x22, 0x50, 0x51
T_INTERVAL, T_EXPOSURE, T_SHOT_COUNT, T_DELAY, T_AUTO_OFF = 0x01, 0x02, 0x03, 0x04, 0x33
T_STATE, T_SHOTS, T_BATTERY, T_ERROR, T_FW = 0x50, 0x52, 0x54, 0x55, 0x56
STATES = {0: "IDLE", 1: "RUNNING", 2: "WAITING", 3: "ERROR"}


def frame(op, *tlvs):
    body = b"".join(bytes([t, len(v)]) + v for t, v in tlvs)
    return bytes([op, VER, len(body)]) + body


def parse(data):
    """-> (opcode, {tag: bytes}) for one notification frame."""
    if len(data) < 3:
        return None, {}
    op, n = data[0], data[2]
    out, i, body = {}, 0, data[3:3 + n]
    while i + 2 <= len(body):
        t, l = body[i], body[i + 1]
        out[t] = body[i + 2:i + 2 + l]
        i += 2 + l
    return op, out


async def stop_run(c):
    await c.write_gatt_char(CMD, frame(OP_STOP), response=True)
    await c.write_gatt_char(CMD, frame(OP_SET_AUTO_OFF, (T_AUTO_OFF, struct.pack("<H", 5))), response=True)
    print("run stopped, auto-off back to 5 min", flush=True)


async def main(a):
    rows, last = [], {}
    t0 = time.time()  # wall clock: a suspend must show as a gap, not vanish
    end = t0 + a.hours * 3600

    def on_status(_, d):
        op, tl = parse(bytes(d))
        if op != 0x80 or T_BATTERY not in tl:
            return
        last.update(
            battery=tl[T_BATTERY][0],
            shots=struct.unpack("<H", tl[T_SHOTS])[0] if T_SHOTS in tl else None,
            state=STATES.get(tl[T_STATE][0], "?") if T_STATE in tl else "?",
            fw=".".join(str(b) for b in tl[T_FW]) if T_FW in tl else "?")

    out = open(a.out, "w", newline="")
    w = csv.writer(out)
    w.writerow(["label", "elapsed_min", "battery_pct", "shots", "state", "fw", "connected", "time"])
    started = stopped = False
    while time.time() < end:
        dev = await BleakScanner.find_device_by_address(a.address, timeout=30)
        if not dev:
            print("not advertising - retrying", flush=True)
            continue
        try:
            async with BleakClient(dev, timeout=60, pair=True) as c:
                await c.start_notify(STATUS, on_status)
                if not started:
                    await c.write_gatt_char(CMD, frame(OP_SET_AUTO_OFF, (T_AUTO_OFF, struct.pack("<H", 0))), response=True)
                    await c.write_gatt_char(CMD, frame(
                        OP_SET_INTERVALOMETER,
                        (T_INTERVAL, struct.pack("<I", a.interval_s * 1000)),
                        (T_EXPOSURE, struct.pack("<I", 200)),
                        (T_SHOT_COUNT, struct.pack("<H", 0)),
                        (T_DELAY, struct.pack("<I", 0))), response=True)
                    await c.write_gatt_char(CMD, frame(OP_START), response=True)
                    started = True
                    print(f"run started: every {a.interval_s} s, 200 ms, unlimited; logging {a.hours} h", flush=True)
                while c.is_connected and time.time() < end:
                    await asyncio.sleep(60)
                    m = (time.time() - t0) / 60
                    w.writerow([a.label, f"{m:.1f}", last.get("battery"), last.get("shots"),
                                last.get("state"), last.get("fw"), int(c.is_connected),
                                time.strftime("%Y-%m-%d %H:%M:%S")])
                    out.flush()
                    print(f"{m:6.1f} min  battery {last.get('battery')}%  shots {last.get('shots')}  {last.get('state')}", flush=True)
                if c.is_connected:
                    await stop_run(c)
                    stopped = True
        except Exception as e:
            print(f"connection lost ({e}) - reconnecting; the run continues on the stick", flush=True)
            await asyncio.sleep(5)
    out.close()

    # 2026-11-12: the link dropped on the final STOP, the loop had ended, and the stick
    # ran on (auto-off disabled) until stopped by hand. Retry on a fresh connection.
    for attempt in range(5):
        if stopped or not started:
            break
        dev = await BleakScanner.find_device_by_address(a.address, timeout=30)
        if not dev:
            print("stop: not advertising (BlueZ may still hold a dead link: "
                  f"bluetoothctl disconnect {a.address}) - retrying", flush=True)
            continue
        try:
            async with BleakClient(dev, timeout=60, pair=True) as c:
                await stop_run(c)
                stopped = True
        except Exception as e:
            print(f"stop attempt {attempt + 1} failed ({e})", flush=True)
            await asyncio.sleep(5)
    if started and not stopped:
        print("!! THE STICK IS STILL RUNNING with auto-off disabled - stop it from the app", flush=True)

    pts = [(float(r[1]), int(r[2])) for r in csv.reader(open(a.out)) if r[0] == a.label and r[2] not in ("", "None")]
    if len(pts) >= 2:
        (m0, b0), (m1, b1) = pts[0], pts[-1]
        print(f"\n{a.label}: {b0}% -> {b1}% in {(m1 - m0) / 60:.2f} h = {(b0 - b1) / ((m1 - m0) / 60):.2f} %/h")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("address")
    p.add_argument("label", help="build name, e.g. m5sticks3 or m5sticks3_pm")
    p.add_argument("--hours", type=float, default=3)
    p.add_argument("--interval-s", type=int, default=30)
    p.add_argument("--out", default=None)
    a = p.parse_args()
    a.out = a.out or f"battery-{a.label}.csv"
    asyncio.run(main(a))
