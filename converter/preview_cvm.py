# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Oracle for the in-game viewmodel: render a .cvm exactly as the mod would (same skinning, same view space)
through a Minecraft-like hand camera (70 degree vertical FOV), painter's algorithm, textured + lambert shaded.

usage: preview_cvm.py <pack dir> <gun> <clip> <time s> <out.png>
"""
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

W, H, FOV = 960, 540, 70.0
OFFSET = np.array([0.0254, -0.0254, -0.0254])  # ViewmodelRenderer: right, up(-1"), forward(+1" = -z)


def read_cvm(path):
    b = open(path, "rb").read()
    o = 0

    def take(fmt):
        nonlocal o
        v = struct.unpack_from(fmt, b, o)
        o += struct.calcsize(fmt)
        return v

    def arr(dtype, n):
        nonlocal o
        a = np.frombuffer(b, dtype, n, o).copy()
        o += a.nbytes
        return a

    def s():
        (n,) = take("<H")
        return take(f"<{n}s")[0].decode()

    assert b[:4] == b"CVM1"
    o = 4
    (bones,) = take("<i")
    (np_,) = take("<i")
    parts = []
    for _ in range(np_):
        tex = s()
        (n,) = take("<i")
        pos, nrm, uv = arr("<f4", n * 3).reshape(n, 3), arr("<f4", n * 3).reshape(n, 3), arr("<f4", n * 2).reshape(n, 2)
        jnt, wgt = arr("u1", n * 4).reshape(n, 4), arr("u1", n * 4).reshape(n, 4)
        (ni,) = take("<i")
        idx = arr("<i4", ni)
        parts.append((tex, pos, nrm, uv, jnt, wgt, idx))
    clips = {}
    (nc,) = take("<i")
    for _ in range(nc):
        name = s()
        dur, fps, frames = take("<ffi")
        mats = arr("<f4", frames * bones * 12).reshape(frames, bones, 3, 4)
        (ne,) = take("<i")
        for _ in range(ne):
            take("<f")
            s()
        clips[name] = (dur, fps, mats)
    return parts, clips


def main():
    global FOV
    pack, gun, clip, t, out = Path(sys.argv[1]), sys.argv[2], sys.argv[3], float(sys.argv[4]), sys.argv[5]
    if len(sys.argv) > 6:
        FOV = float(sys.argv[6])
    assets = pack / "assets" / "countercraft"
    parts, clips = read_cvm(assets / "viewmodels" / f"{gun}.cvm")
    dur, fps, mats = clips[clip]
    m = mats[min(int(t * fps), len(mats) - 1)]

    f = (H / 2) / np.tan(np.radians(FOV / 2))
    light = np.array([0.3, 0.8, 0.5]) / np.linalg.norm([0.3, 0.8, 0.5])
    polys = []
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
        sp += OFFSET
        sn /= np.maximum(np.linalg.norm(sn, axis=1, keepdims=True), 1e-6)
        tri = idx.reshape(-1, 3)
        z = sp[tri][:, :, 2]
        keep = (z < -0.02).all(1)
        for a, bb, c in tri[keep]:
            p = sp[[a, bb, c]]
            scr = [(W / 2 + f * x / -zz, H / 2 - f * y / -zz) for x, y, zz in p]
            u, v = uv[[a, bb, c]].mean(0)
            col = img[int(np.clip(v, 0, 0.999) * img.shape[0]), int(np.clip(u, 0, 0.999) * img.shape[1])]
            shade = 0.45 + 0.55 * max(0.0, float(sn[[a, bb, c]].mean(0) @ light))
            polys.append((float(p[:, 2].mean()), scr, tuple(int(x * shade) for x in col)))
    polys.sort(key=lambda q: q[0])  # far (most negative z) first
    im = Image.new("RGB", (W, H), (120, 160, 210))
    d = ImageDraw.Draw(im)
    for _, scr, col in polys:
        d.polygon(scr, fill=col)
    d.line([(W / 2 - 8, H / 2), (W / 2 + 8, H / 2)], fill=(255, 255, 255))
    d.line([(W / 2, H / 2 - 8), (W / 2, H / 2 + 8)], fill=(255, 255, 255))
    im.save(out)
    print("wrote", out, len(polys), "triangles")


if __name__ == "__main__":
    main()
