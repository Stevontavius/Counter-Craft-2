"""CS2 agents (third-person player models) for Counter-Craft 2.

Reads the user's CS2 install (via the converter's Cs2 helper / Source 2 Viewer) and writes into the asset pack:
- agents/skeleton.json: CS2's third-person animation skeleton (animation/skeletons/characters/worldmodel.vnmskel):
  bone names, parents, reference pose (local, glTF axes at the root, metres) and its named bone masks (weights per
  bone, unlisted bones inherit their parent's weight).
- agents/graphs/<name>.json: every compiled third-person animation graph (animation/graphs/worldmodel/*.vnmgraph):
  the node definitions as CS2 compiled them, the control parameters, and the data slots resolved to clip ids /
  referenced graph names. The mod runs these graphs with its own interpreter (dev.countercraft.client.agent.graph).
- agents/clips.bin: every clip those graphs use (one archive of CAC1 clips by path), sampled per frame (local bone rotations/translations/scales
  in skeleton order), additive flag, sync track, ID events, and the weapon skeleton's tracks (secondary animation).
- agents/<agent>.cam: the agent's third-person mesh (body + gloves), skinned to the agent model's own skeleton, with
  its rest pose and the mapping from model bones to animation skeleton bones.
- agents/weapons/<gun>.json: each gun model's bones and rest pose, so the gun (taken from its viewmodel .cvm mesh)
  can sit in the agent's hands on the wpn bone.

Axes: model space is glTF as VRF exports it (X = Source left, Y = up, Z = Source forward, metres), which is also a
Minecraft entity's model space when the entity faces +Z.
"""
import json
import re
import struct
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import kv3  # noqa: E402
from gltf_lite import Clip, Glb, quat_to_mat  # noqa: E402

GRAPH_DIR = "animation/graphs/worldmodel/"
SKELETON = "animation/skeletons/characters/worldmodel.vnmskel_c"
INCH = 0.0254
DEFAULT_AGENT = ("tm_phoenix", "agents/models/tm_phoenix/tm_phoenix.vmdl_c")
# CS2's default CT agent (items_game customplayer_ct_map_based)
CT_AGENT = ("ctm_sas", "agents/models/ctm_sas/ctm_sas.vmdl_c")
AGENT_MESHES = ("thirdperson_body", "thirdperson_default_gloves")
# Source (x fwd, y left, z up, inches) -> glTF (X = y, Y = z, Z = x, metres)
SOURCE_TO_GLTF = np.array([[0, 1, 0, 0], [0, 0, 1, 0], [1, 0, 0, 0], [0, 0, 0, 1]], float)


def kv3_data(text):
    """The DATA block of one VRF `-b DATA` dump, parsed (binary blobs dropped)."""
    text = text[text.index('--- Data for block "DATA" ---'):]
    text = text[text.index("-->") + 3:]
    text = re.sub(r"#\[[^\]]*\]", "null", text)
    return kv3.loads(text)


def split_dump(text):
    """A multi-file VRF dump -> {path: text}."""
    out = {}
    parts = re.split(r"^\[\d+/\d+\] (\S+)\s*$", text, flags=re.M)
    for i in range(1, len(parts) - 1, 2):
        out[parts[i]] = parts[i + 1]
    return out


def asset_name(res):
    """animation/graphs/worldmodel/worldmodel_gun.vnmgraph+ak47.vnmgraph -> worldmodel_gun__ak47;
    animation/anims/world/rifle/rifle_ak/shoot_ak.vnmclip -> rifle/rifle_ak/shoot_ak"""
    res = res[:-2] if res.endswith("_c") else res
    if "/graphs/" in res:
        base = res.split("/")[-1].replace(".vnmgraph", "")
        return base.replace("+", "__")
    return res.replace("animation/anims/world/", "").replace(".vnmclip", "")


# ---------------------------------------------------------------- skeleton

def skeleton(cs2, ref_glb):
    text = cs2.data_block(SKELETON, "agent_worldmodel_skeleton")
    d = kv3_data(text)
    names = d["m_boneIDs"]
    parents = d["m_parentIndices"]
    g = Glb(ref_glb)
    node = {g.node_name(i): g.j["nodes"][i] for i in range(len(g.j["nodes"]))}
    ref = []
    for n in names:
        nd = node[n]
        ref.append({"r": [round(float(x), 7) for x in nd.get("rotation", [0, 0, 0, 1])],
                    "t": [round(float(x), 6) for x in nd.get("translation", [0, 0, 0])]})
    masks = {}
    for m in d.get("m_maskDefinitions", []):
        pw = m["m_primaryWeightList"]
        listed = dict(zip(pw["m_boneIDs"], pw["m_weights"]))
        w = []
        for i, n in enumerate(names):
            if n in listed:
                w.append(float(listed[n]))
            else:
                w.append(w[parents[i]] if parents[i] >= 0 else 0.0)
        masks[m["m_ID"]] = [round(x, 4) for x in w]
    return {"bones": names, "parents": parents, "ref": ref, "masks": masks}


# ---------------------------------------------------------------- graphs

def graphs(cs2, log):
    """All compiled third-person graphs: {name: json-able dict}, and the set of clip resources they use."""
    text = cs2.data_block(GRAPH_DIR, "agent_graphs_all")
    out, clips = {}, set()
    for path, body in split_dump(text).items():
        if not path.endswith(".vnmgraph_c"):
            continue
        d = kv3_data(body)
        res = []
        for r in d.get("m_resources", []):
            r = str(r)
            if r.endswith(".vnmclip"):
                res.append({"clip": asset_name(r)})
                clips.add(r)
            elif r.endswith(".vnmgraph"):
                res.append({"graph": asset_name(r)})
            else:
                res.append({"other": r})
        nodes = d["m_nodes"]
        name = asset_name(path)
        out[name] = {
            "name": name,
            "root": d["m_nRootNodeIdx"],
            "params": d["m_controlParameterIDs"],
            "virtualParams": d.get("m_virtualParameterIDs", []),
            "virtualParamNodes": d.get("m_virtualParameterNodeIndices", []),
            "persistent": d.get("m_persistentNodeIndices", []),
            "refSlots": [[s["m_nNodeIdx"], s["m_dataSlotIdx"]] for s in d.get("m_referencedGraphSlots", [])],
            "resources": res,
            "paths": d.get("m_nodePaths", []),
            "nodes": nodes,
        }
    log(f"agent: {len(out)} graphs, {len(clips)} clips")
    return out, clips


# ---------------------------------------------------------------- clips

def clip_meta(body):
    d = kv3_data(body)
    sync = [(e["m_ID"], float(e["m_startTime"]["m_flValue"]), float(e["m_duration"]["m_flValue"]))
            for e in d["m_syncTrack"]["m_syncEvents"]]
    events = []
    for e in d.get("m_events", []):
        if e.get("_class") == "CNmIDEvent":
            events.append((e["m_ID"], float(e["m_flStartTime"]["m_flValue"]), float(e["m_flDuration"]["m_flValue"])))
    return {"frames": int(d["m_nNumFrames"]), "duration": float(d["m_flDuration"]), "additive": bool(d["m_bIsAdditive"]),
            "sync": sync, "syncOffset": int(d["m_syncTrack"].get("m_nStartEventOffset", 0)), "events": events}


def _wstr(f, s):
    b = s.encode("utf-8")
    f.write(struct.pack("<H", len(b)))
    f.write(b)


def _tracks(clip, node, rest, times):
    """(rot frames x 4, trans frames x 3, scale frames x 3) sampled at times; rest TRS for missing channels."""
    from gltf_lite import sample
    tr = clip.tracks.get(node, {})
    out = []
    for path, dflt in (("rotation", rest[1]), ("translation", rest[0]), ("scale", rest[2])):
        if path in tr:
            t, v = tr[path]
            out.append(np.array([sample(t, v, x, path) for x in times], float))
        else:
            out.append(np.tile(np.asarray(dflt, float), (len(times), 1)))
    return out


def _write_bone(f, rot, trans, scale):
    """flags: 1 = rotation static, 2 = translation static, 4 = scale static (all ones)."""
    rot = rot / np.linalg.norm(rot, axis=1, keepdims=True)
    # keep one hemisphere so interpolation between frames is short
    for k in range(1, len(rot)):
        if np.dot(rot[k], rot[k - 1]) < 0:
            rot[k] = -rot[k]
    rs = np.abs(rot - rot[0]).max() < 1e-5
    ts = np.abs(trans - trans[0]).max() < 1e-6
    ss = np.abs(scale - 1.0).max() < 1e-5
    f.write(struct.pack("<B", (1 if rs else 0) | (2 if ts else 0) | (4 if ss else 0)))
    f.write(np.ascontiguousarray(rot[:1] if rs else rot, "<f4").tobytes())
    f.write(np.ascontiguousarray(trans[:1] if ts else trans, "<f4").tobytes())
    if not ss:
        f.write(np.ascontiguousarray(scale[:, 0], "<f4").tobytes())


def write_clip(glb_path, meta, bones, f):
    g = Glb(glb_path)
    clip = Clip(g)
    nodes = g.j["nodes"]
    idx = {g.node_name(i): i for i in range(len(nodes))}
    parents = g.parents()
    frames = max(1, meta["frames"])
    duration = meta["duration"]
    times = [duration * k / (frames - 1) if frames > 1 else 0.0 for k in range(frames)]

    def rest(i):
        nd = nodes[i]
        return (nd.get("translation", [0, 0, 0]), nd.get("rotation", [0, 0, 0, 1]), nd.get("scale", [1, 1, 1]))

    # secondary skeletons (the weapon): every node under another .vnmskel root
    sec_roots = [i for i in range(len(nodes)) if g.node_name(i).endswith(".vnmskel")
                 and "characters/worldmodel" not in g.node_name(i)]
    if True:
        f.write(b"CAC1")
        f.write(struct.pack("<fiB", duration, frames, 1 if meta["additive"] else 0))
        f.write(struct.pack("<i", len(bones)))
        for b in bones:
            if b in idx:
                r, t, s = _tracks(clip, idx[b], rest(idx[b]), times)
            else:
                r = np.tile([0, 0, 0, 1.0], (frames, 1)) if meta["additive"] else None
                t = np.zeros((frames, 3))
                s = np.ones((frames, 3))
                if r is None:
                    raise KeyError(f"{glb_path.name}: no bone {b}")
            _write_bone(f, r, t, s)
        f.write(struct.pack("<i", len(meta["sync"])))
        for sid, st, du in meta["sync"]:
            _wstr(f, sid)
            f.write(struct.pack("<ff", st, du))
        f.write(struct.pack("<i", len(meta["events"])))
        for eid, st, du in meta["events"]:
            _wstr(f, eid)
            f.write(struct.pack("<ff", st, du))
        f.write(struct.pack("<i", len(sec_roots)))
        for root in sec_roots:
            members = [i for i in range(len(nodes)) if i != root and _under(parents, i, root)
                       and not g.node_name(i).endswith("empty_mesh_reference")]
            _wstr(f, g.node_name(root).split("/")[-1].replace(".vnmskel", ""))
            f.write(struct.pack("<i", len(members)))
            for i in members:
                _wstr(f, g.node_name(i))
                p = parents[i]
                _wstr(f, "" if p == root else g.node_name(p))
                r, t, s = _tracks(clip, i, rest(i), times)
                _write_bone(f, r, t, s)
        f.write(struct.pack("<i", meta["syncOffset"]))


def _under(parents, i, root):
    while i >= 0:
        if i == root:
            return True
        i = parents[i]
    return False


# CS2's buy menu / loadout poses: one clip per weapon per team (the agent holding it, MapPlayerPreviewPanel
# animgraphcharactermode "buy-menu"); stored as "buymenu/<t|ct>/<clip>"
BUYMENU_ANIMS = "animation/anims/ui_anims/buy_menu/"


def clips(cs2, clip_res, bones, agents_dir, log, files=()):
    import io
    entries = []
    groups = [("animation/anims/world/", "agent_world_clips_all", "wanim", sorted(clip_res), "")]
    buy = sorted(f.removesuffix("_c") for f in files if f.startswith(BUYMENU_ANIMS) and f.endswith(".vnmclip_c"))
    if buy:
        groups.append((BUYMENU_ANIMS, "agent_buymenu_clips_all", "buyanim", buy, "buymenu/"))
    for root, dump, sub, resources, prefix in groups:
        meta_text = cs2.data_block(root, dump)
        metas = {p[:-2] if p.endswith("_c") else p: b for p, b in split_dump(meta_text).items()}
        anim = cs2.export(root, sub, "--gltf_export_format", "glb", "--gltf_export_animations")
        for res in resources:
            body = metas.get(res)
            if body is None:
                log(f"  ! no data for {res}")
                continue
            meta = clip_meta(body)
            tail = res.replace(root, "").replace(".vnmclip", "")
            glb = anim / Path(root) / (tail + ".glb")
            if not glb.exists():
                log(f"  ! no glb for {res}")
                continue
            buf = io.BytesIO()
            write_clip(glb, meta, bones, buf)
            entries.append((prefix + tail, buf.getvalue()))
    # one archive: name -> clip bytes (CAB1, count, then name + length + CAC1 data)
    out = agents_dir / "clips.bin"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f:
        f.write(b"CAB1")
        f.write(struct.pack("<i", len(entries)))
        for rel, data in entries:
            _wstr(f, rel)
            f.write(struct.pack("<i", len(data)))
            f.write(data)
    log(f"agent: wrote {len(entries)} clips ({out.stat().st_size / 1e6:.1f} MB)")


# ---------------------------------------------------------------- model

def model_skeleton(cs2, model_path, tag):
    """The model's own bones: names, parents, rest world matrices in glTF model space (metres)."""
    text = cs2.data_block(model_path, tag)
    names = re.findall(r'"([^"]+)"', re.search(r"m_boneName =\s*\[(.*?)\]", text, re.S).group(1))
    parent = [int(x) for x in re.findall(r"-?\d+", re.search(r"m_nParent =\s*\[(.*?)\]", text, re.S).group(1))]

    def vecs(key):
        body = re.search(key + r" =\s*\[(.*?)\n\t\t\]", text, re.S).group(1)
        return [[float(x) for x in re.findall(r"-?[\d.]+(?:e-?\d+)?", row)] for row in re.findall(r"\[([^\[\]]*)\]", body)]

    from gltf_lite import trs_to_mat
    pos, rot = vecs("m_bonePosParent"), vecs("m_boneRotParent")
    world = []
    for i in range(len(names)):
        local = trs_to_mat(np.array(pos[i]) * INCH, np.array(rot[i]), [1, 1, 1])
        world.append(local if parent[i] < 0 else world[parent[i]] @ local)
    return names, parent, [SOURCE_TO_GLTF @ w for w in world]


def write_cam(out, names, parents, rest, anim_bones, parts):
    """A CAM1 model: its bones (name, parent, the animation skeleton's bone of the same name or -1, rest world 3x4) and
    parts (texture, mesh, vertices, joints / weights into those bones, triangles)."""
    with open(out, "wb") as f:
        f.write(b"CAM1")
        f.write(struct.pack("<i", len(names)))
        for i, n in enumerate(names):
            _wstr(f, n)
            f.write(struct.pack("<i", parents[i]))
            f.write(struct.pack("<i", anim_bones.index(n) if n in anim_bones else -1))
            f.write(np.ascontiguousarray(rest[i][:3], "<f4").tobytes())
        f.write(struct.pack("<i", len(parts)))
        for tex, sub, pos, nrm, uv, jnt, wgt, idx in parts:
            _wstr(f, tex)
            _wstr(f, sub)
            f.write(struct.pack("<i", len(pos)))
            for arr in (pos, nrm, uv):
                f.write(np.ascontiguousarray(arr, "<f4").tobytes())
            f.write(np.ascontiguousarray(jnt).tobytes())
            f.write(np.ascontiguousarray(wgt).tobytes())
            f.write(struct.pack("<i", len(idx)))
            f.write(np.ascontiguousarray(idx, "<i4").tobytes())


def write_agent(cs2, agents_dir, assets, write_texture, anim_bones, log, agent=DEFAULT_AGENT):
    from convert import mesh_parts
    name, model = agent
    mdir = cs2.export(model, "agent", "--gltf_export_format", "glb", "--gltf_export_materials")
    glb = Glb(next(mdir.rglob(Path(model).name.replace(".vmdl_c", ".glb"))))
    names, parents, rest = model_skeleton(cs2, model, f"agent_{name}_model")
    parts = []
    for sub in AGENT_MESHES:
        for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(glb, "." + sub):
            tex = f"agent/{name}_{mat}"
            src = next(mdir.rglob(img), None) if img else None
            if src:
                write_texture(src, assets / "textures" / (tex + ".png"))
            w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
            parts.append((f"countercraft:textures/{tex}.png", sub, pos, nrm, uv, jnt.astype(np.uint8), np.round(w * 255).astype(np.uint8), idx))
    out = agents_dir / f"{name}.cam"
    write_cam(out, names, parents, rest, anim_bones, parts)
    verts = sum(len(p[2]) for p in parts)
    log(f"agent: {name}: {len(names)} bones ({sum(1 for n in names if n in anim_bones)} animated), {len(parts)} parts, "
        f"{verts} vertices, {out.stat().st_size // 1024} KiB")


def write_gun_skeleton(cs2, gun_id, model_path, agents_dir):
    """The gun model's bones and rest pose (glTF model space): the mod places bone `weapon` on the agent's wpn bone."""
    names, parents, rest = model_skeleton(cs2, model_path, gun_id + "_model")
    out = agents_dir / "weapons" / f"{gun_id}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"bones": names, "parents": parents,
                               "rest": [[round(float(x), 6) for x in m[:3].reshape(-1)] for m in rest]}))


# ---------------------------------------------------------------- entry

def write_hitboxes(cs2, model_path, agents_dir, log):
    """CS2's own hitboxes (the model's "cstrike" hitbox set): capsules on bones, endpoints in the bone's frame (Source
    units), radius, hit group (1 head, 2 chest, 3 stomach, 4/5 left/right arm, 6/7 left/right leg, 8 neck)."""
    out = cs2.cache / "dump" / "hitboxes_tm_phoenix.txt"
    if not out.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(cs2._run("-a", "-f", model_path), encoding="utf-8")
    text = out.read_text(encoding="utf-8")
    start = text.index("m_hitboxsets")
    sets = text[start:]
    cs = sets.index('key = "cstrike"')
    nxt = sets.find("key = ", cs + 10)
    block = sets[cs:nxt if nxt > 0 else len(sets)]
    num = r"(-?[\d.]+(?:e-?\d+)?)"
    vec = r"\[\s*" + num + r",\s*" + num + r",\s*" + num + r"\s*\]"
    boxes = []
    for m in re.finditer(r'm_sBoneName = "([^"]+)"\s*m_vMinBounds = ' + vec + r"\s*m_vMaxBounds = " + vec
                         + r"\s*m_flShapeRadius = " + num + r".*?m_nGroupId = (\d+)\s*m_nShapeType = (\d+)", block, re.S):
        g = m.groups()
        boxes.append({"bone": g[0], "a": [float(x) for x in g[1:4]], "b": [float(x) for x in g[4:7]],
                      "r": float(g[7]), "group": int(g[8]), "shape": int(g[9])})
    (agents_dir / "hitboxes.json").write_text(json.dumps({"set": "cstrike", "boxes": boxes}, indent=1))
    log(f"  hitboxes: {len(boxes)} capsules")


def convert(cs2, assets, write_texture, log, guns=()):
    agents_dir = assets / "agents"
    gr, clip_res = graphs(cs2, log)
    anim = cs2.export("animation/anims/world/", "wanim", "--gltf_export_format", "glb", "--gltf_export_animations")
    ref_glb = anim / "animation" / "anims" / "world" / "rifle" / "_default_rifle" / "idle_rifle.glb"
    skel = skeleton(cs2, ref_glb)
    (agents_dir / "graphs").mkdir(parents=True, exist_ok=True)
    (agents_dir / "skeleton.json").write_text(json.dumps(skel))
    for name, g in gr.items():
        (agents_dir / "graphs" / f"{name}.json").write_text(json.dumps(g, separators=(",", ":")))
    files = [ln.split(" CRC:")[0] for ln in cs2._run("-l", "-f", BUYMENU_ANIMS).splitlines() if " CRC:" in ln]
    clips(cs2, clip_res, skel["bones"], agents_dir, log, files)
    write_agent(cs2, agents_dir, assets, write_texture, skel["bones"], log)
    write_agent(cs2, agents_dir, assets, write_texture, skel["bones"], log, CT_AGENT)
    for g in guns:
        try:
            write_gun_skeleton(cs2, g["id"], g["_asset"]["model"], agents_dir)
        except Exception as e:  # noqa: BLE001
            log(f"  ! gun skeleton {g['id']}: {e!r}")
    write_hitboxes(cs2, DEFAULT_AGENT[1], agents_dir, log)
    (agents_dir / "index.json").write_text(json.dumps({"agents": [DEFAULT_AGENT[0], CT_AGENT[0]], "teams": {"t": DEFAULT_AGENT[0], "ct": CT_AGENT[0]}, "root": "worldmodel",
                                                       "graphs": sorted(gr)}, indent=1))
