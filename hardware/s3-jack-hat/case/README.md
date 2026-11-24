<!--
SPDX-License-Identifier: GPL-3.0-or-later
Copyright (C) 2026 Pulsar Trigger contributors
-->

# S3 Jack Hat case (OpenSCAD)

Source: written for Pulsar. A hot-shoe case for the M5StickS3 with the S3 Jack Hat
plugged in. The stick lies screen up; the screen, the front button and both side buttons
stay uncovered, and the USB-C and Grove end is open for a power bank.

**Status: V1 ordered (JLC MJF PA12, 2026-11-13), accepted with JLC's thin-wall warning.** The
parts render as valid solids and do not overlap the stick or the hat (checked in OpenSCAD).
Nothing has been test-fitted. See "V1 thin walls" below before ordering V2.

## Parts

| File | What |
|---|---|
| `out/base.stl` | Floor, low side walls, the hat bay and the cold-shoe foot |
| `out/lid.stl` | Roof over the hat and the far end wall with the jack opening |

Print both in **MJF PA12 nylon** (JLC 3D printing). Nylon flexes where resin cracks, which
matters for the shoe foot and the lid grooves. Clearances assume MJF (`c = 0.30` per side).
For resin (SLA), try `c = 0.20`.

## Screws

- 2 × **M2 × 8 flat head** from below into the stick's two brass inserts (back, USB end).
- 1 × **M2 × 6 flat head** from below into the lid's end wall (self-taps in PA12).

## Assembly

1. Plug the hat into the stick.
2. Lower them into the base together. The hat drops into the bay; the stick sits on the
   floor.
3. Screw the stick down with the two M2 × 8.
4. Slide the lid in from the far end along the side-wall grooves. Its hole slips over the
   jack nose, and its roof stops against the stick's end face. Pushing the lid home also
   pushes the hat fully onto the stick.
5. Fit the M2 × 6 from below.

## Numbers

All in the stick's frame: X 0..24 across, Y 0..48 along (Y = 0 the USB-C end), Z = −14.08
the back. They were measured from M5's StickS3.stl (body 1).

| Feature | Value |
|---|---|
| Outside | 28.6 × 73.6 mm; 22.2 mm tall at the stick including the foot |
| Side walls along the stick | up to Z −11.4, below both side buttons (they start at −10.8) |
| Side buttons | X = 0 face: Y 6.2–11.0. X = 24 face: Y 19.2–29.0. Both Z −3.0 to −10.8 |
| Front button | Y 9.5–11.6, X 6.2–17.9 |
| USB-C opening | X 7.5–16.6, Z −2.7 to −5.8 |
| Grove opening | X 6–18, Z −7.0 to −11.5 |
| Brass inserts | (3.0, 8.67) and (21.0, 8.67), Ø ~1.9, 12 mm deep |
| Hat bay | floor at Z −15.68 (1.6 below the back, the board thickness); roof underside −8.63, 0.35 above the header body |
| Jack opening | X 18.4, Z −12.48; Ø4.4 for the nose, then a Ø7.5 counterbore for the plug's overmold. The counterbore dips 0.55 below the bay floor, so the base floor under the end wall is relieved to match |
| Foot | 18 × 18 × 1.9 flange on a 12 × 12 × 1.6 neck, centred at Y = 30. Square, so it slides into the shoe along either axis |

## 🔴 Unverified

- **The shoe fit.** Flange thickness and width vary between shoes. Check against the
  camera first; `foot_t` and `foot_w` are the knobs.
- **The plug.** The counterbore assumes the Canon cable's overmold is under Ø7.5.
  🔴 **Wrong on the first print (2026-11-24):** the owner's right-angle 2.5 mm plug cannot
  enter the opening far enough to reach the jack - the entrance must be wider. Fix for V2:
  `plug_d` from the overmold's measured width at its front face (plus clearance), and keep
  the U-slot fix below so a wider counterbore does not leave knife edges. Not yet changed:
  the overmold has not been measured.
- **The lid grooves.** The ledge above each groove is 1.0 mm and the wall behind it 1.3 mm,
  just inside JLC's MJF limits (thinnest part 0.8, walls over 1.2). Fine in PA12; fragile in resin.
- **The hat's real heights.** The bay uses the design heights (header 5.1, jack 3.2).

## V1 thin walls (JLC review, 2026-11-13)

JLC flagged two spots below their 1.0 mm nylon minimum. They recommend 2.0. V1 was printed
anyway. A ray-cast thickness check of the same STLs found both, plus three more.

| Where | Thinnest | Cause | V2 fix |
|---|---|---|---|
| Lid, bottom of the end wall, either side of the plug counterbore (JLC "A", 0.39) | knife edge | The Ø7.5 counterbore breaks out through the end wall's underside (X 16.3-20.5), meeting it at a shallow angle | Extend the counterbore straight down as a U-slot (hull with a copy below `Zh`), so its sides meet the underside square |
| Base, the stick pocket's corners at the hat end, Y 48 (JLC "B", 0.09) | knife edge | The pocket's r 3.3 corners end at Y 48.01, leaving corner fillets that taper to nothing | Run the pocket past both ends by `SR + c` so its rounded corners fall in empty space |
| Base, the same corners at the USB end, Y -0.3 | knife edge | The same, at the other end (the pocket starts only 1 mm past the face) | The same fix |
| Lid, the M2 pilot in the end wall | 0.95 | Ø1.7 pilot centred in a 3.6 wall | `endw` 3.6 -> 5.0 (1.65 each side) |
| Base, the lid screw's countersink at the far end | breaks through by 0.3 | Countersink r 2.1, 1.8 from the end face | The same `endw` change (0.4 at the rim) |
| Lid, floor under the jack-nose bore | 0.90 | Ø4.4 bore bottom 0.9 above the end wall's underside | The U-slot fix for the nose bore too |

What to check on V1: ragged or missing slivers at the counterbore and in the pocket corners
(trim with a knife, so they do not catch the stick or the lid). Drive the self-tapping M2 into
the lid gently: 0.95 mm of wall either side can split. The groove lips are exactly 1.0, at
JLC's limit; V2 could raise `Zw` by 0.2.

Re-check after any change:
`python thick.py out/lid.stl 1.0` (`thick.py` here; trimesh: sample each face, cast inward,
report spots thinner than the limit; zero readings at sharp edges are real knife edges).

## Rebuild

```sh
openscad -D 'part="base"' -o out/base.stl case.scad
openscad -D 'part="lid"'  -o out/lid.stl  case.scad
```

`part="assembly"` previews both with a hat stand-in. It also shows the stick if a copy of
M5's model, body 1 only, is saved as `stick.stl` here. That file is M5's, so it is not committed.
