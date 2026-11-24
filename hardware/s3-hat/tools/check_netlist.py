#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Assert the S3 Hat's key connections against the exported KiCad netlist.

Written for Pulsar. Usage (from hardware/s3-hat/):
    kicad-cli sch export netlist --format kicadsexpr -o out/s3-hat.net s3-hat.kicad_sch
    python3 tools/check_netlist.py out/s3-hat.net
Exit 0 = every check passed; 1 = at least one failed (each is printed).

The checks are written against reference/pin, not against net names, so a
renamed label cannot make one pass by accident.
"""
import sys

from sexpr import find, find_all, parse

FAILS = []


def load(path):
    tree = parse(open(path).read())[0]
    pin_net = {}
    nets = {}
    for net in find_all(find(tree, "nets"), "net"):
        name = str(find(net, "name")[1])
        nodes = set()
        for node in find_all(net, "node"):
            key = (str(find(node, "ref")[1]), str(find(node, "pin")[1]))
            nodes.add(key)
            pin_net[key] = name
        nets[name] = nodes
    refs = {str(find(c, "ref")[1]) for c in find_all(find(tree, "components"), "comp")}
    return pin_net, nets, refs


def check(cond, msg):
    print(("PASS  " if cond else "FAIL  ") + msg)
    if not cond:
        FAILS.append(msg)


def main(path):
    pin_net, nets, refs = load(path)

    def net(ref, pin):
        return pin_net.get((ref, pin))

    def nodes(ref, pin):
        n = net(ref, pin)
        return nets.get(n, set()) if n else set()

    def same(a, b):
        return net(*a) is not None and net(*a) == net(*b)

    def other(ref, pin):
        """The far pin of a two-pin part."""
        return (ref, "2" if pin == "1" else "1")

    def unconnected(ref, pin):
        return len(nodes(ref, pin)) <= 1

    gnd = ("J5", "1")  # Hat GND
    check(len(nodes(*gnd)) > 20, "J5.1 (Hat GND) is the board ground net")

    # Shutter: G5 = J5.2 -> R19 330R -> U6 anode; R18 100k pull-down
    check(same(("J5", "2"), ("R19", "1")) or same(("J5", "2"), ("R19", "2")), "G5 (J5.2) reaches the shutter LED resistor R19")
    r19far = other("R19", "1") if same(("J5", "2"), ("R19", "1")) else other("R19", "2")
    check(same(r19far, ("U6", "1")), "R19 drives the shutter opto U6 anode (pin 1)")
    check(same(("J5", "2"), ("R18", "1")) and same(("R18", "2"), gnd), "G5 has the 100k pull-down R18 to GND")
    check(same(("U6", "2"), gnd), "U6 cathode on GND")

    # Focus: G6 = J5.6 -> R21 -> U7 anode; R20 pull-down
    check(same(("J5", "6"), ("R21", "1")), "G6 (J5.6) reaches the focus LED resistor R21")
    check(same(("R21", "2"), ("U7", "1")), "R21 drives the focus opto U7 anode (pin 1)")
    check(same(("J5", "6"), ("R20", "1")) and same(("R20", "2"), gnd), "G6 has the 100k pull-down R20 to GND")
    check(same(("U7", "2"), gnd), "U7 cathode on GND")

    # Camera VBUS enable: G7 = J5.8 -> SY6280 EN (U4.4), 100k pull-down R12
    check(same(("J5", "8"), ("U4", "4")), "G7 (J5.8) drives SY6280 EN (U4.4)")
    check(same(("J5", "8"), ("R12", "1")) and same(("R12", "2"), gnd), "SY6280 EN has the 100k pull-down R12")

    # Battery ADC: G4 = J5.4 = divider midpoint
    check(same(("J5", "4"), ("R16", "2")) and same(("J5", "4"), ("R17", "1")), "G4 (J5.4) is the R16/R17 divider midpoint")
    check(same(("R16", "1"), ("J2", "1")), "Divider top R16 sits on the protected cell + (J2.1 / VBAT)")
    check(same(("R17", "2"), gnd), "Divider bottom R17 to GND")
    check(same(("J5", "4"), ("C10", "1")) and same(("C10", "2"), gnd), "100 nF C10 at the ADC pin")

    # 5V0 -> stick 5V_IN (J5.15), from the boost VOUT (U3.6), into SY6280 IN
    check(same(("J5", "15"), ("U3", "6")), "Boost VOUT (U3.6) feeds stick 5V_IN (J5.15)")
    check(same(("U3", "6"), ("U4", "5")), "Boost VOUT feeds SY6280 IN (U4.5)")

    # Hat pins that must stay open
    for pin, name in [("3", "EXT_5V"), ("5", "G0"), ("10", "G43"), ("11", "BAT"),
                      ("12", "G44"), ("13", "3V3_L2"), ("16", "G3")]:
        check(unconnected("J5", pin), f"Hat {name} (J5.{pin}) not connected")
    for pin, name in [("7", "G1"), ("14", "G2"), ("9", "G8")]:
        check(any(r == "J7" for r, _ in nodes("J5", pin)), f"Spare {name} (J5.{pin}) goes only to the expansion pads J7")

    # Stick-side USB-C J4: data + GND only
    # The symbol stacks the four VBUS pins, so they share one net; it must hold
    # nothing but those four pins.
    vbus4 = {("J4", p) for p in ("A4", "A9", "B4", "B9")}
    for pin in ("A4", "A9", "B4", "B9"):
        check(nodes("J4", pin) <= vbus4, f"Stick USB-C VBUS {pin} reaches nothing but J4's own VBUS pins")
    for pin in ("A5", "B5"):
        check(unconnected("J4", pin), f"Stick USB-C CC {pin} not connected")
    check(same(("J4", "A6"), ("J3", "A6")) and same(("J4", "B6"), ("J3", "A6")), "D+ joins both receptacles, A6 and B6")
    check(same(("J4", "A7"), ("J3", "A7")) and same(("J4", "B7"), ("J3", "A7")), "D- joins both receptacles, A7 and B7")
    check(not same(("J3", "A6"), ("J3", "A7")), "D+ and D- are different nets")
    check(same(("J4", "A1"), gnd), "Stick USB-C GND on board GND")

    # Camera-side USB-C J3: VBUS_CAM from SY6280 OUT, Rp 56k on each CC to VBUS_CAM
    check(same(("J3", "A4"), ("U4", "1")), "Camera USB-C VBUS comes from SY6280 OUT (U4.1)")
    check(not same(("J3", "A4"), ("J5", "15")), "Camera VBUS is not the raw 5V0 rail")
    check(same(("J3", "A5"), ("R13", "2")) and same(("R13", "1"), ("J3", "A4")), "Camera CC1 has Rp R13 to VBUS_CAM")
    check(same(("J3", "B5"), ("R14", "2")) and same(("R14", "1"), ("J3", "A4")), "Camera CC2 has Rp R14 to VBUS_CAM")
    check(same(("U5", "5"), ("J3", "A4")), "USBLC6 VBUS pin on VBUS_CAM")
    check(same(("U5", "1"), ("J3", "A6")) and same(("U5", "6"), ("J3", "A6")), "USBLC6 I/O1 (1/6) on D+")
    check(same(("U5", "3"), ("J3", "A7")) and same(("U5", "4"), ("J3", "A7")), "USBLC6 I/O2 (3/4) on D-")
    check(same(("U4", "3"), ("R11", "1")) and same(("R11", "2"), gnd), "SY6280 ISET has R_SET R11 to GND")

    # Trigger outputs and isolation
    check(same(("U6", "4"), ("J6", "6")), "Shutter opto collector (U6.4) -> jack TIP (J6.6)")
    check(same(("U7", "4"), ("J6", "4")), "Focus opto collector (U7.4) -> jack RING (J6.4)")
    check(same(("U6", "3"), ("J6", "2")) and same(("U7", "3"), ("J6", "2")), "Both emitters -> jack SLEEVE (J6.2)")
    camgnd = nodes("J6", "2")
    check(camgnd == {("U6", "3"), ("U7", "3"), ("J6", "2"), ("J6", "3")},
          f"Jack sleeve net holds only the two emitters and the jack: {sorted(camgnd)}")
    check(not same(("J6", "2"), gnd), "Jack sleeve is NOT board GND")
    check(nodes("J6", "6") == {("U6", "4"), ("J6", "6")}, "Jack TIP net is isolated (only U6.4)")
    check(nodes("J6", "4") == {("U7", "4"), ("J6", "4")}, "Jack RING net is isolated (only U7.4)")

    # Power path
    check(same(("U1", "5"), ("J2", "1")), "TP4056 BAT on cell + (J2.1)")
    check(same(("U1", "4"), ("J1", "A9")), "TP4056 VCC on USB power-in VBUS")
    check(same(("U1", "2"), ("R3", "1")) and same(("R3", "2"), gnd), "TP4056 PROG has R_PROG R3 to GND")
    check(same(("Q1", "1"), ("J2", "2")) and same(("Q1", "3"), gnd), "FS8205A between cell - (S1) and P-/GND (S2)")
    check(same(("U2", "1"), ("Q1", "6")) and same(("U2", "3"), ("Q1", "4")), "DW01A OD -> G1, OC -> G2")
    check(same(("U2", "6"), ("J2", "2")), "DW01A GND on cell -")
    check(same(("Q2", "1"), ("J1", "A9")) and same(("Q2", "3"), ("J2", "1")), "Load-share P-FET: gate on VBUS_IN, drain on VBAT")
    check(same(("Q2", "2"), ("D3", "1")) and same(("D3", "2"), ("J1", "A9")), "Schottky VBUS_IN -> VSYS = P-FET source")
    check(same(("SW1", "1"), ("Q2", "2")) and same(("SW1", "2"), ("U3", "3")), "Switch between VSYS and boost VIN")
    check(same(("U3", "2"), ("U3", "3")), "Boost EN tied to its VIN")
    check(same(("J1", "A5"), ("R1", "1")) and same(("R1", "2"), gnd), "Power-in CC1 Rd 5.1k")
    check(same(("J1", "B5"), ("R2", "1")) and same(("R2", "2"), gnd), "Power-in CC2 Rd 5.1k")

    print()
    if FAILS:
        print(f"{len(FAILS)} check(s) FAILED")
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "out/s3-hat.net"))
