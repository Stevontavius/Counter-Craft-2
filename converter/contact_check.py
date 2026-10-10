# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Oracle: do the posed hands actually touch the gun? Distance from each hand's finger vertices to the nearest
gun vertex, for one frame of one clip, using the exact .cvm the mod loads."""
import sys
from pathlib import Path

import numpy as np

from preview_cvm import read_cvm
from convert import parse_model_dump

pack, gun, clip, t, dump = Path(sys.argv[1]), sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
parts, clips = read_cvm(pack / "assets" / "countercraft" / "viewmodels" / f"{gun}.cvm")
dur, fps, mats = clips[clip]
m = mats[min(int(t * fps), len(mats) - 1)]
names = parse_model_dump(open(dump, encoding="utf-8-sig").read()).names

def posed(part):
    tex, pos, nrm, uv, jnt, wgt, idx = part
    w = wgt.astype(np.float32) / 255.0
    ph = np.c_[pos, np.ones(len(pos))]
    out = np.zeros((len(pos), 3))
    for k in range(4):
        out += w[:, k:k + 1] * np.einsum("nij,nj->ni", m[jnt[:, k]], ph)
    return out, jnt[np.arange(len(jnt)), wgt.argmax(1)]

arm_pts, arm_dom = posed(parts[0])
gun_pts = np.concatenate([posed(p)[0] for p in parts if "ak47" in p[0]])
print("gun posed bounds (mc view, m):", gun_pts.min(0).round(3), gun_pts.max(0).round(3))
for side in ("R", "L"):
    sel = np.array([names[d].startswith("finger_") and names[d].endswith("_" + side) for d in arm_dom])
    pts = arm_pts[sel]
    d = np.array([np.sqrt(((gun_pts[::4] - p) ** 2).sum(1)).min() for p in pts[::3]])
    print(f"hand {side}: {sel.sum()} finger verts, dist to gun median {np.median(d)*100:.1f} cm, "
          f"10th pct {np.percentile(d,10)*100:.1f} cm, centroid {pts.mean(0).round(3)}")
