#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""
canon_ble_probe — Canon BLE-Remote GATT discovery + protocol probe

Two modes:
  --dump      (default)  scan, connect, bond if needed, enumerate every Canon
                         service + characteristic + descriptor with properties.
                         Writes nothing. Use this first to map the body.
  --pair      after a dump, try the M50 pair handshake. Will only run if the
                         characteristics it needs are present.
  --trigger   after a successful pair, fire a bulb exposure.

Canon's custom UUID base is `XXXXXXXX-0000-1000-0000-d8492fffa821`.
M50 (robot9706): family 0001 = pair, family 0003 = trig.
EOS RP observation (2026-05-19): exposes only family 0005 after bond.

Usage:
    pip install --user bleak
    python3 canon_ble_probe.py --dump
    python3 canon_ble_probe.py --pair    --nickname PULSAR
    python3 canon_ble_probe.py --trigger --bulb-ms 2000

Camera-side setup:
    MENU -> Wireless features -> Bluetooth function -> Remote
    Tap "Pair" so the body advertises. Bond will Just-Works on first connect.
"""

import argparse
import asyncio
import sys

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    print("Missing dependency. Install with:  pip install --user bleak", file=sys.stderr)
    sys.exit(1)


# Canon's custom 128-bit UUID base: `XXXXXXXX-0000-1000-0000-d8492fffa821`.
# 32-bit "short" is in the first 4 bytes of standard MSB form. Examples:
#   00010000-... = M50 pair service     (family 0001, characteristic 0000)
#   00010006-... = M50 pair-command char
#   0001000a-... = M50 pair-data char
#   00030000-... = M50 trig service     (family 0003, characteristic 0000)
#   00030030-... = M50 trig characteristic
#   00050000-... = EOS RP observed service (family 0005, unknown purpose)
CANON_BASE_SUFFIX = "-0000-1000-0000-d8492fffa821"
CANON_COMPANY_ID = 0x01A9


def canon_uuid(short: int) -> str:
    """Build a Canon-namespace UUID from its 32-bit short prefix."""
    return f"{short:08x}{CANON_BASE_SUFFIX}"


def is_canon_uuid(uuid: str) -> bool:
    return uuid.lower().endswith(CANON_BASE_SUFFIX)


# M50 (robot9706) UUIDs in correct standard form.
M50_PAIR_SERVICE   = canon_uuid(0x00010000)
M50_PAIR_CMD_CHAR  = canon_uuid(0x00010006)
M50_PAIR_DATA_CHAR = canon_uuid(0x0001000a)
M50_TRIG_SERVICE   = canon_uuid(0x00030000)
M50_TRIG_CHAR      = canon_uuid(0x00030030)
M50_TRIG_NOTIFY    = canon_uuid(0x00030031)


async def scan_for_camera(name: str, timeout: int = 20) -> str | None:
    print(f"[scan] looking for '{name}' or any Canon device (timeout {timeout}s)…")
    found: dict = {}

    def on_adv(device, adv):
        if device.address in found:
            return
        if device.name == name:
            found[device.address] = (device, adv, f"name={name}")
        elif CANON_COMPANY_ID in (adv.manufacturer_data or {}):
            found[device.address] = (device, adv, "manufacturer-id=Canon")

    async with BleakScanner(detection_callback=on_adv):
        for _ in range(timeout * 2):
            if found:
                break
            await asyncio.sleep(0.5)
    if not found:
        return None
    addr, (device, adv, how) = next(iter(found.items()))
    print(f"[scan] found {device.name or '<unnamed>'} @ {addr}  ({how}, RSSI {adv.rssi} dBm)")
    return addr


async def dump_gatt(client: BleakClient) -> None:
    """Print every service / characteristic / descriptor with properties."""
    print(f"\n[discover] enumerating GATT …")
    canon_services = []
    for s in client.services:
        is_canon = is_canon_uuid(s.uuid)
        marker = " <-- CANON" if is_canon else ""
        print(f"\n  service {s.uuid}{marker}")
        if is_canon:
            canon_services.append(s)
        for c in s.characteristics:
            props = ",".join(c.properties)
            print(f"    char {c.uuid}  [{props}]  handle=0x{c.handle:04x}")
            # Some characteristics expose useful read values immediately
            if "read" in c.properties:
                try:
                    val = await client.read_gatt_char(c.uuid)
                    print(f"      read = {val.hex()}  ({val!r})")
                except Exception as e:
                    print(f"      read failed: {e}")
            for d in c.descriptors:
                print(f"      descr {d.uuid}  handle=0x{d.handle:04x}")
                try:
                    val = await client.read_gatt_descriptor(d.handle)
                    print(f"        read = {val.hex()}")
                except Exception:
                    pass

    print(f"\n[discover] found {len(canon_services)} Canon-namespace service(s)")
    for s in canon_services:
        family = s.uuid[:8]
        print(f"  family {family}  ({s.uuid})  -> {len(s.characteristics)} characteristic(s)")


async def try_pair(client: BleakClient, nickname: str) -> bool:
    """Attempt the M50 pair handshake. Returns True on apparent success."""
    print(f"\n[pair] checking for M50-style pair characteristics …")
    char_uuids = {c.uuid.lower() for s in client.services for c in s.characteristics}
    missing = [c for c in (M50_PAIR_CMD_CHAR, M50_PAIR_DATA_CHAR) if c not in char_uuids]
    if missing:
        print(f"[pair] required characteristics not on this body:")
        for m in missing:
            print(f"  {m}")
        return False
    nickname_bytes = bytes([0x01]) + nickname.encode("ascii")
    platform_bytes = bytes([0x05, 0x02])
    confirm_bytes  = bytes([0x01])
    print(f"[pair] 1/3 nickname  ({nickname_bytes.hex()}) -> {M50_PAIR_CMD_CHAR}")
    await client.write_gatt_char(M50_PAIR_CMD_CHAR, nickname_bytes, response=True)
    await asyncio.sleep(0.5)
    print(f"[pair] 2/3 platform  ({platform_bytes.hex()}) -> {M50_PAIR_DATA_CHAR}")
    await client.write_gatt_char(M50_PAIR_DATA_CHAR, platform_bytes, response=True)
    await asyncio.sleep(0.5)
    print(f"[pair] 3/3 confirm   ({confirm_bytes.hex()}) -> {M50_PAIR_DATA_CHAR}")
    await client.write_gatt_char(M50_PAIR_DATA_CHAR, confirm_bytes, response=True)
    await asyncio.sleep(1.0)
    print(f"[pair] handshake sent — check the camera's registered-device list for '{nickname}'")
    return True


async def try_trigger(client: BleakClient, bulb_ms: int) -> bool:
    char_uuids = {c.uuid.lower() for s in client.services for c in s.characteristics}
    if M50_TRIG_CHAR not in char_uuids:
        print(f"[trigger] M50 trig char {M50_TRIG_CHAR} not on this body")
        return False
    notifications: list[bytes] = []
    if M50_TRIG_NOTIFY in char_uuids:
        async def on_notify(_, data):
            notifications.append(bytes(data))
            print(f"[notify] camera -> {bytes(data).hex()}")
        try:
            await client.start_notify(M50_TRIG_NOTIFY, on_notify)
            print(f"[notify] subscribed to {M50_TRIG_NOTIFY}")
        except Exception as e:
            print(f"[notify] subscribe failed (non-fatal): {e}")
    press = bytes([0x00, 0x01])
    release = bytes([0x00, 0x02])
    print(f"[trigger] press   ({press.hex()})")
    await client.write_gatt_char(M50_TRIG_CHAR, press, response=True)
    await asyncio.sleep(bulb_ms / 1000)
    print(f"[trigger] release ({release.hex()})")
    await client.write_gatt_char(M50_TRIG_CHAR, release, response=True)
    await asyncio.sleep(1.0)
    if notifications:
        print(f"[trigger] camera notifications: {[n.hex() for n in notifications]}")
    return True


async def try_brute(client: BleakClient, gap_ms: int = 1000) -> int:
    """Walk every writable Canon characteristic in turn, try a couple of
    candidate trigger payloads against each, and report any indications the
    camera pushes back. Pause between writes so the user can listen for the
    shutter. Returns the number of (char, payload) combinations tried."""
    # Pre-collect the writable Canon chars and the indicate Canon chars.
    write_chars = []
    indicate_chars = []
    for s in client.services:
        if not is_canon_uuid(s.uuid):
            continue
        for c in s.characteristics:
            props = set(c.properties)
            if "write" in props or "write-without-response" in props:
                write_chars.append(c)
            if "indicate" in props or "notify" in props:
                indicate_chars.append(c)

    if not write_chars:
        print(f"[brute] no writable Canon characteristics found")
        return 0
    print(f"[brute] {len(write_chars)} writable char(s), "
          f"{len(indicate_chars)} indicate char(s)")

    # Subscribe to every indicate char so we capture whatever the camera says
    # in response to each write — even if no shutter fires, the body might
    # respond with a status / error code that tells us which char does what.
    indications: list[tuple[str, bytes]] = []
    def make_handler(uuid_str: str):
        async def on_notify(_, data):
            indications.append((uuid_str, bytes(data)))
            print(f"    <- indicate {uuid_str[:8]}: {bytes(data).hex()}")
        return on_notify
    for c in indicate_chars:
        try:
            await client.start_notify(c.uuid, make_handler(c.uuid))
        except Exception as e:
            print(f"[brute] couldn't subscribe to {c.uuid[:8]}: {e}")

    # Candidate trigger payloads. The first is M50's press/release. The
    # others are sensible variants we'd try if the protocol uses different
    # encoding — single byte, longer-with-padding, big-endian, etc.
    payload_sets = [
        ("M50 0001/0002",   bytes([0x00, 0x01]), bytes([0x00, 0x02])),
        ("byte-swap 0100/0200", bytes([0x01, 0x00]), bytes([0x02, 0x00])),
        ("single-byte 01/02", bytes([0x01]), bytes([0x02])),
    ]

    tried = 0
    print(f"\n[brute] press ENTER between rounds; watch + listen the camera for any reaction")
    print(f"[brute] expected: a shutter click, an LED, or any indicate response above\n")
    for label, press, release in payload_sets:
        print(f"\n=== payload set: {label} (press={press.hex()}, release={release.hex()}) ===")
        for c in write_chars:
            short = c.uuid[:8]
            try:
                input(f"  -> ENTER to write to {short}…  ")
            except (KeyboardInterrupt, EOFError):
                print(f"\n[brute] aborted after {tried} write(s)")
                return tried
            before = len(indications)
            try:
                await client.write_gatt_char(c.uuid, press, response=True)
                print(f"    wrote press   to {short}")
                await asyncio.sleep(gap_ms / 1000)
                await client.write_gatt_char(c.uuid, release, response=True)
                print(f"    wrote release to {short}")
            except Exception as e:
                print(f"    write FAILED to {short}: {e}")
                tried += 1
                continue
            await asyncio.sleep(0.5)  # let any indications arrive
            new = indications[before:]
            if new:
                print(f"    {len(new)} indication(s) during this round")
            else:
                print(f"    no indications")
            tried += 1
    print(f"\n[brute] done — tried {tried} combination(s), "
          f"{len(indications)} total indication(s) captured")
    return tried


async def probe(args) -> int:
    address = await scan_for_camera(args.name)
    if not address:
        print("\nNo Canon body found. Put the camera in Remote pair mode and re-run.")
        return 1
    print(f"\n[connect] opening GATT to {address}…")
    async with BleakClient(address, timeout=20) as client:
        print(f"[connect] connected")
        if args.dump or not (args.pair or args.trigger or args.brute):
            await dump_gatt(client)
        if args.pair:
            ok = await try_pair(client, args.nickname)
            if not ok:
                print(f"\nM50 pair characteristics aren't on this body — "
                      f"see --dump output to find what IS present.")
                return 2
        if args.brute:
            await try_brute(client, gap_ms=args.bulb_ms)
        if args.trigger:
            try:
                input(f"\n[trigger] ENTER to fire {args.bulb_ms}ms bulb (Ctrl-C to skip) ")
            except KeyboardInterrupt:
                print()
                return 0
            await try_trigger(client, args.bulb_ms)
    return 0


def main() -> int:
    p = argparse.ArgumentParser(description="Canon BLE-Remote protocol probe")
    p.add_argument("--name", default="MonsteRP", help="camera Bluetooth name")
    p.add_argument("--nickname", default="PULSAR", help="nickname to register")
    p.add_argument("--bulb-ms", type=int, default=2000, help="bulb duration / inter-write gap (ms)")
    p.add_argument("--dump", action="store_true", help="enumerate GATT (default if no other action)")
    p.add_argument("--pair", action="store_true", help="attempt M50 pair handshake")
    p.add_argument("--trigger", action="store_true", help="attempt M50 trigger after pair")
    p.add_argument("--brute", action="store_true",
                   help="walk every writable Canon char with candidate trigger payloads, "
                        "subscribe to all indicate chars to catch responses")
    args = p.parse_args()
    try:
        return asyncio.run(probe(args))
    except KeyboardInterrupt:
        print("\nInterrupted.")
        return 130


if __name__ == "__main__":
    sys.exit(main())
