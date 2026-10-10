"""Kill feed, hit/kill feedback sounds and crosshair-editor backgrounds from the user's CS2 install, for convert.py.

- Kill feed icons: CS2's death notice icons (panorama/images/icons/equipment/<weapon>.vsvg and
  panorama/images/hud/deathnotice/*.vsvg) rasterised with headless Chrome at 2x their 24 px HUD height, keeping each
  SVG's aspect ratio -> textures/hud/killfeed/<name>.png, sizes in hud/killfeed.json.
- Feedback sounds: the Player.(Damage|Death)(HeadShot|Body)[Armor].(AttackerFeedback|Victim|Onlooker) events of
  soundevents/game_sounds_player.vsndevts, with their sub-events (soundevent_01) expanded -> hud/feedback.json
  {event: [sound id, ...]} (a composite like the helmet "dink" plays several sounds together).
- Crosshair editor backgrounds: panorama/images/crosshair/xhair_<map>.png -> textures/hud/crosshair_bg/<map>.png.
"""
import json
import re
import shutil
import subprocess
from pathlib import Path

from PIL import Image

import kv3
from voice import CHROME, _lower_map

ICON_H = 48  # 2x the 24 px death notice icon height
DEATHNOTICE_ICONS = ("icon_headshot", "penetrate", "noscope", "smoke_kill", "blind_kill", "inairkill", "icon_suicide", "domination",
                     "revenge")
FEEDBACK = [f"Player.{kind}{part}{armor}.{who}" for kind in ("Damage", "Death") for part in ("HeadShot", "Body")
            for armor in ("", "Armor") for who in ("AttackerFeedback", "Victim", "Onlooker")]


def _svg_size(svg: Path):
    text = svg.read_text(encoding="utf-8", errors="replace")
    m = re.search(r'viewBox="\s*([-\d.]+)[\s,]+([-\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)', text)
    if m:
        return float(m.group(3)), float(m.group(4))
    w = re.search(r'width="([\d.]+)', text)
    h = re.search(r'height="([\d.]+)', text)
    return (float(w.group(1)), float(h.group(1))) if w and h else (1.0, 1.0)


def rasterise_aspect(svgs, out_dir, log, height=ICON_H):
    """SVG -> PNG at `height` px high, natural width, transparent; returns {name: [w, h]}."""
    ICON_H = height  # noqa: N806
    if not CHROME.exists() or not svgs:
        log("  ! no Chrome: kill feed icons skipped")
        return {}
    out_dir.mkdir(parents=True, exist_ok=True)
    tmp = out_dir.parent / "_kf"
    tmp.mkdir(exist_ok=True)
    cells, sizes, y, maxw = [], {}, 0, 0
    for i, (name, svg) in enumerate(svgs):
        vw, vh = _svg_size(svg)
        w = max(1, round(ICON_H * vw / vh))
        shutil.copy(svg, tmp / f"{i}.svg")
        cells.append(f'<img src="{i}.svg" style="position:absolute;left:0;top:{y}px;width:{w}px;height:{ICON_H}px">')
        sizes[name] = (w, y)
        y += ICON_H + 4
        maxw = max(maxw, w)
    (tmp / "grid.html").write_text("<html><body style='margin:0;background:transparent'>" + "".join(cells) + "</body></html>")
    shot = tmp / "grid.png"
    subprocess.run([str(CHROME), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                    f"--window-size={maxw},{y}", f"--screenshot={shot}", (tmp / "grid.html").as_uri()], capture_output=True, timeout=180)
    if not shot.exists():
        log("  ! Chrome screenshot failed: kill feed icons skipped")
        return {}
    grid = Image.open(shot).convert("RGBA")
    out = {}
    for name, (w, top) in sizes.items():
        grid.crop((0, top, w, top + ICON_H)).save(out_dir / f"{name}.png", optimize=True)
        out[name] = [w, ICON_H]
    shutil.rmtree(tmp, ignore_errors=True)
    return out


def feedback_sounds(cs2, add_sound, log):
    d = cs2.export("soundevents/game_sounds_player.vsndevts_c", "soundevents")
    events = kv3.loads(next(d.rglob("game_sounds_player.vsndevts")).read_text(encoding="utf-8"))
    lower = _lower_map(events)

    def resolve(key):
        e = events[key]
        b = lower.get(str(e.get("base", "")).lower())
        return {**resolve(b), **e} if b and b != key else e

    def expand(key, depth=0):
        """The pack sounds one event plays: its own files (unless silent) and its sub-events'."""
        if depth > 4 or key not in events:
            return []
        e = resolve(key)
        out = []
        files = [f for f in e.get("vsnd_files_track_01", e.get("vsnd_files", [])) if f and "null.vsnd" not in f]
        if isinstance(files, str):
            files = [files]
        vol = float(e.get("volume", 1.0))
        if files and vol > 0:
            sid = add_sound(key, files, vol, float(e.get("pitch", 1.0)))
            if sid:
                out.append(sid)
        subs = e.get("soundevent_01", [])
        for sub in ([subs] if isinstance(subs, str) else subs):
            if sub:
                out += expand(lower.get(sub.lower(), sub), depth + 1)
        return out

    table = {}
    for ev in FEEDBACK:
        key = lower.get(ev.lower())
        if key:
            table[ev] = expand(key)
    log(f"hud: {len(table)} feedback sound events")
    return table


def convert(cs2, assets, add_sound, log):
    hud = assets / "hud"
    hud.mkdir(parents=True, exist_ok=True)
    # kill feed icons
    edir = cs2.export("panorama/images/icons/equipment/", "ui")
    ddir = cs2.export("panorama/images/hud/deathnotice/", "ui")
    svgs = sorted({p.stem: p for p in (edir / "panorama" / "images" / "icons" / "equipment").glob("*.svg")}.items())
    svgs += [(n, p) for n in DEATHNOTICE_ICONS for p in [next(ddir.rglob(n + ".svg"), None)] if p]
    sizes = rasterise_aspect(svgs, assets / "textures" / "hud" / "killfeed", log)
    (hud / "killfeed.json").write_text(json.dumps({"icons": sizes}, indent=1))
    log(f"hud: {len(sizes)} kill feed icons")
    # feedback sounds
    (hud / "feedback.json").write_text(json.dumps(feedback_sounds(cs2, add_sound, log), indent=1))
    # crosshair editor backgrounds
    xdir = cs2.export("panorama/images/crosshair/", "ui")
    n = 0
    for png in sorted(xdir.rglob("xhair_*.png")):
        name = png.stem.replace("xhair_", "").replace("_png", "")
        dst = assets / "textures" / "hud" / "crosshair_bg" / f"{name}.png"
        dst.parent.mkdir(parents=True, exist_ok=True)
        im = Image.open(png).convert("RGB")
        if im.width > 1920:
            im = im.resize((1920, round(im.height * 1920 / im.width)), Image.LANCZOS)
        im.save(dst, optimize=True)
        n += 1
    log(f"hud: {n} crosshair editor backgrounds")
