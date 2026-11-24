#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
# Regenerate, check and export the S3 Jack Hat. Written for Pulsar.
# Run from anywhere; exits non-zero on the first failed step or check.
set -eu
cd "$(dirname "$0")/.."
mkdir -p out

python3 tools/gen_pcb.py --footprint-only
python3 tools/gen_schematic.py
kicad-cli sch erc --severity-all --format json --exit-code-violations -o out/erc.json s3-jack-hat.kicad_sch \
    || { echo "ERC reported violations - see out/erc.json"; exit 1; }
kicad-cli sch export netlist --format kicadsexpr -o out/s3-jack-hat.net s3-jack-hat.kicad_sch
(cd tools && python3 check_netlist.py ../out/s3-jack-hat.net)

python3 tools/gen_pcb.py
kicad-cli pcb drc --refill-zones --save-board --schematic-parity --severity-all --format json \
    -o out/drc.json s3-jack-hat.kicad_pcb
python3 - <<'EOF'
import json, sys
d = json.load(open("out/drc.json"))
errs = [v for k in ("violations", "unconnected_items", "schematic_parity")
        for v in d.get(k, []) if v["severity"] == "error"]
warns = [v for k in ("violations", "unconnected_items", "schematic_parity")
         for v in d.get(k, []) if v["severity"] != "error"]
for v in errs + warns:
    print(v["severity"], v["type"], v["description"])
print(f"DRC: {len(errs)} errors, {len(d.get('unconnected_items', []))} unconnected, {len(warns)} warnings")
# The one expected warning: J1's silk is trimmed at the board edge on purpose.
ok = not errs and all(v["type"] == "lib_footprint_mismatch" for v in warns)
sys.exit(0 if ok else 1)
EOF

rm -rf out/gerbers
kicad-cli pcb export gerbers -o out/gerbers/ --check-zones \
    --layers F.Cu,B.Cu,F.Paste,F.SilkS,B.SilkS,F.Mask,B.Mask,Edge.Cuts s3-jack-hat.kicad_pcb
kicad-cli pcb export drill -o out/gerbers/ --format excellon --excellon-separate-th s3-jack-hat.kicad_pcb
rm -f out/s3-jack-hat-gerbers.zip
(cd out/gerbers && zip -q ../s3-jack-hat-gerbers.zip ./*)
kicad-cli pcb export pos --format csv --units mm --side both --use-drill-file-origin \
    -o out/s3-jack-hat-pos.csv s3-jack-hat.kicad_pcb
(cd tools && python3 jlc_files.py ../out/s3-jack-hat.net ../out/s3-jack-hat-pos.csv ../out)
kicad-cli sch export pdf -o out/s3-jack-hat-schematic.pdf s3-jack-hat.kicad_sch
kicad-cli pcb render --side top -w 1200 -h 1000 --zoom 1.0 -o out/render-top.png s3-jack-hat.kicad_pcb
kicad-cli pcb render --side bottom -w 1200 -h 1000 --zoom 1.0 -o out/render-bottom.png s3-jack-hat.kicad_pcb
echo "build OK"
