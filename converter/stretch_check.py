# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Oracle for skin distortion: ratio of posed to rest edge length over the arm mesh (1.0 = rigid)."""
import sys
from pathlib import Path

import numpy as np

from preview_cvm import read_cvm

pack, gun = Path(sys.argv[1]), sys.argv[2]
parts, clips = read_cvm(pack / "assets" / "countercraft" / "viewmodels" / f"{gun}.cvm")
for clip, (dur, fps, mats) in clips.items():
    worst = []
    for f in np.linspace(0, len(mats) - 1, min(len(mats), 12)).astype(int):
        m = mats[f]
        for tex, pos, nrm, uv, jnt, wgt, idx in parts[:2]:  # arm parts
            w = wgt.astype(np.float32) / 255.0
            ph = np.c_[pos, np.ones(len(pos))]
            sp = np.zeros((len(pos), 3))
            for k in range(4):
                sp += w[:, k:k + 1] * np.einsum("nij,nj->ni", m[jnt[:, k]], ph)
            e = idx.reshape(-1, 3)
            a, b = np.r_[e[:, 0], e[:, 1], e[:, 2]], np.r_[e[:, 1], e[:, 2], e[:, 0]]
            r0 = np.linalg.norm(pos[a] - pos[b], axis=1)
            ok = r0 > 1e-5
            ratio = np.linalg.norm(sp[a] - sp[b], axis=1)[ok] / r0[ok]
            worst.append((np.percentile(ratio, 99), ratio.max()))
    w = np.array(worst)
    print(f"{clip:8s} edge stretch: 99th pct {w[:, 0].max():.2f}x, max {w[:, 1].max():.2f}x")
