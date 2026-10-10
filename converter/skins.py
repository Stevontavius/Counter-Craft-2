"""CS2 weapon and knife skins -> baked albedo textures for the mod (convert.py --step skins).

CS2 builds a skin's textures at runtime: a composite material (weapons/paints/**.vcompmat) feeds the
csgo_customweapon shader a paint style (F_PAINT_STYLE), colours, a pattern texture and the weapon's own composite
inputs (stock colour, paint masks, cavity / AO, object-space position map). This bakes one albedo per finish at its
best condition (no wear), on the mesh the finish uses: the CS2 model (body_hd) or, for legacyModel finishes, the
CS:GO model (body_legacy) with its own inputs (materials/models/weapons/customization/<weapon>/).

APPROXIMATION (the user's choice: the shader isn't decompiled): each paint style is rebuilt from the public CS:GO
workshop description of the styles. Close for custom paint jobs, gunsmith, patina and solid colours; anodized,
hydrographic and spray finishes differ in metal response, pattern scale and projection detail.

Paint styles (F_PAINT_STYLE = catalog style - 1): 0 solid colour, 1 hydrographic, 2 spray paint, 3 anodized,
4 anodized multicolored, 5 anodized airbrushed, 6 custom paint job, 7 patina, 8 gunsmith (9: gunsmith variant).

Outputs (assets/countercraft): textures/skins/<item>/<paint kit>.png (1024, RGB), textures/gui/skins/<item>/<paint kit>.png
(inventory images), skins/index.json.
"""
from paths import EXTRACT
import json
import os
import re
from concurrent.futures import ProcessPoolExecutor
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image

import kv3

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
COS = EXTRACT / "cosmetics"
TEX = EXTRACT / "cache" / "skintex"
DUMPS = EXTRACT / "cache" / "skinvmats"
RES = 1024
# knives bake at their own textures' resolution (CS2's knife textures are 2048; at 1024 a finish halved the knife's detail)
KNIFE_RES = 2048
KNIFE_TINT_GAMMA = 1.5
# knife finishes' roughness / metal maps (finish_spec): smooth data, so half the albedo's resolution
SPEC_RES = 1024
# CS2 item names whose mod id differs (CS2 kept the CS:GO internal names)
ALIAS = {"weapon_knife_survival_bowie": "weapon_knife_bowie", "weapon_knife_gypsy_jackknife": "weapon_knife_navaja",
         "weapon_knife_widowmaker": "weapon_knife_talon"}
THUMB_W = 192
# pattern scale reference: the templates' default weapon length (inches) for a UV scale of 1
REF_LENGTH = 32.0


# ---------------------------------------------------------------- material data

def kv3_docs(path):
    """Every KV3 document in a VRF `-b DATA` dump of many files."""
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    out = []
    for chunk in text.split("<!-- kv3")[1:]:
        body = chunk[chunk.index("-->") + 3:]
        # the dump interleaves VRF's file headers ("--- ...", "[n/N] path") after each document's closing brace
        end = body.rfind("\n}")
        try:
            out.append(kv3.loads(body[:end + 2]))
        except Exception:  # noqa: BLE001
            pass
    return out


def params(doc):
    """{name: value} of a material's int / float / vector / texture / string params (textures as .vtex paths)."""
    p = {}
    for key in ("m_intParams", "m_floatParams", "m_vectorParams", "m_textureParams", "m_stringParams", "m_stringAttributes", "m_intAttributes", "m_floatAttributes"):
        for e in doc.get(key) or []:
            name = e.get("m_name")
            for vk in ("m_nValue", "m_flValue", "m_value", "m_pValue"):
                if vk in e:
                    p[name] = e[vk]
                    break
    return p


# The material dumps the finishes are made from (VRF -b DATA of each set's .vmat files): file -> VPK path filters.
DUMP_SETS = {
    "materials_models_weapons_customization_paints_vmats_.txt": "materials/models/weapons/customization/paints/vmats/",
    "workshop_paintkits_templates_.txt": "workshop/paintkits/templates/",
    "materials_models_weapons_customization_paints_gloves_.txt": "materials/models/weapons/customization/paints_gloves/",
    "weapons_models.txt": "weapons/models/",
    "gloves_paints.txt": "gloves/paints/",
    "legacy_models.txt": "materials/models/weapons/v_models/,materials/models/weapons/customization/",
    "gloves_volatile.txt": "items/assets/paintkits/volatile_02/",
}


def ensure_dumps(cs2, log):
    """Make the material dumps that aren't there yet (a fresh extraction has none)."""
    DUMPS.mkdir(parents=True, exist_ok=True)
    for name, filters in DUMP_SETS.items():
        f = DUMPS / name
        if not f.exists() or f.stat().st_size == 0:
            log(f"skins: dumping the materials in {filters}")
            f.write_text(cs2._run("-b", "DATA", "-e", "vmat_c", "-f", filters), encoding="utf-8")


def load_materials():
    """Material path -> params, from the dumps (paint vmats, templates, weapon materials, composite inputs)."""
    mats = {}
    for f in DUMPS.glob("*.txt"):
        for d in kv3_docs(f):
            name = d.get("m_materialName")
            if name:
                mats[name.lower()] = (d.get("m_shaderName", ""), params(d))
    return mats


def finish_params(vcompmat_rel, mats):
    """A finish's shader inputs: its paint material's params overlaid with the composite material's exposed params."""
    f = COS / "paints" / vcompmat_rel
    if not f.exists():
        return None
    t = f.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'm_strSpecificContainerMaterial = (?:resource_name:)?"([^"]+)"', t)
    base = {}
    if m:
        entry = mats.get(m.group(1).lower())
        if entry:
            base = dict(entry[1])
    # exposed_params loose variables
    sec = t.split('m_strAlias = "exposed_params"')
    if len(sec) > 1:
        body = sec[1].split("m_strAlias =")[0]
        for var in re.split(r"\n\t*\{\n", body):
            nm = re.search(r'm_strName = "(\w+)"', var)
            if not nm:
                continue
            n = nm.group(1)
            if (c := re.search(r"m_cValueColor4 = \[([^\]]*)\]", var)):
                base[n] = [float(x) / 255.0 for x in c.group(1).split(",")[:3]] + [1.0]
            elif (r := re.search(r'm_strTextureRuntimeResourcePath = (?:resource_name:)?"([^"]+)"', var)):
                base[n] = r.group(1)
            elif "LOOSE_VARIABLE_TYPE_FLOAT2" in var:
                x = re.search(r"m_flValueFloatX = (-?[\d.e-]+)", var)
                y = re.search(r"m_flValueFloatY = (-?[\d.e-]+)", var)
                base[n] = [float(x.group(1)) if x else 0.0, float(y.group(1)) if y else 0.0, 0, 0]
            elif (x := re.search(r"m_flValueFloatX = (-?[\d.e-]+)", var)):
                base[n] = float(x.group(1))
            elif (b := re.search(r"m_bValueBoolean = (true|false)", var)):
                base[n] = 1 if b.group(1) == "true" else 0
            elif (i := re.search(r"m_nValueIntX = (-?\d+)", var)):
                base[n] = int(i.group(1))
    return base


# ---------------------------------------------------------------- textures

def tex_file(vtex):
    """Where VRF decompiled a .vtex (png, or exr for HDR), or None."""
    stem = TEX / Path(vtex).with_suffix("")
    for ext in (".png", ".exr"):
        p = stem.with_name(stem.name + ext)
        if p.exists():
            return p
    return None


def export_textures(cs2, paths, log):
    """Decompile the .vtex files that aren't in the cache yet (batches of VRF path filters)."""
    todo = sorted({p for p in paths if p and tex_file(p) is None})
    log(f"skins: exporting {len(todo)} textures")
    TEX.mkdir(parents=True, exist_ok=True)
    for i in range(0, len(todo), 120):
        batch = todo[i:i + 120]
        cs2._run("-o", str(TEX), "-d", "--texture_decode_flags", "none", "--threads", "8", "-f", ",".join(p + "_c" for p in batch) + ",__never__/")
    missing = [p for p in todo if tex_file(p) is None]
    if missing:
        log(f"  ! {len(missing)} textures not found, e.g. {missing[:3]}")


@lru_cache(maxsize=64)
def load_tex(vtex, size=RES, mode="RGBA"):
    """A texture as float32 HxWxC in 0..1 (HDR as stored), resized to size x size; None if missing."""
    if not vtex:
        return None
    f = tex_file(vtex)
    if f is None:
        return None
    if f.suffix == ".exr":
        import OpenEXR
        with OpenEXR.File(str(f)) as e:
            a = np.asarray(next(iter(e.channels().values())).pixels, np.float32)
        if a.ndim == 2:
            a = a[..., None]
        ys = (np.arange(size) * a.shape[0] // size)
        xs = (np.arange(size) * a.shape[1] // size)
        return a[ys][:, xs]
    return _resize(Image.open(f).convert(mode), (size, size))


@lru_cache(maxsize=256)
def load_pattern(vtex):
    """A pattern texture at its own resolution (max 2048), RGBA float."""
    f = tex_file(vtex) if vtex else None
    if f is None or f.suffix == ".exr":
        return None
    im = Image.open(f).convert("RGBA")
    if max(im.size) > 2048:
        k = 2048 / max(im.size)
        return _resize(im, (max(1, round(im.width * k)), max(1, round(im.height * k))))
    return np.asarray(im, np.float32) / 255.0


def _resize(im, size):
    """Resize channel by channel: Pillow premultiplies RGBA by alpha when resizing, and in these textures alpha is
    data (or 0), not coverage, so the colour channels would be wiped."""
    if im.size != size:
        im = Image.merge(im.mode, [b.resize(size, Image.BILINEAR) for b in im.split()])
    return np.asarray(im, np.float32) / 255.0


def _address(t, mode):
    """Source 2 texture address modes: 0 wrap, 2 clamp, 4 mirror once (anything else: wrap)."""
    if mode == 2:
        return np.clip(t, 0.0, 1.0 - 1e-6)
    if mode == 4:
        return np.clip(np.abs(t), 0.0, 1.0 - 1e-6)
    return np.mod(t, 1.0)


def sample(img, u, v, mode_u=0, mode_v=0):
    """Bilinear sample of img (HxWxC) at uv arrays, wrapping or clamped per axis (nearest made scaled-up patterns
    blocky). A clamped Fade has to stay clamped: wrapped, the Karambit's ring picked up the far end of the fade."""
    h, w = img.shape[:2]
    x = _address(u, mode_u) * w - 0.5
    y = _address(v, mode_v) * h - 0.5
    if mode_u in (2, 4):
        x = np.clip(x, 0, w - 1)
    if mode_v in (2, 4):
        y = np.clip(y, 0, h - 1)
    x0 = np.floor(x).astype(np.int32)
    y0 = np.floor(y).astype(np.int32)
    fx = (x - x0)[..., None]
    fy = (y - y0)[..., None]
    x0 %= w
    y0 %= h
    x1 = (x0 + 1) % w if mode_u not in (2, 4) else np.minimum(x0 + 1, w - 1)
    y1 = (y0 + 1) % h if mode_v not in (2, 4) else np.minimum(y0 + 1, h - 1)
    top = img[y0, x0] * (1 - fx) + img[y0, x1] * fx
    bot = img[y1, x0] * (1 - fx) + img[y1, x1] * fx
    return top * (1 - fy) + bot * fy


# ---------------------------------------------------------------- baking

def _vec(p, name, default):
    v = p.get(name, default)
    return np.array((list(v) + [1, 1, 1, 1])[:3], np.float32) if isinstance(v, (list, tuple)) else np.array(default[:3], np.float32)


def bake(target, p, want_spec=False):
    """One finish's albedo (res x res x 3, 0..1) on a target's UV layout (res: RES, KNIFE_RES for knives); with
    want_spec, (albedo, finish_spec(...) or None)."""
    RES = target.get("res", globals()["RES"])  # noqa: N806
    base = load_tex(target["color"], RES)[..., :3] if load_tex(target["color"], RES) is not None else np.full((RES, RES, 3), 0.5, np.float32)
    masks_t = p.get("g_tPaintByNumberMasks") if int(p.get("g_bOverrideDefaultMasks", 0) or p.get("g_bUsePaintByNumberMasks", 0) or 0) else None
    masks = load_tex(masks_t, RES) if masks_t else None
    if masks is None:
        masks = load_tex(target["masks"], RES) if target.get("masks") else None
    if masks is None:
        masks = np.ones((RES, RES, 4), np.float32) * np.array([1, 0, 0, 1], np.float32)
    ao_t = load_tex(target["ao"], RES) if target.get("ao") else None
    ao = ao_t[..., 0] if ao_t is not None else np.ones((RES, RES), np.float32)
    pos = load_tex(target["pos"], RES) if target.get("pos") else None

    style = int(p.get("F_PAINT_STYLE", 6))
    c = [_vec(p, f"g_vColor{i}", [1, 1, 1]) for i in range(4)]
    scale = float(p.get("g_flPatternTexCoordScale", 1.0) or 1.0)
    airbrushed = style == 5 and pos is not None
    if not int(p.get("g_bIgnoreWeaponSizeScale", 0) or 0) and not airbrushed:
        scale *= target.get("uv_scale", 1.0) * target.get("length", REF_LENGTH) / REF_LENGTH
    # the pattern turns the other way from the angle's sign (matched on Doppler phases: phase 2 is pink-dominant, as in
    # CS2's images, only this way round)
    rot = -np.radians(float(p.get("g_flPatternTexCoordRotation", 0.0) or 0.0))
    off = p.get("g_vPatternTexCoordOffset", [0, 0])
    off = (list(off) + [0, 0])[:2] if isinstance(off, (list, tuple)) else [0, 0]

    t = (np.arange(RES, dtype=np.float32) + 0.5) / RES
    u, v = np.meshgrid(t, t)
    if style in (2, 5) and pos is not None:
        # spray / airbrushed: projected from the side through the object-space position map (length along y)
        u, v = np.clip(pos[..., 1], -1, 2), 1.0 - np.clip(pos[..., 2], -1, 2)
    if airbrushed:
        # anodized airbrushed (Fade): the position map (0..1 over the model, no weapon-size scale) turned about its
        # origin. APPROXIMATION fitted on CS2's Karambit / Flip / Butterfly Fade images: silver ring, pink to purple blade
        y, z = np.clip(pos[..., 1], -1, 2), np.clip(pos[..., 2], -1, 2)
        cu, cv, a = 1 - y, z, -rot
        pu = (cu * np.cos(a) - cv * np.sin(a)) * scale + 0.5 + off[0]
        pv = (cu * np.sin(a) + cv * np.cos(a)) * scale + 0.5 + off[1]
    else:
        cu, cv = u - 0.5, v - 0.5
        pu = (cu * np.cos(rot) - cv * np.sin(rot)) * scale + 0.5 + off[0]
        pv = (cu * np.sin(rot) + cv * np.cos(rot)) * scale + 0.5 + off[1]
    pat_img = load_pattern(p.get("g_tPattern")) if isinstance(p.get("g_tPattern"), str) else None
    pat = sample(pat_img, pu, pv, int(p.get("g_nPatternTextureHorizontalSampling", 0) or 0),
                 int(p.get("g_nPatternTextureVerticalSampling", 0) or 0)) if pat_img is not None else np.zeros((RES, RES, 4), np.float32)

    lum = base @ np.array([0.299, 0.587, 0.114], np.float32)
    # anodized: the metal's own shading, relative to the painted metal's median (the whole texture's median is mostly
    # dark grips and empty UV space, which lifted every blade x1.6 and turned Dopplers pastel)
    painted = lum[masks[..., 0] > 0.5]
    metal = np.clip(lum / max(1e-3, float(np.median(painted if painted.size else lum))), 0.45, 1.6)[..., None]
    if target.get("detail"):
        # knives: CS2's finishes don't carry the stock blade's art (its etched logo, scratches, bright bevel) through
        # the paint; the blade's shape reads from AO and the per-finish roughness / metal map instead
        metal = 1.0
    # coverage (matched against CS2's inventory images): custom paint jobs and gunsmith finishes cover the whole weapon;
    # metal treatments (anodized, patina) only the metal (masks red). Paints (solid, hydrographic, spray) on CS:GO
    # models also follow masks red (every hydrographic AWP leaves the barrel and scope bare; the AK's red includes its
    # wood); on CS2 models they cover the whole weapon. A custom pattern's alpha is a finish channel, not coverage (AWP
    # Asiimov's is ~0.13 everywhere). Known difference: CS2 shows Fire Serpent's AK wood bare, which nothing in its data
    # explains.
    if style in (3, 4, 5, 7) or (style in (0, 1, 2) and target.get("legacy")):
        area = masks[..., 0:1]
    else:
        area = np.ones((RES, RES, 1), np.float32)

    def mix(colours):
        col = np.broadcast_to(colours[0], (RES, RES, 3)).copy()
        for k in range(3):
            col = col + (colours[k + 1] - col) * pat[..., k:k + 1]
        return col

    if style == 0:
        col = np.broadcast_to(c[0], (RES, RES, 3)).copy()
        col = col + (c[1] - col) * masks[..., 1:2]
        col = col + (c[2] - col) * masks[..., 2:3]
    elif style in (1, 2):
        col = mix(c)
    elif style == 3:
        col = np.broadcast_to(c[0], (RES, RES, 3)) * metal
        col = col + (c[1] * metal - col) * masks[..., 1:2]
        col = col + (c[2] * metal - col) * masks[..., 2:3]
    elif style in (4, 5):
        tinted = any(np.abs(c[k] - c[0]).max() > 0.02 for k in (1, 2, 3))
        col = (mix(c) if tinted else pat[..., :3]) * metal
        # (the pattern's alpha isn't colour: Tiger Tooth's stripes read lighter on CS2's Karambit and darker on its
        # Flip, so it's the finish's roughness; see the spec map below)
    elif style == 7:
        col = pat[..., :3] * metal
    else:  # 6 custom paint job, 8 / 9 gunsmith
        col = pat[..., :3]
    if target.get("detail") and style in (3, 4, 5, 7):
        # knives, metal tints (anodized, patina): CS2 shows them on polished metal whose reflections deepen the tint
        # (Fade's pink is vivid there); as flat albedo they read pastel. APPROXIMATION (no shader): a curve that keeps
        # light tints light and richens the mid tones.
        col = np.clip(col, 0, 1) ** KNIFE_TINT_GAMMA
    shade = (0.35 + 0.65 * ao)[..., None] if not target.get("detail") else (0.2 + 0.8 * ao)[..., None]
    out = np.clip(base + (np.clip(col, 0, 1) * shade - base) * area, 0, 1)
    if not want_spec:
        return out
    return out, finish_spec(target, p, style, pat, masks, area, RES)


def finish_spec(target, p, style, pat, masks, area, res):
    """A knife finish's roughness / metal map (R roughness, G metal, B 255: its AO is in the albedo), SPEC_RES square,
    for the lit viewmodel: paints (solid, hydrographic, spray, custom, gunsmith) are non-metal at g_flPaintRoughness;
    anodized finishes are metal at g_flPaintRoughness, Doppler-style patterns' alpha making it vary (polished vs satin
    swirls / stripes, APPROXIMATION); unpainted parts keep the blade's own CS2 map. Patina: None (the blade's own map)."""
    if style == 7 or not target.get("spec"):
        return None
    step = max(1, res // SPEC_RES)
    sl = (slice(None, None, step), slice(None, None, step))
    base = np.asarray(Image.open(target["spec"]).convert("RGB").resize((SPEC_RES, SPEC_RES), Image.BILINEAR), np.float32) / 255.0
    a = area[sl][..., 0]
    paint_rough = float(p.get("g_flPaintRoughness") or (0.3 if style in (3, 4, 5) else 0.6))
    rough = np.full((SPEC_RES, SPEC_RES), paint_rough, np.float32)
    if style in (3, 4, 5):
        alpha = pat[sl][..., 3]
        if style == 4 and float(alpha.std()) > 0.02:
            ref = alpha[masks[sl][..., 0] > 0.5]
            rough = np.clip(rough * np.clip(alpha / max(1e-3, float(np.median(ref if ref.size else alpha))), 0.4, 2.5), 0.06, 0.85)
        metal = np.ones_like(rough)
    else:
        metal = np.zeros_like(rough)
    r_ = base[..., 0] + (rough - base[..., 0]) * a
    g_ = base[..., 1] + (metal - base[..., 1]) * a
    return np.dstack([r_, g_, np.ones_like(r_)])


def _bake_job(job):
    target, p, out = job
    try:
        img, spec = bake(target, p, want_spec=True)
        out.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray((img * 255 + 0.5).astype(np.uint8)).save(out, optimize=False, compress_level=6)
        s_out = out.with_name(out.stem + "_s.png")
        if spec is not None:
            Image.fromarray((np.clip(spec, 0, 1) * 255 + 0.5).astype(np.uint8)).save(s_out, optimize=False, compress_level=6)
        elif s_out.exists():
            s_out.unlink()
        return None
    except Exception as e:  # noqa: BLE001
        return f"{out.name}: {e!r}"


# ---------------------------------------------------------------- targets

def _glb_materials(glb_path):
    import struct
    b = Path(glb_path).read_bytes()
    n = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + n])
    out = {}
    for m in j["meshes"]:
        mesh = m.get("name", "").split(".")[-1]
        for prim in m["primitives"]:
            mat = j["materials"][prim["material"]]
            out.setdefault(mesh, []).append((mat.get("name"), (mat.get("extras") or {}).get("vmat") or {}))
    return out


def _glb_rough(glb_path, material):
    """The file of a glTF material's metallic-roughness image (CS2's packed R roughness / G metal), or None."""
    import struct
    b = Path(glb_path).read_bytes()
    n = struct.unpack("<I", b[12:16])[0]
    j = json.loads(b[20:20 + n])
    for m in j.get("materials", []):
        tex = (m.get("pbrMetallicRoughness") or {}).get("metallicRoughnessTexture")
        if m.get("name") == material and tex is not None:
            f = Path(glb_path).parent / j["images"][j["textures"][tex["index"]]["source"]].get("uri", "")
            return str(f) if f.is_file() else None
    return None


def targets(models, mats, log):
    """Per item: the CS2 target (composite inputs) and the legacy target (CS:GO customization inputs)."""
    comp = {}
    for name, (shader, p) in mats.items():
        if shader == "csgo_composite_inputs.vfx" and p.get("layer_name_1"):
            comp[str(p["layer_name_1"]).replace("\\", "/").lower()] = p
    custom_dir = COS / "paints" / "materials" / "models" / "weapons" / "customization"
    out = {}
    for item, model in models.items():
        glb = next((EXTRACT / "cache" / "gltf").glob(model.replace(".vmdl_c", ".glb").replace(".vmdl", ".glb")), None)
        if glb is None:
            log(f"  ! skins: no glTF export for {item} ({model})")
            continue
        meshes = _glb_materials(glb)
        t = {}
        for mesh, mlist in meshes.items():
            if mesh.endswith("legacy") or "legacy" in mesh:
                for name, vm in mlist:
                    vpath = str(vm.get("Name", "")).lower()
                    if "v_models/" not in vpath:
                        continue
                    folder = vpath.split("v_models/")[1].split("/")[0]
                    files = {f.name for f in (custom_dir / folder).glob("*")} if (custom_dir / folder).exists() else set()

                    def pick(kind):
                        f = next((x for x in sorted(files) if f"_{kind}_" in x), None)
                        return f"materials/models/weapons/customization/{folder}/{Path(f).stem}.vtex" if f else None
                    color = (vm.get("TextureParams") or {}).get("g_tColor")
                    color_path = None
                    if color:
                        # the legacy colour sits next to the vmat
                        color_path = str(Path(vpath).parent / color).replace("\\", "/")
                    t["legacy"] = {"material": name, "color": color_path, "masks": pick("masks"), "ao": pick("ao"),
                                   "pos": pick("pos"), "surface": pick("surface"), "legacy": True}
                    break
            for name, vm in mlist:
                vpath = str(vm.get("Name", "")).lower()
                if vpath in comp:
                    p = comp[vpath]
                    t["hd"] = {"material": name, "color": p.get("g_tColor"), "masks": p.get("g_tMasks"), "ao": p.get("g_tAmbientOcclusion"),
                               "pos": p.get("g_tPosition"), "surface": p.get("g_tSurface"),
                               "uv_scale": float(p.get("g_flUvScale1", 1.0)), "length": float(p.get("g_flWeaponLength1", REF_LENGTH))}
        if "legacy" in t and "hd" in t:
            t["legacy"].setdefault("uv_scale", t["hd"]["uv_scale"])
            t["legacy"].setdefault("length", t["hd"]["length"])
        if "knife" in item or "bayonet" in item:
            for w in t.values():
                w["res"] = KNIFE_RES
                w["detail"] = True
                w["spec"] = _glb_rough(glb, w["material"])
        if not t:
            log(f"  ! skins: no skinnable material on {item}")
        out[item] = t
    return out


# ---------------------------------------------------------------- main

def bake_processes():
    """How many finishes bake at once. A 2048 px knife bake holds several 2048^2 float layers (a few GB), so it follows the
    PC's memory: 8 at once ran a 32 GB PC out of it. CC_SKIN_THREADS overrides."""
    if os.environ.get("CC_SKIN_THREADS"):
        return max(1, int(os.environ["CC_SKIN_THREADS"]))
    total_gb = 16
    try:
        import ctypes

        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [(n, ctypes.c_ulonglong) for n in (
                "total_phys", "avail_phys", "total_page", "avail_page", "total_virtual", "avail_virtual", "avail_ext")]
        st = MemoryStatus()
        st.length = ctypes.sizeof(MemoryStatus)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(st)):
            total_gb = st.total_phys / 2**30
    except (AttributeError, OSError):
        pass  # not Windows: assume 16 GB
    return max(1, min(os.cpu_count() or 4, 6, int((total_gb - 8) // 5)))


def convert(cs2, assets, log, models, threads=None, only=None, rebake=None):
    """models: CS2 item name ("weapon_ak47") -> model path, for every gun and knife the mod has."""
    cat = json.loads((COS / "catalog.json").read_text(encoding="utf-8"))
    ensure_dumps(cs2, log)
    mats = load_materials()
    log(f"skins: {len(mats)} materials from the dumps")
    tg = targets(models, mats, log)
    finishes = []
    for kind in ("weapons", "knives"):
        for f in cat["finishes"][kind]:
            f = dict(f, item=ALIAS.get(f["item"], f["item"]))
            if f["item"] in tg and (not only or f["item"] in only):
                finishes.append(f)
    jobs, need, index = [], set(), []
    for f in finishes:
        p = finish_params(f["compositeMaterial"], mats) if f.get("compositeMaterial") else None
        which = "legacy" if f.get("legacyModel") else "hd"
        target = tg[f["item"]].get(which) or tg[f["item"]].get("hd")
        if p is None or target is None:
            log(f"  ! skins: {f['item']} {f['paintKit']}: no {'params' if p is None else 'target'}")
            continue
        for k in ("color", "masks", "ao", "pos"):
            need.add(target.get(k))
        for k, v in p.items():
            if k.startswith("g_t") and isinstance(v, str) and v.endswith(".vtex"):
                need.add(v)
        mod_id = f["item"][7:] if f["item"].startswith("weapon_") else f["item"]
        out = assets / "textures" / "skins" / mod_id / f"{f['paintKit']}.png"
        if rebake is None or rebake(target, p):
            jobs.append((target, p, out))
        thumb = None
        src = COS / f["thumbnail"] if f.get("thumbnail") else None
        if src and src.exists():
            thumb = assets / "textures" / "gui" / "skins" / mod_id / f"{f['paintKit']}.png"
            if not thumb.exists():
                thumb.parent.mkdir(parents=True, exist_ok=True)
                im = Image.open(src).convert("RGBA")
                im.resize((THUMB_W, round(im.height * THUMB_W / im.width)), Image.LANCZOS).save(thumb, optimize=True)
        index.append({"id": f["paintKitId"], "item": mod_id, "kit": f["paintKit"], "name": f["name"], "rarity": f.get("rarity"),
                      "legacy": which == "legacy" and "legacy" in tg[f["item"]], "material": target["material"],
                      "collection": (f.get("collection") or {}).get("name"), "style": f.get("style"),
                      "thumb": thumb is not None})
    export_textures(cs2, need, log)
    threads = threads or bake_processes()
    log(f"skins: baking {len(jobs)} finishes ({threads} processes)")
    errors = []
    with ProcessPoolExecutor(threads) as ex:
        for i, err in enumerate(ex.map(_bake_job, jobs, chunksize=4)):
            if err:
                errors.append(err)
            if (i + 1) % 200 == 0:
                log(f"  {i + 1}/{len(jobs)}")
    for e in errors[:10]:
        log(f"  ! {e}")
    items = {(k[7:] if k.startswith("weapon_") else k): {w: v["material"] for w, v in t.items()} for k, t in tg.items()}
    rar = {k: {"name": v["name"], "color": cat["colors"].get(v["color"], "#b0c3d9")} for k, v in cat["rarities"].items()}
    (assets / "skins").mkdir(parents=True, exist_ok=True)
    old = assets / "skins" / "index.json"
    if only and old.exists():
        # a partial run: keep the other weapons' entries
        done = {e["item"] for e in index}
        index = [e for e in json.loads(old.read_text(encoding="utf-8"))["skins"] if e["item"] not in done] + index
    (assets / "skins" / "index.json").write_text(json.dumps({"items": items, "rarities": rar, "skins": index}, ensure_ascii=False), encoding="utf-8")
    log(f"skins: {len(index)} finishes indexed, {len(errors)} bake errors")


# ---------------------------------------------------------------- gloves

def _tint(p, name, default=(1, 1, 1)):
    v = p.get(name)
    return np.array((list(v) + [1, 1, 1])[:3], np.float32) if isinstance(v, (list, tuple)) else np.array(default, np.float32)


def _is_default(path):
    return not isinstance(path, str) or "/default/" in path or "materials/default" in path


def _tiled(vtex, scale, rot=0.0, off=(0.0, 0.0)):
    img = load_pattern(vtex) if isinstance(vtex, str) else None
    if img is None:
        return None
    t = (np.arange(RES, dtype=np.float32) + 0.5) / RES
    u, v = np.meshgrid(t, t)
    if rot:
        c, s = np.cos(np.radians(rot)), np.sin(np.radians(rot))
        u, v = 0.5 + c * (u - 0.5) - s * (v - 0.5), 0.5 + s * (u - 0.5) + c * (v - 0.5)
    sx, sy = (scale, scale) if np.isscalar(scale) else scale
    return sample(img, u * sx + off[0], v * sy + off[1])


def bake_glove(shader, p, base):
    """One glove finish's albedo on the glove UVs (APPROXIMATION of csgo_customglove / csgo_customglove_preview):
    classic gloves: the layer mask's alpha is a tint id in 8 steps (18, 49, 77 ... 226 / 255) choosing g_vColorTint1-8
    (Black Tie: tint 1 the dark back, tint 2 the white palm side); its red / green / blue pick the material layer
    (detail texture 1-4, the body being layer 1). A pattern replaces tint id g_nPatternReplaceIndex: mode 0 its black /
    red / green / blue pick tints g_vPatternPaletteIndices (like a hydrographic paint), mode 2 (fades, case hardened)
    shows its own colours. Newer gloves: the layer-id map's red / green / blue choose surface
    textures 1-3 (else 4), tiled by g_vUvScaleN and tinted by g_vSurfaceColorTintN. Both shaded by the glove's AO."""
    if "preview" in shader:
        lid = load_tex(p.get("g_tLayerId"))
        lid = lid if lid is not None else np.zeros((RES, RES, 4), np.float32)
        w = [lid[..., 0], lid[..., 1], lid[..., 2]]
        w.append(np.clip(1 - (w[0] + w[1] + w[2]), 0, 1))
        out = np.zeros((RES, RES, 3), np.float32)
        for k in range(4):
            uvs = p.get(f"g_vUvScale{k + 1}", [1, 1])
            uvs = (list(uvs) + [1, 1])[:2] if isinstance(uvs, (list, tuple)) else [1, 1]
            col = _tiled(p.get(f"g_tSurface{k + 1}"), (max(uvs[0], 0.01), max(uvs[1], 0.01)))
            col = col[..., :3] if col is not None else np.full((RES, RES, 3), 0.5, np.float32)
            out += w[k][..., None] * col * _tint(p, f"g_vSurfaceColorTint{k + 1}")
        ao = load_tex(p.get("g_tObjectProperties"))
    else:
        m = load_tex(p.get("g_tLayerMask"))
        m = m if m is not None else np.zeros((RES, RES, 4), np.float32)
        ids = np.clip(np.rint((m[..., 3] * 255 - 18) / 29.71), 0, 7).astype(np.int32)
        tints = np.stack([_tint(p, f"g_vColorTint{i + 1}") for i in range(8)])
        out = tints[ids]
        palette = p.get("g_vPatternPaletteIndices")
        replace = int(p.get("g_nPatternReplaceIndex", 0) or 0)
        if isinstance(palette, (list, tuple)) and 1 <= replace <= 8 and not _is_default(p.get("g_tPattern")):
            off = p.get("g_fPatternTexCoordOffset", [0, 0])
            pat = _tiled(p.get("g_tPattern"), float(p.get("g_fPatternTexCoordScale", 1.0) or 1.0),
                         float(p.get("g_fPatternTexCoordRotation", 0.0) or 0.0), tuple((list(off) + [0, 0])[:2]))
            if pat is not None:
                if p.get("g_nPatternMode") == 2:
                    col = pat[..., :3]
                else:
                    pt = [tints[int(i) - 1] for i in list(palette)[:4]]
                    ch = np.clip(pat[..., :3], 0, 1)
                    col = pt[0] * np.clip(1 - ch.sum(-1, keepdims=True), 0, 1)
                    for c in range(3):
                        col = col + pt[c + 1] * ch[..., c:c + 1]
                out = np.where((ids == replace - 1)[..., None], col, out)
        # each material layer's detail texture shades it (APPROXIMATION of the detail / grunge / cloth terms)
        w = [np.clip(1 - (m[..., 0] + m[..., 1] + m[..., 2]), 0, 1), m[..., 0], m[..., 1], m[..., 2]]
        shade = np.ones((RES, RES, 1), np.float32)
        for k in range(4):
            det = _tiled(p.get(f"g_tDetail{k + 1}"), float(p.get(f"g_fDetailScale{k + 1}", 1.0) or 1.0))
            if det is not None:
                shade += w[k][..., None] * 0.5 * (det[..., :3].mean(-1, keepdims=True) - 0.5)
        out = out * shade
        ao = load_tex(p.get("g_tSurface"))
    if ao is not None:
        out *= (0.35 + 0.65 * ao[..., 0:1])
    return np.clip(out, 0, 1)


def glove_maps(shader, p):
    """A glove finish's roughness / metal map (R roughness, G metal, B 255: the AO is in the albedo) and tangent-space
    normal map (the glove's g_tNormal, stored as XY: Z rebuilt), RES square, for the lit viewmodel. Classic gloves: each
    material layer's roughness from g_fDetailRoughnessBrightnessN about leather's 0.55 and metal from
    g_fDetailMetalnessN (a badge, a buckle); a mode-2 pattern (fades, case hardened) g_fPatternMetalness /
    g_fPatternRoughnessBrightness. APPROXIMATION of csgo_customglove's detail / grunge / cloth terms."""
    rough = np.full((RES, RES), 0.7, np.float32)
    metal = np.zeros((RES, RES), np.float32)
    if "preview" not in shader:
        m = load_tex(p.get("g_tLayerMask"))
        if m is not None:
            w = [np.clip(1 - (m[..., 0] + m[..., 1] + m[..., 2]), 0, 1), m[..., 0], m[..., 1], m[..., 2]]
            rough = sum(w[k] * float(np.clip(0.55 * float(p.get(f"g_fDetailRoughnessBrightness{k + 1}", 1.0) or 1.0), 0.3, 0.95))
                        for k in range(4))
            metal = sum(w[k] * float(p.get(f"g_fDetailMetalness{k + 1}", 0.0) or 0.0) for k in range(4))
            replace = int(p.get("g_nPatternReplaceIndex", 0) or 0)
            if p.get("g_nPatternMode") == 2 and 1 <= replace <= 8 and not _is_default(p.get("g_tPattern")):
                region = np.clip(np.rint((m[..., 3] * 255 - 18) / 29.71), 0, 7) == replace - 1
                metal = np.where(region, float(p.get("g_fPatternMetalness", 0.0) or 0.0), metal)
                rough = np.where(region, rough * float(p.get("g_fPatternRoughnessBrightness", 1.0) or 1.0), rough)
    spec = np.dstack([np.clip(rough, 0.04, 1), np.clip(metal, 0, 1), np.ones((RES, RES), np.float32)])
    n = load_tex(p.get("g_tNormal"))
    if n is not None and not _is_default(p.get("g_tNormal")):
        xy = n[..., :2] * 2 - 1
        z = np.sqrt(np.clip(1 - (xy ** 2).sum(-1), 0, 1))
        n = np.dstack([n[..., 0], n[..., 1], z * 0.5 + 0.5])
    else:
        n = None
    return spec, n


def _glove_job(job):
    shader, p, out = job
    try:
        img = bake_glove(shader, p, None)
        out.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray((img * 255 + 0.5).astype(np.uint8)).save(out, compress_level=6)
        spec, nrm = glove_maps(shader, p)
        Image.fromarray((spec * 255 + 0.5).astype(np.uint8)).save(out.with_name(out.stem + "_s.png"), compress_level=6)
        if nrm is not None:
            Image.fromarray((nrm * 255 + 0.5).astype(np.uint8)).save(out.with_name(out.stem + "_n.png"), compress_level=6)
        return None
    except Exception as e:  # noqa: BLE001
        return f"{out.name}: {e!r}"


def convert_gloves(cs2, assets, log, cat, glove_mats, threads=None):
    """Every glove finish: baked albedo (textures/skins/gloves/<type>/<kit>.png), inventory image, skins/gloves.json."""
    ensure_dumps(cs2, log)
    mats = load_materials()
    by_name = {k.rsplit("/", 1)[-1][:-5]: v for k, v in mats.items() if k.endswith(".vmat") and v[0].startswith("csgo_customglove")}
    jobs, need, index = [], set(), []
    for f in cat["finishes"]["gloves"]:
        gtype, kit = f["item"], f["paintKit"]
        entry = by_name.get(kit)
        if entry is None or gtype not in glove_mats:
            log(f"  ! gloves {gtype} {kit}: no {'paint material' if entry is None else 'model'}")
            continue
        shader, p = entry
        need.update(v for k, v in p.items() if k.startswith("g_t") and isinstance(v, str))
        out = assets / "textures" / "skins" / "gloves" / gtype / f"{kit}.png"
        jobs.append((shader, p, out))
        thumb = False
        src = COS / f["thumbnail"] if f.get("thumbnail") else None
        if src and src.exists():
            t = assets / "textures" / "gui" / "skins" / "gloves" / gtype / f"{kit}.png"
            t.parent.mkdir(parents=True, exist_ok=True)
            im = Image.open(src).convert("RGBA")
            im.resize((THUMB_W, round(im.height * THUMB_W / im.width)), Image.LANCZOS).save(t, optimize=True)
            thumb = True
        index.append({"id": f["paintKitId"], "type": gtype, "kit": kit, "name": f["name"], "rarity": f.get("rarity"), "thumb": thumb})
    export_textures(cs2, need, log)
    errors = []
    threads = threads or bake_processes()
    with ProcessPoolExecutor(threads) as ex:
        errors = [e for e in ex.map(_glove_job, jobs) if e]
    for e in errors[:10]:
        log(f"  ! {e}")
    types = {t: {"name": cat["gloves"][t]["name"], "materials": [m for m in ms if not m.startswith("bare_arm")]} for t, ms in glove_mats.items()}
    (assets / "skins").mkdir(parents=True, exist_ok=True)
    (assets / "skins" / "gloves.json").write_text(json.dumps({"types": types, "skins": index}, ensure_ascii=False, indent=1), encoding="utf-8")
    log(f"gloves: {len(index)} finishes on {len(types)} glove types, {len(errors)} bake errors")


# ---------------------------------------------------------------- pearlescence

PEARL_FINISHES = ("Printstream",)
# CS2's g_flPearlescentScale that the mod's shader treats as full strength (the Desert Eagle's; the M4A1-S / USP-S have
# 20, the AWP 6)
PEARL_FULL = 25.0


def convert_pearl(cs2, assets, log):
    """Printstream's pearlescence (convert.py --step pearl; also after --step skins): for each finish, <kit>_s.png next to
    its skin texture: the paint's own roughness and metalness (g_flPaintRoughness / g_flPaintMetalness: Printstream is
    a non-metal paint, matte white, not the stock gun's bare metal), the stock material's AO, and, in alpha, 1 - CS2's
    pearlescence mask x g_flPearlescentScale / PEARL_FULL (alpha 1: none; textures without this map read as none). The
    mod's lit weapon shader turns it into a view-dependent sheen, scaled by its Pearlescence slider."""
    cat = json.loads((COS / "catalog.json").read_text(encoding="utf-8"))
    idx = json.loads((assets / "skins" / "index.json").read_text(encoding="utf-8"))
    by_kit = {s["kit"]: s for s in idx["skins"]}
    ensure_dumps(cs2, log)
    mats = load_materials()
    default_rgb = (217, 0, 255)  # textures/misc/vm_default_s.png: the mod's maps for a material without any
    jobs = []
    for f in cat["finishes"]["weapons"]:
        if f["name"] not in PEARL_FINISHES or f["paintKit"] not in by_kit or not f.get("compositeMaterial"):
            continue
        p = finish_params(f["compositeMaterial"], mats) or {}
        scale = float(p.get("g_flPearlescentScale", 0) or 0)
        if scale <= 0:
            continue
        mask = p.get("g_tPearlescenceMask") if p.get("F_PEARLESCENCE_MASK") and p.get("g_bUsePearlescenceMask", 1) else None
        paint = (float(p.get("g_flPaintRoughness", 0.6) or 0.6), float(p.get("g_flPaintMetalness", 0) or 0))
        jobs.append((by_kit[f["paintKit"]], scale, mask, paint))
    export_textures(cs2, [j[2] for j in jobs if j[2]], log)
    for s, scale, mask, (rough, metal) in jobs:
        skin = assets / "textures" / "skins" / s["item"] / (s["kit"] + ".png")
        if not skin.exists():
            log(f"  ! no skin texture for {s['kit']}")
            continue
        size = Image.open(skin).size
        stock = assets / "textures" / "viewmodel" / f"{s['item']}_{s['material']}_s.png"
        ao = _resize(Image.open(stock).convert("RGB"), size)[..., 2] if stock.exists() else \
            np.full((size[1], size[0]), default_rgb[2] / 255.0, np.float32)
        rgb = np.dstack([np.full_like(ao, rough), np.full_like(ao, metal), ao])
        m = _resize(Image.open(tex_file(mask)).convert("L"), size) if mask and tex_file(mask) else np.ones((size[1], size[0]), np.float32)
        amount = np.clip(m.reshape(size[1], size[0]) * min(1.0, scale / PEARL_FULL), 0.0, 1.0)
        out = np.dstack([rgb, 1.0 - amount])
        Image.fromarray(np.round(out * 255).astype(np.uint8), "RGBA").save(skin.with_name(s["kit"] + "_s.png"), optimize=True)
        log(f"  pearl {s['item']} {s['name']}: scale {scale:g}, mask {'yes' if mask else 'whole finish'}, mean {amount.mean():.2f}, "
            f"roughness {rough:g}, metal {metal:g}")
    log(f"pearl: {len(jobs)} finishes")
