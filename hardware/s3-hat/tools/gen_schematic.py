#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Generate the S3 Hat schematic (KiCad 10) from a netlist-style description.

Written for Pulsar. Usage:  python3 tools/gen_schematic.py   (from hardware/s3-hat/)

Writes s3-hat.kicad_sch and s3-hat.kicad_sym next to this script's parent
directory. Stock symbols are copied from /usr/share/kicad/symbols; the few
parts KiCad has no symbol for are drawn here as plain boxes (CUSTOM below)
and also written to the project library s3-hat.kicad_sym.

Every pin is wired by a short stub ending in a net label, so connectivity is
by name. Unused pins get a no-connect flag. The netlist check
(tools/check_netlist.py) asserts the important connections afterwards.
"""
import math
import os
import uuid as uuidlib

from sexpr import Q, dump, find, find_all, parse

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.dirname(HERE)
SYMDIR = "/usr/share/kicad/symbols"
PROJECT = "s3-hat"
NS = uuidlib.UUID("6d1f3c2e-5a43-4b8e-9b51-53334861743e")


def uid(*parts):
    return Q(str(uuidlib.uuid5(NS, "/".join(str(p) for p in parts))))


ROOT_UUID = uid("root")

# --------------------------------------------------------------------------
# Custom symbols (no stock KiCad symbol). Pins: (number, name, type, side, slot)
# side L/R/T/B; slot = position index along that side (2.54 mm pitch).
# --------------------------------------------------------------------------
CUSTOM = {
    "TPS61023": dict(
        ref="U", desc="TI TPS61023 boost converter, SOT-563 (DRL). Pinout: SLVSF14B section 5",
        ds="https://www.ti.com/lit/ds/symlink/tps61023.pdf", w=7.62,
        pins=[("3", "VIN", "power_in", "L", 0), ("2", "EN", "input", "L", 1),
              ("1", "FB", "input", "L", 2), ("5", "SW", "passive", "R", 0),
              ("6", "VOUT", "power_out", "R", 1), ("4", "GND", "power_in", "R", 2)]),
    "SY6280AAC": dict(
        ref="U", desc="Silergy SY6280AAC current-limited load switch, SOT-23-5. Ilim = 6800/Rset",
        ds="https://www.lcsc.com/datasheet/lcsc_datasheet_1810121532_Silergy-Corp-SY6280AAC_C55136.pdf", w=7.62,
        pins=[("5", "IN", "power_in", "L", 0), ("4", "EN", "input", "L", 1),
              ("3", "ISET", "passive", "L", 2), ("1", "OUT", "power_out", "R", 0),
              ("2", "GND", "power_in", "R", 2)]),
    "FS8205A": dict(
        ref="Q", desc="Dual N-MOSFET, common drain, SOT-23-6 (Tech Public FS8205A). "
                      "Pin numbers inferred from the datasheet's pin-1 drawing; verify",
        ds="https://www.lcsc.com/datasheet/lcsc_datasheet_2108132230_TECH-PUBLIC-FS8205A_C2830320.pdf", w=7.62,
        pins=[("1", "S1", "passive", "L", 0), ("6", "G1", "passive", "L", 1),
              ("2", "D12", "passive", "L", 2), ("3", "S2", "passive", "R", 0),
              ("4", "G2", "passive", "R", 1), ("5", "D12", "passive", "R", 2)]),
    "PJ-242": dict(
        ref="J", desc="2.5 mm SMD jack, Shou Han PJ-242. 2 = sleeve, 3 = sleeve-zone contact, "
                      "4 = ring (5 = its switch), 6 = tip (7 = its switch); read from the "
                      "drawing, confirm with a meter",
        ds="https://www.lcsc.com/datasheet/lcsc_datasheet_1912111437_SHOU-HAN-PJ-242_C431536.pdf", w=7.62,
        pins=[("6", "TIP", "passive", "L", 0), ("7", "TIP_SW", "passive", "L", 1),
              ("4", "RING", "passive", "L", 2), ("5", "RING_SW", "passive", "L", 3),
              ("2", "SLEEVE", "passive", "L", 4), ("3", "SLEEVE2", "passive", "L", 5)]),
}

# --------------------------------------------------------------------------
# Footprints and parts
# --------------------------------------------------------------------------
FP_R = "Resistor_SMD:R_0603_1608Metric"
FP_C0603 = "Capacitor_SMD:C_0603_1608Metric"
FP_C0805 = "Capacitor_SMD:C_0805_2012Metric"
CUSTOM_FP = "needs custom footprint"

# (lcsc, mpn, footprint)
R = {"5.1k": ("C23186", "0603WAF5101T5E"), "2.4k": ("C22940", "0603WAF2401T5E"),
     "1k": ("C21190", "0603WAF1001T5E"), "100": ("C22775", "0603WAF1000T5E"),
     "100k": ("C25803", "0603WAF1003T5E"), "13k": ("C22797", "0603WAF1302T5E"),
     "56k": ("C23206", "0603WAF5602T5E"), "330": ("C23138", "0603WAF3300T5E"),
     "220k": ("C22961", "0603WAF2203T5E"), "30k": ("C22984", "0603WAF3002T5E"),
     "1M": ("C22935", "0603WAF1004T5E")}
C = {"10uF": ("C15850", "CL21A106KAYNNNE", FP_C0805), "22uF": ("C45783", "CL21A226MAQNNNE", FP_C0805),
     "100nF": ("C14663", "CC0603KRX7R9BB104", FP_C0603), "4.7nF": ("C53987", "0603B472K500NT", FP_C0603)}


def res(ref, val, a, b, note=""):
    lc, mpn = R[val]
    return dict(ref=ref, lib="Device:R", value=val, fp=FP_R, lcsc=lc, mpn=mpn,
                nets={"1": a, "2": b}, note=note)


def cap(ref, val, a, b, note=""):
    lc, mpn, fp = C[val]
    return dict(ref=ref, lib="Device:C", value=val, fp=fp, lcsc=lc, mpn=mpn,
                nets={"1": a, "2": b}, note=note)


NC = None  # no-connect marker

BLOCKS = []


def block(title, parts):
    BLOCKS.append((title, parts))


block("USB-C power in (charging only)", [
    dict(ref="J1", lib="Connector:USB_C_Receptacle_PowerOnly_6P", value="TYPE-C 6P",
         fp=CUSTOM_FP, lcsc="C456012", mpn="TYPE-C 6P (Shou Han)",
         note="No stock footprint; draw from the Shou Han drawing (A5 CC1, A9/B9 VBUS, A12/B12 GND, B5 CC2)",
         nets={"A9": "VBUS_IN", "B9": "VBUS_IN", "A12": "GND", "B12": "GND",
               "A5": "CC1_IN", "B5": "CC2_IN", "SH": "GND"}),
    res("R1", "5.1k", "CC1_IN", "GND", "Rd: sink, 5 V default power"),
    res("R2", "5.1k", "CC2_IN", "GND", "Rd: sink, 5 V default power"),
])

block("Charger TP4056 (~460-500 mA)", [
    dict(ref="U1", lib="Battery_Management:TP4056-42-ESOP8", value="TP4056-42-ESOP8",
         fp="Package_SO:SOIC-8-1EP_3.9x4.9mm_P1.27mm_EP2.41x3.3mm_ThermalVias",
         lcsc="C16581", mpn="TP4056-42-ESOP8",
         note="Stock ESOP-8 footprint from the KiCad symbol; check the exposed-pad size against the TOPPOWER drawing",
         nets={"4": "VBUS_IN", "3": "GND", "9": "GND", "1": "GND", "8": "VBUS_IN",
               "2": "PROG", "5": "VBAT", "7": "CHRG_N", "6": "STDBY_N"}),
    res("R3", "2.4k", "PROG", "GND", "Ibat = 1100 x 1V / Rprog = ~460 mA (TP4056 datasheet)"),
    cap("C1", "10uF", "VBUS_IN", "GND", "TP4056 VCC bypass"),
    cap("C2", "10uF", "VBAT", "GND", "TP4056 BAT"),
    res("R4", "1k", "VBUS_IN", "LED_CHRG"),
    dict(ref="D1", lib="Device:LED", value="RED CHRG", fp="LED_SMD:LED_0603_1608Metric",
         lcsc="C2286", mpn="KT-0603R", nets={"2": "LED_CHRG", "1": "CHRG_N"},
         note="Lit while charging"),
    res("R5", "1k", "VBUS_IN", "LED_STDBY"),
    dict(ref="D2", lib="Device:LED", value="GREEN STDBY", fp="LED_SMD:LED_0805_2012Metric",
         lcsc="C2297", mpn="KT-0805G", nets={"2": "LED_STDBY", "1": "STDBY_N"},
         note="Lit when charge complete. 0805 because no Basic 0603 green is in stock"),
])

block("Cell + protection DW01A / FS8205A", [
    dict(ref="J2", lib="Connector_Generic:Conn_01x02", value="LiPo 1S (PH-2AWT)",
         fp="Connector_JST:JST_PH_S2B-PH-SM4-TB_1x02-1MP_P2.00mm_Horizontal",
         lcsc="C2763693", mpn="PH-2AWT",
         note="Pin 1 = cell +. Cell lead polarity is not standardised: mark + on silk. "
              "Stock JST SM4-TB footprint is a candidate; compare with the PH-2AWT drawing",
         nets={"1": "VBAT", "2": "BATN"}),
    dict(ref="U2", lib="Battery_Management:DW01A", value="DW01A",
         fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C351410", mpn="DW01A (PUOLOP)",
         nets={"5": "DW_VCC", "6": "BATN", "1": "PROT_OD", "3": "PROT_OC", "2": "PROT_CS"},
         note="TD (pin 4) left open, as in the datasheet typical circuit"),
    res("R6", "100", "VBAT", "DW_VCC", "DW01A R1"),
    cap("C3", "100nF", "DW_VCC", "BATN", "DW01A C1"),
    res("R7", "1k", "PROT_CS", "GND", "DW01A R2, VM to P-"),
    dict(ref="Q1", lib="s3-hat:FS8205A", value="FS8205A", fp="Package_TO_SOT_SMD:SOT-23-6",
         lcsc="C2830320", mpn="FS8205A (Tech Public)",
         note="M1 (S1/G1) = discharge FET on cell -, M2 (S2/G2) = charge FET on P- (board GND)",
         nets={"1": "BATN", "6": "PROT_OD", "2": "PROT_D", "5": "PROT_D",
               "4": "PROT_OC", "3": "GND"}),
])

block("Load sharing + power switch", [
    dict(ref="Q2", lib="Transistor_FET:AO3401A", value="AO3401A",
         fp="Package_TO_SOT_SMD:SOT-23", lcsc="C15127", mpn="AO3401A",
         note="Off while VBUS_IN is present; body diode points VBAT -> VSYS",
         nets={"1": "VBUS_IN", "3": "VBAT", "2": "VSYS"}),
    res("R8", "100k", "VBUS_IN", "GND", "Gate pull-down: FET on when USB is absent"),
    dict(ref="D3", lib="Device:D_Schottky", value="B5819W", fp="Diode_SMD:D_SOD-123",
         lcsc="C8598", mpn="B5819W SL", nets={"2": "VBUS_IN", "1": "VSYS"},
         note="Feeds the system from USB while the TP4056 charges"),
    dict(ref="SW1", lib="Switch:SW_SPDT", value="SS-12D10L5", fp=CUSTOM_FP,
         lcsc="C319012", mpn="SS-12D10L5",
         note="THT, 4.7 mm pitch, no stock footprint. Pin 2 = common. Hand-soldered",
         nets={"1": "VSYS", "2": "VSW", "3": NC}),
])

block("Boost TPS61023 -> 5V0", [
    dict(ref="U3", lib="s3-hat:TPS61023", value="TPS61023DRLR", fp="Package_TO_SOT_SMD:SOT-563",
         lcsc="C919459", mpn="TPS61023DRLR",
         note="Stock SOT-563 footprint; check against TI's DRL land pattern",
         nets={"3": "VSW", "2": "VSW", "1": "BOOST_FB", "5": "BOOST_SW",
               "6": "5V0", "4": "GND"}),
    dict(ref="L1", lib="Device:L", value="1uH", fp="Inductor_SMD:L_Changjiang_FNR4030S",
         lcsc="C167865", mpn="FNR4030S1R0MT", nets={"1": "VSW", "2": "BOOST_SW"},
         note="4.7 A / 5.7 A per LCSC; which is Isat is unconfirmed"),
    cap("C4", "10uF", "VSW", "GND", "TPS61023 C1 (TI fig 8-1: 10 uF)"),
    cap("C5", "22uF", "5V0", "GND", "TPS61023 C2 (TI fig 8-1: 2 x 22 uF)"),
    cap("C6", "22uF", "5V0", "GND", "TPS61023 C2 (TI fig 8-1: 2 x 22 uF)"),
    res("R9", "220k", "5V0", "BOOST_FB", "R1: Vout = 0.595 x (1 + 220k/30k) = 4.96 V PWM, 5.01 V PFM"),
    res("R10", "30k", "BOOST_FB", "GND", "R2"),
])

block("Camera VBUS switch SY6280 (EN = G7)", [
    dict(ref="U4", lib="s3-hat:SY6280AAC", value="SY6280AAC", fp="Package_TO_SOT_SMD:SOT-23-5",
         lcsc="C55136", mpn="SY6280AAC",
         nets={"5": "5V0", "4": "CAM_EN", "3": "SY_ISET", "1": "VBUS_CAM", "2": "GND"}),
    res("R11", "13k", "SY_ISET", "GND", "Ilim = 6800 / 13k = 0.52 A"),
    res("R12", "100k", "CAM_EN", "GND", "Camera port off until firmware drives G7 high"),
    cap("C7", "10uF", "5V0", "GND", "SY6280 IN (datasheet: 10 uF for hot-plug)"),
    cap("C8", "10uF", "VBUS_CAM", "GND", "SY6280 OUT (datasheet example 10 uF)"),
])

USB16 = "Connector:USB_C_Receptacle_USB2.0_16P"
USB16_FP = "Connector_USB:USB_C_Receptacle_HRO_TYPE-C-31-M-12"
USB16_NOTE = ("CANDIDATE footprint: HRO TYPE-C-31-M-12 matches the Shou Han drawing's pad pitch, "
              "peg (5.78) and slot (8.64, 4.18) positions, but the rear slots differ "
              "(drawing 1.40 drill / 1.80 pad vs 1.2 / 1.6): make a custom copy before layout")

block("USB-C to camera (source, default power)", [
    dict(ref="J3", lib=USB16, value="TYPE-C 16PIN 2MD(073)", fp=USB16_FP, lcsc="C2765186",
         mpn="TYPE-C 16PIN 2MD(073)", note=USB16_NOTE,
         nets={"A4": "VBUS_CAM", "A9": "VBUS_CAM", "B4": "VBUS_CAM", "B9": "VBUS_CAM",
               "A1": "GND", "A12": "GND", "B1": "GND", "B12": "GND",
               "A5": "CC1_CAM", "B5": "CC2_CAM", "A6": "USB_DP", "B6": "USB_DP",
               "A7": "USB_DN", "B7": "USB_DN", "A8": NC, "B8": NC, "SH": "SHIELD_CAM"}),
    res("R13", "56k", "VBUS_CAM", "CC1_CAM", "Rp default USB power"),
    res("R14", "56k", "VBUS_CAM", "CC2_CAM", "Rp default USB power"),
    res("R15", "1M", "SHIELD_CAM", "GND", "Shield bleed"),
    cap("C9", "4.7nF", "SHIELD_CAM", "GND", "Shield RF return"),
    dict(ref="U5", lib="Power_Protection:USBLC6-2SC6", value="USBLC6-2SC6",
         fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C2687116", mpn="USBLC6-2SC6 (UMW)",
         note="Place at J3. Flow-through: D+ on pins 1/6, D- on pins 3/4",
         nets={"1": "USB_DP", "6": "USB_DP", "3": "USB_DN", "4": "USB_DN",
               "2": "GND", "5": "VBUS_CAM"}),
])

block("USB-C to stick (data only)", [
    dict(ref="J4", lib=USB16, value="TYPE-C 16PIN 2MD(073)", fp=USB16_FP, lcsc="C2765186",
         mpn="TYPE-C 16PIN 2MD(073)", note=USB16_NOTE,
         nets={"A4": NC, "A9": NC, "B4": NC, "B9": NC,
               "A1": "GND", "A12": "GND", "B1": "GND", "B12": "GND",
               "A5": NC, "B5": NC, "A6": "USB_DP", "B6": "USB_DP",
               "A7": "USB_DN", "B7": "USB_DN", "A8": NC, "B8": NC, "SH": "GND"}),
])

block("Battery ADC (G4)", [
    res("R16", "100k", "VBAT", "BAT_ADC", "4.2 V cell -> 2.1 V"),
    res("R17", "100k", "BAT_ADC", "GND"),
    cap("C10", "100nF", "BAT_ADC", "GND", "At the ADC pin"),
])

block("Hat2-Bus header to M5StickS3", [
    dict(ref="J5", lib="Connector_Generic:Conn_02x08_Odd_Even", value="Hat2-Bus 2x8 R/A",
         fp="Connector_PinHeader_2.54mm:PinHeader_2x08_P2.54mm_Horizontal",
         lcsc="C54907672", mpn="PZ2.54-2x8PWZ-L18.8-A",
         note="Right-angle male. Odd pins: GND, EXT_5V, G0, G1, G8, BAT, 3V3_L2, 5V_IN. "
              "Even pins: G5, G4, G6, G7, G43, G44, G2, G3. Check pin-1 orientation against "
              "the stick lying flat before layout (a right-angle header can mirror the rows)",
         nets={"1": "GND", "2": "SHUTTER_G5", "3": NC, "4": "BAT_ADC", "5": NC,
               "6": "FOCUS_G6", "7": "EXP_G1", "8": "CAM_EN", "9": "EXP_G8",
               "10": NC, "11": NC, "12": NC, "13": NC, "14": "EXP_G2",
               "15": "5V0", "16": NC}),
    dict(ref="J7", lib="Connector_Generic:Conn_01x04", value="EXP G1 G2 G8 GND",
         fp="Connector_PinHeader_2.54mm:PinHeader_1x04_P2.54mm_Vertical",
         lcsc="", mpn="", dnp=True,
         note="Unpopulated test/expansion holes, no part ordered",
         nets={"1": "EXP_G1", "2": "EXP_G2", "3": "EXP_G8", "4": "GND"}),
])

block("Trigger: optocouplers + 2.5 mm jack (camera side isolated)", [
    res("R18", "100k", "SHUTTER_G5", "GND", "Keeps the opto off while the S3 boots"),
    res("R19", "330", "SHUTTER_G5", "OPT1_A", "~5-6 mA at 3.3 V"),
    dict(ref="U6", lib="Isolator:LTV-817S", value="LTV-817S-TA1-C", fp="Package_DIP:SMDIP-4_W9.53mm",
         lcsc="C109227", mpn="LTV-817S-TA1-C", note="Shutter",
         nets={"1": "OPT1_A", "2": "GND", "4": "JACK_TIP", "3": "CAM_GND"}),
    res("R20", "100k", "FOCUS_G6", "GND", "Keeps the opto off while the S3 boots"),
    res("R21", "330", "FOCUS_G6", "OPT2_A", "~5-6 mA at 3.3 V"),
    dict(ref="U7", lib="Isolator:LTV-817S", value="LTV-817S-TA1-C", fp="Package_DIP:SMDIP-4_W9.53mm",
         lcsc="C109227", mpn="LTV-817S-TA1-C", note="Focus",
         nets={"1": "OPT2_A", "2": "GND", "4": "JACK_RING", "3": "CAM_GND"}),
    dict(ref="J6", lib="s3-hat:PJ-242", value="PJ-242 2.5mm", fp=CUSTOM_FP, lcsc="C431536",
         mpn="PJ-242",
         note="Pin roles read from the PJ-242 drawing (plug zones 2/3/4/6 from the face): "
              "6 tip, 4 ring, 2 + 3 sleeve. UNCONFIRMED: check with a meter and a Canon plug",
         nets={"6": "JACK_TIP", "4": "JACK_RING", "2": "CAM_GND", "3": "CAM_GND",
               "5": NC, "7": NC}),
])

# Power flags: nets that only passive pins drive.
PWR_FLAGS = ["GND", "VBUS_IN", "BATN", "DW_VCC", "VSYS", "VSW"]

NOTES = [
    "S3 Hat - schematic only, not laid out, not built. Source: written for Pulsar. "
    "SPDX-License-Identifier: GPL-3.0-or-later",
    "Spec: docs/hardware-s3-hat.md. Generated by tools/gen_schematic.py - edit the script, not this sheet.",
    "Camera keeps its own battery; J3 is a 5 V default-power source (Rp 56k), ~0.52 A limit, off until G7.",
    "Stick USB-C (J4): D+/D-/GND only. VBUS open (stick is powered from 5V0 via Hat 5V_IN, never two sources).",
    "J4 CC1/CC2 open: no Rp, so the stick never sees a source and never expects VBUS from this port; no Rd,",
    "  because this side is not a sink either. USB data flows with CC open on a passive C-to-C cable.",
    "Camera-side ground CAM_GND (jack sleeve) is isolated from board GND by the optocouplers.",
    "Cell protection is in the negative leg: board GND is P-, BATN is the cell minus.",
    "CONFIRM ON THE FIRST BOARD: PJ-242 tip/ring with a Canon cable. Footprints marked 'needs custom'.",
]


# --------------------------------------------------------------------------
# Symbol library handling
# --------------------------------------------------------------------------
_libcache = {}


def load_lib(lib):
    if lib not in _libcache:
        tree = parse(open(os.path.join(SYMDIR, lib + ".kicad_sym")).read())[0]
        _libcache[lib] = {s[1]: s for s in find_all(tree, "symbol")}
    return _libcache[lib]


def stock_symbol(libid):
    lib, name = libid.split(":")
    syms = load_lib(lib)
    sym = syms[name]
    ext = find(sym, "extends")
    if ext:
        parent = syms[ext[1]]
        flat = [x for x in parent]
        flat[1] = Q(name)
        child_props = {p[1]: p for p in find_all(sym, "property")}
        out = []
        for x in flat:
            if isinstance(x, list) and x and x[0] == "property" and x[1] in child_props:
                out.append(child_props.pop(x[1]))
            elif isinstance(x, list) and x and x[0] == "symbol":
                sub = list(x)
                sub[1] = Q(str(sub[1]).replace(ext[1] + "_", name + "_", 1))
                out.append(sub)
            else:
                out.append(x)
        # any child-only properties go after the last property
        idx = max(i for i, x in enumerate(out) if isinstance(x, list) and x and x[0] == "property")
        for p in child_props.values():
            idx += 1
            out.insert(idx, p)
        sym = out
    sym = list(sym)
    return sym


def eff(size=1.27, justify=None, hide=False):
    e = ["effects", ["font", ["size", size, size]]]
    if justify:
        e.append(["justify"] + justify.split())
    out = []
    if hide:
        out.append(["hide", "yes"])
    out.append(e)
    return out


def prop(name, value, x, y, ang=0, hide=False, justify=None):
    return ["property", Q(name), Q(value), ["at", x, y, ang]] + eff(hide=hide, justify=justify)


def custom_symbol(name, spec):
    pins = spec["pins"]
    w = spec["w"]
    nleft = max([p[4] for p in pins if p[3] == "L"] + [0]) + 1
    nright = max([p[4] for p in pins if p[3] == "R"] + [0]) + 1
    rows = max(nleft, nright)
    top = 2.54 * ((rows - 1) / 2.0) + 2.54
    top = round(top / 1.27) * 1.27
    bottom = top - 2.54 * (rows + 1)
    body = ["symbol", Q(name + "_0_1"),
            ["rectangle", ["start", -w, top], ["end", w, bottom],
             ["stroke", ["width", 0.254], ["type", "default"]], ["fill", ["type", "background"]]]]
    pinsym = ["symbol", Q(name + "_1_1")]
    for num, pname, ptype, side, slot in pins:
        y = top - 2.54 * (slot + 1)
        if side == "L":
            at = ["at", -w - 2.54, y, 0]
        else:
            at = ["at", w + 2.54, y, 180]
        pinsym.append(["pin", ptype, "line", at, ["length", 2.54],
                       ["name", Q(pname), ["effects", ["font", ["size", 1.27, 1.27]]]],
                       ["number", Q(num), ["effects", ["font", ["size", 1.27, 1.27]]]]])
    return ["symbol", Q(name),
            ["pin_names", ["offset", 1.016]],
            ["exclude_from_sim", "no"], ["in_bom", "yes"], ["on_board", "yes"],
            ["in_pos_files", "yes"], ["duplicate_pin_numbers_are_jumpers", "no"],
            prop("Reference", spec["ref"], -w, top + 1.27, justify="left bottom"),
            prop("Value", name, -w, bottom - 1.27, justify="left top"),
            prop("Footprint", "", 0, 0, hide=True),
            prop("Datasheet", spec["ds"], 0, 0, hide=True),
            prop("Description", spec["desc"], 0, 0, hide=True),
            body, pinsym, ["embedded_fonts", "no"]]


def symbol_def(libid):
    lib, name = libid.split(":")
    if lib == PROJECT:
        return custom_symbol(name, CUSTOM[name])
    return stock_symbol(libid)


def pins_of(sym):
    """[(number, name, type, x, y, angle, hidden)] in library coordinates."""
    res = []
    for sub in find_all(sym, "symbol"):
        for p in find_all(sub, "pin"):
            at = find(p, "at")
            hidden = find(p, "hide") is not None
            res.append((str(find(p, "number")[1]), str(find(p, "name")[1]), p[1],
                        float(at[1]), float(at[2]), int(float(at[3])), hidden))
    return res


# --------------------------------------------------------------------------
# Layout + emission
# --------------------------------------------------------------------------
STUB = 2.54
CH = 1.05  # label character width estimate (mm) at 1.27 mm font


def snap(v, g=1.27):
    return round(v / g) * g


def r2(v):
    return round(v, 4)


def part_geometry(part, sym):
    pins = pins_of(sym)
    xs = [p[3] for p in pins]
    ys = [p[4] for p in pins]
    left = min(xs + [-2.54])
    right = max(xs + [2.54])
    top = max(ys + [2.54])
    bot = min(ys + [-2.54])
    # extra room for labels
    lab_l = lab_r = lab_t = lab_b = 0.0
    for num, name, ptype, x, y, ang, hid in pins:
        net = part["nets"].get(num, "?")
        ln = (len(net) * CH + STUB + 1.5) if net else 2.0
        out = (ang + 180) % 360
        if out == 180:
            lab_l = max(lab_l, ln)
        elif out == 0:
            lab_r = max(lab_r, ln)
        elif out == 90:
            lab_t = max(lab_t, ln)
        else:
            lab_b = max(lab_b, ln)
    return dict(left=left - lab_l, right=right + lab_r,
                top=top + max(lab_t, 5.0), bot=bot - max(lab_b, 5.0))


def main():
    libids = []
    for _, parts in BLOCKS:
        for p in parts:
            if p["lib"] not in libids:
                libids.append(p["lib"])
    libids += ["power:PWR_FLAG"]
    defs = {lid: symbol_def(lid) for lid in libids}

    lib_symbols = ["lib_symbols"]
    for lid in libids:
        d = list(defs[lid])
        d[1] = Q(lid)
        lib_symbols.append(d)

    items = []
    used_refs = set()

    def place(part, X, Y):
        sym = defs[part["lib"]]
        ref = part["ref"]
        assert ref not in used_refs, ref
        used_refs.add(ref)
        pins = pins_of(sym)
        pinnums = {p[0] for p in pins}
        for k in part["nets"]:
            assert k in pinnums, (ref, k, sorted(pinnums))
        missing = [p[0] for p in pins if p[0] not in part["nets"] and p[2] != "no_connect"]
        assert not missing, (ref, "pins without a net or NC", missing)
        g = part_geometry(part, sym)
        dnp = part.get("dnp", False)
        two_pin_vertical = len(pins) == 2 and all(p[3] == 0 for p in pins)
        if two_pin_vertical:
            rpos = (X + 2.54, Y - 1.27, "left")
            vpos = (X + 2.54, Y + 1.27, "left")
        elif len(pins) == 2:
            ys = [p[4] for p in pins]
            rpos = (X, Y - max(ys + [2.54]) - 3.81, "")
            vpos = (X, Y - min(ys + [-2.54]) + 3.81, "")
        else:
            # beside the top-left / bottom-left corner, clear of the stub labels
            xs = [p[3] for p in pins]
            ys = [p[4] for p in pins]
            rpos = (X + min(xs) - 1.27, Y - max(ys) - 2.54, "right bottom")
            vpos = (X + min(xs) - 1.27, Y - min(ys) + 2.54, "right top")
        fields = [
            ("Reference", ref, rpos, False),
            ("Value", part["value"], vpos, False),
            ("Footprint", part["fp"], (X, Y, ""), True),
            ("Datasheet", str(find_prop(sym, "Datasheet")), (X, Y, ""), True),
            ("Description", str(find_prop(sym, "Description")), (X, Y, ""), True),
            ("LCSC", part["lcsc"], (X, Y, ""), True),
            ("MPN", part["mpn"], (X, Y, ""), True),
        ]
        if part.get("note"):
            fields.append(("Note", part["note"], (X, Y, ""), True))
        node = ["symbol", ["lib_id", Q(part["lib"])], ["at", r2(X), r2(Y), 0], ["unit", 1],
                ["exclude_from_sim", "no"], ["in_bom", "no" if dnp else "yes"],
                ["on_board", "yes"], ["dnp", "yes" if dnp else "no"],
                ["uuid", uid("sym", ref)]]
        for name, val, (fx, fy, just), hide in fields:
            node.append(prop(name, val, r2(fx), r2(fy), hide=hide, justify=just or None))
        for p in pins:
            node.append(["pin", Q(p[0]), ["uuid", uid("pin", ref, p[0])]])
        node.append(["instances", ["project", Q(PROJECT),
                                   ["path", Q("/" + ROOT_UUID), ["reference", Q(ref)], ["unit", 1]]]])
        items.append(node)
        # wiring
        done = {}
        for num, pname, ptype, px, py, ang, hid in pins:
            sx, sy = r2(X + px), r2(Y - py)
            net = part["nets"].get(num, NC)
            key = (sx, sy)
            if key in done:
                assert done[key] == net, (ref, num, "stacked pins on different nets")
                continue
            done[key] = net
            if ptype == "no_connect" and num not in part["nets"]:
                continue
            if net is NC:
                items.append(["no_connect", ["at", sx, sy], ["uuid", uid("nc", ref, num)]])
                continue
            out = (ang + 180) % 360
            dx = round(math.cos(math.radians(out)), 3)
            dy = -round(math.sin(math.radians(out)), 3)
            ex, ey = r2(sx + dx * STUB), r2(sy + dy * STUB)
            items.append(["wire", ["pts", ["xy", sx, sy], ["xy", ex, ey]],
                          ["stroke", ["width", 0], ["type", "default"]],
                          ["uuid", uid("w", ref, num)]])
            lang = {0: 0, 90: 90, 180: 180, 270: 270}[out]
            just = "left bottom" if lang in (0, 90) else "right bottom"
            items.append(["label", Q(net), ["at", ex, ey, lang],
                          ["effects", ["font", ["size", 1.27, 1.27]], ["justify"] + just.split()],
                          ["uuid", uid("lbl", ref, num)]])

    # Page A2 landscape: 594 x 420 mm. Blocks flow in columns.
    PAGE_W, PAGE_H = 594.0, 420.0
    margin = 15.0
    col_w = 180.0
    bx, by = margin, margin + 10
    row_h = 0.0
    for bi, (title, parts) in enumerate(BLOCKS):
        geoms = [part_geometry(p, defs[p["lib"]]) for p in parts]
        # lay parts left-to-right, wrap inside the block width
        x = 0.0
        y = 0.0
        line_h = 0.0
        placed = []
        for p, g in zip(parts, geoms):
            w = g["right"] - g["left"]
            h = g["top"] - g["bot"]
            if x > 0 and x + w > col_w:
                x = 0.0
                y += line_h + 4.0
                line_h = 0.0
            placed.append((p, x - g["left"], y + g["top"]))
            x += w + 6.0
            line_h = max(line_h, h)
        bw = col_w
        bh = y + line_h + 12.0
        if bx + bw > PAGE_W - margin:
            bx = margin
            by += row_h + 8.0
            row_h = 0.0
        items.append(["text", Q(title), ["exclude_from_sim", "no"], ["at", r2(snap(bx)), r2(snap(by)), 0],
                      ["effects", ["font", ["size", 2.0, 2.0], ["bold", "yes"]], ["justify", "left", "bottom"]],
                      ["uuid", uid("title", bi)]])
        items.append(["rectangle", ["start", r2(snap(bx - 3)), r2(snap(by - 5))],
                      ["end", r2(snap(bx + bw)), r2(snap(by + bh))],
                      ["stroke", ["width", 0], ["type", "dash"]], ["fill", ["type", "none"]],
                      ["uuid", uid("box", bi)]])
        for p, ox, oy in placed:
            place(p, snap(bx + ox, 2.54), snap(by + 4.0 + oy, 2.54))
        bx += bw + 10.0
        row_h = max(row_h, bh)

    # PWR_FLAGs and notes in the last free area
    fy = by + row_h + 14.0
    fx = margin
    items.append(["text", Q("Power flags (ERC: nets driven only by passive parts)"),
                  ["exclude_from_sim", "no"], ["at", r2(snap(fx)), r2(snap(fy - 8)), 0],
                  ["effects", ["font", ["size", 1.5, 1.5], ["bold", "yes"]], ["justify", "left", "bottom"]],
                  ["uuid", uid("pftitle")]])
    for i, net in enumerate(PWR_FLAGS):
        X = snap(fx + 8 + i * 22.0, 2.54)
        Y = snap(fy, 2.54)
        ref = f"#FLG0{i + 1:02d}"
        node = ["symbol", ["lib_id", Q("power:PWR_FLAG")], ["at", X, Y, 0], ["unit", 1],
                ["exclude_from_sim", "no"], ["in_bom", "yes"], ["on_board", "yes"], ["dnp", "no"],
                ["uuid", uid("flg", net)],
                prop("Reference", ref, X, r2(Y - 6.35), hide=True),
                prop("Value", "PWR_FLAG", X, r2(Y - 3.81)),
                prop("Footprint", "", X, Y, hide=True),
                prop("Datasheet", "", X, Y, hide=True),
                prop("Description", "", X, Y, hide=True),
                ["pin", Q("1"), ["uuid", uid("flgpin", net)]],
                ["instances", ["project", Q(PROJECT),
                               ["path", Q("/" + ROOT_UUID), ["reference", Q(ref)], ["unit", 1]]]]]
        items.append(node)
        items.append(["wire", ["pts", ["xy", X, Y], ["xy", X, r2(Y + STUB)]],
                      ["stroke", ["width", 0], ["type", "default"]], ["uuid", uid("flgw", net)]])
        items.append(["label", Q(net), ["at", X, r2(Y + STUB), 270],
                      ["effects", ["font", ["size", 1.27, 1.27]], ["justify", "right", "bottom"]],
                      ["uuid", uid("flgl", net)]])
    ny = fy + 16.0
    items.append(["text", Q("\n".join(NOTES)), ["exclude_from_sim", "no"],
                  ["at", r2(snap(fx)), r2(snap(ny)), 0],
                  ["effects", ["font", ["size", 1.5, 1.5]], ["justify", "left", "top"]],
                  ["uuid", uid("notes")]])
    assert ny + 25 < PAGE_H, f"layout overflows the page (notes at y={ny})"

    sch = ["kicad_sch", ["version", 20250610], ["generator", Q("eeschema")],
           ["generator_version", Q("10.0")], ["uuid", ROOT_UUID], ["paper", Q("A2")],
           ["title_block", ["title", Q("Pulsar S3 Hat")], ["date", Q("2026-11-09")],
            ["rev", Q("0.1 draft")], ["company", Q("Pulsar Trigger contributors - GPL-3.0-or-later")],
            ["comment", 1, Q("Schematic only - not laid out, not built")],
            ["comment", 2, Q("Spec: docs/hardware-s3-hat.md")],
            ["comment", 3, Q("Generated by hardware/s3-hat/tools/gen_schematic.py")]],
           lib_symbols] + items + [
           ["sheet_instances", ["path", Q("/"), ["page", Q("1")]]],
           ["embedded_fonts", "no"]]
    with open(os.path.join(OUT, PROJECT + ".kicad_sch"), "w") as f:
        f.write(dump(sch) + "\n")

    lib = ["kicad_symbol_lib", ["version", 20251024], ["generator", Q("pulsar_gen_schematic")],
           ["generator_version", Q("10.0")]]
    for name, spec in CUSTOM.items():
        lib.append(custom_symbol(name, spec))
    with open(os.path.join(OUT, PROJECT + ".kicad_sym"), "w") as f:
        f.write(dump(lib) + "\n")
    # Project library tables. The stock libraries are listed too, so the project
    # opens (and ERC resolves its links) on a machine whose global tables are
    # not set up yet; on a configured machine they resolve to the same files.
    symlibs = sorted({lid.split(":")[0] for lid in libids} - {PROJECT})
    fplibs = sorted({p["fp"].split(":")[0] for _, ps in BLOCKS for p in ps if ":" in p["fp"]})
    with open(os.path.join(OUT, "sym-lib-table"), "w") as f:
        f.write("(sym_lib_table\n\t(version 7)\n")
        f.write(f'\t(lib (name "{PROJECT}") (type "KiCad") (uri "${{KIPRJMOD}}/{PROJECT}.kicad_sym") '
                '(options "") (descr "Pulsar S3 Hat custom symbols (generated)"))\n')
        for lib in symlibs:
            f.write(f'\t(lib (name "{lib}") (type "KiCad") (uri "${{KICAD10_SYMBOL_DIR}}/{lib}.kicad_sym") '
                    '(options "") (descr "KiCad stock"))\n')
        f.write(")\n")
    with open(os.path.join(OUT, "fp-lib-table"), "w") as f:
        f.write("(fp_lib_table\n\t(version 7)\n")
        for lib in fplibs:
            f.write(f'\t(lib (name "{lib}") (type "KiCad") (uri "${{KICAD10_FOOTPRINT_DIR}}/{lib}.pretty") '
                    '(options "") (descr "KiCad stock"))\n')
        f.write(")\n")
    print(f"wrote {PROJECT}.kicad_sch ({len(used_refs)} parts) and {PROJECT}.kicad_sym")


def find_prop(sym, name):
    for p in find_all(sym, "property"):
        if p[1] == name:
            return p[2]
    return ""


if __name__ == "__main__":
    main()
