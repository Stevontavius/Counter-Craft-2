"""CS:GO weapon sounds (convert.py --step csgo, or --csgo with a full build): OPTIONAL. Reads the user's own CS:GO install
(Steam app 4465480, "Counter-Strike: Global Offensive"; or the csgo_legacy branch of 730 in its own folder) and adds, for
every weapon sound in the pack (CS2's weapon.* events and the grenades' sound roles), CS:GO's version of the same event
as csgo.<id> in sounds.json, and the flag file csgo/weapon_sounds.json ({pack id: CS:GO event}) that tells the mod they
are there (CC2 Options > Music & Sounds > Weapon Sound Style). Nothing of CS:GO is ever redistributed: like the CS2
pack, it is built from your own install.

Which CS:GO event: gunshots from CS:GO's own weapon definitions (items_game.txt prefabs: sound_single_shot, and
sound_special1 for a silencer on), since the names moved (CS:GO's M4A4 fires Weapon_M4A1.Single, which CS2 uses for the
unsilenced M4A1-S); everything else by its CS2 event name (Source event names are case-insensitive), CS2's quiet "_q"
variants by their CS:GO original. Events CS:GO doesn't have (CS2-only impacts, knife swishes, ammo buys) stay CS2.
"""
import json
import re
import shutil
import subprocess
from pathlib import Path

import kv1

APP = 4465480
# events CS2 renamed (beyond the gunshots, which come from items_game): pack id -> CS:GO event
ALIASES = {"molotov.start": "Inferno.Start"}  # the fire catching
# leading Source sound characters (spatialisation / streaming flags), not part of the path
SOUND_CHARS = "`)^*#@<>?!&$~(%"


def find_install(cs2: Path):
    """CS:GO next to the CS2 install: the Steam library's appmanifest_4465480.acf, else the usual folder names."""
    steamapps = cs2.parent.parent
    acf = steamapps / f"appmanifest_{APP}.acf"
    if acf.exists():
        m = re.search(r'"installdir"\s+"([^"]+)"', acf.read_text(encoding="utf-8", errors="replace"))
        if m and (steamapps / "common" / m.group(1) / "csgo" / "pak01_dir.vpk").exists():
            return steamapps / "common" / m.group(1)
    for name in ("csgo legacy", "Counter-Strike Global Offensive Legacy", "csgo"):
        if (steamapps / "common" / name / "csgo" / "pak01_dir.vpk").exists():
            return steamapps / "common" / name
    return None


def _build(install: Path):
    acf = install.parent.parent / f"appmanifest_{APP}.acf"
    m = re.search(r'"buildid"\s+"(\d+)"', acf.read_text(encoding="utf-8", errors="replace")) if acf.exists() else None
    return m.group(1) if m else ""


def _num(v, default):
    """A volume / pitch value: a number, a "min, max" range (its middle), or a named constant."""
    v = (v[0] if isinstance(v, list) else v or "").strip()
    named = {"VOL_NORM": 1.0, "PITCH_NORM": 100.0, "PITCH_LOW": 95.0, "PITCH_HIGH": 120.0}
    if v in named:
        return named[v]
    parts = [p for p in re.split(r"[,\s]+", v) if p]
    try:
        vals = [float(p) for p in parts]
        return sum(vals) / len(vals) if vals else default
    except ValueError:
        return default


def _waves(entry):
    out = []
    for key in ("wave", "rndwave"):
        for v in kv1.each(entry.get(key)):
            if isinstance(v, dict):
                out += [w for w in kv1.each(v.get("wave")) if isinstance(w, str)]
            elif isinstance(v, str):
                out.append(v)
    return ["sound/" + w.lstrip(SOUND_CHARS).replace("\\", "/") for w in out]


def convert(install: Path, vrf: Path, cache: Path, assets: Path, sounds_json: dict, weapons_json: Path, ffmpeg: Path, ns: str, log,
            exclusive=False):
    """exclusive (the installer's Weapon audio "CS:GO Legacy"): CS:GO's version replaces CS2's in the pack (the CS2 files
    it no longer uses are deleted, a smaller pack) and the flag says so: the mod's option then shows CS:GO, greyed out."""
    vpk = install / "csgo" / "pak01_dir.vpk"
    if not vpk.exists():
        raise SystemExit(f"not a CS:GO install (no {vpk})")
    out = cache / "csgo"

    def extract(paths):
        # VRF reads CS:GO's VPK too; a comma-separated path filter, in batches (the command line has a limit)
        batch = []
        for p in list(paths) + [None]:
            if p is not None:
                batch.append(p)
            if batch and (p is None or sum(len(b) + 1 for b in batch) > 6000):
                subprocess.run([str(vrf), "-i", str(vpk), "-o", str(out), "-f", ",".join(batch)], capture_output=True, check=False)
                batch = []

    # CS:GO's sound events (every game_sounds script; the agents' voice files too, harmlessly) and weapon definitions
    extract(["scripts/game_sounds"])
    events = {}
    for f in sorted((out / "scripts").glob("game_sounds*.txt")):
        # a few entries write their paths with backslashes, which KeyValues would read as escapes
        for k, v in kv1.loads(f.read_text(encoding="utf-8", errors="replace").replace("\\", "/")).items():
            v = kv1.merged(v) if isinstance(v, list) else v
            if isinstance(v, dict):
                events[k.lower()] = v
    items = install / "csgo" / "scripts" / "items" / "items_game.txt"
    if not items.exists():
        extract(["scripts/items/items_game.txt"])
        items = out / "scripts" / "items" / "items_game.txt"
    prefabs = kv1.merged(kv1.loads(items.read_text(encoding="utf-8", errors="replace"))["items_game"]).get("prefabs", {})
    prefabs = kv1.merged(prefabs) if isinstance(prefabs, list) else prefabs

    # the pack's weapon sounds and which CS:GO event plays each
    wj = json.loads(weapons_json.read_text(encoding="utf-8"))
    pick = {}
    for g in wj.get("weapons", []):
        s = g.get("sounds", {})
        pf = prefabs.get(f"weapon_{g['id']}_prefab", {})
        pf = kv1.merged(pf) if isinstance(pf, list) else pf
        vis = pf.get("visuals", {})  # the sound keys sit in the prefab's visuals block
        vis = kv1.merged(vis) if isinstance(vis, list) else vis
        shot, special = vis.get("sound_single_shot"), vis.get("sound_special1")
        if s.get("fireUnsilenced"):  # the USP-S / M4A1-S: special1 with the silencer on, single_shot off
            if special and s.get("fire"):
                pick[s["fire"]] = special
            if shot:
                pick[s["fireUnsilenced"]] = shot
        elif shot and s.get("fire"):
            pick[s["fire"]] = shot
    wanted = {k for k in sounds_json if k.startswith("weapon.")}
    for g in wj.get("grenades", []):
        wanted |= {v for v in g.get("sounds", {}).values() if v in sounds_json}
    mapping = {}
    for sid in sorted(wanted):
        if sid in pick or sid in ALIASES:
            mapping[sid] = pick.get(sid) or ALIASES[sid]
            continue
        name = "weapon_" + sid[len("weapon."):] if sid.startswith("weapon.") else sid
        for cand in (name, re.sub(r"_q$", "", name)):
            if cand in events:
                mapping[sid] = cand
                break
    mapping = {sid: ev for sid, ev in mapping.items() if ev.lower() in events and _waves(events[ev.lower()])}

    # the audio: extracted from the VPK, converted like CS2's (mono Ogg: Minecraft only positions mono sounds)
    for k in [k for k in sounds_json if k.startswith("csgo.")]:
        del sounds_json[k]
    shutil.rmtree(assets / "sounds" / "csgo", ignore_errors=True)
    files = sorted({w for ev in mapping.values() for w in _waves(events[ev.lower()])})
    extract(files)
    done, missing, n = {}, 0, 0
    for sid, ev in sorted(mapping.items()):
        e = events[ev.lower()]
        vol = min(max(_num(e.get("volume"), 1.0), 0.05), 1.0)
        pitch = min(max(_num(e.get("pitch"), 100.0) / 100.0, 0.5), 2.0)
        entries = []
        for w in _waves(e):
            name = done.get(w)
            if name is None:
                src = out / w
                if not src.exists():
                    missing += 1
                    continue
                name = "csgo/" + str(Path(w).relative_to("sound").with_suffix("")).replace("\\", "/").lower()
                ogg = assets / "sounds" / (name + ".ogg")
                ogg.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run([str(ffmpeg), "-y", "-loglevel", "error", "-i", str(src), "-ac", "1", "-c:a", "libvorbis", "-q:a", "6", str(ogg)], check=True)
                done[w] = name
                n += 1
            entry = {"name": f"{ns}:{name}", "volume": round(vol, 3)}
            if abs(pitch - 1.0) > 1e-3:
                entry["pitch"] = round(pitch, 3)
            entries.append(entry)
        if entries:
            sounds_json["csgo." + sid] = {"sounds": entries}
        else:
            del mapping[sid]
    if exclusive:
        def names(entry):
            return {e["name"].split(":", 1)[1] for e in entry.get("sounds", []) if isinstance(e, dict) and "name" in e}
        replaced = set()
        for sid in mapping:
            replaced |= names(sounds_json.get(sid, {}))
            sounds_json[sid] = sounds_json.pop("csgo." + sid)
        still = set()
        for entry in sounds_json.values():
            still |= names(entry)
        gone = [x for x in replaced - still if not x.startswith("csgo/")]
        for x in gone:
            (assets / "sounds" / (x + ".ogg")).unlink(missing_ok=True)
        log(f"csgo: CS:GO only, {len(gone)} CS2 files no longer used, deleted")
    flag = assets / "csgo" / "weapon_sounds.json"
    flag.parent.mkdir(parents=True, exist_ok=True)
    flag.write_text(json.dumps({"source": "Counter-Strike: Global Offensive", "app": APP, "build": _build(install), "exclusive": exclusive,
                                "sounds": dict(sorted(mapping.items()))}, indent=2), encoding="utf-8")
    kept = len(wanted) - len(mapping)
    log(f"csgo: {len(mapping)} weapon sounds from CS:GO ({n} files), {kept} stay CS2" + (f", {missing} files missing" if missing else ""))
    return mapping
