"""Radio / chat wheel / ping / scope assets from the user's CS2 install, for convert.py.

- The default T agent's (Phoenix Connexion) voice: CS2's talker rules (scripts/talker/phoenix.vrr) map each concept
  (TLK_CW.GoGoGo, TLK_Radio.FireInTheHole, ...) to response lines with odds; the lines' sound events are converted
  and the mapping is written to assets/countercraft/radio/phoenix.json ({concept: [[sound id, odds], ...]}).
- The chat wheel's options (panorama settingsmenu_chatwheel.js: label key, concept, icon) with their English labels
  (resource/csgo_english.txt) -> radio/chatwheel.json; icons (SVG) rasterised with headless Chrome -> textures/hud/
  chatwheel/<name>.png.
- Ping: arrow / ring textures (particles/overhead_icon_fx/player_ping) and the UI.PlayerPing(Urgent) sounds.
- Scope: CS2's HUD scope textures (panorama/images/hud/scope).
"""
import json
import re
import shutil
import subprocess
from pathlib import Path

import numpy as np
from PIL import Image

import kv3

# the default agents' voices: T Phoenix, CT SAS (items_game customplayer_*_map_based vo folders)
AGENTS = ("phoenix", "sas")
CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
ICON = 64


def _lower_map(root):
    return {k.lower(): k for k in root}


def talker_concepts(text, agent=""):
    """{concept: [(sound event, odds)]} from a .vrr (response groups with an embedded rule naming TLK_<concept>; a rule
    without requirements is named <concept><agent>, e.g. sas's "Radio.Molotovsas")."""
    root = kv3.loads(text)
    out = {}
    for g in root.get("m_ResponseGroups", []):
        rule = g.get("m_pEmbeddedRule") or {}
        concept = next((r[4:] for r in rule.get("m_Requirements", []) if isinstance(r, str) and r.startswith("TLK_")), None)
        if not concept and not rule.get("m_Requirements") and agent and str(rule.get("m_name", "")).lower().endswith(agent):
            concept = rule["m_name"][:-len(agent)]
        if not concept:
            continue
        lines = [(r["m_value"], int((r.get("m_params") or {}).get("m_odds", 100))) for r in g.get("m_responses", [])
                 if r.get("m_type") == "SPEAK" and r.get("m_value")]
        if lines:
            out.setdefault(concept, []).extend(lines)
    return out


def chatwheel_options(js):
    """[(label key, concept, icon path)] from settingsmenu_chatwheel.js's m_options."""
    return [(m.group(1), m.group(2), m.group(3)) for m in
            re.finditer(r'text:\s*"#([A-Za-z0-9_]+)",\s*radio:\s*"([^"]+)",\s*icon:\s*"([^"]+)"', js)]


def english(text):
    return {m.group(1): m.group(2) for m in re.finditer(r'"([A-Za-z0-9_]+)"\s+"([^"]*)"', text)}


def rasterise_svgs(svgs, out_dir, log):
    """SVG -> PNG (ICON px, transparent) through one headless-Chrome screenshot of a grid page."""
    if not CHROME.exists() or not svgs:
        log("  ! no Chrome: chat wheel icons skipped")
        return
    out_dir.mkdir(parents=True, exist_ok=True)
    cols = 8
    rows = (len(svgs) + cols - 1) // cols
    tmp = out_dir.parent / "_icons"
    tmp.mkdir(exist_ok=True)
    cells = []
    for i, (name, svg) in enumerate(svgs):
        src = tmp / f"{i}.svg"
        shutil.copy(svg, src)
        cells.append(f'<img src="{i}.svg" style="position:absolute;left:{(i % cols) * ICON}px;top:{(i // cols) * ICON}px;'
                     f'width:{ICON}px;height:{ICON}px">')
    (tmp / "grid.html").write_text("<html><body style='margin:0;background:transparent'>" + "".join(cells) + "</body></html>")
    shot = tmp / "grid.png"
    subprocess.run([str(CHROME), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                    f"--window-size={cols * ICON},{rows * ICON}", f"--screenshot={shot}", (tmp / "grid.html").as_uri()],
                   capture_output=True, timeout=120)
    if not shot.exists():
        log("  ! Chrome screenshot failed: chat wheel icons skipped")
        return
    grid = Image.open(shot).convert("RGBA")
    for i, (name, _) in enumerate(svgs):
        x, y = (i % cols) * ICON, (i // cols) * ICON
        grid.crop((x, y, x + ICON, y + ICON)).save(out_dir / f"{name}.png", optimize=True)
    shutil.rmtree(tmp, ignore_errors=True)


def soundevents(cs2, vpk_path, add_sound):
    """add(event name) -> pack sound id (or None), for one soundevents file, following `base` inheritance."""
    d = cs2.export(vpk_path + "_c", "soundevents")
    events = kv3.loads(next(d.rglob(Path(vpk_path).name)).read_text(encoding="utf-8"))
    lower = _lower_map(events)

    def resolve(key):
        e = events[key]
        b = lower.get(str(e.get("base", "")).lower())
        return {**resolve(b), **e} if b and b != key else e

    def add(ev):
        key = lower.get(ev.lower())
        if not key:
            return None
        e = resolve(key)
        files = e.get("vsnd_files_track_01") or e.get("vsnd_files") or []
        files = [files] if isinstance(files, str) else files
        return add_sound(ev, files, float(e.get("volume", 1.0)), float(e.get("pitch", 1.0)))
    return add


def convert(cs2, assets, add_sound, log):
    for agent in AGENTS:
        convert_voice(cs2, assets, add_sound, log, agent)
    convert_ui(cs2, assets, add_sound, log)


def convert_voice(cs2, assets, add_sound, log, AGENT):
    # --- voice lines
    tdir = cs2.export(f"scripts/talker/{AGENT}.vrr_c", "ui")
    concepts = talker_concepts(next(tdir.rglob(f"{AGENT}.vrr")).read_text(encoding="utf-8"), AGENT)
    add_vo = soundevents(cs2, f"soundevents/vo/agents/game_sounds_{AGENT}.vsndevts", add_sound)

    wanted = {c: v for c, v in concepts.items() if c.startswith("CW.") or c.startswith("Radio.")}
    radio = {}
    for concept, lines in sorted(wanted.items()):
        out = []
        for ev, odds in lines:
            sid = add_vo(ev)
            if sid:
                out.append([sid, odds])
        if out:
            radio[concept] = out
    (assets / "radio").mkdir(parents=True, exist_ok=True)
    (assets / "radio" / f"{AGENT}.json").write_text(json.dumps(radio, indent=1))
    log(f"radio: {len(radio)} {AGENT} concepts, {sum(len(v) for v in radio.values())} lines")


def convert_ui(cs2, assets, add_sound, log):

    # --- chat wheel options, labels, icons
    udir = cs2.export("panorama/scripts/settingsmenu_chatwheel.vts_c", "ui")
    options = chatwheel_options(next(udir.rglob("settingsmenu_chatwheel.js")).read_text(encoding="utf-8"))
    ldir = cs2.export("resource/csgo_english.txt", "ui")
    raw = next(ldir.rglob("csgo_english.txt")).read_bytes()
    loc = english(raw.decode("utf-16" if raw[:2] in (b"\xff\xfe", b"\xfe\xff") else "utf-8", errors="replace"))
    idir = cs2.export("panorama/images/icons/ui/", "ui")
    svgs, entries = {}, []
    for key, concept, icon in options:
        name = Path(icon).stem.lower()
        svg = next(idir.rglob(Path(icon).name.lower()), None) or next(idir.rglob(Path(icon).name), None)
        if svg:
            svgs[name] = svg
        entries.append({"key": key, "text": loc.get(key, key), "concept": concept, "icon": name if svg else None})
    for extra in ("ping_1", "ping_2", "ping_3", "ping_4"):
        svg = next(idir.rglob(extra + ".svg"), None)
        if svg:
            svgs[extra] = svg
    (assets / "radio" / "chatwheel.json").write_text(json.dumps({"options": entries,
                                                                  "location": loc.get("Game_radio_location", "")}, indent=1))
    rasterise_svgs(sorted(svgs.items()), assets / "textures" / "hud" / "chatwheel", log)
    log(f"chat wheel: {len(entries)} options, {len(svgs)} icons")

    # --- ping and chat wheel sounds
    add_ui = soundevents(cs2, "soundevents/game_sounds_ui.vsndevts", add_sound)
    # achievements: CS2's achievement-earned coin chime and the XP level-up (advancement banners)
    for ev in ("UI.PlayerPing", "UI.PlayerPingUrgent", "PanoramaUI.Chatwheel.Alert", "HudChat.Message", "UI.CoinLevelUp", "UI.XP.LevelUp"):
        if not add_ui(ev):
            log(f"  ! no sound event {ev}")
    # dropped weapons: pick-up sounds per weapon class, and their physics impact sounds on landing
    add_player = soundevents(cs2, "soundevents/game_sounds_player.vsndevts", add_sound)
    for ev in ("Player.PickupWeapon", "Player.PickupPistol", "Player.PickupRifle", "Player.PickupSMG", "Player.PickupShotgun",
               "Player.PickupSniper", "Player.PickupHeavy", "Player.PickupGrenadeAudible"):
        if not add_player(ev):
            log(f"  ! no sound event {ev}")
    add_phys = soundevents(cs2, "soundevents/game_sounds_physics.vsndevts", add_sound)
    for ev in ("weapon.Pistol.Impact", "weapon.Rifle.Impact", "weapon.SMG.Impact", "weapon.Shotgun.Impact", "weapon.Sniper.Impact",
               "weapon.Heavy.Impact", "weapon.HEGrenade.Impact", "weapon.Flashbang.Impact", "weapon.Molotov.Impact",
               "weapon.Incendiary.Impact"):
        if not add_phys(ev):
            log(f"  ! no sound event {ev}")
    for vtex, name in (("materials/particle/playerping/playerping_arrow.vtex_c", "ping_arrow"),
                       ("materials/particle/playerping/playerping_arrow_outline.vtex_c", "ping_arrow_outline"),
                       ("materials/particle/playerping/playerping_ring.vtex_c", "ping_ring")):
        d = cs2.export(vtex, "fxtex")
        png = next(d.rglob(Path(vtex).name.replace(".vtex_c", ".png")), None)
        if png:
            (assets / "textures" / "fx").mkdir(parents=True, exist_ok=True)
            Image.open(png).convert("RGBA").save(assets / "textures" / "fx" / f"{name}.png", optimize=True)
        else:
            log(f"  ! no texture {vtex}")

    # --- scope overlay
    sdir2 = cs2.export("panorama/images/hud/scope/", "ui")
    for src, dst in (("scope_circle_png.png", "scope_circle"), ("scope_lens_tga.png", "scope_lens"), ("scope_line_blur_tga.png", "scope_line_blur")):
        png = next(sdir2.rglob(src), None)
        if png:
            (assets / "textures" / "hud").mkdir(parents=True, exist_ok=True)
            Image.open(png).convert("RGBA").save(assets / "textures" / "hud" / f"{dst}.png", optimize=True)
    log("scope / ping textures done")
