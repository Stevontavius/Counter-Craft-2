"""Viewmodel and agent shading maps (convert.py --step vmmaps): for every viewmodel texture
textures/viewmodel/<owner>_<material>.png and agent texture textures/agent/<agent>_<material>.png, the CS2 material's packed roughness / metalness map and ambient occlusion as <..>_s.png (R roughness, G metalness, B AO)
and its tangent-space normal map as <..>_n.png (when it isn't CS2's flat default), for the mod's CS:GO-style viewmodel
lighting (shaders/core/agent_vm.fsh; agents use it too when Agent Shaders is on). Agents' maps are capped at 1024 px
(they're never as close as the viewmodel; full size would add ~2 GB to the pack). Read from the glTF exports already in the cache (their materials name the maps);
nothing is decompiled again. Weapons (csgo_weapon) pack R roughness / G metalness; agent arms, sleeves and gloves
(csgo_character) keep roughness in the normal map's alpha and metalness in their metal map's green (red is empty; CS2's
metal constant 1 compiles to green, e.g. professional_watch_gold, the SAS lenses, the visors), and most of them
point g_tMetalness at CS2's black default (which read as roughness 0: skin like a mirror).
"""
from paths import EXTRACT
import json
import struct
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
CACHE = EXTRACT / "cache"
GLTF = [CACHE / "gltf", CACHE / "agent"]
# texture folder -> largest map side
FOLDERS = {"viewmodel": 2048, "agent": 1024}
# APPROXIMATIONS for agents (the mod's shader has neither CS2's anisotropic hair / gloss nor its cloth sheen BRDF, and
# read as glossy plastic with these materials' raw values): anisotropic-gloss materials (hair, some uniforms) keep
# their roughness in g_tAnisoGloss, not the normal map's alpha (empty there, which read as a mirror), so they get a
# fixed matte roughness; cloth-shaded materials are never smoother than CLOTH_MIN_ROUGHNESS. Lenses (flagged as cloth in
# CS2's data, but glass) are left as CS2 has them.
ANISO_ROUGHNESS = 0.7
CLOTH_MIN_ROUGHNESS = 0.75


def _materials():
    """Material name -> (glb folder, base colour, metal/rough, normal, occlusion image file names), from every export."""
    out = {}
    for glb in (g for d in GLTF for g in d.rglob("*.glb")):
        if "physics" in glb.name:
            continue
        b = glb.read_bytes()
        n = struct.unpack("<I", b[12:16])[0]
        j = json.loads(b[20:20 + n])

        def uri(tex):
            if tex is None:
                return None
            return j["images"][j["textures"][tex["index"]]["source"]].get("uri")
        for m in j.get("materials", []):
            pbr = m.get("pbrMetallicRoughness", {})
            vmat = (m.get("extras") or {}).get("vmat") or {}
            adj = (vmat.get("VectorParams") or {}).get("g_vRoughnessAdjustBrightnessContrast")
            out.setdefault(m.get("name"), (glb.parent, uri(pbr.get("baseColorTexture")), uri(pbr.get("metallicRoughnessTexture")),
                                           uri(m.get("normalTexture")), uri(m.get("occlusionTexture")), adj, vmat.get("IntParams") or {}))
    return out


def _load(folder, name, size, mode):
    f = folder / name if name else None
    if f is None or not f.exists():
        return None
    im = Image.open(f).convert(mode)
    if im.size != size:
        im = im.resize(size, Image.BILINEAR)
    return np.asarray(im)


def convert(assets, log, folders=tuple(FOLDERS)):
    mats = _materials()
    names = sorted(mats, key=len, reverse=True)
    for kind in folders:
        _convert(assets / "textures" / kind, FOLDERS[kind], mats, names, log)


def _convert(vm, cap, mats, names, log):
    spec_n = norm_n = 0
    for tex in sorted(vm.glob("*.png")):
        if tex.stem.endswith(("_s", "_n")) or "chargemeter" in tex.stem:
            continue
        mat = next((m for m in names if m and tex.stem.endswith("_" + m)), None)
        if mat is None:
            continue
        folder, _, rough, normal, ao, adj, flags = mats[mat]
        size = Image.open(tex).size
        size = (min(size[0], cap), min(size[1], cap))
        character = "agents" in folder.parts
        if character:
            nm = _load(folder, normal, size, "RGBA")
            mt = _load(folder, rough, size, "RGB")
            r = None if nm is None else np.dstack([nm[..., 3], mt[..., 1] if mt is not None else np.zeros(size[::-1], np.uint8)])
            if r is not None and adj:
                # g_vRoughnessAdjustBrightnessContrast (APPROXIMATION of the formula: contrast about 0.5, then brightness)
                g = (r[..., 0] / 255.0 - 0.5) * adj[1] + 0.5 + adj[0]
                r = np.dstack([np.clip(g * 255 + 0.5, 0, 255).astype(np.uint8), r[..., 1]])
            if r is not None and vm.name == "agent" and "lens" not in mat:  # glass (SAS gas mask lenses) keeps its gloss
                if flags.get("F_ANISOTROPIC_GLOSS"):
                    r = np.dstack([np.full(r.shape[:2], round(ANISO_ROUGHNESS * 255), np.uint8), r[..., 1]])
                elif flags.get("F_CLOTH_SHADING"):
                    r = np.dstack([np.maximum(r[..., 0], round(CLOTH_MIN_ROUGHNESS * 255)).astype(np.uint8), r[..., 1]])
        else:
            r = _load(folder, rough, size, "RGB")
        if r is not None:
            a = _load(folder, ao, size, "L")
            spec = np.dstack([r[..., 0], r[..., 1], a if a is not None else np.full(size[::-1], 255, np.uint8)])
            Image.fromarray(spec.astype(np.uint8)).save(vm / f"{tex.stem}_s.png", optimize=False, compress_level=6)
            spec_n += 1
        if normal and not normal.startswith("default_normal"):
            nm = _load(folder, normal, size, "RGB")
            if nm is not None:
                Image.fromarray(nm).save(vm / f"{tex.stem}_n.png", optimize=False, compress_level=6)
                norm_n += 1
    log(f"vmmaps {vm.name}: {spec_n} roughness/metal maps, {norm_n} normal maps")
