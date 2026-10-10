"""Menu assets (buy menu, loadout, team select, main menu music) from the user's CS2 install, for convert.py --step ui.

- Buy menu icons: panorama/images/icons/equipment/<item>.vsvg rasterised at 96 px high (natural width)
  -> textures/gui/buy/<item>.png, sizes in gui/buy_icons.json.
- Loadout tiles: panorama/images/econ/weapons/base_weapons/<item>.png (CS2's inventory renders) -> textures/gui/weapons/.
- Team select: t_logo / ct_logo (256 px), the default map background, the cancel / camera / refresh button icons.
- Music kit: CS2's default kit (valve_cs2_01), every track, stereo and streamed -> sounds/music/valve_cs2_01/<track>.ogg,
  sound events music.valve_cs2_01.<track> (only mainmenu and chooseteam are used so far).
"""
from paths import EXTRACT
import json
import re
import subprocess
from pathlib import Path

from PIL import Image

from hud import rasterise_aspect

DEFAULT_KIT = "valve_cs2_01"


def _png(src, dst, max_w=None):
    dst.parent.mkdir(parents=True, exist_ok=True)
    im = Image.open(src).convert("RGBA")
    if max_w and im.width > max_w:
        im = im.resize((max_w, round(im.height * max_w / im.width)), Image.LANCZOS)
    im.save(dst, optimize=True)


# loadout cosmetic slots (agent, music kit): CS2's inventory renders, from the cosmetics extraction (cosmetics.py)
COSMETIC_THUMBS = ("customplayer_tm_phoenix", "customplayer_ctm_sas", DEFAULT_KIT)


def cosmetic_thumbnails(assets, log):
    src = EXTRACT / "cosmetics" / "thumbnails"
    n = 0
    for name in COSMETIC_THUMBS:
        if (src / f"{name}.png").exists():
            _png(src / f"{name}.png", assets / "textures" / "gui" / "cosmetics" / f"{name}.png", 256)
            n += 1
    log(f"ui: {n} cosmetic thumbnails")


# music kit tracks the mod plays: the main menu and the team select screen
# menus; the death cam (survival) and round lost (hardcore deaths); Counter-Craft's day as a round: won round at dawn,
# the bomb track for the last 40 s of night (CS2's bomb timer) and its 10 second warning
KIT_TRACKS = ("mainmenu", "chooseteam", "deathcam", "lostround", "wonround", "bombplanted", "bombtenseccount", "roundtenseccount")
# tracks a kit has several takes of (CS2 picks one at random): one event each, Minecraft picks among the sounds
KIT_VARIANTS = ("startround", "startaction")


def music_kits(assets, sounds_json, ffmpeg, ns, log):
    """Every CS2 music kit from the cosmetics extraction (cosmetics.py): KIT_TRACKS as stereo streamed oggs
    (sounds/music/<kit>/<track>.ogg, events music.<kit>.<track>), the kit's inventory image (textures/gui/cosmetics/<kit>.png)
    and loadout/musickits.json ([{kit, name}] in CS2's item order, names from csgo_english)."""
    from concurrent.futures import ThreadPoolExecutor
    root = EXTRACT
    cat = json.loads((root / "cosmetics" / "catalog.json").read_text(encoding="utf-8"))
    loc = {}
    english = next((root / "cache" / "ui").rglob("csgo_english.txt"), None)
    if english:
        import re
        raw = english.read_bytes()
        text = raw.decode("utf-16" if raw[:2] in (bytes([0xFF, 0xFE]), bytes([0xFE, 0xFF])) else "utf-8-sig", errors="replace")
        loc = {m.group(1).lower(): m.group(2) for m in re.finditer(r'"([A-Za-z0-9_]+)"\s+"([^"]*)"', text)}
    kits, jobs = [], []
    for m in sorted(cat["musicKits"], key=lambda m: m["id"]):
        kit = m["kit"]
        name = m["name"]
        if name.startswith("#"):
            # valve_02 has no string in CS2's csgo_english: CS:GO's name for it
            name = loc.get(name[1:].lower(), {"valve_02": "Valve, CS:GO 2"}.get(kit, kit))
        kits.append({"kit": kit, "name": name})
        thumb = root / "cosmetics" / m["thumbnail"]
        if thumb.exists():
            _png(thumb, assets / "textures" / "gui" / "cosmetics" / f"{kit}.png", 256)
        for t in KIT_TRACKS:
            src = root / "cosmetics" / "audio" / "sounds" / "music" / kit / f"{t}.mp3"
            if not src.exists():
                log(f"  ! music kit {kit}: no {t}")
                continue
            snd = f"music/{kit}/{t}"
            ogg = assets / "sounds" / (snd + ".ogg")
            sounds_json[f"music.{kit}.{t}"] = {"sounds": [{"name": f"{ns}:{snd}", "stream": True}]}
            if not ogg.exists():
                ogg.parent.mkdir(parents=True, exist_ok=True)
                jobs.append([str(ffmpeg), "-y", "-loglevel", "error", "-i", str(src), "-ac", "2", "-c:a", "libvorbis", "-q:a", "5", str(ogg)])
        for t in KIT_VARIANTS:
            entries = []
            for src in sorted((root / "cosmetics" / "audio" / "sounds" / "music" / kit).glob(t + "_*.mp3")):
                snd = f"music/{kit}/{src.stem}"
                ogg = assets / "sounds" / (snd + ".ogg")
                entries.append({"name": f"{ns}:{snd}", "stream": True})
                # each take on its own too (music.<kit>.startround_02): the mod locks one take, and its matching
                # startaction, for a whole round
                sounds_json[f"music.{kit}.{src.stem}"] = {"sounds": [{"name": f"{ns}:{snd}", "stream": True}]}
                if not ogg.exists():
                    ogg.parent.mkdir(parents=True, exist_ok=True)
                    jobs.append([str(ffmpeg), "-y", "-loglevel", "error", "-i", str(src), "-ac", "2", "-c:a", "libvorbis", "-q:a", "5", str(ogg)])
            if entries:
                sounds_json[f"music.{kit}.{t}"] = {"sounds": entries}
            else:
                log(f"  ! music kit {kit}: no {t}")
        # the round MVP anthem (CS2 picks one of the kit's roundmvpanthem variants): event music.<kit>.mvp
        entries = []
        for src in sorted((root / "cosmetics" / "audio" / "sounds" / "music" / kit).glob("roundmvpanthem*.mp3")):
            snd = f"music/{kit}/mvp_{src.stem}"
            ogg = assets / "sounds" / (snd + ".ogg")
            entries.append({"name": f"{ns}:{snd}", "stream": True})
            if not ogg.exists():
                ogg.parent.mkdir(parents=True, exist_ok=True)
                jobs.append([str(ffmpeg), "-y", "-loglevel", "error", "-i", str(src), "-ac", "2", "-c:a", "libvorbis", "-q:a", "5", str(ogg)])
        if entries:
            sounds_json[f"music.{kit}.mvp"] = {"sounds": entries}
        else:
            log(f"  ! music kit {kit}: no MVP anthem")
    with ThreadPoolExecutor(8) as ex:
        list(ex.map(lambda cmd: subprocess.run(cmd, check=True), jobs))
    (assets / "loadout").mkdir(parents=True, exist_ok=True)
    (assets / "loadout" / "musickits.json").write_text(json.dumps(kits, indent=1, ensure_ascii=False), encoding="utf-8")
    log(f"ui: {len(kits)} music kits, {len(jobs)} tracks converted")


def convert(cs2, assets, sounds_json, ffmpeg, ns, log):
    gui = assets / "gui"
    gui.mkdir(parents=True, exist_ok=True)
    # buy menu icons
    edir = cs2.export("panorama/images/icons/equipment/", "ui")
    svgs = sorted({p.stem: p for p in (edir / "panorama" / "images" / "icons" / "equipment").glob("*.svg")}.items())
    sizes = rasterise_aspect(svgs, assets / "textures" / "gui" / "buy", log, height=96)
    (gui / "buy_icons.json").write_text(json.dumps({"icons": sizes}, indent=1))
    log(f"ui: {len(sizes)} buy menu icons")
    # team logos and button icons
    idir = cs2.export("panorama/images/icons/", "ui")
    logos = [(n, p) for n in ("t_logo", "ct_logo") for p in [next(idir.rglob(n + ".svg"), None)] if p]
    rasterise_aspect(logos, assets / "textures" / "gui" / "team", log, height=256)
    buttons = [(n, p) for n in ("cancel", "camera", "refresh", "filter", "sort") for p in [next((idir / "panorama" / "images" / "icons" / "ui").glob(n + ".svg"), None)] if p]
    rasterise_aspect(buttons, assets / "textures" / "gui" / "icons", log, height=56)
    # the Counter-Strike 2 wordmark (end credits, under the Minecraft logo)
    wordmark = [(n, p) for n in ("cs2_logo_horizontal",) for p in [next((idir / "panorama" / "images" / "icons" / "ui").glob(n + ".svg"), None)] if p]
    rasterise_aspect(wordmark, assets / "textures" / "gui" / "logo", log, height=96)
    # skill group badges (My CS2 Stats: Competitive per map, Wingman)
    sdir = idir / "panorama" / "images" / "icons" / "skillgroups"
    badges = [(p.stem, p) for p in sorted(sdir.glob("*.svg")) if p.stem.startswith(("skillgroup", "wingman"))]
    rasterise_aspect(badges, assets / "textures" / "gui" / "skillgroups", log, height=48)
    # the round end banner's double arrows (hudwinpanel)
    hdir = cs2.export("panorama/images/hud/double_arrows.vsvg_c", "ui")
    arrows = [(n, p) for n in ("double_arrows",) for p in [next(hdir.rglob(n + ".svg"), None)] if p]
    rasterise_aspect(arrows, assets / "textures" / "gui" / "hud", log, height=64)
    # team select background
    bdir = cs2.export("panorama/images/map_icons/screenshots/1080p/default_psd.vtex_c", "ui")
    bg = next(bdir.rglob("default_psd*.png"), None)
    if bg:
        _png(bg, assets / "textures" / "gui" / "team" / "background.png", 1920)
    # loadout tiles
    wdir = cs2.export("panorama/images/econ/weapons/base_weapons/", "ui")
    n = 0
    for png in sorted((wdir / "panorama" / "images" / "econ" / "weapons" / "base_weapons").glob("*.png")):
        name = png.stem.replace("_png", "")
        _png(png, assets / "textures" / "gui" / "weapons" / f"{name}.png", 256)
        n += 1
    log(f"ui: {n} loadout tiles")
    cosmetic_thumbnails(assets, log)
    # default music kit: stereo, streamed
    mdir = cs2.export(f"sounds/music/{DEFAULT_KIT}/", "sounds")
    tracks = 0
    for src in sorted(list(mdir.rglob("*.mp3")) + list(mdir.rglob("*.wav"))):
        if f"/music/{DEFAULT_KIT}/" not in src.as_posix():
            continue
        name = f"music/{DEFAULT_KIT}/{src.stem}"
        ogg = assets / "sounds" / (name + ".ogg")
        ogg.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(ffmpeg), "-y", "-loglevel", "error", "-i", str(src), "-ac", "2", "-c:a", "libvorbis", "-q:a", "6", str(ogg)], check=True)
        sounds_json[f"music.{DEFAULT_KIT}.{src.stem}"] = {"sounds": [{"name": f"{ns}:{name}", "stream": True}]}
        tracks += 1
    log(f"ui: music kit {DEFAULT_KIT}: {tracks} tracks")
    music_kits(assets, sounds_json, ffmpeg, ns, log)


def maps(cs2, assets, log):
    """Map pictures for My CS2 Stats (Ranks, Matches): CS2's 1080p map screenshots -> textures/gui/maps/<map>.png
    (480 px wide), and the round map icons -> textures/gui/maps/icon_<map>.png (64 px high)."""
    out = assets / "textures" / "gui" / "maps"
    out.mkdir(parents=True, exist_ok=True)
    sdir = cs2.export("panorama/images/map_icons/screenshots/1080p/", "ui")
    n = 0
    for png in sorted((sdir / "panorama" / "images" / "map_icons" / "screenshots" / "1080p").glob("*.png")):
        name = png.stem.replace("_png", "").replace("_psd", "")
        if not name.startswith(("de_", "cs_", "ar_")) or re.search(r"_\d+$", name):
            continue  # CS2's extra numbered shots of a map: the main one is enough
        _png(png, out / f"{name}.png", 480)
        n += 1
    idir = cs2.export("panorama/images/map_icons/map_icon_", "ui")
    icons = [(p.stem.replace("map_icon_", "icon_"), p) for p in sorted((idir / "panorama" / "images" / "map_icons").glob("map_icon_*.svg"))]
    sizes = rasterise_aspect(icons, out, log, height=64)
    log(f"ui: {n} map screenshots, {len(sizes)} map icons")

