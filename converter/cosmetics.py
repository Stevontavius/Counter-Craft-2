# /// script
# dependencies = []
# ///
"""Extract CS2 cosmetics from the user's own CS2 install into cs2-extract/cosmetics/ (local only, never shipped; not
used by the mod yet).

Catalog (catalog.json), from scripts/items/items_game.txt + resource/csgo_english.txt:
  - agents: every agent (default + tradable): model, team, rarity, voice prefix, thumbnail
  - weapon finishes: every valid (weapon, paint kit) pair, from CS2's own generated icons
    (panorama/images/econ/default_generated/<item>_<paintkit>_<wear>), with rarity (the collection's loot list), the
    paint kit's style / wear range / legacy-model flag, its composite material, and the Factory New thumbnail
  - knife finishes and glove finishes: the same for the 20 knives and 8 glove types (★ rarity)
  - music kits: name, image, sound folder and every track
Factory New only: one thumbnail per finish (CS2's "_light" render); finishes whose wear range doesn't reach Factory
New (wear_remap_min >= 0.07) are kept and flagged ("factoryNew": false, the thumbnail is then CS2's best wear).
StatTrak, cases, stickers, graffiti, keychains: skipped.

Files (export step, slow): agent and glove models (glTF with textures; agent models include their first-person arms),
weapon and knife models (glTF, all mesh groups: body_hd and body_legacy = the CS:GO-era model older finishes use),
paint kit composite materials and textures (weapons/paints, items/assets/paintkits, materials/.../customization),
agent voice lines, music kits, thumbnails.

usage: python converter/cosmetics.py [--cs2 DIR] [--catalog-only]
"""
from paths import EXTRACT
import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
import kv1  # noqa: E402

VRF = ROOT / "tools" / "vrf" / "Source2Viewer-CLI.exe"
OUT = EXTRACT / "cosmetics"
LIST = EXTRACT / "cache" / "pak01_list.txt"
GEN = "panorama/images/econ/default_generated/"
WEARS = ("light", "medium", "heavy")
FN_MAX = 0.07
RARITY_ORDER = ["default", "common", "uncommon", "rare", "mythical", "legendary", "ancient", "immortal", "unusual"]


def log(msg):
    print(msg, flush=True)


def vrf(vpk, *args):
    r = subprocess.run([str(VRF), "-i", str(vpk), *args], capture_output=True, text=True, encoding="utf-8-sig", errors="replace")
    return r.stdout + r.stderr


def listing(vpk):
    if not LIST.exists() or LIST.stat().st_size == 0:
        LIST.parent.mkdir(parents=True, exist_ok=True)
        LIST.write_text(vrf(vpk, "-l"), encoding="utf-8")
    return [line.split(" CRC:")[0] for line in LIST.read_text(encoding="utf-8").splitlines() if " CRC:" in line]


def export(vpk, out, *filters, extra=()):
    """Decompile files under these VPK path prefixes into out/ (VRF keeps the folder structure)."""
    out.mkdir(parents=True, exist_ok=True)
    log(f"  exporting {', '.join(filters)}")
    text = vrf(vpk, "-o", str(out), "-d", "-f", ",".join(filters) + ",__never__/", *extra)
    errors = [ln for ln in text.splitlines() if "rror" in ln or "xception" in ln]
    if errors:
        log(f"    {len(errors)} errors, e.g. {errors[0][:200]}")


def localizer(raw):
    lang = kv1.loads(raw.read_text(encoding="utf-8-sig", errors="replace"))
    tokens = {}
    for k, v in kv1.merged(kv1.merged(lang.get("lang", {})).get("Tokens", {})).items():
        if isinstance(v, str):
            tokens[k.lower()] = v
    return lambda key: tokens.get(key.lstrip("#").lower(), key) if key else ""


def catalog(vpk, files):
    raw = OUT / "raw"
    if not (raw / "scripts" / "items" / "items_game.txt").exists():
        export(vpk, raw, "scripts/items/items_game.txt", "resource/csgo_english.txt")
    loc = localizer(raw / "resource" / "csgo_english.txt")
    g = kv1.merged(kv1.loads((raw / "scripts" / "items" / "items_game.txt").read_text(encoding="utf-8-sig", errors="replace"))["items_game"])
    items = {k: v for k, v in kv1.merged(g["items"]).items() if isinstance(v, dict)}
    prefabs = kv1.merged(g["prefabs"])
    paint_kits = {v["name"]: dict(v, id=int(k)) for k, v in kv1.merged(g["paint_kits"]).items() if isinstance(v, dict) and "name" in v}
    pk_rarity = kv1.merged(g["paint_kits_rarity"])
    rarities = {k: {"value": int(v.get("value", 0)), "name": loc(v.get("loc_key_weapon", "")), "character": loc(v.get("loc_key_character", "")),
                    "color": v.get("color")} for k, v in kv1.merged(g["rarities"]).items() if isinstance(v, dict)}
    colors = {k: v.get("hex_color") for k, v in kv1.merged(g["colors"]).items() if isinstance(v, dict)}

    def prefab_chain(item):
        seen = []
        p = item.get("prefab", "")
        while p:
            for name in p.split():
                seen.append(name)
            p = " ".join(prefabs.get(n, {}).get("prefab", "") for n in p.split() if isinstance(prefabs.get(n), dict)).strip()
            if len(seen) > 20:
                break
        return seen

    def item_name(item):
        n = item.get("item_name") or next((prefabs[p].get("item_name") for p in prefab_chain(item)
                                         if isinstance(prefabs.get(p), dict) and prefabs[p].get("item_name")), "")
        return loc(n)

    # weapons / knives / gloves by item name
    weapons, knives, gloves = {}, {}, {}
    for k, v in items.items():
        name = v.get("name", "")
        chain = prefab_chain(v)
        if v.get("prefab") == "melee_unusual":
            knives[name] = {"defindex": int(k), "name": item_name(v), "model": v.get("model_player", "")}
        elif v.get("prefab") == "hands_paintable":
            gloves[name] = {"defindex": int(k), "name": item_name(v), "model": v.get("model_player", "")}
        elif name.startswith("weapon_") and any(c.startswith("weapon_") or c in ("primary", "secondary") for c in chain) and "knife" not in name:
            weapons[name] = {"defindex": int(k), "name": item_name(v)}

    # skin rarity: the collection loot list each (paint kit, weapon) pair is in (list name ends with the rarity)
    lists = kv1.merged(g["client_loot_lists"])
    sets = {k: v for k, v in kv1.merged(g["item_sets"]).items() if isinstance(v, dict)}
    pair_rarity, pair_set = {}, {}
    for lname, entries in lists.items():
        if not isinstance(entries, dict):
            continue
        rar = next((r for r in RARITY_ORDER if lname.endswith("_" + r)), None)
        for e in entries:
            m = re.fullmatch(r"\[([^\]]+)\](.+)", e)
            if m and rar:
                pair_rarity[(m.group(1), m.group(2))] = rar
    for sname, s in sets.items():
        for e in (s.get("items") or {}):
            m = re.fullmatch(r"\[([^\]]+)\](.+)", e)
            if m:
                pair_set[(m.group(1), m.group(2))] = {"id": sname, "name": loc(s.get("name", ""))}

    # composite materials and paint kit materials, by paint kit name
    compmat = {Path(f).name.removesuffix(".vcompmat_c"): f.removesuffix("_c") for f in files if f.startswith("weapons/paints/") and f.endswith(".vcompmat_c")}

    # valid (item, paint kit) pairs from CS2's generated icons
    owners = sorted(list(weapons) + list(knives) + list(gloves), key=len, reverse=True)
    icons = {}
    for f in files:
        if not f.startswith(GEN):
            continue
        stem = f[len(GEN):].removesuffix("_png.vtex_c")
        wear = stem.rsplit("_", 1)[-1]
        if wear not in WEARS:
            continue
        body = stem[: -len(wear) - 1]
        owner = next((o for o in owners if body.startswith(o + "_")), None)
        if owner is None:
            continue
        kit = body[len(owner) + 1:]
        if kit in paint_kits:
            icons.setdefault((owner, kit), {})[wear] = f

    def finish(owner, kit, wears):
        pk = paint_kits[kit]
        lo = float(pk.get("wear_remap_min", 0.06))
        hi = float(pk.get("wear_remap_max", 0.8))
        fn = lo < FN_MAX
        best = "light" if "light" in wears else next(w for w in WEARS if w in wears)
        rar = pair_rarity.get((kit, owner))
        star = owner in knives or owner in gloves
        return {
            "item": owner, "paintKit": kit, "paintKitId": pk["id"],
            "name": loc(pk.get("description_tag", "")),
            "rarity": "ancient" if star else (rar or pk_rarity.get(kit)),
            "rarityFrom": "star item" if star else ("collection" if rar else "paint kit"),
            "collection": pair_set.get((kit, owner)),
            "style": int(pk.get("style", 0)) if str(pk.get("style", "0")).isdigit() else pk.get("style"),
            "wear": [lo, hi], "factoryNew": fn, "factoryNewWear": [lo, min(hi, FN_MAX)] if fn else None,
            "legacyModel": pk.get("use_legacy_model") == "1",
            "compositeMaterial": compmat.get(kit),
            "thumbnail": "thumbnails/" + Path(wears[best]).name.replace("_png.vtex_c", ".png"),
            "thumbnailWear": best,
            "_icon": wears[best],
        }

    finishes = {"weapons": [], "knives": [], "gloves": []}
    for (owner, kit), wears in sorted(icons.items()):
        group = "knives" if owner in knives else "gloves" if owner in gloves else "weapons"
        finishes[group].append(finish(owner, kit, wears))

    # agents: voice lines live in sounds/vo/agents/<folder>; agents without vo_prefix use their faction's default voice
    faction_voice = {"ctm_fbi": "fbihrt", "ctm_sas": "sas", "ctm_st6": "seal", "ctm_swat": "swat", "ctm_gsg9": "gsg9",
                     "tm_balkan": "balkan", "tm_leet": "leet", "tm_phoenix": "phoenix", "tm_professional": "professional"}

    def voice_folder(v):
        pre = v.get("vo_prefix")
        if pre:
            return "vo/agents/" + re.sub(r"^c?tm_", "", pre) + "/"
        faction = (v.get("model_player") or "").split("/")[2] if (v.get("model_player") or "").count("/") >= 2 else ""
        return "vo/agents/" + faction_voice[faction] + "/" if faction in faction_voice else None

    # agents
    agents = []
    for k, v in items.items():
        if v.get("prefab") not in ("customplayer", "customplayertradable"):
            continue
        team = "t" if "terrorists" in (v.get("used_by_classes") or {}) else "ct"
        agents.append({"defindex": int(k), "id": v["name"], "name": loc(v.get("item_name", "")), "team": team,
                       "tradable": v["prefab"] == "customplayertradable", "rarity": v.get("item_rarity"),
                       "model": v.get("model_player"), "voicePrefix": v.get("vo_prefix"),
                       "voiceFolder": voice_folder(v),
                       "defaultCheer": v.get("default_cheer"), "defaultDefeat": v.get("default_defeat"),
                       "thumbnail": "thumbnails/" + Path(v.get("image_inventory", "")).name + ".png" if v.get("image_inventory") else None,
                       "_icon": "panorama/images/" + v["image_inventory"] + "_png.vtex_c" if v.get("image_inventory") else None})

    # music kits
    music = []
    for k, v in kv1.merged(g["music_definitions"]).items():
        if not isinstance(v, dict) or "name" not in v:
            continue
        kit = v["name"]
        tracks = sorted(Path(f).name.removesuffix(".vsnd_c") for f in files if f.startswith(f"sounds/music/{kit}/") and f.endswith(".vsnd_c"))
        music.append({"id": int(k), "kit": kit, "name": loc(v.get("loc_name", "")), "description": loc(v.get("loc_description", "")),
                      "thumbnail": "thumbnails/" + Path(v.get("image_inventory", "")).name + ".png" if v.get("image_inventory") else None,
                      "_icon": "panorama/images/" + v["image_inventory"] + "_png.vtex_c" if v.get("image_inventory") else None,
                      "soundFolder": f"music/{kit}/" if tracks else None, "tracks": tracks})

    cat = {"source": "CS2 items_game.txt + csgo_english.txt + generated icons (local install)", "rarities": rarities, "colors": colors,
           "weapons": weapons, "knives": knives, "gloves": gloves, "finishes": finishes, "agents": agents, "musicKits": music}
    return cat


def strip(o):
    if isinstance(o, dict):
        return {k: strip(v) for k, v in o.items() if not k.startswith("_")}
    if isinstance(o, list):
        return [strip(v) for v in o]
    return o


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cs2", type=Path, default=Path(r"D:\Steam\steamapps\common\Counter-Strike Global Offensive"))
    ap.add_argument("--catalog-only", action="store_true")
    ap.add_argument("--step", nargs="*", default=["thumbnails", "agents", "gloves", "weapons", "paints", "voice", "music"])
    args = ap.parse_args()
    vpk = args.cs2 / "game" / "csgo" / "pak01_dir.vpk"
    files = listing(vpk)
    cat = catalog(vpk, files)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "catalog.json").write_text(json.dumps(strip(cat), indent=1, ensure_ascii=False), encoding="utf-8")
    f = cat["finishes"]
    log(f"catalog: {len(cat['agents'])} agents, {len(f['weapons'])} weapon finishes, {len(f['knives'])} knife finishes, "
        f"{len(f['gloves'])} glove finishes, {len(cat['musicKits'])} music kits")
    if args.catalog_only:
        return

    tmp = OUT / "_vrf"
    if "thumbnails" in args.step:
        # Factory New (or best) icons of every finish, agent and music kit portraits, flattened into thumbnails/
        icons = [x["_icon"] for grp in f.values() for x in grp] + [a["_icon"] for a in cat["agents"] if a["_icon"]] \
            + [m["_icon"] for m in cat["musicKits"] if m["_icon"]]
        icons = [i for i in icons if i in set(files)]
        for i in range(0, len(icons), 200):
            export(vpk, tmp, *icons[i:i + 200])
        dst = OUT / "thumbnails"
        dst.mkdir(parents=True, exist_ok=True)
        moved = 0
        for p in (tmp / "panorama").rglob("*.png"):
            p.replace(dst / p.name.replace("_png.png", ".png"))
            moved += 1
        log(f"  thumbnails: {moved}")
    gltf = ("--gltf_export_format", "glb", "--gltf_export_materials")
    if "agents" in args.step:
        export(vpk, OUT / "models", "agents/models/", extra=gltf)
    if "gloves" in args.step:
        pass  # glove models live under agents/models/shared/arms (exported with the agents)
    if "weapons" in args.step:
        export(vpk, OUT / "models", "weapons/models/", extra=gltf)
    if "paints" in args.step:
        export(vpk, OUT / "paints", "weapons/paints/", "items/assets/paintkits/", "materials/models/weapons/customization/")
    if "voice" in args.step:
        export(vpk, OUT / "audio", "sounds/vo/agents/", "soundevents/vo/agents/")
    if "music" in args.step:
        export(vpk, OUT / "audio", "sounds/music/", "soundevents/music/")
    log("done")


if __name__ == "__main__":
    main()
