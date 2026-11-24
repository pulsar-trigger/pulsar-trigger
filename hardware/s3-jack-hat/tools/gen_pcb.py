#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Generate the S3 Jack Hat PCB (and the PJ-242 footprint) from the netlist.

Written for Pulsar. Usage (from hardware/s3-jack-hat/), after gen_schematic.py
and the netlist export:
    python3 tools/gen_pcb.py [out/s3-jack-hat.net] [--out FILE.kicad_pcb]
    kicad-cli pcb drc --refill-zones --save-board ... s3-jack-hat.kicad_pcb

Components, values, LCSC/MPN fields and pad nets come from the exported
netlist, so the board cannot drift from the schematic. Placement and tracks
are below in board coordinates (BOARD FRAME): u runs west -> east across the
24 mm width, v runs from the stick end (v = 0, the edge that touches the
stick) south to the far edge (v = L). The board is drawn at (OX, OY).

The header routing is computed from hat2.GND_CORNER, so a changed orientation
re-routes the G5/G6 lines without hand edits. The GND pour covers only the
stick side; the camera side (jack nets) is kept >= 1 mm away by the custom
rule in s3-jack-hat.kicad_dru.
"""
import math
import os
import sys
import uuid as uuidlib

from hat2 import GND_CORNER, SIGNAL_PIN, hat_to_pad
from sexpr import Q, dump, find, find_all, parse

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
PROJECT = "s3-jack-hat"
FPDIR = "/usr/share/kicad/footprints"
NS = uuidlib.UUID("7c3d1b8e-0f2a-4f51-9a64-5043424a4b31")

OX, OY = 100.0, 100.0          # board frame origin on the KiCad sheet
W = 24.0                       # board width (= the stick's width)
L = 21.4                       # board length beyond the stick's end face
THICK = 1.6

# --- Hat2 header J1 ---------------------------------------------------------
HDR_BODY_FRONT = 6.58          # KiCad footprint: plastic body front face at local x = 6.58
HDR_U0 = W / 2 - 3.5 * 2.54    # column 1 centre (header centred on the width)
HDR_V_UPPER = HDR_BODY_FRONT           # odd pads (local x = 0): the upper row
HDR_V_LOWER = HDR_BODY_FRONT - 2.54    # even pads (local x = 2.54): the lower row

# --- PJ-242 -----------------------------------------------------------------
JACK_U = 18.4                  # jack centreline
JACK_FACE_V = L                # body front face on the far edge; the nose overhangs
JACK_ORIGIN_V = JACK_FACE_V - 5.4   # footprint origin = middle pad row (5.40 behind the face)

PLACE = {  # ref: (u, v, rotation)
    "J1": (HDR_U0, HDR_BODY_FRONT, 90),
    "R1": (1.9, 9.2, 180), "R2": (4.9, 9.2, 0), "R3": (7.9, 9.2, 180), "R4": (10.9, 9.2, 0),
    "U1": (6.6, 13.0, 0), "U2": (6.6, 18.6, 0),
    "J2": (JACK_U, JACK_ORIGIN_V, 180),
}

TRACK = 0.3
VIA = (0.6, 0.3)
LANE_V = 7.95                  # horizontal lane between the header pads and the resistors


def uid(*parts):
    return Q(str(uuidlib.uuid5(NS, "/".join(str(p) for p in parts))))


def r4(x):
    return round(x, 4)


# --------------------------------------------------------------------------
# PJ-242 footprint, from the Shou Han drawing (C431536 datasheet p.1,
# "P.C.B LAYOUT TOP VIEW"). Local frame: jack face toward -y.
# Pads 1.8 x 1.5 at x = +-2.9; rows 1.70 / 5.40 / 9.10 behind the face;
# 2 x d1.30 holes on the centreline 2.80 and 7.80 behind the face.
# Body 5.00 wide, 10.5 long behind the face, 3.20 tall; nose d4.00, 1.50 long,
# plug hole d2.60 with its centre 1.60 above the PCB.
# --------------------------------------------------------------------------
def pj242_footprint():
    y0 = -5.4                   # face, relative to the middle pad row
    pads = [("3", -2.9, -3.7), ("2", 2.9, -3.7), ("6", -2.9, 0.0),
            ("4", 2.9, 0.0), ("7", -2.9, 3.7), ("5", 2.9, 3.7)]

    def line(a, b, layer, w=0.1):
        return ["fp_line", ["start", a[0], a[1]], ["end", b[0], b[1]],
                ["stroke", ["width", w], ["type", "solid"]], ["layer", Q(layer)]]

    def rect(a, b, layer, w=0.05):
        return ["fp_rect", ["start", a[0], a[1]], ["end", b[0], b[1]],
                ["stroke", ["width", w], ["type", "solid"]], ["fill", "no"], ["layer", Q(layer)]]

    def fprop(name, val, x, y, layer, hide=False):
        p = ["property", Q(name), Q(val), ["at", x, y, 0], ["layer", Q(layer)]]
        if hide:
            p.append(["hide", "yes"])
        p.append(["effects", ["font", ["size", 1.0, 1.0], ["thickness", 0.15]]])
        return p

    fp = ["footprint", Q("PJ-242_ShouHan"), ["version", 20241229], ["generator", Q("pulsar_gen_pcb")],
          ["generator_version", Q("10.0")], ["layer", Q("F.Cu")],
          ["descr", Q("Shou Han PJ-242 2.5 mm SMD jack (LCSC C431536). Land pattern from the maker's "
                      "drawing, P.C.B LAYOUT TOP VIEW. Origin = middle pad row; jack face toward -y. "
                      "Written for Pulsar")],
          ["tags", Q("2.5mm jack audio PJ-242")],
          fprop("Reference", "REF**", 0, 6.4, "F.Fab"),
          fprop("Value", "PJ-242", 0, 7.6, "F.Fab"),
          fprop("Datasheet", "https://www.lcsc.com/datasheet/lcsc_datasheet_1912111437_SHOU-HAN-PJ-242_C431536.pdf",
                0, 0, "F.Fab", hide=True),
          fprop("Description", "2.5 mm SMD jack, 4 contacts + 2 switch contacts", 0, 0, "F.Fab", hide=True),
          ["attr", "smd"]]
    # fab: body, nose, plug axis
    fp.append(rect((-2.5, y0), (2.5, y0 + 10.5), "F.Fab", 0.1))
    fp.append(rect((-2.0, y0 - 1.5), (2.0, y0), "F.Fab", 0.1))
    fp.append(line((0, y0 - 1.5), (0, y0 + 4.0), "F.Fab"))
    # silk: only the back edge and short side ticks clear of the pads
    fp.append(line((-2.6, y0 + 10.6), (2.6, y0 + 10.6), "F.SilkS", 0.12))
    fp.append(line((-2.6, y0 + 10.6), (-2.6, 4.75), "F.SilkS", 0.12))
    fp.append(line((2.6, y0 + 10.6), (2.6, 4.75), "F.SilkS", 0.12))
    # courtyard: pads + body + nose (the nose sits beyond the board edge)
    fp.append(rect((-4.05, y0 - 1.75), (4.05, y0 + 10.75), "F.CrtYd"))
    for num, x, y in pads:
        fp.append(["pad", Q(num), "smd", "rect", ["at", x, y], ["size", 1.8, 1.5],
                   ["layers", Q("F.Cu"), Q("F.Paste"), Q("F.Mask")]])
    for y in (y0 + 2.8, y0 + 7.8):
        fp.append(["pad", Q(""), "np_thru_hole", "circle", ["at", 0, r4(y)], ["size", 1.3, 1.3],
                   ["drill", 1.3], ["layers", Q("*.Cu"), Q("*.Mask")]])
    fp.append(["embedded_fonts", "no"])
    return fp


# --------------------------------------------------------------------------
# Netlist
# --------------------------------------------------------------------------
def load_netlist(path):
    tree = parse(open(path).read())[0]
    comps = {}
    for c in find_all(find(tree, "components"), "comp"):
        ref = str(find(c, "ref")[1])
        fields = {}
        fl = find(c, "fields")
        for f in find_all(fl, "field") if fl else []:
            fields[str(find(f, "name")[1])] = str(f[2]) if len(f) > 2 else ""
        sp = find(c, "sheetpath")
        for key, fname in (("datasheet", "Datasheet"), ("description", "Description")):
            node = find(c, key)
            fields[fname] = str(node[1]) if node is not None and len(node) > 1 else ""
        comps[ref] = dict(value=str(find(c, "value")[1]), fp=str(find(c, "footprint")[1]),
                          fields=fields, tstamp=str(find(c, "tstamps")[1]),
                          sheet=str(find(sp, "names")[1]), sheet_ts=str(find(sp, "tstamps")[1]))
    pad_net = {}
    names = []
    for net in find_all(find(tree, "nets"), "net"):
        name = str(find(net, "name")[1])
        names.append(name)
        for node in find_all(net, "node"):
            pad_net[(str(find(node, "ref")[1]), str(find(node, "pin")[1]))] = name
    return comps, pad_net, names


# --------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------
def B(u, v):
    """Board frame -> KiCad sheet coordinates."""
    return (r4(OX + u), r4(OY + v))


def rot(x, y, deg):
    a = math.radians(deg)
    return (x * math.cos(a) + y * math.sin(a), -x * math.sin(a) + y * math.cos(a))


class Board:
    def __init__(self, netlist):
        self.comps, self.pad_net, names = load_netlist(netlist)
        self.netnum = {"": 0}
        for n in names:
            self.netnum.setdefault(n, len(self.netnum))
        self.items = []
        self.fps = {}
        self.n = 0

    def net(self, name):
        return ["net", self.netnum[name], Q(name)]

    def lib_footprint(self, libid):
        lib, name = libid.split(":")
        if lib == PROJECT:
            path = os.path.join(PROJ, PROJECT + ".pretty", name + ".kicad_mod")
        else:
            path = os.path.join(FPDIR, lib + ".pretty", name + ".kicad_mod")
        return parse(open(path).read())[0]

    def place(self, ref, u, v, angle):
        c = self.comps[ref]
        lib = self.lib_footprint(c["fp"])
        X, Y = B(u, v)
        fp = ["footprint", Q(c["fp"]), ["layer", Q("F.Cu")], ["uuid", uid("fp", ref)], ["at", X, Y, angle]]
        pads = {}
        for x in lib[2:]:
            if not isinstance(x, list):
                continue
            head = x[0]
            if head in ("version", "generator", "generator_version", "layer"):
                continue
            if head == "property":
                x = list(x)
                pname = str(x[1])
                if pname == "Reference":
                    x[2] = Q(ref)
                    x = [y for y in x if not (isinstance(y, list) and y[0] == "layer")]
                    x.insert(3, ["layer", Q("F.Fab")])
                elif pname == "Value":
                    x[2] = Q(c["value"])
                elif pname in ("Datasheet", "Description"):
                    x[2] = Q(c["fields"].get(pname, ""))
                at = find(x, "at")
                if at is not None:
                    at[:] = ["at", at[1], at[2], angle]
                fp.append(x)
                continue
            if head == "pad":
                x = [list(y) if isinstance(y, list) else y for y in x]
                at = find(x, "at")
                base = float(at[3]) if len(at) > 3 else 0.0
                at[:] = ["at", at[1], at[2], (base + angle) % 360]
                num = str(x[1])
                if num:
                    name = self.pad_net.get((ref, num))
                    assert name is not None, (ref, num, "pad has no net in the netlist")
                    x.append(self.net(name))
                    lx, ly = float(at[1]), float(at[2])
                    dx, dy = rot(lx, ly, angle)
                    pads[num] = (r4(X + dx), r4(Y + dy))
                x.append(["uuid", uid("pad", ref, num, at[1], at[2])])
                fp.append(x)
                continue
            if head in ("fp_line", "fp_rect", "fp_circle", "fp_poly", "fp_arc") and \
                    str(find(x, "layer")[1]) == "F.SilkS" and not self.inside(X, Y, angle, x):
                continue  # silk that would hang over the board edge (the header body)
            fp.append(x)
        seen = {str(x[1]) for x in fp if isinstance(x, list) and x[0] == "property"}
        for k in ("Datasheet", "Description", "LCSC", "MPN", "Note"):
            if k in seen:
                continue
            val = c["fields"].get(k, "")
            fp.append(["property", Q(k), Q(val), ["at", 0, 0, angle], ["layer", Q("F.Fab")], ["hide", "yes"],
                       ["uuid", uid("prop", ref, k)],
                       ["effects", ["font", ["size", 1.0, 1.0], ["thickness", 0.15]]]])
        fp.append(["path", Q(c["sheet_ts"] + c["tstamp"])])
        fp.append(["sheetname", Q(c["sheet"])])
        fp.append(["sheetfile", Q(PROJECT + ".kicad_sch")])
        self.fps[ref] = pads
        self.items.append(fp)

    @staticmethod
    def inside(X, Y, angle, item, margin=0.0):
        """True when every start/end/center point of a footprint graphic is on the board."""
        for key in ("start", "end", "center", "mid"):
            pt = find(item, key)
            if pt is None:
                continue
            dx, dy = rot(float(pt[1]), float(pt[2]), angle)
            u, v = X + dx - OX, Y + dy - OY
            if not (margin <= u <= W - margin and margin <= v <= L - margin):
                return False
        return True

    def pad(self, ref, num):
        """Pad centre in the BOARD FRAME."""
        X, Y = self.fps[ref][num]
        return (r4(X - OX), r4(Y - OY))

    def track(self, pts, netname, layer="F.Cu", width=TRACK):
        for a, b in zip(pts, pts[1:]):
            self.n += 1
            self.items.append(["segment", ["start", *B(*a)], ["end", *B(*b)], ["width", width],
                               ["layer", Q(layer)], ["net", self.netnum[netname]],
                               ["uuid", uid("seg", self.n)]])

    def via(self, p, netname):
        self.n += 1
        self.items.append(["via", ["at", *B(*p)], ["size", VIA[0]], ["drill", VIA[1]],
                           ["layers", Q("F.Cu"), Q("B.Cu")], ["net", self.netnum[netname]],
                           ["uuid", uid("via", self.n)]])

    def text(self, s, u, v, layer, size=1.0, justify=None):
        eff = ["effects", ["font", ["size", size, size], ["thickness", 0.15]]]
        if layer.startswith("B."):
            eff.append(["justify", "mirror"] + ([justify] if justify else []))
        elif justify:
            eff.append(["justify", justify])
        self.n += 1
        self.items.append(["gr_text", Q(s), ["at", *B(u, v), 0], ["layer", Q(layer)],
                           ["uuid", uid("txt", self.n)], eff])

    def zone(self, netname, layers, poly, name):
        self.items.append(
            ["zone", ["net", self.netnum[netname]], ["net_name", Q(netname)],
             ["layers"] + [Q(x) for x in layers], ["uuid", uid("zone", name)], ["name", Q(name)],
             ["hatch", "edge", 0.5], ["connect_pads", ["clearance", 0.3]],
             ["min_thickness", 0.25], ["filled_areas_thickness", "no"],
             ["fill", "yes", ["thermal_gap", 0.3], ["thermal_bridge_width", 0.4]],
             ["polygon", ["pts"] + [["xy", *B(*p)] for p in poly]]])


# --------------------------------------------------------------------------
# Routing
# --------------------------------------------------------------------------
def route_header_signal(b, sig, target_u, netname, layer_lane):
    """From the Hat2 pad carrying `sig` down to the resistor junction at target_u."""
    pad = str(hat_to_pad(SIGNAL_PIN[sig]))
    pu, pv = b.pad("J1", pad)
    if abs(pv - (HDR_V_LOWER)) < 1e-6:
        # lower row: step diagonally into the gap between two columns, then down
        d = 1 if target_u >= pu else -1
        drop = r4(pu + 1.27 * d)
        pts = [(pu, pv), (drop, r4(pv + 1.27)), (drop, LANE_V)]
    else:
        drop = pu
        pts = [(pu, pv), (drop, LANE_V)]
    if layer_lane == "F.Cu" or abs(drop - target_u) < 1.5:
        b.track(pts + [(target_u, LANE_V), (target_u, 9.2)], netname)
    else:
        b.track(pts, netname)
        b.via((drop, LANE_V), netname)
        b.track([(drop, LANE_V), (target_u, LANE_V)], netname, layer="B.Cu")
        b.via((target_u, LANE_V), netname)
        b.track([(target_u, LANE_V), (target_u, 9.2)], netname)
    return drop


def build(netlist, out):
    b = Board(netlist)
    for ref, (u, v, a) in PLACE.items():
        b.place(ref, u, v, a)
    missing = set(b.comps) - set(PLACE)
    assert not missing, f"unplaced parts: {missing}"

    net = lambda ref, pin: b.pad_net[(ref, pin)]  # noqa: E731
    G5, G6, GND = net("R1", "1"), net("R3", "1"), net("R2", "2")
    TIP, RING, SLV = net("J2", "6"), net("J2", "4"), net("J2", "2")

    # Resistor junctions: R1.1/R2.1 (G5) and R3.1/R4.1 (G6)
    t5 = r4((b.pad("R1", "1")[0] + b.pad("R2", "1")[0]) / 2)
    t6 = r4((b.pad("R3", "1")[0] + b.pad("R4", "1")[0]) / 2)
    b.track([b.pad("R1", "1"), b.pad("R2", "1")], G5)
    b.track([b.pad("R3", "1"), b.pad("R4", "1")], G6)

    # G6 lane on F.Cu; G5 lane on B.Cu only if it would cross G6's path.
    p5 = b.pad("J1", str(hat_to_pad(SIGNAL_PIN["G5"])))
    p6 = b.pad("J1", str(hat_to_pad(SIGNAL_PIN["G6"])))
    lo5, hi5 = sorted((p5[0], t5))
    lo6, hi6 = sorted((p6[0], t6))
    overlap = not (hi5 + 1.5 < lo6 or hi6 + 1.5 < lo5)
    route_header_signal(b, "G6", t6, G6, "F.Cu")
    route_header_signal(b, "G5", t5, G5, "B.Cu" if overlap else "F.Cu")

    # Hat GND pad: a short F.Cu stub to the nearest side edge, so the THT pad
    # has a full thermal connection on F.Cu (the pads around it starve it).
    gp = b.pad("J1", str(hat_to_pad(SIGNAL_PIN["GND"])))
    b.track([gp, (1.3 if gp[0] < W / 2 else r4(W - 1.3), gp[1])], GND)

    # LED side
    r1a = b.pad("R1", "2")
    u1a = b.pad("U1", "1")
    b.track([r1a, (r1a[0], r1a[1] + 0.3), (u1a[0], r4(r1a[1] + 1.06)), u1a], net("U1", "1"))
    r3a = b.pad("R3", "2")
    u2a = b.pad("U2", "1")
    va1 = (r3a[0], 10.25)
    va2 = (4.6, u2a[1])
    b.track([r3a, va1], net("U2", "1"))
    b.via(va1, net("U2", "1"))
    b.track([va1, (4.6, r4(va1[1] + abs(va1[0] - 4.6))), va2], net("U2", "1"), layer="B.Cu")
    b.via(va2, net("U2", "1"))
    b.track([va2, u2a], net("U2", "1"))

    # GND: pull-downs and cathodes stitched to the B.Cu pour
    r2g = b.pad("R2", "2")
    b.track([r2g, (r2g[0], 10.15)], GND)
    b.via((r2g[0], 10.15), GND)
    r4g = b.pad("R4", "2")
    b.track([r4g, (12.75, r4g[1])], GND)
    b.via((12.75, r4g[1]), GND)
    for u in ("U1", "U2"):
        k = b.pad(u, "2")
        b.track([k, (3.6, k[1])], GND)
        b.via((3.6, k[1]), GND)

    # Camera side (all F.Cu except the sleeve link)
    c1 = b.pad("U1", "4")
    tip = b.pad("J2", "6")
    b.track([c1, (13.0, c1[1]), (13.73, 11.0), (17.3, 11.0), (17.3, r4(tip[1] - 1.0)),
             (18.3, tip[1]), tip], TIP)
    c2 = b.pad("U2", "4")
    ring = b.pad("J2", "4")
    b.track([c2, (r4(ring[0] - 1.33), c2[1]), ring], RING)
    e1, e2 = b.pad("U1", "3"), b.pad("U2", "3")
    s2, s3 = b.pad("J2", "2"), b.pad("J2", "3")
    b.track([e1, (13.3, e1[1])], SLV)
    b.via((13.3, e1[1]), SLV)
    b.track([(13.3, e1[1]), (13.3, e2[1])], SLV, layer="B.Cu")
    b.via((13.3, e2[1]), SLV)
    b.track([e2, (13.3, e2[1]), s2, s3], SLV)

    # GND pour: stick side only (north band + west strip)
    poly = [(0, 0), (W, 0), (W, 10.2), (7.6, 10.2), (7.6, L), (0, L)]
    b.zone(GND, ["F.Cu", "B.Cu"], poly, "GND stick side")

    # Outline
    b.items.append(["gr_rect", ["start", *B(0, 0)], ["end", *B(W, L)],
                    ["stroke", ["width", 0.1], ["type", "solid"]], ["fill", "no"],
                    ["layer", Q("Edge.Cuts")], ["uuid", uid("outline")]])

    # Bottom silk (the top is full)
    row, side = GND_CORNER
    for i, s in enumerate(["PULSAR JACK HAT r0.1", "GPL-3.0-or-later",
                           f"HAT2 GND {row} {side}", "UNVERIFIED", "TIP=G5 SHUTTER", "RING=G6 FOCUS"]):
        b.text(s, 0.8, 11.2 + i * 1.6, "B.SilkS", size=0.8, justify="right")
    b.text("STICK SIDE", W / 2, 1.4, "B.SilkS", size=0.8)

    write_board(b, out)


def write_board(b, out):
    layers = ["layers",
              [0, Q("F.Cu"), "signal"], [2, Q("B.Cu"), "signal"],
              [9, Q("F.Adhes"), "user", Q("F.Adhesive")], [11, Q("B.Adhes"), "user", Q("B.Adhesive")],
              [13, Q("F.Paste"), "user"], [15, Q("B.Paste"), "user"],
              [5, Q("F.SilkS"), "user", Q("F.Silkscreen")], [7, Q("B.SilkS"), "user", Q("B.Silkscreen")],
              [1, Q("F.Mask"), "user"], [3, Q("B.Mask"), "user"],
              [17, Q("Dwgs.User"), "user", Q("User.Drawings")], [19, Q("Cmts.User"), "user", Q("User.Comments")],
              [25, Q("Edge.Cuts"), "user"], [27, Q("Margin"), "user"],
              [31, Q("F.CrtYd"), "user", Q("F.Courtyard")], [29, Q("B.CrtYd"), "user", Q("B.Courtyard")],
              [35, Q("F.Fab"), "user"], [33, Q("B.Fab"), "user"]]
    setup = ["setup",
             ["stackup",
              ["layer", Q("F.SilkS"), ["type", Q("Top Silk Screen")]],
              ["layer", Q("F.Paste"), ["type", Q("Top Solder Paste")]],
              ["layer", Q("F.Mask"), ["type", Q("Top Solder Mask")], ["thickness", 0.01]],
              ["layer", Q("F.Cu"), ["type", Q("copper")], ["thickness", 0.035]],
              ["layer", Q("dielectric 1"), ["type", Q("core")], ["thickness", 1.51], ["material", Q("FR4")],
               ["epsilon_r", 4.5], ["loss_tangent", 0.02]],
              ["layer", Q("B.Cu"), ["type", Q("copper")], ["thickness", 0.035]],
              ["layer", Q("B.Mask"), ["type", Q("Bottom Solder Mask")], ["thickness", 0.01]],
              ["layer", Q("B.Paste"), ["type", Q("Bottom Solder Paste")]],
              ["layer", Q("B.SilkS"), ["type", Q("Bottom Silk Screen")]],
              ["copper_finish", Q("HAL lead-free")], ["dielectric_constraints", "no"]],
             ["pad_to_mask_clearance", 0], ["allow_soldermask_bridges_in_footprints", "no"],
             ["tenting", "front", "back"],
             ["aux_axis_origin", OX, r4(OY + L)], ["grid_origin", OX, OY]]
    nets = [["net", n, Q(name)] for name, n in sorted(b.netnum.items(), key=lambda kv: kv[1])]
    pcb = ["kicad_pcb", ["version", 20241229], ["generator", Q("pulsar_gen_pcb")],
           ["generator_version", Q("10.0")],
           ["general", ["thickness", THICK], ["legacy_teardrops", "no"]],
           ["paper", Q("A4")],
           ["title_block", ["title", Q("Pulsar S3 Jack Hat")], ["date", Q("2026-11-10")],
            ["rev", Q("0.1")], ["company", Q("Pulsar Trigger contributors - GPL-3.0-or-later")],
            ["comment", 1, Q("Designed, not ordered, not built")],
            ["comment", 2, Q("Hat2 orientation UNVERIFIED (tools/hat2.py)")],
            ["comment", 3, Q("Generated by hardware/s3-jack-hat/tools/gen_pcb.py")]],
           layers, setup] + nets + b.items + [["embedded_fonts", "no"]]
    with open(out, "w") as f:
        f.write(dump(pcb) + "\n")
    print(f"wrote {out}: {len(b.fps)} footprints, board {W} x {L} mm, GND_CORNER={GND_CORNER}")


def main(argv):
    netlist = os.path.join(PROJ, "out", PROJECT + ".net")
    out = os.path.join(PROJ, PROJECT + ".kicad_pcb")
    args = list(argv)
    footprint_only = "--footprint-only" in args
    if footprint_only:
        args.remove("--footprint-only")
    if "--out" in args:
        i = args.index("--out")
        out = args[i + 1]
        del args[i:i + 2]
    if args:
        netlist = args[0]
    lib = os.path.join(PROJ, PROJECT + ".pretty")
    os.makedirs(lib, exist_ok=True)
    with open(os.path.join(lib, "PJ-242_ShouHan.kicad_mod"), "w") as f:
        f.write(dump(pj242_footprint()) + "\n")
    if footprint_only:
        print("wrote PJ-242_ShouHan.kicad_mod")
        return
    build(netlist, out)


if __name__ == "__main__":
    main(sys.argv[1:])
