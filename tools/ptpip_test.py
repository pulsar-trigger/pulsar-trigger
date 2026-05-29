#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""
PTP/IP test client for Canon EOS over Wi-Fi — dependency-free (sockets + struct).

Purpose: validate that a Canon body (esp. the EOS R, which has no BLE shutter
and no CCAPI) can be driven over Wi-Fi with the SAME PTP operations Pulsar
already speaks over USB — and capture the exact PTP/IP init + Canon handshake
so it can be ported into an Android Wi-Fi transport that reuses PtpClient's
operation layer. See docs/ptp.md and docs/canon-ble-research.md.

What it does:
  1. Opens the PTP/IP command + event TCP sockets to the camera and runs the
     PTP/IP init handshake (Init Command Request/Ack, Init Event Request/Ack).
     On a NEW device the camera shows an "allow this device?" prompt — confirm
     it on the body (like the BLE pairing accept).
  2. OpenSession, GetDeviceInfo (dumps model + supported ops + vendor).
  3. Canon EOS PC-remote init (SetRemoteMode/EventMode) if it's a Canon body.
  4. Fires a shot: plain InitiateCapture, or Canon RemoteReleaseOn/Off (--bulb).

Setup on the camera (EOS R):
  Wireless → Wi-Fi → "Remote control (EOS Utility)" (or "Connect to smartphone")
  → register/add a device → leave it waiting. Join the camera's Wi-Fi AP from
  this laptop. The camera is usually the default gateway (the tool auto-detects
  it) or pass --ip explicitly.

Usage:
    python3 ptpip_test.py                 # auto-detect camera IP (gateway), info + capture
    python3 ptpip_test.py --ip 192.168.1.1
    python3 ptpip_test.py --info-only     # init + GetDeviceInfo, no capture
    python3 ptpip_test.py --bulb 4        # Canon bulb: 4-second exposure
    python3 ptpip_test.py --name Pulsar --port 15740
"""

import argparse
import concurrent.futures
import socket
import struct
import sys
import time
import uuid

PTPIP_PORT = 15740

# Ports worth probing on a Canon body: standard PTP/IP, Canon's WiFi discovery /
# control range (8610-8620), HTTP-ish (CCAPI/web service), and the dynamic range
# Canon control sockets are often assigned from.
CANDIDATE_PORTS = sorted(set(
    [80, 443, 8080, 8000, 15740, 5050, 5051, 21, 23]
    + list(range(8600, 8625))
    + list(range(49152, 49175))
))

# ── PTP/IP packet types (ISO 15740 / PTP-over-TCP-IP) ──────────────────
PKT_INIT_CMD_REQ   = 1
PKT_INIT_CMD_ACK   = 2
PKT_INIT_EVENT_REQ = 3
PKT_INIT_EVENT_ACK = 4
PKT_INIT_FAIL      = 5
PKT_OP_REQUEST     = 6
PKT_OP_RESPONSE    = 7
PKT_EVENT          = 8
PKT_START_DATA     = 9
PKT_DATA           = 10
PKT_CANCEL         = 11
PKT_END_DATA       = 12
PKT_PING           = 13
PKT_PONG           = 14

# dataPhaseInfo in an Operation Request
DPH_NO_DATA_OR_IN = 1   # no data phase, or data flows responder→initiator
DPH_DATA_OUT      = 2   # initiator→responder data phase

# ── PTP ops / props / rc (mirror PtpClient.kt) ─────────────────────────
OP_GET_DEVICE_INFO          = 0x1001
OP_OPEN_SESSION             = 0x1002
OP_CLOSE_SESSION            = 0x1003
OP_INITIATE_CAPTURE         = 0x100E
OP_GET_DEVICE_PROP_VALUE    = 0x1015
OP_CANON_SET_REMOTE_MODE    = 0x9114
OP_CANON_EVENT_MODE         = 0x9115
OP_CANON_GET_EVENT          = 0x9116
OP_CANON_REMOTE_RELEASE_ON  = 0x9128
OP_CANON_REMOTE_RELEASE_OFF = 0x9129
RC_OK = 0x2001
VENDOR_EXT_CANON_EOS = 11

PROTOCOL_VERSION = 0x00010000


def log(msg):
    print(f"  {msg}", flush=True)


def default_gateway():
    """Parse /proc/net/route for the default-gateway IP (the camera, when it's
    the Wi-Fi AP). Returns dotted string or None."""
    try:
        with open("/proc/net/route") as f:
            for line in f.readlines()[1:]:
                p = line.split()
                if len(p) > 2 and p[1] == "00000000":  # destination 0.0.0.0
                    g = int(p[2], 16)
                    return socket.inet_ntoa(struct.pack("<L", g))
    except OSError:
        pass
    return None


def local_ip_for(target):
    """The laptop's own IP on the route toward `target` (no packets sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((target or "8.8.8.8", 1))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def scan_tcp(ip, ports, timeout=0.6, workers=120):
    """Probe a list of TCP ports on ip; return the open ones."""
    def probe(port):
        try:
            with socket.create_connection((ip, port), timeout=timeout):
                return port
        except OSError:
            return None
    found = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as ex:
        for r in ex.map(probe, ports):
            if r is not None:
                found.append(r)
    return sorted(found)


def probe_udp_discovery(ip, port=8612, timeout=2.0):
    """Canon WiFi devices answer a discovery datagram on UDP 8612. Send a few
    probe payloads and report any reply — tells us whether the camera is
    waiting on the UDP discovery handshake before it opens its control port."""
    probes = [
        b"\x00",                       # minimal poke
        struct.pack("<I", 0),          # 4-byte zero
        b"CANON\x00",                  # vendor-ish marker
    ]
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(timeout)
    got = None
    try:
        for p in probes:
            try:
                s.sendto(p, (ip, port))
                data, addr = s.recvfrom(2048)
                got = (len(p), data)
                log(f"  UDP {port}: reply to {len(p)}-byte probe from {addr}: {data[:64].hex()}")
                break
            except socket.timeout:
                continue
            except OSError as e:
                log(f"  UDP {port}: {e} (laptop not on the camera's network?)")
                break
    finally:
        s.close()
    if got is None:
        log(f"  UDP {port}: no reply to discovery probes")
    return got


def utf16(s):
    """PTP/IP friendly-name: UTF-16LE, NUL-terminated."""
    return s.encode("utf-16-le") + b"\x00\x00"


def read_utf16(buf, off):
    """Read a NUL-terminated UTF-16LE string from buf at off; return (str, newoff)."""
    end = off
    while end + 1 < len(buf) and not (buf[end] == 0 and buf[end + 1] == 0):
        end += 2
    s = buf[off:end].decode("utf-16-le", "replace")
    return s, end + 2


class PtpIp:
    def __init__(self, ip, port, name, timeout=10.0):
        self.ip, self.port, self.name = ip, port, name
        self.timeout = timeout
        self.cmd = None
        self.evt = None
        self.connection_number = 0
        self.transaction_id = 0
        self.session_id = 0

    # ── packet framing ────────────────────────────────────────────────
    @staticmethod
    def _send_packet(sock, ptype, payload):
        length = 8 + len(payload)
        sock.sendall(struct.pack("<II", length, ptype) + payload)

    @staticmethod
    def _recv_exact(sock, n):
        out = b""
        while len(out) < n:
            chunk = sock.recv(n - len(out))
            if not chunk:
                raise ConnectionError("socket closed mid-packet")
            out += chunk
        return out

    def _recv_packet(self, sock):
        hdr = self._recv_exact(sock, 8)
        length, ptype = struct.unpack("<II", hdr)
        payload = self._recv_exact(sock, length - 8) if length > 8 else b""
        return ptype, payload

    # ── init handshake ────────────────────────────────────────────────
    def connect(self):
        guid = uuid.uuid4().bytes
        log(f"connecting command channel to {self.ip}:{self.port} …")
        self.cmd = socket.create_connection((self.ip, self.port), timeout=self.timeout)
        self.cmd.settimeout(self.timeout)

        log(f"→ Init Command Request (name='{self.name}', guid={guid.hex()})")
        self._send_packet(self.cmd, PKT_INIT_CMD_REQ,
                          guid + utf16(self.name) + struct.pack("<I", PROTOCOL_VERSION))
        log("   (if the camera shows an 'allow device?' prompt, CONFIRM IT NOW)")
        ptype, payload = self._recv_packet(self.cmd)
        if ptype == PKT_INIT_FAIL:
            reason = struct.unpack("<I", payload[:4])[0] if len(payload) >= 4 else -1
            log(f"✗ Init FAILED (reason=0x{reason:08x}). Device not registered / rejected?")
            return False
        if ptype != PKT_INIT_CMD_ACK:
            log(f"✗ unexpected packet type {ptype} (expected Init Command Ack)")
            return False
        self.connection_number = struct.unpack("<I", payload[:4])[0]
        resp_guid = payload[4:20].hex()
        resp_name, _ = read_utf16(payload, 20)
        log(f"✓ Init Command Ack: connNo={self.connection_number} "
            f"responder='{resp_name}' guid={resp_guid}")

        log("connecting event channel …")
        self.evt = socket.create_connection((self.ip, self.port), timeout=self.timeout)
        self.evt.settimeout(self.timeout)
        self._send_packet(self.evt, PKT_INIT_EVENT_REQ,
                          struct.pack("<I", self.connection_number))
        ptype, _ = self._recv_packet(self.evt)
        if ptype != PKT_INIT_EVENT_ACK:
            log(f"✗ event-channel init failed (got type {ptype})")
            return False
        log("✓ Init Event Ack — PTP/IP link up")
        return True

    # ── transactions ──────────────────────────────────────────────────
    def transact(self, opcode, params=(), data_out=None, expect_data_in=False, label=None):
        self.transaction_id += 1
        txid = self.transaction_id
        dph = DPH_DATA_OUT if data_out is not None else DPH_NO_DATA_OR_IN
        body = struct.pack("<IHI", dph, opcode, txid) + b"".join(struct.pack("<I", p) for p in params)
        self._send_packet(self.cmd, PKT_OP_REQUEST, body)

        if data_out is not None:
            self._send_packet(self.cmd, PKT_START_DATA,
                              struct.pack("<IQ", txid, len(data_out)))
            self._send_packet(self.cmd, PKT_END_DATA, struct.pack("<I", txid) + data_out)

        data = bytearray()
        rc = None
        rparams = []
        while True:
            ptype, payload = self._recv_packet(self.cmd)
            if ptype == PKT_START_DATA:
                pass  # txid(4) + totalLen(8) — we just accumulate Data/EndData
            elif ptype == PKT_DATA:
                data += payload[4:]            # skip txid
            elif ptype == PKT_END_DATA:
                data += payload[4:]
            elif ptype == PKT_OP_RESPONSE:
                rc = struct.unpack("<H", payload[:2])[0]
                # payload: rc(2) txid(4) params...
                nparams = max(0, (len(payload) - 6) // 4)
                rparams = list(struct.unpack("<%dI" % nparams, payload[6:6 + nparams * 4])) if nparams else []
                break
            elif ptype == PKT_EVENT:
                continue
            else:
                log(f"   (unexpected packet type {ptype} during transact)")
        tag = label or f"op 0x{opcode:04x}"
        log(f"  {tag}: rc=0x{rc:04x} params={[hex(p) for p in rparams]} data={len(data)}B")
        return rc, rparams, bytes(data)

    def open_session(self, sid=1):
        rc, _, _ = self.transact(OP_OPEN_SESSION, (sid,), label="OpenSession")
        if rc == RC_OK:
            self.session_id = sid
        return rc == RC_OK

    def get_device_info(self):
        rc, _, data = self.transact(OP_GET_DEVICE_INFO, (), expect_data_in=True, label="GetDeviceInfo")
        if rc != RC_OK or not data:
            return None
        return parse_device_info(data)

    def close(self):
        for s in (self.evt, self.cmd):
            try:
                if s:
                    s.close()
            except OSError:
                pass


def _read_u16_array(buf, off):
    (n,) = struct.unpack_from("<I", buf, off); off += 4
    vals = list(struct.unpack_from("<%dH" % n, buf, off)); off += n * 2
    return vals, off


def _read_ptp_str(buf, off):
    units = buf[off]; off += 1
    if units == 0:
        return "", off
    raw = buf[off:off + units * 2]; off += units * 2
    return raw.decode("utf-16-le", "replace").rstrip("\x00 "), off


def parse_device_info(data):
    """PIMA 15740 §5.5.1 — subset, matching PtpClient.parseDeviceInfo."""
    off = 0
    (std_ver,) = struct.unpack_from("<H", data, off); off += 2
    (vendor_id,) = struct.unpack_from("<I", data, off); off += 4
    (vendor_ver,) = struct.unpack_from("<H", data, off); off += 2
    vendor_desc, off = _read_ptp_str(data, off)
    off += 2  # FunctionalMode
    ops, off = _read_u16_array(data, off)
    events, off = _read_u16_array(data, off)
    props, off = _read_u16_array(data, off)
    _, off = _read_u16_array(data, off)  # capture formats
    _, off = _read_u16_array(data, off)  # image formats
    manuf, off = _read_ptp_str(data, off)
    model, off = _read_ptp_str(data, off)
    dev_ver, off = _read_ptp_str(data, off)
    serial, off = _read_ptp_str(data, off)
    return dict(std_ver=std_ver, vendor_id=vendor_id, vendor_desc=vendor_desc,
                ops=ops, model=model, manuf=manuf, dev_ver=dev_ver, serial=serial)


def run(args):
    ip = args.ip or default_gateway()
    if not ip:
        log("no --ip given and couldn't detect the default gateway — pass --ip <camera>")
        return 2

    if args.scan:
        gw = default_gateway()
        me = local_ip_for(gw)
        log(f"laptop IP   : {me}")
        log(f"gateway     : {gw}   (this should be the camera in AP mode)")
        if me and gw and me == gw:
            log("⚠ laptop IP == gateway — that's wrong; the camera is elsewhere.")
        # Build the target list: explicit --ip, the gateway, and .1/.2 of the
        # subnet (a common Canon AP address is x.x.x.1, client gets .2).
        targets = []
        for t in (args.ip, gw):
            if t and t not in targets and t != me:
                targets.append(t)
        if me:
            base = me.rsplit(".", 1)[0]
            for last in ("1", "2"):
                cand = f"{base}.{last}"
                if cand != me and cand not in targets:
                    targets.append(cand)
        ports = list(range(1, 65536)) if args.full else CANDIDATE_PORTS
        log(f"scanning {len(targets)} host(s) × {len(ports)} ports "
            f"({'FULL 1-65535' if args.full else 'candidate set'})…")
        for t in targets:
            openp = scan_tcp(t, ports, timeout=0.3, workers=400)
            log(f"  {t:>15} open TCP: {openp or '(none)'}")
        log("probing Canon UDP discovery on the gateway (8612)…")
        probe_udp_discovery(gw or ip)
        log("→ report the laptop IP, gateway, and any open TCP port.")
        return 0

    c = PtpIp(ip, args.port, args.name)
    if not c.connect():
        return 3
    try:
        if not c.open_session():
            log("OpenSession failed — aborting")
            return 4
        info = c.get_device_info()
        if info:
            log(f"── DeviceInfo ──")
            log(f"  model        : {info['model']}")
            log(f"  manufacturer : {info['manuf']}")
            log(f"  device ver   : {info['dev_ver']}")
            log(f"  vendor ext   : {info['vendor_id']} ({info['vendor_desc']})")
            log(f"  serial       : {info['serial']}")
            log(f"  supported ops: {', '.join('0x%04x' % o for o in info['ops'])}")
            is_canon = info['vendor_id'] == VENDOR_EXT_CANON_EOS
        else:
            is_canon = True

        if args.info_only:
            return 0

        if is_canon:
            # Canon Wi-Fi PC-remote init — values confirmed on an EOS R by
            # featherbear/eos-ptp. NB SetRemoteMode=2 over Wi-Fi (USB uses 1).
            log("── Canon EOS PC-remote init (Wi-Fi) ──")
            c.transact(OP_CANON_SET_REMOTE_MODE, (2,), label="Canon SetRemoteMode(2)")
            c.transact(OP_CANON_EVENT_MODE, (1,), label="Canon EventMode(1)")
            rc, _, data = c.transact(OP_CANON_GET_EVENT, (3,), expect_data_in=True,
                                     label="Canon GetEvent(3)")
            if rc == RC_OK and data:
                log(f"  GetEvent → {len(data)}B of camera state — control channel is LIVE")

        if args.bulb is not None:
            log(f">>> Canon bulb {args.bulb}s — RemoteReleaseOn(3) → wait → Off(3) <<<")
            c.transact(OP_CANON_REMOTE_RELEASE_ON, (3,), label="RemoteReleaseOn(3)")
            time.sleep(args.bulb)
            c.transact(OP_CANON_REMOTE_RELEASE_OFF, (3,), label="RemoteReleaseOff(3)")
        else:
            log(">>> InitiateCapture (single shot) <<<")
            rc, _, _ = c.transact(OP_INITIATE_CAPTURE, (0, 0), label="InitiateCapture")
            if rc != RC_OK and is_canon:
                log("InitiateCapture not OK — trying Canon RemoteReleaseOn/Off …")
                c.transact(OP_CANON_REMOTE_RELEASE_ON, (3,), label="RemoteReleaseOn(3)")
                time.sleep(0.3)
                c.transact(OP_CANON_REMOTE_RELEASE_OFF, (3,), label="RemoteReleaseOff(3)")

        log("done. Did the camera fire? (check the body / card)")
        return 0
    finally:
        c.close()


def main():
    p = argparse.ArgumentParser(description="PTP/IP test client for Canon EOS over Wi-Fi")
    p.add_argument("--ip", help="camera IP (default: detect the Wi-Fi default gateway)")
    p.add_argument("--port", type=int, default=PTPIP_PORT)
    p.add_argument("--name", default="Pulsar", help="friendly name shown on the camera")
    p.add_argument("--scan", action="store_true",
                   help="print network topology + scan the camera for open TCP ports + "
                        "probe UDP 8612 discovery, then exit. Add --full for a 1-65535 sweep.")
    p.add_argument("--full", action="store_true",
                   help="with --scan: sweep all 65535 TCP ports (slower) instead of the candidate set")
    p.add_argument("--info-only", action="store_true",
                   help="init + GetDeviceInfo only, don't fire")
    p.add_argument("--bulb", type=float, metavar="SECONDS",
                   help="bulb exposure of N seconds (Canon RemoteReleaseOn/Off) instead of single shot")
    try:
        return run(p.parse_args())
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
