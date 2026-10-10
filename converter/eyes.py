"""Agent eyes. CS2 draws the iris and pupil in its character shader (csgo_character F_EYEBALLS): the head's colour map
has blank eyeballs, and the shader projects g_tEyeAlbedo1 (the iris) onto the eyeball area given by g_tEyeMask1 (red
channel), sized by g_flEyeIrisSize1 / g_flEyePupilSize1 and aimed by eye bones. Minecraft has no such shader, so this
bakes the eyes into each agent head texture (textures/agent/<agent>_<material>.png, rewritten from CS2's colour map, so
re-running is safe).

Approximation (flagged): the iris is centred on each eye area of the mask, its diameter IRIS_FRAC x the area's width x
g_flEyeIrisSize1, the pupil a dark disc of g_flEyePupilSize1 x 2 of the iris diameter; CS2's walleye angles and eye
movement are ignored (the eyes look straight ahead)."""
import re
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image

# iris diameter / visible eye width for an iris size of 1 (a human iris is ~40-45 % of the eye opening)
IRIS_FRAC = 0.42


def _vmat_params(cs2, vmat):
    text = cs2._run("-b", "DATA", "-f", vmat + "_c")
    out = {"ints": {}, "floats": {}, "tex": {}}
    for m in re.finditer(r'm_name = "(\w+)"\s*m_nValue = (-?\d+)', text):
        out["ints"][m.group(1)] = int(m.group(2))
    for m in re.finditer(r'm_name = "(\w+)"\s*m_flValue = (-?[\d.eE+-]+)', text):
        out["floats"][m.group(1)] = float(m.group(2))
    for m in re.finditer(r'm_name = "(\w+)"\s*m_pValue = resource:"([^"]+)"', text):
        out["tex"][m.group(1)] = m.group(2)
    return out


def _components(mask, cell=4, thresh=0.3):
    """Connected areas of mask > thresh on a coarse grid: list of (x0, y0, x1, y1, cx, cy) in full-res pixels."""
    h, w = mask.shape
    gh, gw = h // cell, w // cell
    grid = mask[:gh * cell, :gw * cell].reshape(gh, cell, gw, cell).max(axis=(1, 3)) > thresh
    seen = np.zeros_like(grid)
    out = []
    for y in range(gh):
        for x in range(gw):
            if not grid[y, x] or seen[y, x]:
                continue
            q = deque([(y, x)])
            seen[y, x] = True
            cells = []
            while q:
                cy, cx = q.popleft()
                cells.append((cy, cx))
                for ny, nx in ((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)):
                    if 0 <= ny < gh and 0 <= nx < gw and grid[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
            if len(cells) < 4:
                continue
            ys = np.array([c[0] for c in cells])
            xs = np.array([c[1] for c in cells])
            x0, x1, y0, y1 = xs.min() * cell, (xs.max() + 1) * cell, ys.min() * cell, (ys.max() + 1) * cell
            out.extend(_split(mask, x0, y0, x1, y1))
    # stray specks of the mask aren't eyes
    big = max(((c[2] - c[0]) * (c[3] - c[1]) for c in out), default=0)
    return [c for c in out if (c[2] - c[0]) * (c[3] - c[1]) >= 0.15 * big]


def _area(mask, x0, y0, x1, y1):
    sub = mask[y0:y1, x0:x1]
    yy, xx = np.mgrid[y0:y1, x0:x1]
    wsum = max(sub.sum(), 1e-6)
    ys = np.nonzero(sub.max(1) > 0.3)[0]
    if len(ys):
        y0, y1 = y0 + ys.min(), y0 + ys.max() + 1
    return (x0, y0, x1, y1, float((xx * sub).sum() / wsum), float((yy * sub).sum() / wsum))


def _split(mask, x0, y0, x1, y1):
    """An area much wider than one eye (> 4:1) is both eyes touching (ski masks): split at its narrowest column."""
    if (x1 - x0) <= 4 * (y1 - y0):
        return [_area(mask, x0, y0, x1, y1)]
    cols = (mask[y0:y1, x0:x1] > 0.3).sum(0).astype(np.float32)
    lo, hi = int(len(cols) * 0.3), int(len(cols) * 0.7)
    cut = x0 + lo + int(np.argmin(cols[lo:hi]))
    return [_area(mask, x0, y0, cut, y1), _area(mask, cut, y0, x1, y1)]


def _iris(albedo):
    """The iris disc of an eyeball albedo (its alpha marks the iris), cropped square, RGBA float."""
    a = np.asarray(albedo.getchannel("A"))
    ys, xs = np.nonzero(a > 8)
    if len(xs) == 0:
        return albedo
    side = max(xs.max() - xs.min(), ys.max() - ys.min()) + 1
    cx, cy = (xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2
    return albedo.crop((int(cx - side / 2), int(cy - side / 2), int(cx + side / 2), int(cy + side / 2)))


def bake(tex_path, mask_img, albedo_img, iris_size, pupil_size):
    im = np.asarray(Image.open(tex_path).convert("RGBA")).astype(np.float32)
    h, w = im.shape[:2]
    mask = np.asarray(mask_img.convert("RGBA").getchannel("R").resize((w, h), Image.BILINEAR)).astype(np.float32) / 255
    iris_src = _iris(albedo_img.convert("RGBA"))
    n = 0
    for x0, y0, x1, y1, cx, cy in _components(mask):
        d = IRIS_FRAC * (x1 - x0) * iris_size
        if d < 3:
            continue
        size = max(3, int(round(d)))
        # resize per channel: Pillow's RGBA resize premultiplies and wipes the colour where alpha is 0
        chans = [np.asarray(c.resize((size, size), Image.LANCZOS)).astype(np.float32) for c in iris_src.split()]
        iris = np.stack(chans, -1)
        yy, xx = np.mgrid[0:size, 0:size]
        r = np.hypot(xx - (size - 1) / 2, yy - (size - 1) / 2) / (size / 2)
        disc = np.clip((1 - r) * size / 2, 0, 1)  # antialiased circle edge
        alpha = disc  # the iris art is opaque inside its disc
        rgb = iris[..., :3]
        pupil = np.clip((pupil_size * 2 - r) * size / 2, 0, 1)
        rgb = rgb * (1 - pupil[..., None]) + np.array([12, 9, 8], np.float32) * pupil[..., None]
        px0, py0 = int(round(cx - size / 2)), int(round(cy - size / 2))
        sx0, sy0 = max(0, -px0), max(0, -py0)
        tx0, ty0 = max(0, px0), max(0, py0)
        tx1, ty1 = min(w, px0 + size), min(h, py0 + size)
        if tx1 <= tx0 or ty1 <= ty0:
            continue
        a = alpha[sy0:sy0 + ty1 - ty0, sx0:sx0 + tx1 - tx0] * mask[ty0:ty1, tx0:tx1]  # the eyelids (mask edge) cover it
        dst = im[ty0:ty1, tx0:tx1, :3]
        im[ty0:ty1, tx0:tx1, :3] = dst * (1 - a[..., None]) + rgb[sy0:sy0 + ty1 - ty0, sx0:sx0 + tx1 - tx0] * a[..., None]
        n += 1
    Image.fromarray(np.clip(im, 0, 255).astype(np.uint8)).save(tex_path, optimize=True)
    return n


def convert(cs2, assets, write_texture, log, models):
    """models: {agent name: vmdl_c path}. Bakes every eyed material of each converted agent."""
    from convert import Glb, mesh_parts
    sys_agent = Path(__file__).resolve().parent / "agent"
    import sys
    sys.path.insert(0, str(sys_agent))
    from agent import AGENT_MESHES
    total = 0
    for name, model in models.items():
        if not (assets / "agents" / f"{name}.cam").exists():
            continue
        mdir = cs2.export(model, "agent", "--gltf_export_format", "glb", "--gltf_export_materials")
        glb_file = next(mdir.rglob(Path(model).name.replace(".vmdl_c", ".glb")), None)
        if glb_file is None:
            continue
        glb = Glb(glb_file)
        dump = cs2.data_block(model, f"agent_{name}_model")
        done = set()
        for sub in AGENT_MESHES:
            for mat, img, *_ in mesh_parts(glb, "." + sub):
                if mat in done or not img:
                    continue
                done.add(mat)
                vm = re.search(r'[\w/]+/' + re.escape(mat) + r'\.vmat', dump)
                if not vm:
                    continue
                p = _vmat_params(cs2, vm.group(0))
                if not p["ints"].get("F_EYEBALLS") or "g_tEyeMask1" not in p["tex"] or "g_tEyeAlbedo1" not in p["tex"]:
                    continue
                pngs = {}
                for key in ("g_tEyeMask1", "g_tEyeAlbedo1"):
                    stem = Path(p["tex"][key]).stem
                    f = next(mdir.rglob(stem + ".png"), None) or next(cs2.cache.rglob(stem + ".png"), None)
                    if f is None:
                        tdir = cs2.export(p["tex"][key] + "_c", "eyes", "--texture_decode_flags", "none")
                        f = next(tdir.rglob(stem + ".png"), None)
                    pngs[key] = f
                if not all(pngs.values()):
                    log(f"  ! eyes {name}/{mat}: textures missing")
                    continue
                src = next(mdir.rglob(img), None)
                dst = assets / "textures" / f"agent/{name}_{mat}.png"
                if src is None:
                    continue
                write_texture(src, dst)
                n = bake(dst, Image.open(pngs["g_tEyeMask1"]), Image.open(pngs["g_tEyeAlbedo1"]),
                         p["floats"].get("g_flEyeIrisSize1", 1.0), p["floats"].get("g_flEyePupilSize1", 0.15))
                total += n
                if n != 2:
                    log(f"  eyes {name}/{mat}: {n} eye(s)")
    log(f"eyes: {total} eyes baked")
