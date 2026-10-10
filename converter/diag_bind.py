# /// script
# dependencies = ["numpy"]
# ///
"""Compare where the arm mesh's vertices sit per bone against the clip skeleton's bind pose."""
import sys

import numpy as np

from gltf_lite import Glb
from preview_pose import mesh_prims, read_model_dump

arms_glb, sub, clip_glb, dump = sys.argv[1:5]
names, remap = read_model_dump(dump)
g = Glb(arms_glb)
c = Glb(clip_glb)
skin = c.j["skins"][0]
inv = c.acc(skin["inverseBindMatrices"])
bind = {c.node_name(j): np.linalg.inv(inv[k])[:3, 3] for k, j in enumerate(skin["joints"])}

p = next(mesh_prims(g, sub))
pos = g.acc(p["attributes"]["POSITION"]).astype(float)
jnt = g.acc(p["attributes"]["JOINTS_0"]).astype(int)
wgt = g.acc(p["attributes"]["WEIGHTS_0"]).astype(float)
dom = jnt[np.arange(len(jnt)), wgt.argmax(1)]
for mesh_j in sorted(set(dom.tolist()))[:60]:
    for label, bone in (("remap", names[remap[mesh_j]]), ("direct", names[mesh_j] if mesh_j < len(names) else "?")):
        pass
    sel = pos[dom == mesh_j]
    rb, db = names[remap[mesh_j]], names[mesh_j]
    print(f"mesh j{mesh_j:2d} n={len(sel):4d} centroid={sel.mean(0).round(3)} "
          f"| remap->{rb:24s} bind={bind.get(rb.split('_TWIST')[0], np.full(3, np.nan)).round(3)} "
          f"| direct->{db:24s} bind={bind.get(db.split('_TWIST')[0], np.full(3, np.nan)).round(3)}")
