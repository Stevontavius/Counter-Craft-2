# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Oracle for the skinning assumption: pose the arms and AK meshes with a clip and draw three orthographic views.

usage: preview_pose.py <arms.glb> <arms_mesh_substr> <gun.glb> <gun_mesh_substr> <clip.glb> <t> <out.png>
"""
import sys

import numpy as np
from PIL import Image, ImageDraw

from gltf_lite import Clip, Glb


def mesh_prims(glb, substr):
    for m in glb.j["meshes"]:
        if substr in m.get("name", ""):
            for p in m["primitives"]:
                yield p


def read_model_dump(path):
    """Bone names and the mesh->model bone remapping table from a VRF `-b DATA` dump of a vmdl."""
    import re
    text = open(path, encoding="utf-8-sig").read()
    names = re.findall(r'"([^"]+)"', re.search(r"m_boneName =\s*\[(.*?)\]", text, re.S).group(1))
    remap = [int(x) for x in re.findall(r"-?\d+", re.search(r"m_remappingTable =\s*\[(.*?)\]", text, re.S).group(1))]
    return names, remap


# Source (x fwd, y left, z up, inches) -> glTF mesh space as VRF exports it (X = y, Y = z, Z = x, metres).
SOURCE_TO_GLTF = np.array([[0, 1, 0, 0], [0, 0, 1, 0], [1, 0, 0, 0], [0, 0, 0, 1]], float)
INCH = 0.0254


def model_rest_world(path):
    """World rest matrices of a vmdl's bones, in glTF mesh space, with Source-native bone frames (like the clips)."""
    import re

    from gltf_lite import trs_to_mat
    text = open(path, encoding="utf-8-sig").read()

    def block(key):
        return re.search(key + r" =\s*\[(.*?)\n\t\t\]", text, re.S).group(1)

    def vecs(key):
        return [[float(x) for x in re.findall(r"-?[\d.]+(?:e-?\d+)?", row)] for row in re.findall(r"\[([^\[\]]*)\]", block(key))]

    pos = vecs("m_bonePosParent")
    rot = vecs("m_boneRotParent")
    parent = [int(x) for x in re.findall(r"-?\d+", re.search(r"m_nParent =\s*\[(.*?)\]", text, re.S).group(1))]
    world = []
    for i in range(len(pos)):
        local = trs_to_mat(np.array(pos[i]) * INCH, np.array(rot[i]), [1, 1, 1])
        world.append(local if parent[i] < 0 else world[parent[i]] @ local)
    return [SOURCE_TO_GLTF @ w for w in world]


def remap_by_name(model_bones, skin_joint_names):
    """Model bone index -> clip skin joint index. *_TWIST* helper bones fold onto the bone they twist."""
    idx = {n: k for k, n in enumerate(skin_joint_names)}
    out = []
    for b in model_bones:
        base = b.split("_TWIST")[0]
        if base not in idx:
            raise KeyError(f"bone {b} not in clip skeleton")
        out.append(idx[base])
    return np.array(out)


def skinned(glb, substr, joint_mats):
    out = []
    for p in mesh_prims(glb, substr):
        a = p["attributes"]
        pos = glb.acc(a["POSITION"]).astype(float)
        jnt = glb.acc(a["JOINTS_0"]).astype(int)
        wgt = glb.acc(a["WEIGHTS_0"]).astype(float)
        print(f"  {substr}: {len(pos)} verts, max joint index {jnt.max()}", file=sys.stderr)
        ph = np.c_[pos, np.ones(len(pos))]
        res = np.zeros((len(pos), 4))
        for k in range(4):
            m = joint_mats[np.clip(jnt[:, k], 0, len(joint_mats) - 1)]
            res += wgt[:, k:k + 1] * np.einsum("nij,nj->ni", m, ph)
        out.append(res[:, :3])
    return np.concatenate(out)


def main():
    arms_glb, arms_sub, gun_glb, gun_sub, clip_glb, t, out = sys.argv[1:8]
    clip_file = Glb(clip_glb)
    clip = Clip(clip_file)
    world = clip.world_matrices(float(t))
    skins = clip_file.j["skins"]
    # The weapon skeleton is its own root in the clip; in game it rides on the arms' "wpn" bone.
    node_of = {clip_file.node_name(i): i for i in range(len(clip_file.j["nodes"]))}
    wroot = next(i for i in range(len(clip_file.j["nodes"])) if clip_file.node_name(i).endswith("ak47.vnmskel"))
    attach = world[node_of["wpn"]] @ np.linalg.inv(world[node_of["weapon"]])
    parents = clip_file.parents()

    def under(i, r):
        while i >= 0:
            if i == r:
                return True
            i = parents[i]
        return False

    world = [attach @ w if under(i, wroot) else w for i, w in enumerate(world)]
    mats = []
    for s in skins:
        inv = clip_file.acc(s["inverseBindMatrices"])
        mats.append(np.array([world[j] @ inv[k] for k, j in enumerate(s["joints"])]))
        print("skin", len(s["joints"]), "first joint", clip_file.node_name(s["joints"][0]), file=sys.stderr)

    # Arms: the mesh is in the glove model's own rest pose, so skin = clip world @ inverse(model rest world).
    # Mesh joint indices are already model bone indices (VRF applied m_remappingTable on export).
    arm_bones, _ = read_model_dump(sys.argv[8])
    rest = model_rest_world(sys.argv[8])
    arm_mats = np.array([world[node_of[b.split("_TWIST")[0]]] @ np.linalg.inv(rest[k]) for k, b in enumerate(arm_bones)])
    arms = skinned(Glb(arms_glb), arms_sub, arm_mats)
    gun = skinned(Glb(gun_glb), gun_sub, mats[1])
    allp = np.concatenate([arms, gun])
    lo, hi = allp.min(0), allp.max(0)
    print("posed bounds", lo.round(3), hi.round(3), file=sys.stderr)

    size = 500
    img = Image.new("RGB", (size * 3, size), (20, 20, 24))
    d = ImageDraw.Draw(img)
    for v, (ax, ay, label) in enumerate([(0, 1, "X right / Y up"), (2, 1, "Z right / Y up"), (0, 2, "X right / Z up")]):
        span = max(hi[ax] - lo[ax], hi[ay] - lo[ay]) * 1.1
        cx, cy = (lo[ax] + hi[ax]) / 2, (lo[ay] + hi[ay]) / 2

        def px(p):
            return v * size + (p[ax] - cx) / span * size + size / 2, size / 2 - (p[ay] - cy) / span * size

        for pts, col in ((arms[::3], (230, 120, 90)), (gun[::3], (110, 170, 255))):
            for p in pts:
                x, y = px(p)
                d.point((x, y), fill=col)
        d.text((v * size + 8, 8), label, fill=(255, 255, 255))
    img.save(out)
    print("wrote", out, file=sys.stderr)


if __name__ == "__main__":
    main()
