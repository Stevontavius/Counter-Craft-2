# /// script
# dependencies = ["numpy", "pillow", "openexr"]
# ///
"""Build the whole Counter-Craft 2 asset pack in one go (the installer runs this; usable by hand too).

Runs, in order: CS2's weapon data, the cosmetics catalog and exports (cosmetics.py), the base pack (convert.py), then
each convert.py step the choices call for. Extraction goes to a throwaway folder (--extract, CC2_EXTRACT) that is
deleted at the end unless --keep-extract: only what each step needs is extracted, and only the finished pack stays.

  --audio cs2 | both | csgo   weapon sounds: CS2's; CS2's + CS:GO's (chosen in game); CS:GO's only (smaller pack)
  --skip skins gloves musickits   optional content to leave out

The pack's manifest (cc2-manifest.json, MANIFEST_FORMAT): the release, each step's id and conversion version, the
games' Steam builds, the options, and every file with its size, SHA-256 and the step that wrote it (found by comparing
the pack before and after each step). It's what updates work from:

  --update     compare the pack's manifest with this release (each step's conversion version in STEPS, the options):
               nothing changed, nothing is built; anything changed, the pack is built again in full, next to the old
               one (.cc2-next), and swapped in only once it validates, so the old pack keeps working until then and an
               interrupted update leaves it as it was (and is simply run again).
               ponytail: always in full, because the steps share extracted files that a build deletes (the skins step
               reads the base step's model exports and the cosmetics catalog); make each step fetch what it needs and
               an update could rerun only the steps that changed (the manifest already records which step made each file).
  --manifest   write the manifest for a pack built before manifests existed.

A build in progress is marked by .cc2-build.json in the pack; the manifest is written (atomically) only when the build has
finished and the pack validates.

Progress lines for the installer: "@@STEP i/n name", "@@TIME i/n seconds" (when a step ends), "@@OK", "@@FAIL message".

usage: uv run converter/build_pack.py --cs2 "D:/Steam/steamapps/common/Counter-Strike Global Offensive" --out <folder>
       [--csgo <CS:GO folder>|auto] [--audio both] [--skip musickits] [--update | --manifest] [--extract <folder>]
       [--keep-extract] [--dry-run]
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
VRF = ROOT / "tools" / "vrf" / "Source2Viewer-CLI.exe"
PACK = "CounterCraft-CS2-Assets"
RELEASE = "BETA 1.1.1"
OPTIONAL = ("skins", "gloves", "musickits")
MANIFEST = "cc2-manifest.json"
MANIFEST_FORMAT = 1
MARK = ".cc2-build.json"
# step id: (name the installer shows, conversion version: bump it when the step's output changes, wipes the pack)
STEPS = {
    "weapondata": ("CS2 weapon data", 1, False),
    "cosmetics": ("Cosmetics catalog", 1, False),
    "base": ("Weapons, sounds, HUD", 1, True),
    "weapontable": ("Weapon table", 1, False),  # the mod's weapons.json, made here so none of Valve's data ships with the mod
    "agents": ("Agents", 1, False),
    "ragdoll": ("Ragdolls", 1, False),
    "eyes": ("Agent eyes", 1, False),
    "deathcam": ("Death cam", 1, False),
    "maps": ("Map art", 1, False),
    "legacy": ("Legacy weapon models", 1, False),
    "skins": ("Weapon finishes", 2, False),  # 2: the material dumps are made by the step (a fresh build had none)
    "gloves": ("Glove finishes", 2, False),
    "musickits": ("Music kits", 1, False),
    "vmmaps": ("Shading maps", 1, False),
    "csgo": ("CS:GO weapon sounds", 1, False),
}


def say(kind, msg=""):
    print(f"@@{kind} {msg}".rstrip(), flush=True)


def plan(args):
    """[(step id, command)] in build order; commands are argument lists (python scripts get the interpreter prepended)."""
    skip = set(args.skip or ())
    cs2 = ["--cs2", str(args.cs2)]
    out = ["--out", str(args.out)]
    conv = [str(HERE / "convert.py"), *cs2, *out, "--cache", str(args.extract / "cache")]
    exports = ["thumbnails", "agents", "weapons", "voice"] + (["gloves"] if "gloves" not in skip else []) \
        + (["paints"] if "skins" not in skip else []) + (["music"] if "musickits" not in skip else [])
    steps = [
        ("weapondata", ["@vrf", "-i", str(args.cs2 / "game" / "csgo" / "pak01_dir.vpk"), "-o", str(args.extract), "-d",
                        "-f", "scripts/weapons.vdata_c"]),
        ("cosmetics", [str(HERE / "cosmetics.py"), *cs2, "--step", *exports]),
        ("base", conv),
        ("weapontable", [str(HERE / "weapons.py"), "--out", str(args.out / PACK / "assets" / "countercraft" / "weapons.json")]),
    ]
    for step in ("agents", "ragdoll", "eyes", "deathcam", "maps"):
        steps.append((step, conv + ["--step", step]))
    if "skins" not in skip:
        steps.append(("legacy", conv + ["--step", "legacy"]))
        steps.append(("skins", conv + ["--step", "skins"]))  # with Printstream's pearlescence
    if "gloves" not in skip:
        steps.append(("gloves", conv + ["--step", "gloves"]))
    if "musickits" not in skip:
        steps.append(("musickits", conv + ["--step", "musickits"]))
    steps.append(("vmmaps", conv + ["--step", "vmmaps"]))
    if args.audio != "cs2":
        steps.append(("csgo", conv + ["--step", "csgo", "--csgo", str(args.csgo or "auto")]
                      + (["--csgo-only"] if args.audio == "csgo" else [])))
    return steps


def validate(pack, args):
    """What the mod needs from the pack; returns problems (empty: fine)."""
    a = pack / "assets" / "countercraft"
    problems = []
    for rel in ("pack.mcmeta",):
        if not (pack / rel).exists():
            problems.append(f"missing {rel}")
    for rel in ("sounds.json", "weapons.json", "agents/agents.json", "agents/index.json"):
        if not (a / rel).exists():
            problems.append(f"missing assets/countercraft/{rel}")
    try:
        json.loads((a / "sounds.json").read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        problems.append(f"sounds.json unreadable: {e}")
    if not any((a / "viewmodels").glob("*.cvm")):
        problems.append("no viewmodels")
    if "skins" not in (args.skip or ()) and not (a / "skins" / "index.json").exists():
        problems.append("missing weapon finishes (skins/index.json)")
    if args.audio != "cs2" and not (a / "csgo" / "weapon_sounds.json").exists():
        problems.append("missing CS:GO weapon sounds")
    return problems


# ---- the manifest

def snapshot(pack):
    """{relative path: (size, mtime_ns)} of every file in the pack (but the build's own files)."""
    out = {}
    stack = [pack]
    while stack:
        d = stack.pop()
        try:
            entries = list(os.scandir(d))
        except FileNotFoundError:
            continue
        for e in entries:
            if e.name.startswith(".cc2") or e.name == MANIFEST:
                continue
            if e.is_dir(follow_symlinks=False):
                stack.append(e.path)
            else:
                st = e.stat()
                out[Path(e.path).relative_to(pack).as_posix()] = (st.st_size, st.st_mtime_ns)
    return out


def steam_build(game_dir, app):
    """The Steam build id of an installed game (its appmanifest), or None."""
    try:
        acf = Path(game_dir).parent.parent / f"appmanifest_{app}.acf"
        m = re.search(r'"buildid"\s+"(\d+)"', acf.read_text(encoding="utf-8", errors="replace"))
        return m.group(1) if m else None
    except (OSError, TypeError):
        return None


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def write_manifest(pack, args, owners, old=None):
    """Hash the pack (files unchanged since the old manifest keep their hash) and write cc2-manifest.json atomically."""
    snap = snapshot(pack)
    old_files = (old or {}).get("files", {})
    todo = [p for p, (size, mtime) in snap.items()
            if not (p in old_files and old_files[p].get("size") == size and old_files[p].get("mtime") == mtime)]
    with ThreadPoolExecutor(8) as ex:
        hashes = dict(zip(todo, ex.map(lambda p: sha256(pack / p), todo)))
    files = {}
    for p, (size, mtime) in sorted(snap.items()):
        files[p] = {"size": size, "mtime": mtime, "sha256": hashes.get(p) or old_files[p]["sha256"],
                    "step": owners.get(p, old_files.get(p, {}).get("step"))}
    csgo_dir = None if args.audio == "cs2" else args.csgo_dir
    manifest = {
        "format": MANIFEST_FORMAT,
        "release": RELEASE,
        "built": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": {"cs2": {"app": 730, "build": steam_build(args.cs2, 730)},
                    "csgo": {"app": 4465480, "build": steam_build(csgo_dir, 4465480)} if csgo_dir else None},
        "options": {"audio": args.audio, "skip": sorted(args.skip or [])},
        "steps": {s: {"name": STEPS[s][0], "version": STEPS[s][1]} for s, _ in plan(args)},
        "files": files,
    }
    tmp = pack / (MANIFEST + ".tmp")
    tmp.write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
    os.replace(tmp, pack / MANIFEST)
    return manifest


def to_rerun(old, args):
    """What an update has to build (all the steps, or none) and why."""
    steps = [s for s, _ in plan(args)]
    if not old or old.get("format") != MANIFEST_FORMAT:
        return steps, "the pack has no manifest"
    if old.get("options") != {"audio": args.audio, "skip": sorted(args.skip or [])}:
        return steps, "the options changed"
    changed = [s for s in steps if old.get("steps", {}).get(s, {}).get("version") != STEPS[s][1]]
    if changed:
        return steps, "this release changed " + ", ".join(STEPS[s][0] for s in changed)
    return [], None


def swap(out, staged):
    """Put the pack built in staged/ in place of out/'s: the old one goes aside first and is deleted last."""
    pack, old = out / PACK, out / (".cc2-old-" + PACK)
    if pack.exists():
        os.replace(pack, old)
    os.replace(staged / PACK, pack)
    shutil.rmtree(old, ignore_errors=True)
    shutil.rmtree(staged, ignore_errors=True)


def recover(out):
    """An update stopped between the two moves of swap(): the old pack is put back."""
    pack, old = out / PACK, out / (".cc2-old-" + PACK)
    if old.exists() and not pack.exists():
        os.replace(old, pack)
        print("restored the pack an interrupted update had set aside", flush=True)


# ---- the build

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cs2", required=True, type=Path)
    ap.add_argument("--csgo", type=str, default=None, help="CS:GO folder, or auto (found next to CS2)")
    ap.add_argument("--audio", choices=["cs2", "both", "csgo"], default="cs2")
    ap.add_argument("--skip", nargs="*", choices=OPTIONAL, default=[])
    ap.add_argument("--out", type=Path, default=ROOT / "cs2-pack")
    ap.add_argument("--extract", type=Path, default=None, help="throwaway extraction folder (default: <out>/.cc2-extract)")
    ap.add_argument("--keep-extract", action="store_true", help="keep the extracted files (faster rebuilds, many GB)")
    ap.add_argument("--update", action="store_true", help="rerun only the steps that changed since the pack's manifest")
    ap.add_argument("--manifest", action="store_true", help="only write the manifest of an existing pack")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if not (args.cs2 / "game" / "csgo" / "pak01_dir.vpk").exists():
        say("FAIL", f"not a CS2 install: {args.cs2}")
        return 1
    args.extract = args.extract or args.out / ".cc2-extract"
    args.csgo_dir = None
    if args.audio != "cs2":
        if args.csgo and args.csgo != "auto":
            args.csgo_dir = Path(args.csgo)
        else:
            import csgo as csgo_mod
            args.csgo_dir = csgo_mod.find_install(args.cs2)
    recover(args.out)
    pack = args.out / PACK
    if args.manifest:
        problems = validate(pack, args)
        if problems:
            say("FAIL", "pack incomplete: " + "; ".join(problems))
            return 1
        m = write_manifest(pack, args, {})
        say("OK", f"manifest written: {len(m['files'])} files")
        return 0
    old = None
    if args.update:
        try:
            old = json.loads((pack / MANIFEST).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            old = None
    ids, why = to_rerun(old, args) if args.update else ([s for s, _ in plan(args)], None)
    if args.update:
        print("update: " + (f"full build ({why})" if why else "nothing changed"), flush=True)
        if ids and (pack / MANIFEST).exists():  # build next to the old pack and swap it in at the end
            final_out, args.out = args.out, args.out / ".cc2-next"
            shutil.rmtree(args.out, ignore_errors=True)
            pack = args.out / PACK
    steps = [(s, cmd) for s, cmd in plan(args) if s in ids]
    if args.dry_run:
        for i, (s, cmd) in enumerate(steps, 1):
            print(f"{i}/{len(steps)} {STEPS[s][0]}: {' '.join(cmd)}")
        return 0
    if not steps:
        say("OK", f"the pack is up to date: {pack}")
        return 0
    args.extract.mkdir(parents=True, exist_ok=True)
    pack.mkdir(parents=True, exist_ok=True)
    (pack / MARK).write_text(json.dumps({"release": RELEASE, "update": bool(args.update), "started": time.time()}), encoding="utf-8")
    env = dict(os.environ, CC2_EXTRACT=str(args.extract), PYTHONUNBUFFERED="1")
    start = time.time()
    owners = {}
    try:
        for i, (s, cmd) in enumerate(steps, 1):
            say("STEP", f"{i}/{len(steps)} {STEPS[s][0]}")
            t0 = time.time()
            before = snapshot(pack)
            full_cmd = [str(VRF), *cmd[1:]] if cmd[0] == "@vrf" else [sys.executable, *cmd]
            p = subprocess.Popen(full_cmd, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                 encoding="utf-8", errors="replace")
            for line in p.stdout:
                print(line.rstrip(), flush=True)
            if p.wait() != 0:
                say("FAIL", f"{STEPS[s][0]} failed (exit {p.returncode})")
                return 1
            if cmd[0] == "@vrf":  # VRF writes scripts/weapons.vdata: the converter reads it from the extract root
                shutil.copy2(args.extract / "scripts" / "weapons.vdata", args.extract / "weapons.vdata")
            after = snapshot(pack)
            if STEPS[s][2]:
                owners = {}  # it wiped the pack: everything in it now is this step's
            made = {f for f, st in after.items() if before.get(f) != st}
            for f in made:
                owners[f] = s
            for f in set(before) - set(after):
                owners.pop(f, None)
            say("TIME", f"{i}/{len(steps)} {time.time() - t0:.0f}")
        problems = validate(pack, args)
        if problems:
            say("FAIL", "pack incomplete: " + "; ".join(problems))
            return 1
        print("writing the pack's manifest...", flush=True)
        m = write_manifest(pack, args, owners)
        (pack / MARK).unlink(missing_ok=True)
        if args.out.name == ".cc2-next":
            swap(final_out, args.out)
            pack = final_out / PACK
        say("OK", f"pack built in {(time.time() - start) / 60:.0f} min ({len(m['files'])} files): {pack}")
        return 0
    finally:
        if not args.keep_extract:
            shutil.rmtree(args.extract, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
