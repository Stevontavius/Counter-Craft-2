# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Orthographic side view (camera on the right, looking left) of a posed .cvm frame: shows hand/gun contact.

usage: side_view.py <pack dir> <gun> <clip> <time s> <out.png>
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from preview_cvm import read_cvm

W, H = 1200, 620
Z_NEAR, Z_FAR = 0.05, -1.10  # view-space z range shown (forward is -z)
Y_TOP, Y_BOTTOM = 0.15, -0.45


def main():
    pack, gun, clip, t, out = Path(sys.argv[1]), sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
    assets = pack / "assets" / "countercraft"
    parts, clips = read_cvm(assets / "viewmodels" / f"{gun}.cvm")
    dur, fps, mats = clips[clip]
    m = mats[min(int(t * fps), len(mats) - 1)]
    s = min(W / (Z_NEAR - Z_FAR), H / (Y_TOP - Y_BOTTOM))

    def px(p):
        return ((Z_NEAR - p[2]) * s, (Y_TOP - p[1]) * s)

    polys = []
    light = np.array([0.6, 0.6, 0.5]) / np.linalg.norm([0.6, 0.6, 0.5])
    for tex, pos, nrm, uv, jnt, wgt, idx in parts:
        img = np.asarray(Image.open(assets / tex.split(":", 1)[1]).convert("RGB"))
        w = wgt.astype(np.float32) / 255.0
        ph = np.c_[pos, np.ones(len(pos))]
        sp = np.zeros((len(pos), 3))
        sn = np.zeros((len(pos), 3))
        for k in range(4):
            mk = m[jnt[:, k]]
            sp += w[:, k:k + 1] * np.einsum("nij,nj->ni", mk, ph)
            sn += w[:, k:k + 1] * np.einsum("nij,nj->ni", mk[:, :, :3], nrm)
        sn /= np.maximum(np.linalg.norm(sn, axis=1, keepdims=True), 1e-6)
        tri = idx.reshape(-1, 3)
        uvc = uv[tri].mean(1)
        cols = img[(np.clip(uvc[:, 1], 0, .999) * img.shape[0]).astype(int), (np.clip(uvc[:, 0], 0, .999) * img.shape[1]).astype(int)]
        shade = 0.4 + 0.6 * np.clip(sn[tri].mean(1) @ light, 0, 1)
        depth = sp[tri][:, :, 0].mean(1)
        for k in range(len(tri)):
            polys.append((depth[k], sp[tri[k]], tuple((cols[k] * shade[k]).astype(int).tolist())))
    polys.sort(key=lambda q: q[0])  # camera on the right (+x): draw small x first
    im = Image.new("RGB", (W, H), (120, 160, 210))
    d = ImageDraw.Draw(im)
    for gz in np.arange(0.0, Z_FAR - 0.01, -0.1):
        d.line([px((0, Y_TOP, gz))[0], 0, px((0, Y_TOP, gz))[0], H], fill=(105, 145, 195))
    for _, p, col in polys:
        d.polygon([px(v) for v in p], fill=col)
    ex, ey = px((0, 0, 0))
    d.ellipse([ex - 6, ey - 6, ex + 6, ey + 6], outline=(255, 0, 0), width=2)
    d.text((8, 8), f"{clip} t={t}: side view from the right, red = eye, grid 10 cm", fill=(0, 0, 0))
    im.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
