<!--
SPDX-License-Identifier: GPL-3.0-or-later
Copyright (C) 2026 Pulsar Trigger contributors
-->

# S3 Hat (KiCad 10 schematic)

Source: written for Pulsar.

**Status: schematic only. It is not laid out and not built, and nothing here has been
tested on hardware.** The specification is [docs/hardware-s3-hat.md](../../docs/hardware-s3-hat.md).

The S3 Hat is a flat paddle board, about 55 × 75 mm, that the M5StickS3 lies on and plugs
into through a right-angle 2×8 header. It carries:

- a 1S LiPo on a JST-PH connector, with DW01A + FS8205A protection;
- TP4056 charging from a USB-C power-in socket, with load sharing so the board runs from
  USB while the cell charges;
- a slide power switch, then a TPS61023 boost converter to 5V0, which feeds the stick's
  5V_IN;
- a camera-side USB-C that sources 5 V default power (Rp 56k) through an SY6280 limited to
  ~0.52 A, switched by G7;
- a stick-side USB-C that carries D+/D− and GND only;
- two optocouplers (G5 shutter, G6 focus) driving a 2.5 mm jack, so the camera stays
  isolated from the board.

## Files

| Path | What |
| --- | --- |
| `s3-hat.kicad_pro`, `s3-hat.kicad_sch` | The project and its one A2 sheet |
| `s3-hat.kicad_sym` | Custom symbols: TPS61023, SY6280AAC, FS8205A, PJ-242 |
| `sym-lib-table`, `fp-lib-table` | Project library tables (custom library + the stock ones used) |
| `tools/gen_schematic.py` | Generates the schematic and the symbol library. **Edit this, not the sheet** |
| `tools/check_netlist.py` | Asserts the key connections in the exported netlist |
| `tools/sexpr.py` | Small s-expression reader/writer used by both scripts |
| `out/` | ERC report, netlist and PDF from the last generation |

## Open it

Open `s3-hat.kicad_pro` in KiCad 10. To look without KiCad, see `out/s3-hat.pdf`.

## Regenerate and check

Run from `hardware/s3-hat/`:

```sh
python3 tools/gen_schematic.py
kicad-cli sch erc --severity-all --format json -o out/erc.json s3-hat.kicad_sch
kicad-cli sch export netlist --format kicadsexpr -o out/s3-hat.net s3-hat.kicad_sch
kicad-cli sch export pdf -o out/s3-hat.pdf s3-hat.kicad_sch
cd tools && python3 check_netlist.py ../out/s3-hat.net   # exit 0 = all checks pass
```

Every pin is wired by a short stub to a net label, so connectivity is by name. If you
edit the sheet by hand in KiCad, the generator will overwrite the edit the next time it runs.

ERC on 2026-11-09 gave 0 errors and 3 warnings. The warnings say that J1 (TYPE-C 6P),
SW1 (SS-12D10L5) and J6 (PJ-242) have no footprint yet. That is true: they need custom
footprints before layout.

## Before layout

- Draw the custom footprints for J1, SW1 and J6. Make a corrected copy of the HRO
  TYPE-C-31-M-12 footprint for J3/J4: the Shou Han rear shell slots are larger.
- Confirm the PJ-242 pin roles with a meter and a Canon remote plug.
- Confirm the Hat header's pin-1 orientation with the stick lying flat on the board.
- Measure GND and 5V_IN on the stick's Hat connector with a meter, as the spec asks.

The spec has the full list of unverified items, under "Schematic decisions".
