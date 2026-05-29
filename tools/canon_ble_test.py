#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""
Canon BR-E1 BLE shutter test — bleak 3.x / Python 3.13+.

Diagnostic / reverse-engineering driver for the Canon BLE direct transport.
Protocol facts, the EOS R GATT dump, and the open "pairs-but-won't-shoot" bug
this tool is investigating are documented in docs/canon-ble-research.md.

Purpose: a clean, well-logged driver to find out whether the documented
BR-E1 protocol can actually fire an EOS R-series body from a computer.
This is the reference test for the Pulsar Android app's "pairs but won't
shoot" bug on the EOS R / RP.

What it does:
  1. Scans for a camera advertising Canon's BR-E1 service UUID
     (00050000-…-d8492fffa821), or connects to a MAC you pass.
  2. Connects and DUMPS every service + characteristic + its properties
     (this is the diagnostic gold: does the control char advertise
     write / write-without-response / notify?).
  3. Arms: writes [0x03, "Pulsar"] to the pair char (00050002), no response.
  4. Optionally subscribes to notifications on the control char first
     (--notify) — some bodies only honour writes once you've subscribed.
  5. Fires: AF half-press, then a single shutter (0x8C → wait → 0x0C),
     with a log line for every write.

Usage:
    # scan + connect + fire (default)
    python3 canon_ble_test.py

    # target a specific MAC (skip scan)
    python3 canon_ble_test.py --mac AA:BB:CC:DD:EE:FF

    # just dump the GATT table, don't fire
    python3 canon_ble_test.py --dump-only

    # try subscribing to control-char notifications before firing
    python3 canon_ble_test.py --notify

    # send a 2-second-timer release instead of immediate (drive-mode probe)
    python3 canon_ble_test.py --delay-mode
"""

import argparse
import asyncio
import os
import sys
import time

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

# Wall-clock origin so every indication/write line gets a relative timestamp —
# the whole point of --probe is correlating "we wrote X at t" with "camera
# indicated Y at t+ε".
_T0 = time.monotonic()


def _ts():
    return f"[{time.monotonic() - _T0:7.3f}s]"

CANON_SUFFIX = "-0000-1000-0000-d8492fffa821"

# BR-E1 "Remote" mode (advertised when camera BT = Remote)
SERVICE_UUID = "00050000" + CANON_SUFFIX
PAIR_UUID    = "00050002" + CANON_SUFFIX
CONTROL_UUID = "00050003" + CANON_SUFFIX

# Smartphone mode (advertised when camera BT = Connect to smartphone / register
# a device). A completely different, richer protocol — see docs/canon-ble-research.md §7.
SMART_SERVICE_UUID = "00010000" + CANON_SUFFIX   # identity service (scan-match)
SMART_NAME_UUID    = "00010006" + CANON_SUFFIX   # name + pairing-result indication
SMART_IDEN_UUID    = "0001000a" + CANON_SUFFIX   # identity / registration writes
SMART_MODE_UUID    = "00030010" + CANON_SUFFIX   # mode select (write MODE_SHOOT)
SMART_SHUTTER_UUID = "00030030" + CANON_SUFFIX   # shutter ([00 01] press / [00 02] release)
SMART_GEO_W_UUID   = "00040002" + CANON_SUFFIX   # geo/time-sync write
SMART_GEO_I_UUID   = "00040003" + CANON_SUFFIX   # geo request indication

# furble (built for an EOS M6) puts mode+shutter on service 00030000. The
# original EOS R instead exposes service 00020000 pre-registration (with a
# write+notify char 00020002). We probe BOTH: if 00030000 appears after the
# camera accepts our registration we use furble's UUIDs; otherwise we fall
# back to the 00020000 channel.
SMART_ALT_CTRL_SVC  = "00020000" + CANON_SUFFIX  # R-series pre/alt control service
SMART_ALT_CTRL_UUID = "00020002" + CANON_SUFFIX  # write+notify char in 00020000

# Smartphone-mode constants (from gkoh/furble CanonEOSSmart)
SMART_PAIR_ACCEPT  = 0x02
SMART_PAIR_REJECT  = 0x03
SMART_MODE_SHOOT   = 0x02

# Last value the camera indicated, keyed by short uuid ("0x0006") — lets the
# --smart handshake wait for the pairing-accept byte on 00010006.
_INDICATIONS = {}

def canon_uuid(short):
    """00050003 → full 128-bit Canon-namespace UUID."""
    return f"{short}{CANON_SUFFIX}"

# On the EOS R-series the Canon service is much richer than the old-DSLR
# BR-E1 (which only used 0002=pair + 0003=control). These are the extra
# channels we explore to find the registration handshake the R demands
# before it honours a shutter write.
INDICATE_UUIDS = [canon_uuid(s) for s in ("00050004", "00050006", "00050007", "0005000b")]
READ_UUIDS     = [canon_uuid(s) for s in ("00050001", "00050004", "00050006", "00050007", "0005000b")]
EXTRA_WRITE_UUIDS = [canon_uuid(s) for s in ("00050005", "0005000a", "0005000c")]

DEVICE_NAME = "Pulsar"

# Control byte = mode | button
MODE_IMMEDIATE = 0x0C
MODE_DELAY     = 0x04   # 2-second self-timer
MODE_MOVIE     = 0x08
BTN_RELEASE    = 0x80
BTN_FOCUS      = 0x40
RELEASE_NONE   = 0x00   # buttons up (just the mode bits)


def log(msg):
    print(f"  {msg}", flush=True)


def _canon_service_in(ad):
    """Return any Canon-namespace service UUID present in an advertisement,
    matching BR-E1 (00050000) OR smartphone-mode (00010000) OR any other
    Canon-suffixed service — so we find the body in whichever BT mode it's in."""
    for u in (ad.service_uuids or []):
        if u.lower().endswith(CANON_SUFFIX):
            return u.lower()
    return None


def _mode_of(uuids):
    """Classify a camera by its advertised Canon service: SMARTPHONE (00010000),
    REMOTE/BR-E1 (00050000), or CANON? (other Canon-suffixed)."""
    us = [u.lower() for u in (uuids or [])]
    if any(u.startswith("00010000") for u in us):
        return "SMARTPHONE"
    if any(u.startswith("00050000") for u in us):
        return "REMOTE"
    if any(u.endswith(CANON_SUFFIX) for u in us):
        return "CANON?"
    return None


async def scan_canon(timeout):
    """Discover ALL Canon cameras in range. Returns [(device, adv, mode)]."""
    log(f"scanning {timeout}s for Canon cameras…")
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    out = []
    for _addr, (dev, ad) in found.items():
        mode = _mode_of(ad.service_uuids)
        if mode:
            out.append((dev, ad, mode))
    return out


def _log_canon_list(cams):
    for dev, ad, mode in cams:
        canon_svcs = [u.lower() for u in (ad.service_uuids or [])
                      if u.lower().endswith(CANON_SUFFIX)]
        log(f"  • {dev.address}  {(dev.name or '(no name)'):18}  [{mode}]  {canon_svcs}")


async def find_camera(timeout, prefer=None):
    """Find one Canon camera. `prefer` = 'smart' (require 00010000) /
    'brel' (require 00050000) / None (any). With two bodies around, refuses to
    guess when multiple match — tells you to pass --mac."""
    cams = await scan_canon(timeout)
    if not cams:
        log("no camera advertising any Canon service was found.")
        log("→ Remote mode:    Bluetooth → Remote → Pair")
        log("→ Smartphone mode: Bluetooth → Connect to smartphone → register/add a device")
        return None

    log(f"found {len(cams)} Canon camera(s):")
    _log_canon_list(cams)

    want = {"smart": "SMARTPHONE", "brel": "REMOTE"}.get(prefer)
    pool = [c for c in cams if c[2] == want] if want else cams
    if want and not pool:
        log(f"→ none in {want} mode. "
            + ("For --smart, set the camera to 'Connect to smartphone' / "
               "register-a-device (advertises 00010000)."
               if want == "SMARTPHONE"
               else "Set the camera to Bluetooth → Remote → Pair (advertises 00050000)."))
        return None
    if len(pool) > 1:
        log(f"→ multiple {want or 'Canon'} cameras in range — pass --mac to pick one.")
        return None

    dev = pool[0][0]
    log(f"→ using {dev.address}  [{pool[0][2]}]")
    return dev


def dump_gatt(client):
    log("── GATT table ──────────────────────────────────────────")
    for service in client.services:
        log(f"service {service.uuid}")
        for ch in service.characteristics:
            props = ",".join(ch.properties)
            tag = ""
            if ch.uuid.lower() == PAIR_UUID:
                tag = "   <-- PAIR (arm)"
            elif ch.uuid.lower() == CONTROL_UUID:
                tag = "   <-- CONTROL (shutter)"
            log(f"    char {ch.uuid}  [handle 0x{ch.handle:04x}]  props=[{props}]{tag}")
            for d in ch.descriptors:
                log(f"        descr {d.uuid} [handle 0x{d.handle:04x}]")
    log("────────────────────────────────────────────────────────")


async def write(client, uuid, data, response, label):
    try:
        await client.write_gatt_char(uuid, bytearray(data),
                                     response=response)
        log(f"WRITE {label}: {bytes(data).hex()} "
            f"(response={response}) → OK")
        return True
    except Exception as e:
        log(f"WRITE {label}: {bytes(data).hex()} "
            f"(response={response}) → FAILED: {type(e).__name__}: {e}")
        return False


def _short_uuid(u):
    """00050004-0000-… → '0x0004' for compact logs."""
    return "0x" + u.split("-")[0][-4:]


def make_indicate_handler(uuid):
    def handler(_char, data):
        _INDICATIONS[_short_uuid(uuid)] = bytes(data)
        log(f"{_ts()}  ◀ INDICATE {_short_uuid(uuid)}: {bytes(data).hex()}")
    return handler


async def subscribe_indicates(client):
    """Enable every indicate channel + log what the camera sends. Returns
    the list of UUIDs successfully subscribed (for clean teardown)."""
    ok = []
    for u in INDICATE_UUIDS:
        try:
            await client.start_notify(u, make_indicate_handler(u))
            ok.append(u)
            log(f"subscribed to indicate {_short_uuid(u)}")
        except Exception as e:
            log(f"subscribe {_short_uuid(u)} FAILED: {type(e).__name__}: {e}")
    return ok


async def read_all(client):
    """Read every readable Canon char — these often hold protocol/version
    /state bytes that hint at the handshake."""
    log("── reading Canon characteristics ──")
    for u in READ_UUIDS:
        try:
            val = await client.read_gatt_char(u)
            log(f"  READ {_short_uuid(u)}: {bytes(val).hex()}  ({len(val)} bytes)")
        except Exception as e:
            log(f"  READ {_short_uuid(u)} FAILED: {type(e).__name__}: {e}")


# ─────────────────────────── --probe machinery ───────────────────────────
# Everything below works off the LIVE GATT table rather than the hardcoded
# UUID guesses above, so it adapts to whatever the EOS R actually exposes.

def enumerate_channels(client):
    """Walk the live GATT and classify Canon-service chars by capability.
    Returns (readable, notifiable, writable) lists of characteristic objs."""
    readable, notifiable, writable = [], [], []
    for service in client.services:
        for ch in service.characteristics:
            p = ch.properties
            if "read" in p:
                readable.append(ch)
            if "notify" in p or "indicate" in p:
                notifiable.append(ch)
            if "write" in p or "write-without-response" in p:
                writable.append(ch)
    return readable, notifiable, writable


async def subscribe_all(client, notifiable):
    """Subscribe to every notify/indicate char on the live GATT (not a guess
    list). Returns the chars we successfully hooked, for clean teardown."""
    ok = []
    for ch in notifiable:
        try:
            await client.start_notify(ch, make_indicate_handler(ch.uuid))
            ok.append(ch)
            log(f"subscribed {_short_uuid(ch.uuid)} "
                f"[handle 0x{ch.handle:04x}] props=[{','.join(ch.properties)}]")
        except Exception as e:
            log(f"subscribe {_short_uuid(ch.uuid)} FAILED: "
                f"{type(e).__name__}: {e}")
    return ok


async def snapshot(client, readable):
    """Read every readable char → {uuid: hexstr}. Errors recorded, not raised,
    so one unreadable char doesn't abort the snapshot."""
    snap = {}
    for ch in readable:
        try:
            val = await client.read_gatt_char(ch)
            snap[ch.uuid] = bytes(val).hex()
        except Exception as e:
            snap[ch.uuid] = f"<err {type(e).__name__}>"
    return snap


def log_snapshot(snap, label):
    """Print every readable char's value — the contents of the unknown
    00020000 chars are our best clue to the R's command structure."""
    log(f"{_ts()} ── reads: {label} ──")
    for uuid in sorted(snap):
        log(f"      {_short_uuid(uuid)} = {snap[uuid] or '∅'}")


def diff_snapshots(before, after, label):
    """Log only the readable chars whose value changed between two snapshots.
    A change on an extra char across arm/AF/shutter is the handshake tell."""
    log(f"{_ts()} ── state diff: {label} ──")
    changed = False
    for uuid in sorted(after):
        b, a = before.get(uuid, "<none>"), after[uuid]
        if a != b:
            log(f"      Δ {_short_uuid(uuid)}: {b or '∅'} → {a or '∅'}")
            changed = True
    if not changed:
        log("      (no readable char changed)")


async def probe_sequence(client, mode, mode_name, poke):
    """Observe-everything fire attempt. Subscribes to all notify channels,
    then arm → AF → shutter, snapshotting + diffing every readable char at
    each step so any state machine the R drives becomes visible."""
    readable, notifiable, writable = enumerate_channels(client)
    log(f"live GATT: {len(readable)} readable, {len(notifiable)} notify, "
        f"{len(writable)} writable chars")

    subscribed = await subscribe_all(client, notifiable)
    log(f"{_ts()} listening 2s for spontaneous indications…")
    await asyncio.sleep(2.0)

    snap = await snapshot(client, readable)
    log(f"{_ts()} baseline snapshot taken ({len(snap)} readable chars)")

    log(f"{_ts()} ── ARM ──")
    await write(client, PAIR_UUID, [0x03] + list(DEVICE_NAME.encode("ascii")),
                response=False, label="ARM pair-name")
    await asyncio.sleep(2.0)
    new = await snapshot(client, readable)
    diff_snapshots(snap, new, "after ARM"); snap = new

    log(f"{_ts()} ── AF half-press ── (mode={mode_name})")
    await write(client, CONTROL_UUID, [mode | BTN_FOCUS],
                response=False, label="AF half-press")
    await asyncio.sleep(1.2)
    await write(client, CONTROL_UUID, [mode | RELEASE_NONE],
                response=False, label="AF release")
    await asyncio.sleep(0.8)
    new = await snapshot(client, readable)
    diff_snapshots(snap, new, "after AF"); snap = new

    log(f"{_ts()} ── SHUTTER ──")
    await write(client, CONTROL_UUID, [mode | BTN_RELEASE],
                response=False, label="SHUTTER press")
    await asyncio.sleep(0.6)
    await write(client, CONTROL_UUID, [mode | RELEASE_NONE],
                response=False, label="SHUTTER release")
    await asyncio.sleep(1.5)
    new = await snapshot(client, readable)
    diff_snapshots(snap, new, "after SHUTTER"); snap = new

    if poke:
        await poke_writables(client, writable, mode, snap, readable)

    log(f"{_ts()} listening 3s for trailing indications…")
    await asyncio.sleep(3.0)
    for ch in subscribed:
        try:
            await client.stop_notify(ch)
        except Exception:
            pass


# Candidate payloads for the EXPERIMENTAL blind-write probe. Each is a guess at
# what a "register / confirm / enable shooting" write might look like, drawn
# from how the arm + control writes are shaped. Order: shortest/safest first.
POKE_PAYLOADS = [
    ("arm-name", [0x03] + list(DEVICE_NAME.encode("ascii"))),
    ("0x01",     [0x01]),
    ("0x02",     [0x02]),
    ("0x01-0x01",[0x01, 0x01]),
    ("0x02-0x00",[0x02, 0x00]),  # mirrors the 0200 the camera indicated to us
]


async def poke_writables(client, writable, mode, baseline, readable):
    """EXPERIMENTAL: blind-write candidate handshake payloads to every writable
    char that ISN'T the known pair/control char, then retry the shutter and
    snapshot. This is trial-and-error — it can leave the body in an odd state
    (power-cycle to recover). Gated behind --poke for exactly that reason."""
    log(f"{_ts()} ╔═ POKE: experimental blind writes to extra writables ═╗")
    extras = [ch for ch in writable
              if ch.uuid.lower() not in (PAIR_UUID, CONTROL_UUID)]
    if not extras:
        log("      no extra writable chars beyond pair/control — nothing to poke")
        return
    for ch in extras:
        for name, payload in POKE_PAYLOADS:
            resp = "write" not in ch.properties  # prefer with-response if offered
            ok = await write(client, ch.uuid, payload, response=not resp,
                             label=f"POKE {_short_uuid(ch.uuid)} «{name}»")
            await asyncio.sleep(0.4)
            # retry one shutter after each poke and see if anything fires/changes
            await write(client, CONTROL_UUID, [mode | BTN_RELEASE],
                        response=False, label="  retry SHUTTER press")
            await asyncio.sleep(0.5)
            await write(client, CONTROL_UUID, [mode | RELEASE_NONE],
                        response=False, label="  retry SHUTTER release")
            await asyncio.sleep(0.6)
            new = await snapshot(client, readable)
            diff_snapshots(baseline, new,
                           f"after POKE {_short_uuid(ch.uuid)} «{name}» + shutter")
            baseline = new
    log(f"{_ts()} ╚═ POKE complete ═╝")


# ─────────────────────────── --smart (smartphone-mode) ───────────────────────────
# Canon's smartphone-mode protocol (service 00010000), per gkoh/furble
# CanonEOSSmart, adapted to the EOS R's live GATT. See docs/canon-ble-research.md §7.

def get_or_make_device_uuid():
    """Persist a 16-byte identity so re-connects reuse it (else the camera
    re-prompts to pair every time). Stored in ~/.canon_ble_smart_uuid."""
    path = os.path.expanduser("~/.canon_ble_smart_uuid")
    try:
        with open(path) as f:
            u = bytes.fromhex(f.read().strip())
            if len(u) == 16:
                return u
    except (OSError, ValueError):
        pass
    u = os.urandom(16)
    try:
        with open(path, "w") as f:
            f.write(u.hex())
    except OSError:
        pass
    return u


def has_char(client, uuid):
    try:
        return client.services.get_characteristic(uuid) is not None
    except Exception:
        return False


async def _smart_teardown(subscribed, client):
    for ch in subscribed:
        try:
            await client.stop_notify(ch)
        except Exception:
            pass


async def smart_sequence(client):
    # furble's secureConnection() — the handshake needs an encrypted/bonded link.
    try:
        ok = await client.pair()
        log(f"{_ts()} client.pair() → {ok}")
    except Exception as e:
        log(f"{_ts()} client.pair() unsupported/failed: {type(e).__name__}: {e}")
        log(f"      if writes fail with auth errors, run `bluetoothctl pair {client.address}` first")

    readable, notifiable, writable = enumerate_channels(client)
    log(f"live GATT: {len(readable)} readable, {len(notifiable)} notify, "
        f"{len(writable)} writable chars")

    subscribed = await subscribe_all(client, notifiable)
    snap = await snapshot(client, readable)
    log(f"{_ts()} baseline snapshot ({len(snap)} readable chars)")
    log_snapshot(snap, "baseline")

    name = list(DEVICE_NAME.encode("ascii"))
    uuid16 = list(get_or_make_device_uuid())
    log(f"{_ts()} identity uuid = {bytes(uuid16).hex()}")

    log(f"{_ts()} ── REGISTER (furble identify 1–4) ──")
    await write(client, SMART_NAME_UUID, [0x01] + name, response=False, label="ID1 [01,name]→0006")
    await write(client, SMART_IDEN_UUID, [0x03] + uuid16, response=False, label="ID2 [03,uuid]→000a")
    await write(client, SMART_IDEN_UUID, [0x04] + name, response=False, label="ID3 [04,name]→000a")
    await write(client, SMART_IDEN_UUID, [0x05, 0x02], response=False, label="ID4 [05,02]→000a")

    log(f"{_ts()} >>> CONFIRM / ACCEPT THE PAIRING ON THE CAMERA NOW (waiting ≤60s) <<<")
    _INDICATIONS.pop("0x0006", None)
    result = None
    for _ in range(60):
        v = _INDICATIONS.get("0x0006")
        if v:
            result = v[0]
            break
        await asyncio.sleep(1.0)
    if result is None:
        log(f"{_ts()} no pairing result on 0006 after 60s — proceeding anyway (diagnostic)")
    elif result == SMART_PAIR_ACCEPT:
        log(f"{_ts()} pairing ACCEPTED (0x02)")
    elif result == SMART_PAIR_REJECT:
        log(f"{_ts()} pairing REJECTED (0x03) — camera declined; aborting")
        return await _smart_teardown(subscribed, client)
    else:
        log(f"{_ts()} pairing result = 0x{result:02x} (unknown) — proceeding")

    # finalize identity (furble step 10)
    await write(client, SMART_IDEN_UUID, [0x01], response=False, label="ID5 [01]→000a finalize")
    await asyncio.sleep(1.0)

    # Did the control service (00030000) appear once the camera accepted us?
    new = await snapshot(client, readable)
    diff_snapshots(snap, new, "after REGISTER"); snap = new
    if has_char(client, SMART_MODE_UUID):
        mode_uuid, shutter_uuid = SMART_MODE_UUID, SMART_SHUTTER_UUID
        log(f"{_ts()} control service 00030000 present → using furble UUIDs (mode 0xf307 / shutter 0xf311)")
    elif has_char(client, SMART_ALT_CTRL_UUID):
        mode_uuid = shutter_uuid = SMART_ALT_CTRL_UUID
        log(f"{_ts()} 00030000 absent → falling back to 00020002 for mode + shutter")
    else:
        log(f"{_ts()} no control characteristic found — re-dumping GATT and stopping")
        dump_gatt(client)
        return await _smart_teardown(subscribed, client)

    log(f"{_ts()} ── MODE → SHOOT ──")
    await write(client, mode_uuid, [SMART_MODE_SHOOT], response=False, label="MODE_SHOOT")
    await asyncio.sleep(1.0)
    new = await snapshot(client, readable); diff_snapshots(snap, new, "after MODE_SHOOT"); snap = new

    log(f"{_ts()} ── SHUTTER ──")
    await write(client, shutter_uuid, [0x00, 0x01], response=False, label="SHUTTER press [00,01]")
    await asyncio.sleep(0.6)
    await write(client, shutter_uuid, [0x00, 0x02], response=False, label="SHUTTER release [00,02]")
    await asyncio.sleep(1.5)
    new = await snapshot(client, readable); diff_snapshots(snap, new, "after SHUTTER")

    log(f"{_ts()} listening 3s for trailing indications…")
    await asyncio.sleep(3.0)
    await _smart_teardown(subscribed, client)


async def fire_sequence(client, mode, mode_name):
    log(f"using shutter mode = {mode_name} (0x{mode:02x})")
    await write(client, CONTROL_UUID, [mode | BTN_FOCUS],
                response=False, label="AF half-press")
    await asyncio.sleep(0.6)
    await write(client, CONTROL_UUID, [mode | RELEASE_NONE],
                response=False, label="AF release")
    await asyncio.sleep(1.0)
    log(">>> FIRING SHUTTER NOW <<<")
    await write(client, CONTROL_UUID, [mode | BTN_RELEASE],
                response=False, label="SHUTTER press")
    await asyncio.sleep(0.5)
    await write(client, CONTROL_UUID, [mode | RELEASE_NONE],
                response=False, label="SHUTTER release")
    await asyncio.sleep(1.5)


async def run(args):
    if args.list:
        cams = await scan_canon(args.scan_timeout)
        if not cams:
            log("no Canon cameras found.")
        else:
            log(f"found {len(cams)} Canon camera(s):")
            _log_canon_list(cams)
        return 0

    if args.mac:
        log(f"connecting to {args.mac} (scan skipped)…")
        target = args.mac
    else:
        # --smart needs a smartphone-mode body; BR-E1 modes need a remote-mode
        # body; --dump-only takes whatever single Canon camera is advertising.
        prefer = "smart" if args.smart else (None if args.dump_only else "brel")
        target = await find_camera(args.scan_timeout, prefer=prefer)
        if target is None:
            return 2

    mac = args.mac or getattr(target, "address", str(target))
    attempts = 3
    for attempt in range(1, attempts + 1):
        try:
            async with BleakClient(target, timeout=args.connect_timeout) as client:
                return await do_session(client, args)
        except BleakError as e:
            log(f"connect/discovery attempt {attempt}/{attempts} failed: "
                f"{type(e).__name__}: {e}")
            if "disconnect" in str(e).lower():
                log(f"  → the camera dropped the link during service discovery. "
                    f"Typical of a body in REMOTE/pairing mode, or a STALE BOND.")
                log(f"  → clear the bond and retry:  bluetoothctl remove {mac}")
            if attempt < attempts:
                log("  retrying in 2s…")
                await asyncio.sleep(2.0)
    log("giving up — could not connect + discover services.")
    return 3


async def do_session(client, args):
    log(f"connected: {client.address}")

    dump_gatt(client)
    if args.dump_only:
        return 0

    if args.smart:
        # Smartphone-mode (service 00010000) registration + fire, per
        # furble CanonEOSSmart — the likely EOS R-series path.
        await smart_sequence(client)
        log("done. Did the camera fire? (check the body / card)")
        return 0

    mode = MODE_DELAY if args.delay_mode else MODE_IMMEDIATE
    mode_name = "DELAY(2s)" if args.delay_mode else "IMMEDIATE"

    if args.probe:
        # Observe-everything mode: enumerate the LIVE gatt, subscribe to
        # every notify channel, and snapshot+diff every readable char at
        # each step (arm/AF/shutter). --poke adds blind trial-writes.
        await probe_sequence(client, mode, mode_name, args.poke)
    elif args.explore:
        # Reverse-engineering mode: read everything, subscribe to all
        # indicate channels, THEN arm + fire — so any handshake the
        # camera drives over the indicate channels shows up in the log.
        await read_all(client)
        subscribed = await subscribe_indicates(client)
        log("listening 2s for any spontaneous indications after subscribe…")
        await asyncio.sleep(2.0)

        log("── ARM ──")
        await write(client, PAIR_UUID,
                    [0x03] + list(DEVICE_NAME.encode("ascii")),
                    response=False, label="ARM pair-name")
        log("listening 2s for indications after ARM…")
        await asyncio.sleep(2.0)

        log("── FIRE ──")
        await fire_sequence(client, mode, mode_name)
        log("listening 3s for indications after FIRE…")
        await asyncio.sleep(3.0)

        for u in subscribed:
            try:
                await client.stop_notify(u)
            except Exception:
                pass
    else:
        # Plain mode: bare arm + fire (the old-DSLR BR-E1 subset).
        await write(client, PAIR_UUID,
                    [0x03] + list(DEVICE_NAME.encode("ascii")),
                    response=False, label="ARM pair-name")
        await asyncio.sleep(0.3)
        await fire_sequence(client, mode, mode_name)

    log("done. Did the camera fire? (check the body / card)")
    return 0


def main():
    p = argparse.ArgumentParser(description="Canon BR-E1 BLE shutter test")
    p.add_argument("--mac", help="camera MAC; skip scanning")
    p.add_argument("--scan-timeout", type=float, default=15.0)
    p.add_argument("--connect-timeout", type=float, default=20.0)
    p.add_argument("--list", action="store_true",
                   help="scan and list every Canon camera in range (MAC + name + "
                        "mode: SMARTPHONE/REMOTE), then exit. Use to find a body's "
                        "MAC for --mac when two cameras are around")
    p.add_argument("--dump-only", action="store_true",
                   help="print the GATT table and exit (no firing)")
    p.add_argument("--explore", action="store_true",
                   help="reverse-engineering mode: read all chars + subscribe "
                        "to every indicate channel, then arm + fire, logging "
                        "everything the camera sends back")
    p.add_argument("--probe", action="store_true",
                   help="observe-everything mode: enumerate the LIVE gatt, "
                        "subscribe to every notify channel, snapshot+diff every "
                        "readable char before/after arm/AF/shutter (timestamped). "
                        "Non-destructive — best first experiment for the R-series")
    p.add_argument("--poke", action="store_true",
                   help="EXPERIMENTAL (use with --probe): after the observe pass, "
                        "blind-write candidate handshake payloads to the extra "
                        "writable chars and retry the shutter. Trial-and-error — "
                        "may leave the body in an odd state (power-cycle to fix)")
    p.add_argument("--smart", action="store_true",
                   help="SMARTPHONE-MODE protocol (service 00010000): bond, run "
                        "furble's identify handshake, wait for you to ACCEPT the "
                        "pairing on the camera, switch to shoot mode, then fire. "
                        "The likely EOS R-series path. Set camera BT to 'Connect "
                        "to smartphone' / register-a-device first")
    p.add_argument("--delay-mode", action="store_true",
                   help="use the 2-second-timer mode bit instead of immediate")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
