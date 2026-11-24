#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Assert the S3 Jack Hat's connections against the exported KiCad netlist.

Written for Pulsar. Usage (from hardware/s3-jack-hat/):
    kicad-cli sch export netlist --format kicadsexpr -o out/s3-jack-hat.net s3-jack-hat.kicad_sch
    python3 tools/check_netlist.py out/s3-jack-hat.net
Exit 0 = every check passed; 1 = at least one failed (each is printed).

Checks are written against reference/pin, not net names, so a renamed label
cannot make one pass by accident. Hat2 signals are looked up through
hat2.hat_to_pad, the same map the generators use.
"""
import sys

from hat2 import HAT2, SIGNAL_PIN, hat_to_pad
from sexpr import find, find_all, parse

FAILS = []


def load(path):
    tree = parse(open(path).read())[0]
    pin_net, nets = {}, {}
    for net in find_all(find(tree, "nets"), "net"):
        name = str(find(net, "name")[1])
        nodes = set()
        for node in find_all(net, "node"):
            key = (str(find(node, "ref")[1]), str(find(node, "pin")[1]))
            nodes.add(key)
            pin_net[key] = name
        nets[name] = nodes
    comps = {}
    for c in find_all(find(tree, "components"), "comp"):
        fields = {}
        fl = find(c, "fields")
        for f in find_all(fl, "field") if fl else []:
            fields[str(find(f, "name")[1])] = str(f[2]) if len(f) > 2 else ""
        comps[str(find(c, "ref")[1])] = (str(find(c, "value")[1]), fields)
    return pin_net, nets, comps


def check(cond, msg):
    print(("PASS  " if cond else "FAIL  ") + msg)
    if not cond:
        FAILS.append(msg)


def main(path):
    pin_net, nets, comps = load(path)

    def net(ref, pin):
        return pin_net.get((ref, pin))

    def nodes(ref, pin):
        n = net(ref, pin)
        return nets.get(n, set()) if n else set()

    def same(a, b):
        return net(*a) is not None and net(*a) == net(*b)

    def hat(sig):
        return ("J1", str(hat_to_pad(SIGNAL_PIN[sig])))

    gnd, g5, g6 = hat("GND"), hat("G5"), hat("G6")
    check(len(nodes(*gnd)) == 5, f"Hat GND ({gnd[1]}) net = J1 GND + 2 pull-downs + 2 LED cathodes: {sorted(nodes(*gnd))}")

    # Shutter: G5 -> R1 330R -> U1 anode; R2 100k to GND; U1 cathode to GND
    for (sig, hp, rs, rp, u, what) in [("G5", g5, "R1", "R2", "U1", "shutter"),
                                       ("G6", g6, "R3", "R4", "U2", "focus")]:
        check(comps.get(rs, ("",))[0] == "330", f"{rs} is 330 R")
        check(comps.get(rp, ("",))[0] == "100k", f"{rp} is 100k")
        check(same(hp, (rs, "1")), f"{sig} (J1.{hp[1]}) reaches the {what} LED resistor {rs}")
        check(same((rs, "2"), (u, "1")), f"{rs} drives the {what} opto {u} anode (pin 1)")
        check(nodes(rs, "2") == {(rs, "2"), (u, "1")}, f"{u} anode net holds only {rs}.2 and {u}.1")
        check(same(hp, (rp, "1")) and same((rp, "2"), gnd), f"{sig} has the 100k pull-down {rp} to GND")
        check(same((u, "2"), gnd), f"{u} cathode (pin 2) on GND")
        check(nodes(*hp) == {hp, (rs, "1"), (rp, "1")}, f"{sig} net holds only J1.{hp[1]}, {rs}.1, {rp}.1")

    # Camera side
    check(same(("U1", "4"), ("J2", "6")), "Shutter collector U1.4 -> jack TIP (J2.6)")
    check(same(("U2", "4"), ("J2", "4")), "Focus collector U2.4 -> jack RING (J2.4)")
    check(same(("U1", "3"), ("J2", "2")) and same(("U2", "3"), ("J2", "2")), "Both emitters -> jack SLEEVE (J2.2)")
    check(same(("J2", "2"), ("J2", "3")), "Jack pins 2 and 3 tied (sleeve)")
    check(nodes("J2", "2") == {("U1", "3"), ("U2", "3"), ("J2", "2"), ("J2", "3")},
          f"Sleeve net holds only the emitters and J2.2/3: {sorted(nodes('J2', '2'))}")
    check(not same(("J2", "2"), gnd), "Sleeve is NOT board GND")
    check(nodes("J2", "6") == {("U1", "4"), ("J2", "6")}, "TIP net holds only U1.4 and J2.6")
    check(nodes("J2", "4") == {("U2", "4"), ("J2", "4")}, "RING net holds only U2.4 and J2.4")
    for p in ("5", "7"):
        check(len(nodes("J2", p)) <= 1, f"Jack switch contact J2.{p} unconnected")

    # Every other Hat2 pin unconnected
    for hp_num, sig in sorted(HAT2.items()):
        if sig in ("GND", "G5", "G6"):
            continue
        pad = str(hat_to_pad(hp_num))
        check(len(nodes("J1", pad)) <= 1, f"Hat {sig} (M5 pin {hp_num}, J1 pad {pad}) not connected")

    # LCSC + MPN on every part
    for ref, (val, f) in sorted(comps.items()):
        check(f.get("LCSC", "").startswith("C") and f.get("MPN"), f"{ref} has LCSC ({f.get('LCSC')}) and MPN ({f.get('MPN')})")

    print()
    if FAILS:
        print(f"{len(FAILS)} check(s) FAILED")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "out/s3-jack-hat.net"))
