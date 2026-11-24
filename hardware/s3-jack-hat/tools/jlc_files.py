#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""Write JLCPCB's BOM and CPL CSVs from the netlist and KiCad's position file.

Written for Pulsar. Usage (from hardware/s3-jack-hat/):
    python3 tools/jlc_files.py out/s3-jack-hat.net out/s3-jack-hat-pos.csv out/

BOM: Comment, Designator, Footprint, LCSC  (one row per distinct part)
CPL: Designator, Mid X, Mid Y, Layer, Rotation  (mm, from the board's
     lower-left corner, which is the drill/place origin in the board file)

🔴 Rotations are KiCad's. JLC's library orientation differs for some parts
(SMDIP-4 and the PJ-242 are the likely ones); check every part in JLC's
placement preview before paying, and correct the rotation there.
"""
import csv
import os
import sys

from sexpr import find, find_all, parse


def main(net, pos, outdir):
    tree = parse(open(net).read())[0]
    parts = {}
    for c in find_all(find(tree, "components"), "comp"):
        ref = str(find(c, "ref")[1])
        fields = {}
        fl = find(c, "fields")
        for f in find_all(fl, "field") if fl else []:
            fields[str(find(f, "name")[1])] = str(f[2]) if len(f) > 2 else ""
        fp = str(find(c, "footprint")[1]).split(":")[-1]
        val = str(find(c, "value")[1])
        lcsc = fields.get("LCSC", "")
        assert lcsc.startswith("C"), (ref, "no LCSC number")
        key = (val, fp, lcsc)
        parts.setdefault(key, []).append(ref)
    with open(os.path.join(outdir, "jlc-bom.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Comment", "Designator", "Footprint", "LCSC"])
        for (val, fp, lcsc), refs in sorted(parts.items(), key=lambda kv: kv[1][0]):
            w.writerow([val, ",".join(sorted(refs)), fp, lcsc])
    rows = list(csv.DictReader(open(pos)))
    with open(os.path.join(outdir, "jlc-cpl.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
        for r in sorted(rows, key=lambda r: r["Ref"]):
            layer = "Top" if r["Side"].lower() == "top" else "Bottom"
            w.writerow([r["Ref"], f'{float(r["PosX"]):.3f}mm', f'{float(r["PosY"]):.3f}mm', layer,
                        f'{float(r["Rot"]):.0f}'])
    print(f"wrote jlc-bom.csv ({len(parts)} lines) and jlc-cpl.csv ({len(rows)} parts)")


if __name__ == "__main__":
    main(*sys.argv[1:4])
