# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
"""The M5StickS3 Hat2-Bus map, and THE one header-orientation choice.

Written for Pulsar. Imported by gen_schematic.py, gen_pcb.py and check_netlist.py,
so the orientation lives here and nowhere else.

Hat2 pin numbers are M5's (StickS3 docs PDF, page 10 "Hat2-Bus"): LEFT column
pins 1, 3, ... 15, RIGHT column pins 2, 4, ... 16. Pins 2k-1 and 2k face each
other across the socket (column k = 1..8).

Settled 2026-11-10 (see GND_CORNER): which socket row is nearer the stick's back, and at which end of
the socket column 1 sits. Both are settled by one meter reading: find the GND
hole. Look straight into the stick's Hat2 socket from outside, with the stick
lying flat, screen UP (back down, as it lies in the case):

    GND_CORNER = (row, side)
      row  = "upper"  GND is in the row farther from the stick's back
             "lower"  GND is in the row nearer the stick's back
      side = "left" | "right"  GND is at your left / right end of that row

The printed "edge" row (G5 G4 G6 G7 G43 G44 G2 G3) is the one FARTHER from
the back, so GND is "lower", on the left (see the note at GND_CORNER). Change GND_CORNER, re-run the three generators/checks, and everything
(schematic, netlist check, PCB routing) follows.
"""

# CONFIRMED 2026-11-10, two sources that agree: the back sticker's label lines are
# laid out like the rows (G5... line farther from the back, GND... line nearer),
# and the Bruce firmware's StickS3 CC1101 wiring diagram puts the GND wire in the
# row next to the back, at the LED end. Seen from outside, screen up: lower, left.
#
# 🔴 WRONG SIDE - found on the first built board (r0.1), 2026-11-24. The ROW was right,
# the END was not: plugged in component side towards the stick's back, r0.1's pad 1
# (the square one, shutter) sits at the G3 / 5V_IN end, and pad 2 (board GND) lands on
# 5V_IN. Nothing fired; plugged upside down, focus tracked G5/G0. Whatever the stick's
# own label says, what this file must produce is "column 1 at the end farther from pad 1
# as the footprint is placed", i.e. side = "right". r0.2 regenerates from this value
# with tools/build.sh (needs kicad-cli); the committed r0.1 board and gerbers predate it.
GND_CORNER = ("lower", "right")

# M5 pin number -> signal (StickS3 docs PDF p.10; agrees with the schematic's CN2)
HAT2 = {
    1: "GND", 3: "EXT_5V", 5: "G0", 7: "G1", 9: "G8", 11: "BAT", 13: "3V3_L2", 15: "5V_IN",
    2: "G5", 4: "G4", 6: "G6", 8: "G7", 10: "G43", 12: "G44", 14: "G2", 16: "G3",
}
SIGNAL_PIN = {v: k for k, v in HAT2.items()}

# Right-angle 2x08 footprint (KiCad PinHeader_2x08_P2.54mm_Horizontal), in its own
# frame: odd pads at x = 0, even pads at x = 2.54 (the row beside the plastic body),
# pad pair n at y = (n-1) * 2.54, mating pins pointing +x. On a right-angle header
# the row beside the body is the LOWER one (nearer the PCB), so even pads = lower row.
# The PCB top surface sits level with the stick's back, so lower row = row nearer
# the stick's back.


def hat_to_pad(hat_pin):
    """Footprint pad number that lands in M5 Hat2 pin `hat_pin`."""
    row, side = GND_CORNER
    assert row in ("upper", "lower") and side in ("left", "right"), GND_CORNER
    col = (hat_pin + 1) // 2            # 1..8, GND/G5 = 1
    inner = hat_pin % 2 == 1            # the GND row
    upper = inner == (row == "upper")
    # The header is placed with its pins pointing at the stick (north on the board)
    # and footprint y running west -> east, so slot 0 is at the left as seen
    # looking into the socket from the hat.
    slot = col - 1 if side == "left" else 8 - col
    return 2 * slot + (1 if upper else 2)


PAD_SIGNAL = {hat_to_pad(p): s for p, s in HAT2.items()}
assert sorted(PAD_SIGNAL) == list(range(1, 17))
