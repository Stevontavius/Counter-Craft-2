# /// script
# dependencies = ["numpy", "pillow", "openexr"]
# ///
"""Build the Counter-Craft 2 asset pack from the user's own CS2 install.

Nothing from CS2 is shipped with the mod. This script reads the user's install with Source 2 Viewer (VRF),
bakes the first-person viewmodels (gloved arms + gun, skinned per animation frame), converts the weapon
sounds to Ogg with ffmpeg, and writes a Minecraft resource pack.

usage: uv run convert.py --cs2 "D:\\...\\Counter-Strike Global Offensive" [--out ../cs2-pack] [--install <resourcepacks dir>]
"""
from paths import EXTRACT
import argparse
import json
import re
import shutil
import struct
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from gltf_lite import Clip, Glb, trs_to_mat

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
VRF = ROOT / "tools" / "vrf" / "Source2Viewer-CLI.exe"
NS = "countercraft"
PACK_NAME = "CounterCraft-CS2-Assets"
FPS = 60.0
MAX_TEX = 2048
# Source (x fwd, y left, z up, inches) -> glTF as VRF exports it (X = y, Y = z, Z = x, metres).
SOURCE_TO_GLTF = np.array([[0, 1, 0, 0], [0, 0, 1, 0], [1, 0, 0, 0], [0, 0, 0, 1]], float)
INCH = 0.0254
# glTF viewmodel space (X left, Y up, Z forward) -> Minecraft view space (X right, Y up, -Z forward).
GLTF_TO_MC = np.diag([-1.0, 1.0, -1.0, 1.0])


def log(*a):
    print("[convert]", *a, flush=True)


# ---------------------------------------------------------------- extraction

class Cs2:
    def __init__(self, game_dir: Path, cache: Path):
        self.vpk = game_dir / "game" / "csgo" / "pak01_dir.vpk"
        if not self.vpk.exists():
            sys.exit(f"not a CS2 install (no {self.vpk})")
        if not VRF.exists():
            sys.exit(f"Source 2 Viewer CLI not found at {VRF}")
        self.cache = cache

    def _run(self, *args):
        r = subprocess.run([str(VRF), "-i", str(self.vpk), *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
        return r.stdout + r.stderr

    def export(self, vpk_path, sub, *extra):
        """Decompile one or more VPK paths into cache/sub (a folder). Skips if already there."""
        out = self.cache / sub
        marker = out / (".done-" + re.sub(r"[^\w]+", "_", vpk_path + " ".join(extra))[:150])
        if not marker.exists():
            out.mkdir(parents=True, exist_ok=True)
            # A filter matching exactly one file makes VRF treat -o as a file name, so always add a never-matching
            # second filter to keep it a folder.
            self._run("-o", str(out), "-d", "-f", vpk_path + ",__never__/", *extra)
            marker.touch()
        return out

    def data_block(self, vpk_path, name):
        """`-b DATA` text dump of a resource (bones, clip events, ...)."""
        out = self.cache / "dump" / (name + ".txt")
        if not out.exists():
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(self._run("-b", "DATA", "-f", vpk_path), encoding="utf-8")
        return out.read_text(encoding="utf-8")


# ---------------------------------------------------------------- model data

class Skeleton:
    """A vmdl's own bones: names, parents and rest pose (world, glTF mesh space, Source-native bone frames)."""

    def __init__(self, names, parents, rest):
        self.names, self.parents, self.rest = names, parents, rest

    def index(self, name):
        return self.names.index(name)


def parse_model_dump(text):
    def block(key):
        return re.search(key + r" =\s*\[(.*?)\n\t\t\]", text, re.S).group(1)

    def vecs(key):
        return [[float(x) for x in re.findall(r"-?[\d.]+(?:e-?\d+)?", row)] for row in re.findall(r"\[([^\[\]]*)\]", block(key))]

    names = re.findall(r'"([^"]+)"', re.search(r"m_boneName =\s*\[(.*?)\]", text, re.S).group(1))
    parent = [int(x) for x in re.findall(r"-?\d+", re.search(r"m_nParent =\s*\[(.*?)\]", text, re.S).group(1))]
    pos, rot = vecs("m_bonePosParent"), vecs("m_boneRotParent")
    world = []
    for i in range(len(names)):
        local = trs_to_mat(np.array(pos[i]) * INCH, np.array(rot[i]), [1, 1, 1])
        world.append(local if parent[i] < 0 else world[parent[i]] @ local)
    return Skeleton(names, parent, [SOURCE_TO_GLTF @ w for w in world])


# model attachments the mod needs, baked as extra bones "att_<name>" (muzzle flash, shell ejection; Dual Berettas
# have a second of each). Their pose matrix maps the attachment's own frame (x = forward along the barrel) to view space.
ATTACHMENTS = ("muzzle_flash", "muzzle_flash2", "shell_eject", "shell_eject2")


def _angle_matrix(pitch, yaw, roll):
    """Source AngleMatrix (degrees): columns forward, left, up."""
    sp, cp = np.sin(np.radians(pitch)), np.cos(np.radians(pitch))
    sy, cy = np.sin(np.radians(yaw)), np.cos(np.radians(yaw))
    sr, cr = np.sin(np.radians(roll)), np.cos(np.radians(roll))
    m = np.eye(4)
    m[:3, :3] = [[cp * cy, sr * sp * cy - cr * sy, cr * sp * cy + sr * sy],
                 [cp * sy, sr * sp * sy + cr * cy, cr * sp * sy - sr * cy],
                 [-sp, sr * cp, cr * cp]]
    return m


def model_attachments(cs2, model):
    """{name: (parent bone, local 4x4 in the bone's Source frame, metres)} from the decompiled .vmdl."""
    d = cs2.export(model, "vmdl")
    f = d / model[:-2]
    text = f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""
    out = {}
    for m in re.finditer(r'_class = "Attachment"\s*name = "([^"]+)"[^}]*?parent_bone = "([^"]*)"[^}]*?relative_origin = \[([^\]]*)\]'
                         r'[^}]*?relative_angles = \[([^\]]*)\]', text, re.S):
        if m.group(1) in ATTACHMENTS:
            o = [float(x) for x in m.group(3).split(",")]
            a = [float(x) for x in m.group(4).split(",")]
            local = _angle_matrix(*a)
            local[:3, 3] = np.array(o) * INCH
            out[m.group(1)] = (m.group(2), local)
    return out


def mesh_parts(glb, mesh_substr, skip_materials=()):
    """Yield (material name, base colour image file, positions, normals, uvs, joints, weights, indices)."""
    j = glb.j
    for m in j["meshes"]:
        if mesh_substr not in m.get("name", ""):
            continue
        for p in m["primitives"]:
            mat = j["materials"][p["material"]]
            if mat.get("name") in skip_materials or mat.get("alphaMode") == "BLEND":
                continue  # sticker overlays / lens glass: transparent layers the cutout renderer can't blend
            tex = mat.get("pbrMetallicRoughness", {}).get("baseColorTexture", {}).get("index")
            img = j["images"][j["textures"][tex]["source"]]["uri"] if tex is not None else None
            a = p["attributes"]
            idx = glb.acc(p["indices"])[:, 0].astype(np.int64) if "indices" in p else np.arange(glb.acc(a["POSITION"]).shape[0])
            yield (mat.get("name"), img,
                   glb.acc(a["POSITION"]).astype(np.float32),
                   glb.acc(a["NORMAL"]).astype(np.float32),
                   glb.acc(a["TEXCOORD_0"]).astype(np.float32),
                   glb.acc(a["JOINTS_0"]).astype(np.int64),
                   glb.acc(a["WEIGHTS_0"]).astype(np.float32),
                   idx)


def scope_lens(glb, mesh_substr):
    """(centre, radius, axis) of the scope lens disc (the BLEND "scope_lens" material) in model space, or None."""
    j = glb.j
    for m in j["meshes"]:
        if mesh_substr not in m.get("name", ""):
            continue
        for p in m["primitives"]:
            mat = j["materials"][p["material"]]
            if "scope_lens" not in (mat.get("name") or "") or "indices" not in p:
                continue
            pos = glb.acc(p["attributes"]["POSITION"]).astype(np.float64)[np.unique(glb.acc(p["indices"])[:, 0])]
            c = pos.mean(0)
            axis = np.zeros(3)
            axis[int(np.argmin(pos.max(0) - pos.min(0)))] = 1.0  # the disc is thin along its axis
            radial = pos - c - np.outer((pos - c) @ axis, axis)
            return c, float(np.linalg.norm(radial, axis=1).max()), axis
    return None


def open_scope(pos, idx, lens):
    """Drop the faces that close the scope tube in front of the lens (CS2 draws the view through it with a lens
    shader; without one the cap reads as a black disc). Caps sit inside the lens radius; the tube walls are at the
    radius and face sideways, so they stay."""
    c, r, axis = lens
    tri = idx.reshape(-1, 3)
    P = pos[tri].astype(np.float64)
    cen = P.mean(1)
    along = (cen - c) @ axis
    radial = np.linalg.norm(cen - c - np.outer(along, axis), axis=1)
    n = np.cross(P[:, 1] - P[:, 0], P[:, 2] - P[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    # well inside the tube anything goes (the AUG's cap is a shallow cone with a toothed rim); out at the wall, only
    # faces that aren't wall (a wall faces straight sideways), and only out where the cap is, not along the tube
    facing = np.abs(n @ axis)
    cap = (np.abs(along) < 0.3) & ((radial < r * 0.95) | ((radial < r * 1.15) & ((facing > 0.8) | ((facing > 0.15) & (np.abs(along) > 0.08)))))
    return tri[~cap].reshape(-1), int(cap.sum())


# CS2's lens shows the view across the whole eyepiece bore (a ~3.7 cm cylinder from the lens ~5 cm out towards the eye;
# +axis points away from the eye). The mod draws that as a portal at the bore's mouth (ViewmodelRenderer lensCull).
EYEPIECE_BORE = 0.0365


def write_lens(assets, gun_id, lens, pos, jnt, offset, log):
    """viewmodels/<gun>_lens.json: the lens's centre and axis (pointing away from the eye) in the gun's model space, the
    eyepiece bore radius, and the cvm bone it moves with."""
    c, r, axis = lens
    near = int(np.argmin(np.linalg.norm(pos.astype(np.float64) - c, axis=1)))
    out = {"bone": int(jnt[near, 0]) + offset, "center": [float(x) for x in c], "axis": [float(x) for x in axis], "radius": EYEPIECE_BORE}
    (assets / "viewmodels").mkdir(parents=True, exist_ok=True)
    (assets / "viewmodels" / f"{gun_id}_lens.json").write_text(json.dumps(out))
    log(f"  scope: lens portal r={EYEPIECE_BORE} bone {out['bone']}")


# ---------------------------------------------------------------- baking
#
# Every mesh is stored in its own model's rest pose, so a bone's skinning matrix is
#     clip world(bone) @ inverse(model rest(bone))
# using the MODEL's rest (m_bonePosParent/RotParent), never the clip's inverse-bind matrices: the AK's root bone
# "weapon" sits 2.97" forward / 2.98" up from the model origin while the clip skeleton has it at zero.
# Model bones the clip doesn't animate ride rigidly on their nearest animated ancestor, except the forearm twist
# bones, which also take a share of the hand's roll about the forearm (1/3 and 2/3 along it).

def _twist_about_x(rel):
    """Signed rotation angle (radians) of a 3x3 rotation about its local x axis (swing-twist decomposition)."""
    w = np.sqrt(max(0.0, 1.0 + rel[0, 0] + rel[1, 1] + rel[2, 2])) / 2.0
    x = (rel[2, 1] - rel[1, 2]) / 4.0 if w < 1e-6 else (rel[2, 1] - rel[1, 2]) / (4.0 * w)
    if w < 1e-6:
        return np.pi
    return 2.0 * np.arctan2(x, w)


def _rot_x(a):
    m = np.eye(4)
    c, s = np.cos(a), np.sin(a)
    m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    return m


def _bone_plan(skel, node_of):
    """For each model bone: (clip node it follows, model bone of that node, twist info or None)."""
    plan = []
    for k, name in enumerate(skel.names):
        anc = k
        while anc >= 0 and skel.names[anc] not in node_of:
            anc = skel.parents[anc]
        if anc < 0:
            # a separate root the clip never moves (e.g. the Dual Berettas' holster): ride on the weapon root
            if "weapon" not in node_of or "weapon" not in skel.names:
                raise KeyError(f"no animated ancestor for bone {name}")
            anc = skel.index("weapon")
        twist = None
        if name.startswith("arm_lower_") and "_TWIST" in name:
            side = name.split("_")[2]
            hand = skel.index("hand_" + side)
            rest_par = np.linalg.inv(skel.rest[anc])
            along = np.linalg.norm((rest_par @ skel.rest[k])[:3, 3]) / np.linalg.norm((rest_par @ skel.rest[hand])[:3, 3])
            twist = (hand, rest_par @ skel.rest[k], (rest_par @ skel.rest[hand])[:3, :3], along)
        plan.append((node_of[skel.names[anc]], anc, twist))
    return plan


def bake_clip(clip_glb_path, arms: Skeleton, gun: Skeleton, skeleton_name, atts=()):
    """Per-frame skinning matrices (Minecraft view space): the arm model's bones, then the gun model's bones, then
    the attachment frames ({@code atts}: (name, parent bone, local) in a fixed order)."""
    g = Glb(clip_glb_path)
    clip = Clip(g)
    node_of = {g.node_name(i): i for i in range(len(g.j["nodes"]))}
    parents = g.parents()
    skels = [i for i in range(len(g.j["nodes"])) if g.node_name(i).endswith(".vnmskel") and "characters/viewmodel" not in g.node_name(i)]
    # the gun's own skeleton, or (shared default clips) whatever weapon skeleton the clip carries
    wroot = next((i for i in skels if g.node_name(i).endswith(skeleton_name)), skels[0] if skels else None)
    if wroot is None:
        raise RuntimeError(f"no weapon skeleton in {clip_glb_path}")

    def under(i, r):
        while i >= 0:
            if i == r:
                return True
            i = parents[i]
        return False

    weapon_nodes = [i for i in range(len(g.j["nodes"])) if under(i, wroot)]
    arm_plan, gun_plan = _bone_plan(arms, node_of), _bone_plan(gun, node_of)
    arm_inv = [np.linalg.inv(r) for r in arms.rest]
    gun_inv = [np.linalg.inv(r) for r in gun.rest]

    frames = max(1, int(round(clip.duration * FPS)) + 1)
    out = np.zeros((frames, len(arms.names) + len(gun.names) + len(atts), 3, 4), np.float32)
    base = len(arms.names) + len(gun.names)
    for f in range(frames):
        world = clip.world_matrices(min(f / FPS, clip.duration))
        # the weapon skeleton rides on the arms' wpn bone
        attach = world[node_of["wpn"]] @ np.linalg.inv(world[node_of["weapon"]])
        for i in weapon_nodes:
            world[i] = attach @ world[i]
        for k, (node, anc, twist) in enumerate(arm_plan):
            if twist is None:
                w = world[node] @ arm_inv[anc] @ arms.rest[k]
            else:
                hand, local_rest, rest_hand_rot, along = twist
                rel = world[node][:3, :3].T @ world[node_of[arms.names[hand]]][:3, :3]
                roll = _twist_about_x(rel @ rest_hand_rot.T)
                w = world[node] @ _rot_x(along * roll) @ local_rest
            out[f, k] = (GLTF_TO_MC @ w @ arm_inv[k])[:3]
        for k, (node, anc, _) in enumerate(gun_plan):
            out[f, len(arms.names) + k] = (GLTF_TO_MC @ world[node] @ gun_inv[anc])[:3]
        for j, (_, parent, local) in enumerate(atts):
            node = node_of.get(parent, node_of["weapon"])
            out[f, base + j] = (GLTF_TO_MC @ world[node] @ local)[:3]
    return clip.duration, out


def clip_sound_events(dump_text, duration):
    """(time in seconds, CS2 sound event name) from a vnmclip DATA dump. Start times are clip fractions."""
    events = []
    for m in re.finditer(r'_class = "CNmSoundEvent".*?m_flStartTime =\s*\{\s*m_flValue = ([\d.e-]+).*?m_name = "([^"]+)"', dump_text, re.S):
        events.append((float(m.group(1)) * duration, m.group(2)))
    return events


# ---------------------------------------------------------------- sounds

def sound_event_files(vsndevts_text, event):
    """vsnd files and volume of one sound event in game_sounds_weapons.vsndevts."""
    # Source sound event names are case-insensitive (vdata: Weapon_Elite.Single, vsndevts: Weapon_ELITE.Single)
    m = re.search(r"\n\t" + re.escape(event) + r" =\s*\n\t\{(.*?)\n\t\}", vsndevts_text, re.S | re.I)
    if not m:
        return [], 1.0
    body = m.group(1)
    files = re.findall(r'"(sounds/[^"]+?\.vsnd)"', body)
    vol = re.search(r"\n\t\tvolume = ([\d.]+)", body)
    return files, float(vol.group(1)) if vol else 1.0


def mc_sound_id(event):
    # Weapon_AK47.Clipout -> weapon.ak47.clipout
    return re.sub(r"[^a-z0-9_.]", "_", event.lower().replace("weapon_", "weapon."))


# ---------------------------------------------------------------- writing

def wstr(f, s):
    b = s.encode("utf-8")
    f.write(struct.pack("<H", len(b)))
    f.write(b)


def write_texture(src: Path, dst: Path):
    # Opaque on purpose: CS2 colour maps keep material masks (paint/metalness) in alpha, which Minecraft's
    # cutout render type would treat as holes.
    # ...except the Zeus's charge meter: a glowing display whose alpha really is its shape
    im = Image.open(src).convert("RGBA") if "chargemeter" in dst.name else Image.open(src).convert("RGB").convert("RGBA")
    if max(im.size) > MAX_TEX:
        s = MAX_TEX / max(im.size)
        im = im.resize((max(1, int(im.width * s)), max(1, int(im.height * s))), Image.LANCZOS)
    dst.parent.mkdir(parents=True, exist_ok=True)
    im.save(dst, optimize=True)


ICON_SIZE = 128


def write_icon(src: Path, dst: Path):
    """Inventory icon: CS2's econ image, trimmed, padded to a square (item sprites are square) and downscaled."""
    im = Image.open(src).convert("RGBA")
    bbox = im.getchannel("A").getbbox()
    if bbox:
        im = im.crop(bbox)
    side = max(im.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
    dst.parent.mkdir(parents=True, exist_ok=True)
    sq.resize((ICON_SIZE, ICON_SIZE), Image.LANCZOS).save(dst, optimize=True)


ARMS_MODEL = "agents/models/shared/arms/glove_fullfinger/glove_fullfinger.vmdl_c"
ARMS_MESH = "viewmodel"

# viewmodel clip roles: (role, name prefixes in priority order, substrings that disqualify a clip)
ROLE_RULES = [
    ("draw", ("draw_silenced_", "draw_"), ("draw2", "_lgcy", "lookat")),
    ("idle", ("idle_", "idle1_"), ("slide_back", "leftempty", "leftrightempty", "from_activity", "_lgcy", "ironsight")),
    ("idle_empty", ("idle_slide_back_",), ()),
    ("shoot", ("shoot_right1_", "shoot1_"), ("_lgcy", "ironsight")),
    ("shoot_alt", ("shoot_left1_", "shoot_alt1_"), ()),
    ("shoot_empty", ("shoot_empty_",), ()),
    ("reload", ("reload_",), ("empty", "reload2", "_lgcy")),
    ("reload_empty", ("reload_empty_", "empty_reload_"), ("reload2",)),
    ("inspect", ("lookat01_",), ("_draw", "transfix", "_lgcy")),
    ("silencer_on", ("silencer_attach_",), ()),
    ("silencer_off", ("silencer_detach_",), ()),
    # R8: hammer pull before a primary shot, dry fire on an empty cylinder
    ("prepare", ("prepare_shoot_",), ()),
    ("dryfire", ("dryfire_",), ()),
    # AUG / SG 553: aiming down the scope (CS2 blends into this pose while zoomed)
    ("ads_idle", ("ironsight_fidget_",), ()),
    ("ads_shoot", ("ironsight_shoot_",), ()),
    # knives
    ("slash", ("light_miss1_",), ()),
    ("slash2", ("light_miss2_",), ()),
    ("slash_hit", ("light_hit1_",), ()),
    ("slash_hit2", ("light_hit2_",), ()),
    ("stab", ("heavy_miss1_",), ()),
    ("stab_hit", ("heavy_hit1_",), ()),
    ("backstab", ("light_backstab_",), ("backstab2",)),
    # the C4
    ("plant", ("plant_",), ()),
    ("stab_backstab", ("heavy_backstab_",), ()),
    # grenades
    ("pullpin", ("pullpin_",), ()),
    ("throw", ("throw_overhand_",), ()),
    ("throw_under", ("throw_underhand_",), ()),
    # the pin is out: CS2 holds the grenade back for an overhand throw (left), low for a lob (right), between (both)
    ("charge_high", ("throwcharge_high_",), ()),
    ("charge_mid", ("throwcharge_mid_",), ()),
    ("charge_low", ("throwcharge_low_",), ()),
]


def pick_clips(names, silencer):
    """Clip name per role from a viewmodel clip folder's names."""
    names = sorted(n for n in names if "+" not in n)
    out = {}
    for role, prefixes, bad in ROLE_RULES:
        if role.startswith("silencer") and silencer != "detachable":
            continue
        for pre in prefixes:
            if pre == "draw_silenced_" and silencer != "detachable":
                continue
            hit = next((n for n in names if n.startswith(pre) and not any(b in n for b in bad)), None)
            if hit:
                out[role] = hit
                break
    return out


def choose_mesh(glb):
    """The gun body: the HD mesh if there is one, else the first that isn't legacy/hidden."""
    names = [m.get("name", "") for m in glb.j["meshes"]]
    for n in names:
        if n.endswith(".body_hd"):
            return n
    for n in names:
        if not any(b in n for b in ("legacy", "hidden", "worldmodel", "physics")):
            return n
    return names[0]


def convert_gun(g, cs2, assets, arms_glb, arms_dir, arms_skel, add_sound, gltf_flags):
    gun_id, a = g["id"], g["_asset"]
    log(f"gun {gun_id}")
    gdir = cs2.export(a["model"], "gltf", *gltf_flags)
    gun_glb = Glb(next(gdir.rglob(Path(a["model"]).name.replace(".vmdl_c", ".glb"))))
    clip_dir = cs2.export(a["clipDir"], "anim", "--gltf_export_format", "glb", "--gltf_export_animations")
    gun_skel = parse_model_dump(cs2.data_block(a["model"], gun_id + "_model"))
    folder = a["clipDir"].rstrip("/").split("/")[-1]
    names = [p.stem for p in clip_dir.rglob("*.glb") if p.parent.name == folder]
    roles = pick_clips(names, g.get("silencer", "none"))
    needed = {"knife": ("draw", "idle", "slash", "stab", "inspect"), "grenade": ("draw", "idle", "pullpin", "throw"),
              "c4": ("draw", "idle", "plant", "inspect")}.get(
        g.get("kind"), ("draw", "idle", "shoot", "reload", "inspect"))
    missing = [r for r in needed if r not in roles]
    log(f"  mesh {choose_mesh(gun_glb).split('.')[-1]}, bones {gun_skel.names}, clips {roles}" + (f", MISSING {missing}" if missing else ""))
    if "idle" not in roles:
        raise RuntimeError(f"no idle clip in {a['clipDir']} ({sorted(names)})")

    # meshes: arm model bones first, then gun model bones (VRF exports joints in model-bone order)
    parts = []
    lens = scope_lens(gun_glb, choose_mesh(gun_glb)) if g.get("ironsight") else None
    for owner, glb, src_dir, sub, offset in (
            ("arms", arms_glb, arms_dir, ARMS_MESH, 0),
            ("gun", gun_glb, gdir, choose_mesh(gun_glb), len(arms_skel.names))):
        for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(glb, sub):
            tex_name = f"viewmodel/{gun_id if owner == 'gun' else 'arms'}_{mat}"
            src = next(src_dir.rglob(img), None) if img else None
            if src:
                write_texture(src, assets / "textures" / (tex_name + ".png"))
            w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
            if owner == "gun" and lens is not None:
                idx, removed = open_scope(pos, idx, lens)
                log(f"  scope: opened the tube ({removed} cap faces in front of the lens)")
                write_lens(assets, gun_id, lens, pos, jnt, offset, log)
            parts.append((f"{NS}:textures/{tex_name}.png", pos, nrm, uv, (jnt + offset).astype(np.uint8), np.round(w * 255).astype(np.uint8), idx))

    atts = sorted(model_attachments(cs2, a["model"]).items(), key=lambda kv: ATTACHMENTS.index(kv[0]))
    atts = [(n, parent, local) for n, (parent, local) in atts]
    clips = []
    for role, clip_name in roles.items():
        # additive clips (deltas on top of another pose, e.g. the R8's hammer pull) come with a full-pose version
        cands = [p for p in clip_dir.rglob(clip_name + "*.glb") if p.parent.name == folder
                 and p.name in (clip_name + ".glb", clip_name + ".vnmclip+non_additive.glb")]
        path = min(cands, key=lambda p: 0 if "non_additive" in p.name else 1)
        duration, mats = bake_clip(path, arms_skel, gun_skel, a["skeleton"], atts)
        dump = cs2.data_block(a["clipDir"] + clip_name + ".vnmclip_c", f"{folder}__{clip_name}.clip")
        events = [(t, add_sound(e)) for t, e in clip_sound_events(dump, duration)]
        clips.append((role, duration, mats, events))
    log("  clips " + ", ".join(f"{r} {d:.2f}s/{len(e)}snd" for r, d, _, e in clips))
    for e in a["soundEvents"]:
        add_sound(e)

    # inventory icon (overrides the mod's placeholder item model while the pack is enabled)
    idir = cs2.export(a["icon"], "icons")
    png = next(idir.rglob(Path(a["icon"]).name.replace(".vtex_c", ".png")), None)
    if png:
        write_icon(png, assets / "textures" / "item" / f"{gun_id}.png")
        model = assets / "models" / "item" / f"{gun_id}.json"
        model.parent.mkdir(parents=True, exist_ok=True)
        model.write_text(json.dumps({"parent": "minecraft:item/handheld", "textures": {"layer0": f"{NS}:item/{gun_id}"}}, indent=2))
    else:
        log(f"  ! no icon image for {a['icon']}")

    out = assets / "viewmodels" / f"{gun_id}.cvm"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f:
        f.write(b"CVM1")
        f.write(struct.pack("<i", clips[0][2].shape[1]))
        f.write(struct.pack("<i", len(parts)))
        for tex, pos, nrm, uv, jnt, wgt, idx in parts:
            wstr(f, tex)
            f.write(struct.pack("<i", len(pos)))
            for arr in (pos, nrm, uv):
                f.write(np.ascontiguousarray(arr, "<f4").tobytes())
            f.write(np.ascontiguousarray(jnt).tobytes())
            f.write(np.ascontiguousarray(wgt).tobytes())
            f.write(struct.pack("<i", len(idx)))
            f.write(np.ascontiguousarray(idx, "<i4").tobytes())
        f.write(struct.pack("<i", len(clips)))
        for role, duration, mats, events in clips:
            wstr(f, role)
            f.write(struct.pack("<ffi", duration, FPS, len(mats)))
            f.write(np.ascontiguousarray(mats, "<f4").tobytes())
            f.write(struct.pack("<i", len(events)))
            for t, sid in events:
                f.write(struct.pack("<f", t))
                wstr(f, f"{NS}:{sid}")
        # optional trailer: bone names (arms then gun), so the mod can hide parts such as a removed silencer
        names = list(arms_skel.names) + list(gun_skel.names) + ["att_" + n for n, _, _ in atts]
        if len(names) == clips[0][2].shape[1]:
            f.write(b"BONE")
            f.write(struct.pack("<i", len(names)))
            for n in names:
                wstr(f, n)
    log(f"  wrote viewmodels/{gun_id}.cvm ({out.stat().st_size // 1024} KiB, {len(parts)} parts, attachments {[n for n, _, _ in atts]})")
    if g.get("kind") in ("grenade", "c4"):
        write_thrown(gun_id, gun_glb, gun_skel, assets)  # the C4's: the planted bomb


def write_glove_arms(cs2, assets, arms_skel, cat, log):
    """Each CS2 glove type's first-person mesh (its 'viewmodel' mesh: both gloves and the bare forearm) as
    viewmodels/gloves_<type>.cva, joints remapped by name to the viewmodel arms skeleton; returns {type: [materials]}."""
    out = {}
    for gtype, g in cat["gloves"].items():
        model = g["model"] + "_c"
        try:
            mdir = cs2.export(model, "agent", "--gltf_export_format", "glb", "--gltf_export_materials")
            glb = Glb(next(mdir.rglob(Path(model).name.replace(".vmdl_c", ".glb"))))
            skel = parse_model_dump(cs2.data_block(model, f"glove_{gtype}_model"))
        except Exception as e:  # noqa: BLE001
            log(f"  ! gloves {gtype}: {e!r}")
            continue
        remap = np.array([arms_skel.names.index(n) if n in arms_skel.names else 0 for n in skel.names])
        parts, mats = [], []
        for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(glb, ".viewmodel"):
            w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
            tex_name = f"viewmodel/gloves_{gtype}_{mat}"
            src = next(mdir.rglob(img), None) if img else None
            if src:
                write_texture(src, assets / "textures" / (tex_name + ".png"))
            parts.append((f"{NS}:textures/{tex_name}.png", pos, nrm, uv, remap[jnt].astype(np.uint8), np.round(w * 255).astype(np.uint8), idx))
            mats.append(mat)
        dst = assets / "viewmodels" / f"gloves_{gtype}.cva"
        with open(dst, "wb") as f:
            f.write(b"CVA1")
            f.write(struct.pack("<i", len(parts)))
            for tex, pos, nrm, uv, jnt, wgt, idx in parts:
                wstr(f, tex)
                f.write(struct.pack("<i", len(pos)))
                for arr in (pos, nrm, uv):
                    f.write(np.ascontiguousarray(arr, "<f4").tobytes())
                f.write(np.ascontiguousarray(jnt).tobytes())
                f.write(np.ascontiguousarray(wgt).tobytes())
                f.write(struct.pack("<i", len(idx)))
                f.write(np.ascontiguousarray(idx, "<i4").tobytes())
        out[gtype] = mats
        log(f"gloves {gtype}: {len(parts)} parts {mats}")
    return out


def write_glove_world(cs2, assets, cat, log):
    """Each CS2 glove type's third-person mesh (its 'worldmodel' mesh) as agents/gloves_<type>.cam: a CAM1 model on the
    glove's own skeleton, which is CS2's character skeleton (bones matched to the agent animation by name), so it moves
    with the agent's hands. Its materials point at the first-person glove textures; a finish replaces them the same
    way. Agents wear it in third person instead of their own gloves."""
    sys.path.insert(0, str(HERE / "agent"))
    import agent
    anim_bones = json.loads((assets / "agents" / "skeleton.json").read_text(encoding="utf-8"))["bones"]
    done = 0
    for gtype, g in cat["gloves"].items():
        model = g["model"] + "_c"
        try:
            mdir = cs2.export(model, "agent", "--gltf_export_format", "glb", "--gltf_export_materials")
            glb = Glb(next(mdir.rglob(Path(model).name.replace(".vmdl_c", ".glb"))))
            names, parents, rest = agent.model_skeleton(cs2, model, f"glove_{gtype}_model")
        except Exception as e:  # noqa: BLE001
            log(f"  ! gloves {gtype} (third person): {e!r}")
            continue
        parts = []
        for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(glb, ".worldmodel"):
            w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
            parts.append((f"{NS}:textures/viewmodel/gloves_{gtype}_{mat}.png", "worldmodel", pos, nrm, uv, jnt.astype(np.uint8),
                          np.round(w * 255).astype(np.uint8), idx))
        if parts:
            agent.write_cam(assets / "agents" / f"gloves_{gtype}.cam", names, parents, rest, anim_bones, parts)
            done += 1
    log(f"gloves: {done} third-person glove models")


def convert_agents(cs2, assets, arms_skel, log):
    """Every CS2 agent (cosmetics catalog): its third-person body (agents/<model>.cam), its own first-person arms
    (viewmodels/arms_<model>.cva), its voice (radio/<voice>.json) and agents/agents.json + inventory images."""
    sys.path.insert(0, str(HERE / "agent"))
    import agent
    import voice
    cat = json.loads((EXTRACT / "cosmetics" / "catalog.json").read_text(encoding="utf-8"))
    anim = cs2.export("animation/anims/world/", "wanim", "--gltf_export_format", "glb", "--gltf_export_animations")
    skel = agent.skeleton(cs2, anim / "animation" / "anims" / "world" / "rifle" / "_default_rifle" / "idle_rifle.glb")
    agents_dir = assets / "agents"
    models = {}
    for a in cat["agents"]:
        models[Path(a["model"]).stem] = a["model"] + "_c"
    for name, model in models.items():
        if (agents_dir / f"{name}.cam").exists():
            continue
        try:
            agent.write_agent(cs2, agents_dir, assets, write_texture, skel["bones"], log, (name, model))
        except Exception as e:  # noqa: BLE001
            log(f"  ! agent {name}: {e!r}")
    todo = [(n, m) for n, m in models.items() if not (assets / "viewmodels" / f"arms_{n}.cva").exists()]
    try:
        write_agent_arms(cs2, assets, arms_skel, todo)
    except Exception as e:  # noqa: BLE001
        log(f"  ! agent arms: {e!r}")
    voices = sorted({a["voiceFolder"].rstrip("/").split("/")[-1] for a in cat["agents"] if a.get("voiceFolder")})
    return models, voices, cat


def current_agent(a):
    """Not one of CS:GO's old default agents (GIGN, Anarchist...: CS2 keeps them as "Default T / CT Agent" items you
    can't pick), except the FBI ones, which CS2 still has; CS2's own defaults (map_based) stay."""
    return not a["name"].startswith("Default") or a["id"].endswith("_map_based") or a["id"].startswith("customplayer_ctm_fbi")


def agent_voice(a):
    """The agent's radio voice: its own folder, or the faction's. The generic (default) FBI agents have none of their
    own and the FBI HRT set is the operators' voice: they use the SWAT voice (by request)."""
    if a["name"].startswith("Default") and a["id"].startswith("customplayer_ctm_fbi"):
        return "swat"
    return (a.get("voiceFolder") or "vo/agents/phoenix/").rstrip("/").split("/")[-1]


def write_agent_index(assets, cat, log):
    out = []
    gui = assets / "textures" / "gui" / "agents"
    gui.mkdir(parents=True, exist_ok=True)
    for a in cat["agents"]:
        model = Path(a["model"]).stem
        if not (assets / "agents" / f"{model}.cam").exists() or not current_agent(a):
            continue
        thumb = EXTRACT / "cosmetics" / a["thumbnail"] if a.get("thumbnail") else None
        has_thumb = False
        if thumb and thumb.exists():
            im = Image.open(thumb).convert("RGBA")
            im.resize((192, round(im.height * 192 / im.width)), Image.LANCZOS).save(gui / f"{a['id']}.png", optimize=True)
            has_thumb = True
        out.append({"id": a["id"], "name": a["name"], "team": a["team"], "model": model, "rarity": a.get("rarity"),
                    "voice": agent_voice(a), "thumb": has_thumb})
    (assets / "agents" / "agents.json").write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    log(f"agents: {len(out)} indexed")


def write_legacy_meshes(cs2, assets, arms_skel, guns, gltf_flags):
    """CS:GO-model skins (finishes with use_legacy_model) go on the gun's legacy mesh (body_legacy), which shares the gun
    skeleton: viewmodels/<gun>_legacy.cva holds its parts (CVM part format, joints offset past the arms bones like the
    .cvm's gun parts), drawn in place of the .cvm's gun parts when a legacy skin is equipped."""
    n = 0
    for g in guns:
        model = g.get("_asset", {}).get("model")
        if not model:
            continue
        gdir = cs2.export(model, "gltf", *gltf_flags)
        glb_path = next(gdir.rglob(Path(model).name.replace(".vmdl_c", ".glb")), None)
        if glb_path is None:
            continue
        glb = Glb(glb_path)
        legacy = next((m.get("name", "") for m in glb.j["meshes"] if m.get("name", "").endswith("legacy")), None)
        if legacy is None:
            continue
        parts = []
        for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(glb, legacy):
            tex_name = f"viewmodel/{g['id']}_{mat}"
            src = next(gdir.rglob(img), None) if img else None
            if src:
                write_texture(src, assets / "textures" / (tex_name + ".png"))
            w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
            parts.append((f"{NS}:textures/{tex_name}.png", pos, nrm, uv, (jnt + len(arms_skel.names)).astype(np.uint8),
                          np.round(w * 255).astype(np.uint8), idx))
        out = assets / "viewmodels" / f"{g['id']}_legacy.cva"
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "wb") as f:
            f.write(b"CVA1")
            f.write(struct.pack("<i", len(parts)))
            for tex, pos, nrm, uv, jnt, wgt, idx in parts:
                wstr(f, tex)
                f.write(struct.pack("<i", len(pos)))
                for arr in (pos, nrm, uv):
                    f.write(np.ascontiguousarray(arr, "<f4").tobytes())
                f.write(np.ascontiguousarray(jnt).tobytes())
                f.write(np.ascontiguousarray(wgt).tobytes())
                f.write(struct.pack("<i", len(idx)))
                f.write(np.ascontiguousarray(idx, "<i4").tobytes())
        n += 1
    log(f"legacy meshes: {n} guns / knives")


def write_deathcam_lut(cs2, assets, log):
    """CS2's death cam colour grading (lighting/postprocessing/effects/death_cam_phase1.vpost: a 32^3 colour lookup
    table, a red tint) as textures/effect/deathcam_phase1.png: 1024 x 32, red across each 32 px slice, green down,
    blue picks the slice. Phase 2 (death_cam_phase2) maps everything to black: the fade-out, drawn by the mod as a
    black fade, so it needs no table."""
    d = cs2.export("lighting/postprocessing/effects/death_cam_phase1.vpost_c", "deathcam")
    raw = next(d.rglob("death_cam_phase1.raw"), None)
    if raw is None:
        log("  ! deathcam: no LUT")
        return
    lut = np.fromfile(raw, np.uint8).reshape(32, 32, 32, 3)  # [b][g][r][rgb]
    img = np.zeros((32, 1024, 3), np.uint8)
    for b in range(32):
        img[:, b * 32:(b + 1) * 32, :] = lut[b]  # rows g, columns r
    out = assets / "textures" / "effect" / "deathcam_phase1.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(img).save(out)
    log("deathcam: LUT written")


# agents whose first-person arms the mod swaps in (the team's default agents; agent/agent.py converts their bodies)
ARM_AGENTS = (("tm_phoenix", "agents/models/tm_phoenix/tm_phoenix.vmdl_c"), ("ctm_sas", "agents/models/ctm_sas/ctm_sas.vmdl_c"))


def write_agent_arms(cs2, assets, arms_skel, agents=None):
    """Each agent's own first-person arms (CS2 draws the agent's firstperson_default_gloves_arms + firstperson_sleeves
    meshes as the viewmodel arms): viewmodels/arms_<agent>.cva, the CVM part format with joints remapped to the
    viewmodel arms skeleton (same bone names and rest pose), so they ride the baked clips in place of the .cvm arms."""
    for name, model in agents or ARM_AGENTS:
        mdir = cs2.export(model, "agent", "--gltf_export_format", "glb", "--gltf_export_materials")
        glb = Glb(next(mdir.rglob(Path(model).name.replace(".vmdl_c", ".glb"))))
        skel = parse_model_dump(cs2.data_block(model, f"agent_{name}_model"))
        remap = np.array([arms_skel.names.index(n) if n in arms_skel.names else 0 for n in skel.names])
        parts = []
        for sub in ("firstperson_default_gloves_arms", "firstperson_sleeves"):
            for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(glb, "." + sub):
                w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
                lost = {skel.names[j] for j in np.unique(jnt[w > 0]) if skel.names[j] not in arms_skel.names}
                if lost:
                    log(f"  ! arms {name}: bones not in the viewmodel skeleton {sorted(lost)}")
                # tagged by mesh: gloves can replace the agent's default gloves + bare arm and keep its sleeves
                tex_name = f"viewmodel/arms_{name}_{'sleeves' if sub == 'firstperson_sleeves' else 'gloves'}_{mat}"
                src = next(mdir.rglob(img), None) if img else None
                if src:
                    write_texture(src, assets / "textures" / (tex_name + ".png"))
                parts.append((f"{NS}:textures/{tex_name}.png", pos, nrm, uv, remap[jnt].astype(np.uint8), np.round(w * 255).astype(np.uint8), idx))
        out = assets / "viewmodels" / f"arms_{name}.cva"
        out.parent.mkdir(parents=True, exist_ok=True)
        with open(out, "wb") as f:
            f.write(b"CVA1")
            f.write(struct.pack("<i", len(parts)))
            for tex, pos, nrm, uv, jnt, wgt, idx in parts:
                wstr(f, tex)
                f.write(struct.pack("<i", len(pos)))
                for arr in (pos, nrm, uv):
                    f.write(np.ascontiguousarray(arr, "<f4").tobytes())
                f.write(np.ascontiguousarray(jnt).tobytes())
                f.write(np.ascontiguousarray(wgt).tobytes())
                f.write(struct.pack("<i", len(idx)))
                f.write(np.ascontiguousarray(idx, "<i4").tobytes())
        log(f"arms {name}: {len(parts)} parts, {sum(len(p[1]) for p in parts)} vertices")


# bones a thrown grenade leaves behind: pin, ring and spoon (CS2 drops them on release), the molotov's lighter
THROWN_HIDDEN = ("pin", "ring", "handle", "lighter", "lighter_flame", "lighter_hinge", "lighter_lever")


def write_thrown(gun_id, gun_glb, gun_skel, assets):
    """The grenade as a world object (viewmodels/<id>_thrown.cvm): its own mesh in the rest pose, centred, no arms."""
    hidden = {i for i, n in enumerate(gun_skel.names) if n in THROWN_HIDDEN}
    parts, pts = [], []
    for mat, img, pos, nrm, uv, jnt, wgt, idx in mesh_parts(gun_glb, choose_mesh(gun_glb)):
        w = wgt / np.maximum(wgt.sum(1, keepdims=True), 1e-6)
        dom = jnt[np.arange(len(jnt)), w.argmax(1)]
        pts.append(pos[~np.isin(dom, list(hidden))])
        parts.append((f"{NS}:textures/viewmodel/{gun_id}_{mat}.png", pos, nrm, uv, jnt.astype(np.uint8), np.round(w * 255).astype(np.uint8), idx))
    pts = np.concatenate(pts)
    centre = (GLTF_TO_MC @ np.append((pts.min(0) + pts.max(0)) / 2, 1.0))[:3]
    m = np.eye(4)
    m[:3, 3] = -centre
    m = m @ GLTF_TO_MC
    mats = np.repeat(m[None, None, :3, :], len(gun_skel.names), axis=1).astype(np.float32)
    mats[0, list(hidden)] = 0.0  # collapsed to a point = not drawn
    out = assets / "viewmodels" / f"{gun_id}_thrown.cvm"
    with open(out, "wb") as f:
        f.write(b"CVM1")
        f.write(struct.pack("<i", len(gun_skel.names)))
        f.write(struct.pack("<i", len(parts)))
        for tex, pos, nrm, uv, jnt, wgt, idx in parts:
            wstr(f, tex)
            f.write(struct.pack("<i", len(pos)))
            for arr in (pos, nrm, uv):
                f.write(np.ascontiguousarray(arr, "<f4").tobytes())
            f.write(np.ascontiguousarray(jnt).tobytes())
            f.write(np.ascontiguousarray(wgt).tobytes())
            f.write(struct.pack("<i", len(idx)))
            f.write(np.ascontiguousarray(idx, "<i4").tobytes())
        f.write(struct.pack("<i", 1))
        wstr(f, "idle")
        f.write(struct.pack("<ffi", 0.0, FPS, 1))
        f.write(np.ascontiguousarray(mats, "<f4").tobytes())
        f.write(struct.pack("<i", 0))
        f.write(b"BONE")
        f.write(struct.pack("<i", len(gun_skel.names)))
        for n in gun_skel.names:
            wstr(f, n)
    size = pts.max(0) - pts.min(0)
    log(f"  wrote viewmodels/{gun_id}_thrown.cvm (size {size[0]:.3f} x {size[1]:.3f} x {size[2]:.3f} m, hidden {sorted(gun_skel.names[i] for i in hidden)})")


PLAYER_SOUNDEVENTS = ("game_sounds_footsteps", "game_sounds_player")
# CS2 surfaces the mod maps Minecraft blocks to (see client/movement/Footsteps)
STEP_SURFACES = ("Concrete", "Dirt", "Grass", "Gravel", "Sand", "Snow", "Wood", "Wood_Panel", "Wood_Crate", "SolidMetal",
                 "MetalGrate", "MetalVent", "Tile", "Glass", "Carpet", "Mud", "Default", "Rubber", "Plastic_Box", "Water",
                 "Foliage", "Cardboard", "ChainLink", "Ladder", "Wood_Ladder")


def convert_player_sounds(cs2, add_sound):
    """CS2 footsteps (CT and T, one event per surface), landings, the jump and fall damage. These events inherit
    files / volume / pitch from base events, so they're read with the KV3 reader."""
    import kv3
    root = {}
    for name in PLAYER_SOUNDEVENTS:
        d = cs2.export(f"soundevents/{name}.vsndevts_c", "soundevents")
        root.update(kv3.loads(next(d.rglob(name + ".vsndevts")).read_text(encoding="utf-8")))
    lower = {k.lower(): k for k in root}

    def resolve(key):  # soundevents inherit with `base = "..."`
        e = root[key]
        b = lower.get(str(e.get("base", "")).lower())
        return {**resolve(b), **e} if b and b != key else e

    n = 0
    events = [f"{pre}{surf}.StepLeft" for surf in STEP_SURFACES for pre in ("CT_", "T_", "Land_")]
    for ev in events + ["Default.WalkJump", "Player.DamageFall"]:
        key = lower.get(ev.lower())
        if not key:
            continue
        e = resolve(key)
        files = e.get("vsnd_files_track_01") or e.get("vsnd_files") or []
        files = [files] if isinstance(files, str) else files
        add_sound(ev, files, float(e.get("volume", 1.0)), float(e.get("pitch", 1.0)))
        n += 1
    log(f"player sounds: {n} events")


# CS2 particle textures for muzzle flashes and tracers (first frame of each sequence the mod uses)
FX_TEXTURES = {
    "materials/particle/fire_gas/fire_gas_batch_b_top.vtex_c": [("fire_gas_batch_b_top_seq{}_0.png", "fx/flash_fire_{}.png", range(4))],
    "materials/particle/simulated/steam/wispy_steam_burst_b.vtex_c": [("wispy_steam_burst_b_seq{}_0.png", "fx/flash_steam_{}.png", range(2))],
    "materials/effects/spark.vtex_c": [("spark.png", "fx/spark.png", None)],
    "materials/particle/effects/bullet_tracer_seq.vtex_c": [("bullet_tracer_seq.png", "fx/tracer_rope.png", None)],
    "materials/particle/smoke/smokeburst/smokeloop_i_0_sc_hardedge.vtex_c": [("smokeloop_i_0_sc_hardedge_seq0_{}.png", "fx/smoke_{}.png", range(0, 64, 8))],
}


def convert_fx_textures(cs2, assets):
    """Additive textures: colour premultiplied by alpha (the mod draws them with additive blending)."""
    n = 0
    for vtex, outs in FX_TEXTURES.items():
        d = cs2.export(vtex, "fxtex")
        for src_pat, dst_pat, rng in outs:
            for i in (rng if rng is not None else [None]):
                src_name = src_pat.format(i) if i is not None else src_pat
                src = next(d.rglob(src_name), None)
                if src is None and i is not None and "{}" in src_pat:  # single-sequence textures export without _seqN
                    src = next(d.rglob(src_name.replace("_seq0_", "_").replace(f"_seq{i}_0", "")), None)
                if src is None:
                    log(f"  ! no fx texture {src_name} in {vtex}")
                    continue
                im = np.asarray(Image.open(src).convert("RGBA")).astype(np.float32)
                rgb = im[..., :3] * (im[..., 3:4] / 255.0)
                out = np.concatenate([rgb, np.full(im.shape[:2] + (1,), 255.0)], -1).clip(0, 255).astype(np.uint8)
                dst = assets / "textures" / (dst_pat.format(i // 8 if "smoke" in dst_pat else i) if i is not None else dst_pat)
                dst.parent.mkdir(parents=True, exist_ok=True)
                Image.fromarray(out, "RGBA").save(dst, optimize=True)
                n += 1
    log(f"fx textures: {n}")


def add_csgo(args, assets, sounds_json):
    """The optional CS:GO weapon sounds (converter/csgo.py), from --csgo PATH or the install found next to CS2."""
    import csgo
    install = Path(args.csgo) if args.csgo and args.csgo != "auto" else csgo.find_install(args.cs2)
    if install is None:
        raise SystemExit("CS:GO not found next to CS2: install Counter-Strike: Global Offensive (Steam app 4465480) or pass --csgo <folder>")
    log(f"csgo: {install}")
    csgo.convert(install, VRF, args.cache, assets, sounds_json, assets / "weapons.json", args.ffmpeg, NS, log,
                 exclusive=args.csgo_only)


# the C4's sounds (CS2's sound events) and the announcer's bomb lines
EXTRA_EVENTS = ("c4.initiate", "c4.plant", "C4.PlantSound", "C4.PlantSound_10sec", "C4.ExplodeWarning", "c4.disarmstart",
                "c4.disarmfinish", "c4.explode")
EXTRA_FILES = {"announcer.bombpl": "sounds/vo/announcer/cs2_classic/bombpl.vsnd",
               "announcer.bombdef": "sounds/vo/announcer/cs2_classic/bombdef.vsnd"}


def write_extras(assets, add_sound, log, convert_c4):
    """Extras: the C4 (viewmodel with its clips, the planted model, icon) and its sounds, the announcer's bomb lines."""
    for event in EXTRA_EVENTS:
        add_sound(event)
    for sid, vsnd in EXTRA_FILES.items():
        add_sound(sid, [vsnd], 1.0)
    convert_c4()
    log(f"extras: the C4, {len(EXTRA_EVENTS) + len(EXTRA_FILES)} sounds")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cs2", required=True, type=Path, help="CS2 install folder (the one containing game/)")
    ap.add_argument("--out", type=Path, default=ROOT / "cs2-pack")
    ap.add_argument("--cache", type=Path, default=EXTRACT / "cache")
    ap.add_argument("--ffmpeg", type=Path, default=next((ROOT / "tools").glob("ffmpeg-dist/*/bin/ffmpeg.exe"), Path("ffmpeg")))
    ap.add_argument("--install", type=Path, help="copy the finished pack folder into this resourcepacks dir")
    ap.add_argument("--only", nargs="*", help="convert just these guns, merging into the existing pack")
    ap.add_argument("--step", nargs="*", choices=["hud", "agent", "ui", "maps", "vmmaps", "voice", "arms", "musickits", "skins", "legacy", "agents", "gloves", "eyes", "ragdoll", "deathcam", "csgo", "pearl", "extras"], help="run just these steps, merging into the existing pack")
    ap.add_argument("--csgo-only", action="store_true", help="with --csgo: CS:GO's weapon sounds replace CS2's (no style choice in game)")
    ap.add_argument("--since", help="with --step skins: OLD:NEW, the pack's step version and this one's: rebake only what changed")
    ap.add_argument("--csgo", nargs="?", const="auto", type=str,
                    help="OPTIONAL: also add CS:GO's weapon sounds from your own CS:GO install (Steam app 4465480; default: found next to CS2), "
                         "for CC2's Weapon Sound Style option. With --step csgo: just that, into the existing pack")
    args = ap.parse_args()

    import weapons
    melee, grenades = weapons.load_extra()
    guns = [g for g in weapons.load() + melee + grenades if not args.only or g["id"] in args.only]
    cs2 = Cs2(args.cs2, args.cache)
    pack = args.out / PACK_NAME
    if pack.exists() and not args.only and not args.step:
        shutil.rmtree(pack)
    assets = pack / "assets" / NS
    gltf_flags = ("--gltf_export_format", "glb", "--gltf_export_materials")

    # arms (CS2's default full-finger gloves)
    arms_path = ARMS_MODEL
    arms_dir = cs2.export(arms_path, "gltf", *gltf_flags)
    arms_glb = Glb(next(arms_dir.rglob(Path(arms_path).name.replace(".vmdl_c", ".glb"))))
    arms_skel = parse_model_dump(cs2.data_block(arms_path, "arms_model"))
    log(f"arms: {len(arms_skel.names)} bones")

    vsndevts = cs2.export("soundevents/game_sounds_weapons.vsndevts_c", "soundevents")
    vsndevts_text = next(vsndevts.rglob("game_sounds_weapons.vsndevts")).read_text(encoding="utf-8")
    sounds_path = assets / "sounds.json"
    sounds_json = json.loads(sounds_path.read_text()) if (args.only or args.step) and sounds_path.exists() else {}

    converted = {}  # vsnd -> pack sound name: events sharing a file (CT / T footsteps) share the ogg

    def add_sound(event, files=None, vol=1.0, pitch=1.0):
        sid = mc_sound_id(event)
        if sid in sounds_json:
            return sid
        if files is None:
            files, vol = sound_event_files(vsndevts_text, event)
        entries = []
        for vs in files:
            name = converted.get(vs)
            if name is None:
                sdir = cs2.export(vs + "_c", "sounds")
                # the file at its own path: the cache is shared, and agents' voices reuse file names (phoenix/
                # affirmative01 and sas/affirmative01), so a search by name can pick another agent's line
                exact = sdir / Path(vs).with_suffix("")
                wav = next((p for p in (exact.with_name(exact.name + ".wav"), exact.with_name(exact.name + ".mp3")) if p.exists()), None) \
                    or next(sdir.rglob(Path(vs).stem + ".wav"), None) or next(sdir.rglob(Path(vs).stem + ".mp3"), None)
                if wav is None:
                    log(f"  ! no audio for {vs}")
                    continue
                name = sid.replace(".", "/") + "_" + Path(vs).stem
                ogg = assets / "sounds" / (name + ".ogg")
                ogg.parent.mkdir(parents=True, exist_ok=True)
                # mono: Minecraft only attenuates and positions mono sounds
                subprocess.run([str(args.ffmpeg), "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis", "-q:a", "6", str(ogg)], check=True)
                converted[vs] = name
            # Minecraft rejects the whole sounds.json if any volume is <= 0 (CS2 has silent placeholder events)
            entry = {"name": f"{NS}:{name}", "volume": round(min(max(vol, 0.05), 1.0), 3)}
            if abs(pitch - 1.0) > 1e-3:
                entry["pitch"] = round(pitch, 3)
            entries.append(entry)
        if entries:
            sounds_json[sid] = {"sounds": entries}
        return sid

    if args.step:
        if "hud" in args.step:
            import hud
            hud.convert(cs2, assets, add_sound, log)
        if "agent" in args.step:
            sys.path.insert(0, str(HERE / "agent"))
            import agent
            agent.convert(cs2, assets, write_texture, log, guns)
        if "ui" in args.step:
            import ui
            ui.convert(cs2, assets, sounds_json, args.ffmpeg, NS, log)
        if "vmmaps" in args.step:
            import vmmaps
            vmmaps.convert(assets, log)
        if "maps" in args.step:
            import ui
            ui.maps(cs2, assets, log)
        if "agents" in args.step:
            models, voices, cat = convert_agents(cs2, assets, arms_skel, log)
            import voice
            for v in voices:
                if not (assets / "radio" / f"{v}.json").exists():
                    try:
                        voice.convert_voice(cs2, assets, add_sound, log, v)
                    except Exception as e:  # noqa: BLE001
                        log(f"  ! voice {v}: {e!r}")
            write_agent_index(assets, cat, log)
        if "ragdoll" in args.step:
            import ragdoll
            cat = json.loads((EXTRACT / "cosmetics" / "catalog.json").read_text(encoding="utf-8"))
            models = {Path(a["model"]).stem: a["model"] + "_c" for a in cat["agents"]}
            for n, m in ARM_AGENTS:
                models.setdefault(n, m)
            ragdoll.convert(cs2, assets, models, log)
        if "deathcam" in args.step:
            write_deathcam_lut(cs2, assets, log)
        if "eyes" in args.step:
            import eyes
            cat = json.loads((EXTRACT / "cosmetics" / "catalog.json").read_text(encoding="utf-8"))
            models = {Path(a["model"]).stem: a["model"] + "_c" for a in cat["agents"]}
            for n, m in ARM_AGENTS:
                models.setdefault(n, m)
            if args.only:
                models = {k: v for k, v in models.items() if k in args.only}
            eyes.convert(cs2, assets, write_texture, log, models)
        if "gloves" in args.step:
            import skins
            cat = json.loads((EXTRACT / "cosmetics" / "catalog.json").read_text(encoding="utf-8"))
            mats = write_glove_arms(cs2, assets, arms_skel, cat, log)
            write_glove_world(cs2, assets, cat, log)
            skins.convert_gloves(cs2, assets, log, cat, mats)
        if "legacy" in args.step:
            write_legacy_meshes(cs2, assets, arms_skel, [g for g in weapons.load() + melee if not args.only or g["id"] in args.only], gltf_flags)
        if "skins" in args.step:
            import skins
            models = {"weapon_" + g["id"]: g["_asset"]["model"] for g in weapons.load() + melee if g.get("_asset", {}).get("model")}
            since = [int(v) for v in args.since.split(":")] if args.since else None
            rebake = skins.rebake_since(*since) if since else None
            skins.convert(cs2, assets, log, models, only={"weapon_" + x for x in args.only} if args.only else None, rebake=rebake,
                          export=lambda m: cs2.export(m, "gltf", *gltf_flags))
            if rebake is None:  # an update rebakes only what changed; the pearlescence maps didn't
                skins.convert_pearl(cs2, assets, log)
        if "pearl" in args.step and "skins" not in args.step:
            import skins
            skins.convert_pearl(cs2, assets, log)
        if "musickits" in args.step:
            import ui
            ui.music_kits(assets, sounds_json, args.ffmpeg, NS, log)
        if "voice" in args.step:
            import voice
            voice.convert(cs2, assets, add_sound, log)
        if "csgo" in args.step:
            add_csgo(args, assets, sounds_json)
        if "extras" in args.step:
            def convert_c4():
                c4 = weapons.c4_entry()
                convert_gun(c4, cs2, assets, arms_glb, arms_dir, arms_skel, add_sound, gltf_flags)
                sys.path.insert(0, str(HERE / "agent"))
                import agent
                agent.write_gun_skeleton(cs2, c4["id"], c4["_asset"]["model"], assets / "agents")  # in agents' hands
            write_extras(assets, add_sound, log, convert_c4)
        if "arms" in args.step:
            write_agent_arms(cs2, assets, arms_skel)
        guns = []
    elif not args.only:
        convert_player_sounds(cs2, add_sound)
        convert_fx_textures(cs2, assets)
        import voice
        voice.convert(cs2, assets, add_sound, log)
        import hud
        hud.convert(cs2, assets, add_sound, log)
        # CS2 agents (third-person player models, animation graphs and clips): converter/agent/agent.py
        sys.path.insert(0, str(HERE / "agent"))
        import agent
        agent.convert(cs2, assets, write_texture, log, guns)
        import ui
        ui.convert(cs2, assets, sounds_json, args.ffmpeg, NS, log)
        write_agent_arms(cs2, assets, arms_skel)

    ok, failed = [], []
    for g in guns:
        try:
            convert_gun(g, cs2, assets, arms_glb, arms_dir, arms_skel, add_sound, gltf_flags)
            ok.append(g["id"])
        except Exception as e:  # keep going: one odd gun shouldn't stop the arsenal
            import traceback
            traceback.print_exc()
            failed.append(f"{g['id']}: {e!r}")
            log(f"  ! {g['id']} failed: {e!r}")
    log(f"converted {len(ok)} gun(s): {ok}")
    if failed:
        log("FAILED: " + "; ".join(failed))

    if args.csgo and not args.step:
        add_csgo(args, assets, sounds_json)  # after the guns: their sounds are what it pairs up

    (assets / "sounds.json").write_text(json.dumps(sounds_json, indent=2))
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {
        "description": "Counter-Craft 2: CS2 assets converted from your own install. Do not redistribute.",
        "min_format": [97, 0], "max_format": [97, 1]}}, indent=2))
    log(f"pack ready: {pack}")
    if args.install:
        dst = args.install / PACK_NAME
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(pack, dst)
        log(f"installed to {dst}")


if __name__ == "__main__":
    main()
