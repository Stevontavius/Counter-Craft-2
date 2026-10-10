# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Oracle for the agent data: reads the converted pack files (skeleton.json, clips.bin, tm_phoenix.cam,
weapons/<gun>.json) the way the mod does, poses the agent with a base clip (+ optional additive clip, applied as
base * delta), places the gun model's `weapon` bone on the `wpn` bone, and renders front / side / top views.

usage: uv run preview_agent.py out.png [base_clip] [additive_clip] [time]
"""
import json
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from gltf_lite import Glb, quat_to_mat  # noqa: E402

ROOT = HERE.parent.parent
AG = ROOT / "cs2-pack" / "CounterCraft-CS2-Assets" / "assets" / "countercraft" / "agents"


def rstr(b, o):
    n = struct.unpack_from("<H", b, o)[0]
    return b[o + 2:o + 2 + n].decode(), o + 2 + n


def read_clips():
    b = (AG / "clips.bin").read_bytes()
    assert b[:4] == b"CAB1"
    n = struct.unpack_from("<i", b, 4)[0]
    o, out = 8, {}
    for _ in range(n):
        name, o = rstr(b, o)
        ln = struct.unpack_from("<i", b, o)[0]
        out[name] = b[o + 4:o + 4 + ln]
        o += 4 + ln
    return out


def read_bone(b, o, frames):
    flags = b[o]
    o += 1
    nr = 1 if flags & 1 else frames
    rot = np.frombuffer(b, "<f4", nr * 4, o).reshape(nr, 4)
    o += nr * 16
    nt = 1 if flags & 2 else frames
    tr = np.frombuffer(b, "<f4", nt * 3, o).reshape(nt, 3)
    o += nt * 12
    sc = None
    if not flags & 4:
        sc = np.frombuffer(b, "<f4", frames, o)
        o += frames * 4
    return (rot, tr, sc), o


def parse_clip(b):
    assert b[:4] == b"CAC1"
    dur, frames, add = struct.unpack_from("<fiB", b, 4)
    o = 13
    nb = struct.unpack_from("<i", b, o)[0]
    o += 4
    bones = []
    for _ in range(nb):
        t, o = read_bone(b, o, frames)
        bones.append(t)
    return {"duration": dur, "frames": frames, "additive": bool(add), "bones": bones, "end": o}


def sample(clip, t):
    f = 0 if clip["frames"] <= 1 or clip["duration"] <= 0 else min(clip["frames"] - 1, t / clip["duration"] * (clip["frames"] - 1))
    k = int(f)
    a = f - k
    k1 = min(k + 1, clip["frames"] - 1)
    out = []
    for rot, tr, sc in clip["bones"]:
        r0, r1 = rot[min(k, len(rot) - 1)], rot[min(k1, len(rot) - 1)]
        if np.dot(r0, r1) < 0:
            r1 = -r1
        r = r0 * (1 - a) + r1 * a
        r /= np.linalg.norm(r)
        t0, t1 = tr[min(k, len(tr) - 1)], tr[min(k1, len(tr) - 1)]
        out.append((r, t0 * (1 - a) + t1 * a))
    return out


def qmul(a, b):
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return np.array([aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz])


def load_mesh():
    b = (AG / "tm_phoenix.cam").read_bytes()
    o = 4
    nb = struct.unpack_from("<i", b, o)[0]
    o += 4
    mb = []
    for _ in range(nb):
        n, o = rstr(b, o)
        par, ai = struct.unpack_from("<ii", b, o)
        o += 8
        rest = np.eye(4)
        rest[:3] = np.frombuffer(b, "<f4", 12, o).reshape(3, 4)
        o += 48
        mb.append((n, par, ai, rest))
    parts = []
    nparts = struct.unpack_from("<i", b, o)[0]
    o += 4
    for _ in range(nparts):
        tex, o = rstr(b, o)
        sub, o = rstr(b, o)
        nv = struct.unpack_from("<i", b, o)[0]
        o += 4
        pos = np.frombuffer(b, "<f4", nv * 3, o).reshape(nv, 3); o += nv * 12
        o += nv * 20
        jn = np.frombuffer(b, np.uint8, nv * 4, o).reshape(nv, 4); o += nv * 4
        wt = np.frombuffer(b, np.uint8, nv * 4, o).reshape(nv, 4).astype(float) / 255; o += nv * 4
        ni = struct.unpack_from("<i", b, o)[0]; o += 4
        idx = np.frombuffer(b, "<i4", ni, o).reshape(-1, 3); o += ni * 4
        parts.append((sub, pos, jn, wt, idx))
    return mb, parts


GUN_GLB = {}


def gun_tris(gun_id, wpn):
    gun = json.loads((AG / "weapons" / f"{gun_id}.json").read_text())
    rest = [np.vstack([np.array(m).reshape(3, 4), [0, 0, 0, 1]]) for m in gun["rest"]]
    wroot = gun["bones"].index("weapon") if "weapon" in gun["bones"] else 0
    place = wpn @ np.linalg.inv(rest[wroot])
    if gun_id not in GUN_GLB:
        name = None
        for cand in (ROOT / "cs2-extract" / "cache" / "gltf").rglob("*.glb"):
            if cand.stem.endswith(gun_id.replace("knife_t", "knife_default_t")) and "physics" not in cand.stem:
                name = cand
                break
        GUN_GLB[gun_id] = Glb(name) if name else None
    g = GUN_GLB[gun_id]
    tris = []
    if g is None:
        return tris
    for m in g.j["meshes"]:
        if "legacy" in m["name"] or "physics" in m["name"]:
            continue
        for p in m["primitives"]:
            pos = g.acc(p["attributes"]["POSITION"])
            idx = g.acc(p["indices"])[:, 0].reshape(-1, 3)
            sp = (place @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
            tris += [(sp[i], (60, 60, 70)) for i in idx]
        break
    return tris


def skin_tris(mb, parts, world):
    nb = len(mb)
    mw = []
    for k, (n, par, ai, rest) in enumerate(mb):
        if ai >= 0:
            mw.append(world[ai])
        elif par >= 0:
            mw.append(mw[par] @ np.linalg.inv(mb[par][3]) @ rest)
        else:
            mw.append(rest)
    skin = np.array([(mw[k] @ np.linalg.inv(mb[k][3]))[:3] for k in range(nb)])
    tris = []
    for sub, pos, jn, wt, idx in parts:
        hp = np.c_[pos, np.ones(len(pos))]
        sp = np.zeros((len(pos), 3))
        for k in range(4):
            sp += wt[:, k:k + 1] * np.einsum("nij,nj->ni", skin[jn[:, k]], hp)
        col = (180, 150, 120) if "gloves" in sub else (120, 130, 110)
        tris += [(sp[i], col) for i in idx]
    return tris


def draw(dr, tris, f, ox, oy, S):
    light = np.array([0.3, 0.8, 0.5])
    light /= np.linalg.norm(light)
    items = []
    for tri, col in tris:
        q = np.array([f(v) for v in tri])
        n = np.cross(q[1] - q[0], q[2] - q[0])
        ln = np.linalg.norm(n)
        if ln < 1e-12:
            continue
        shade = 0.35 + 0.65 * abs(np.dot(n / ln, light))
        items.append((q[:, 2].mean(), q, tuple(int(c * shade) for c in col)))
    items.sort(key=lambda x: x[0])
    for _, q, col in items:
        dr.polygon([(ox + x * S, oy - y * S) for x, y, _ in q], fill=col)


def render_poses(path, out):
    poses = json.loads(Path(path).read_text())
    skel = json.loads((AG / "skeleton.json").read_text())
    names = skel["bones"]
    mb, parts = load_mesh()
    W, H, S = 300, 420, 190
    # side view from the model's right (+X is left, so look along +X), and a 3/4 front view
    views = [lambda v: (v[2], v[1], v[0]), lambda v: (-v[0] * 0.7 + v[2] * 0.7, v[1], v[0] * 0.7 + v[2] * 0.7)]
    img = Image.new("RGB", (W * 2 * min(4, len(poses)), H * ((len(poses) + 3) // 4)), (235, 235, 240))
    dr = ImageDraw.Draw(img)
    for i, p in enumerate(poses):
        m = np.array(p["m"]).reshape(-1, 3, 4)
        world = [np.vstack([x, [0, 0, 0, 1]]) for x in m]
        tris = skin_tris(mb, parts, world)
        try:
            tris += gun_tris(p["weapon"], world[names.index("wpn")])
        except FileNotFoundError:
            pass
        cx, cy = (i % 4) * W * 2, (i // 4) * H
        for vi, f in enumerate(views):
            draw(dr, tris, f, cx + vi * W + W // 2, cy + H - 25, S)
        dr.text((cx + 6, cy + 6), p["name"], fill=(0, 0, 0))
    img.save(out)
    print("wrote", out)


def main():
    if len(sys.argv) > 2 and sys.argv[1] == "--poses":
        render_poses(sys.argv[2], sys.argv[3])
        return
    out = sys.argv[1]
    base = sys.argv[2] if len(sys.argv) > 2 else "rifle/_default_rifle/idle_rifle"
    addn = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] != "-" else "rifle/rifle_ak/idle_ak"
    t = float(sys.argv[4]) if len(sys.argv) > 4 else 0.0
    skel = json.loads((AG / "skeleton.json").read_text())
    clips = read_clips()
    pose = sample(parse_clip(clips[base]), t)
    if addn:
        d = sample(parse_clip(clips[addn]), t)
        pose = [(qmul(r, dr), tr + dt) for (r, tr), (dr, dt) in zip(pose, d)]
    names, parents = skel["bones"], skel["parents"]
    world = []
    for i, (r, tr) in enumerate(pose):
        m = np.eye(4)
        m[:3, :3] = quat_to_mat(r)
        m[:3, 3] = tr
        world.append(m if parents[i] < 0 else world[parents[i]] @ m)

    # agent mesh
    b = (AG / "tm_phoenix.cam").read_bytes()
    o = 4
    nb = struct.unpack_from("<i", b, o)[0]
    o += 4
    mb = []
    for _ in range(nb):
        n, o = rstr(b, o)
        par, ai = struct.unpack_from("<ii", b, o)
        o += 8
        rest = np.eye(4)
        rest[:3] = np.frombuffer(b, "<f4", 12, o).reshape(3, 4)
        o += 48
        mb.append((n, par, ai, rest))
    # posed model bones: animated -> anim world; others ride their parent at rest offset
    mw = []
    for k, (n, par, ai, rest) in enumerate(mb):
        if ai >= 0:
            mw.append(world[ai])
        elif par >= 0:
            mw.append(mw[par] @ np.linalg.inv(mb[par][3]) @ rest)
        else:
            mw.append(rest)
    skin = [mw[k] @ np.linalg.inv(mb[k][3]) for k in range(nb)]
    tris = []
    nparts = struct.unpack_from("<i", b, o)[0]
    o += 4
    for _ in range(nparts):
        tex, o = rstr(b, o)
        sub, o = rstr(b, o)
        nv = struct.unpack_from("<i", b, o)[0]
        o += 4
        pos = np.frombuffer(b, "<f4", nv * 3, o).reshape(nv, 3); o += nv * 12
        o += nv * 12  # normals
        o += nv * 8  # uv
        jn = np.frombuffer(b, np.uint8, nv * 4, o).reshape(nv, 4); o += nv * 4
        wt = np.frombuffer(b, np.uint8, nv * 4, o).reshape(nv, 4).astype(float) / 255; o += nv * 4
        ni = struct.unpack_from("<i", b, o)[0]; o += 4
        idx = np.frombuffer(b, "<i4", ni, o).reshape(-1, 3); o += ni * 4
        hp = np.c_[pos, np.ones(nv)]
        sp = np.zeros((nv, 3))
        for k in range(4):
            mats = np.array([skin[j][:3] for j in range(nb)])[jn[:, k]]
            sp += wt[:, k:k + 1] * np.einsum("nij,nj->ni", mats, hp)
        col = (180, 150, 120) if "gloves" in sub else (120, 130, 110)
        tris += [(sp[i], col) for i in idx]

    # gun
    gun = json.loads((AG / "weapons" / "ak47.json").read_text())
    rest = [np.vstack([np.array(m).reshape(3, 4), [0, 0, 0, 1]]) for m in gun["rest"]]
    wroot = gun["bones"].index("weapon")
    wpn = world[names.index("wpn")]
    place = wpn @ np.linalg.inv(rest[wroot])
    g = Glb(next((ROOT / "cs2-extract" / "cache" / "gltf").rglob("weapon_rif_ak47.glb")))
    for m in g.j["meshes"]:
        if not m["name"].endswith("body_hd"):
            continue
        for p in m["primitives"]:
            pos = g.acc(p["attributes"]["POSITION"])
            idx = g.acc(p["indices"])[:, 0].reshape(-1, 3)
            sp = (place @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
            tris += [(sp[i], (60, 60, 70)) for i in idx]

    views = [("front", lambda v: (-v[0], v[1], v[2])), ("side (right)", lambda v: (v[2], v[1], -v[0])), ("top", lambda v: (-v[0], -v[2], v[1]))]
    W, H, S = 420, 520, 230
    img = Image.new("RGB", (W * 3, H), (235, 235, 240))
    dr = ImageDraw.Draw(img)
    light = np.array([0.3, 0.8, 0.5])
    light /= np.linalg.norm(light)
    for vi, (label, f) in enumerate(views):
        items = []
        for tri, col in tris:
            q = np.array([f(v) for v in tri])
            n = np.cross(q[1] - q[0], q[2] - q[0])
            ln = np.linalg.norm(n)
            if ln < 1e-12:
                continue
            shade = 0.35 + 0.65 * abs(np.dot(n / ln, light))
            items.append((q[:, 2].mean(), q, tuple(int(c * shade) for c in col)))
        items.sort(key=lambda x: -x[0])
        ox, oy = vi * W + W // 2, H - 40
        for _, q, col in items:
            dr.polygon([(ox + x * S, oy - y * S) for x, y, _ in q], fill=col)
        dr.text((vi * W + 8, 8), f"{label}: {base} + {addn} @ {t}", fill=(0, 0, 0))
    img.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main()
