// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Pulsar Trigger contributors
//
// Hot-shoe case for the M5StickS3 + S3 Jack Hat. Written for Pulsar.
//
// Two printed parts:
//   base - floor, low side walls, the hat bay and the cold-shoe foot. The stick
//          screws to it through its own two M2 brass inserts (from below).
//   lid  - the roof over the hat plus the far end wall with the jack opening.
//          It slides in along the side-wall grooves from the far end, its hole
//          slipping over the jack nose, and one M2 screw from below holds it.
//
// Frame (the stick's own, measured from M5's StickS3.stl):
//   X 0..24 across the stick, Y 0..48 along it (Y = 0 is the USB-C end,
//   Y = 48 the Hat end), Z = 0.92 the front (screen) face, Z = -14.08 the back.
//   The stick lies screen up. The hat board runs Y 48..69.4, its top surface
//   level with the stick's back (README "Mechanics: numbers for the case").
//
// part = "base" | "lid" | "assembly" (preview with the stick and a hat stand-in)

part = "assembly";
// Preview only: M5's StickS3 model, body 1 (X 0..24) extracted. Not in the repo
// (M5's file); point this at a local copy, or leave it missing and see no stick.
stick_stl = "stick.stl";

/* [Fit] */
c      = 0.30;   // clearance per side around the stick and the board (MJF PA12)
gc     = 0.15;   // clearance in the lid grooves

/* [Stick, from StickS3.stl] */
SX = 24; SY = 48; SR = 3;           // plan size and corner radius
Zf = 0.92; Zb = -14.08;             // front and back faces
insert_xy = [[3.0, 8.67], [21.0, 8.67]];   // M2 brass inserts in the back

/* [Hat board, from the KiCad design] */
HB  = 1.6;                          // board thickness
HY0 = 48; HY1 = 69.4;               // board extent along Y
Zh  = Zb - HB;                      // hat bay floor (board underside)
Ztall = Zb + 5.1;                   // tallest part: the header body
jack_x = 18.4; jack_z = Zb + 1.6;   // jack nose centre
nose_d = 4.0; nose_y1 = HY1 + 1.5;  // nose stands 1.5 proud of the board edge

/* [Case] */
wall   = 2.0;
Zbot   = -17.8;                     // underside of the base
Zs     = -11.4;                     // side-wall top along the stick (below the side buttons, -10.8)
Zr0    = Ztall + 0.35;              // roof underside
roof_t = 1.4;
Zr1    = Zr0 + roof_t;              // roof top
Zw     = Zr1 + 1.15;                // hat-bay wall top: leaves a 1.0 lip above the groove (MJF minimum 0.8)
groove = 0.7;                       // groove depth: leaves 1.3 of wall behind it (MJF wants > 1.2)
endw   = 3.6;                       // lid end-wall thickness
Ye0    = HY1 + c;                   // lid end wall inner face
Ye1    = Ye0 + endw;                // case far end
plug_d = 7.5;                       // counterbore for the cable plug's overmold
lid_screw = [3.0, Ye0 + endw/2];    // M2 from below into the lid end wall

/* [Cold-shoe foot (ISO 518 style, square so it slides either way)] */
foot_c  = [SX/2, 30];               // under the stick, near its mass centre
foot_w  = 18.0;  foot_t = 1.9;      // flange
neck_w  = 12.0;  neck_h = 1.6;      // neck (clears shoe rails up to 1.6 thick)
foot_ch = 0.6;                      // lead-in chamfer on the flange

X0 = -c - wall; X1 = SX + c + wall; // outer width 28.6

$fn = 48;

module rrect(x0, y0, x1, y1, r, z0, z1) {
    translate([0, 0, z0]) linear_extrude(z1 - z0)
        hull() for (x = [x0 + r, x1 - r], y = [y0 + r, y1 - r]) translate([x, y]) circle(r);
}
module box(x0, y0, z0, x1, y1, z1) { translate([x0, y0, z0]) cube([x1 - x0, y1 - y0, z1 - z0]); }

// M2 flat-head screw hole from below: clearance shaft + 90-degree countersink.
module m2_csk(x, y, z_floor_top) {
    translate([x, y, Zbot - 0.01]) {
        cylinder(d = 2.3, h = z_floor_top - Zbot + 0.02);
        cylinder(d1 = 4.2, d2 = 2.3, h = 0.95);
    }
}

module foot() {
    translate([foot_c[0], foot_c[1], 0]) {
        // neck
        translate([-neck_w/2, -neck_w/2, Zbot - neck_h]) cube([neck_w, neck_w, neck_h + 0.01]);
        // flange with chamfered lower edges all round
        hull() {
            translate([-foot_w/2, -foot_w/2, Zbot - neck_h - foot_t + foot_ch])
                cube([foot_w, foot_w, foot_t - foot_ch]);
            translate([-foot_w/2 + foot_ch, -foot_w/2 + foot_ch, Zbot - neck_h - foot_t])
                cube([foot_w - 2*foot_ch, foot_w - 2*foot_ch, foot_ch]);
        }
    }
}

module base() {
    difference() {
        union() {
            // floor, full length
            rrect(X0, -c, X1, Ye1, 1.2, Zbot, Zh);
            // stick floor (raised 1.6 above the hat bay floor) + low side walls
            difference() {
                rrect(X0, -c, X1, HY0, 1.2, Zh, Zs);
                rrect(-c, -c - 1, SX + c, HY0 + 0.01, SR + c, Zb, Zs + 1);
            }
            // hat-bay side walls, up to the lid grooves
            for (x = [[X0, -c], [SX + c, X1]]) box(x[0], HY0, Zh - 0.01, x[1], Ye0, Zw);
            foot();
        }
        // grooves for the lid roof (open at the far end)
        box(-c - groove, HY0, Zr0 - gc, SX + c + groove, Ye0 + 0.01, Zr1 + gc);
        // the plug counterbore runs below the lid's end wall (jack centre is only
        // 3.2 above the bay floor), so the base floor under it is relieved too
        translate([jack_x, nose_y1 + 0.1, jack_z]) rotate([-90, 0, 0])
            cylinder(d = plug_d, h = Ye1 - nose_y1);
        // stick inserts and the lid screw
        for (p = insert_xy) m2_csk(p[0], p[1], Zb);
        m2_csk(lid_screw[0], lid_screw[1], Zh);
    }
}

module lid() {
    difference() {
        union() {
            // roof, riding in the grooves; its near edge stops on the stick's end face
            box(-c - groove + gc, HY0 + 0.2, Zr0, SX + c + groove - gc, Ye0 + 0.01, Zr1);
            // far end wall, full width, from the bay floor up to the wall tops
            box(X0, Ye0, Zh + 0.1, X1, Ye1, Zw);
        }
        // jack nose, then a counterbore for the plug's overmold
        translate([jack_x, Ye0 - 0.01, jack_z]) rotate([-90, 0, 0]) {
            cylinder(d = nose_d + 0.4, h = nose_y1 - Ye0 + 0.11);
            translate([0, 0, nose_y1 - Ye0 + 0.1]) cylinder(d = plug_d, h = endw);
        }
        // pilot for the M2 screw from below (self-taps in PA12)
        translate([lid_screw[0], lid_screw[1], Zh]) cylinder(d = 1.7, h = 6.5);
    }
}

// Rough stand-in for the hat, for the preview only.
module hat_standin() {
    color("darkgreen") box(0, HY0, Zh, SX, HY1, Zb);
    color("black") box(1.84, HY0, Zb, 22.16, HY0 + 2.54, Ztall);      // header body
    color("dimgray") for (y = [13.0, 18.6]) box(6.6 - 3.25, HY0 + y - 2.3, Zb, 6.6 + 3.25, HY0 + y + 2.3, Zb + 4.35);
    color("silver") box(15.9, HY0 + 10.9, Zb, 20.9, HY1, Zb + 3.2);    // jack body
    color("silver") translate([jack_x, HY1, jack_z]) rotate([-90, 0, 0]) cylinder(d = nose_d, h = 1.5);
}

if (part == "base") base();
else if (part == "lid") lid();
else if (part == "assembly") {
    color("SlateGray") base();
    color("LightSlateGray", 0.85) lid();
    color("SteelBlue", 0.9) import(stick_stl);
    hat_standin();
}
