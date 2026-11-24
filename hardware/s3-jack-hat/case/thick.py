# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Pulsar Trigger contributors
# Wall-thickness check for the case STLs (pip install trimesh numpy scipy rtree).
# usage: thick.py <part.stl> [limit-mm]
import sys, numpy as np, trimesh
m = trimesh.load(sys.argv[1]); lim = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
# sample points on every face, cast a ray inward (-normal), distance to first exit = local thickness
pts, fi = trimesh.sample.sample_surface_even(m, 60000)
n = m.face_normals[fi]
o = pts - n * 1e-3
loc, ri, _ = m.ray.intersects_location(o, -n, multiple_hits=False)
t = np.full(len(pts), np.inf); t[ri] = np.linalg.norm(loc - o[ri], axis=1)
thin = t < lim
print(f"{sys.argv[1].split('/')[-1]}: watertight={m.is_watertight} bounds={np.round(m.bounds,2).tolist()}")
print(f"  samples {len(pts)}, thinner than {lim} mm: {thin.sum()}  min {t.min():.2f} mm")
# cluster thin samples into regions (1 mm grid)
P = pts[thin]; T = t[thin]
keys = {}
for p, tt in zip(P, T):
    k = tuple(np.round(p / 1.5).astype(int))
    keys.setdefault(k, []).append((tt, p))
regs = sorted(keys.values(), key=lambda v: min(x[0] for x in v))
seen = []
for v in regs:
    tt, p = min(v, key=lambda x: x[0])
    if any(np.linalg.norm(p - q) < 3 for q in seen): continue
    seen.append(p)
    print(f"  {tt:.2f} mm at X {p[0]:.2f} Y {p[1]:.2f} Z {p[2]:.2f}  ({len(v)} pts nearby)")
    if len(seen) >= 12: break
