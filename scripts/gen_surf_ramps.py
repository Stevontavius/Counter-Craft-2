"""Generate Counter-Craft 2's surf ramps: one per vanilla stair, the stair's slope, crafted from it and a feather.

Reads the vanilla stair list, each stair's model textures, its English name and its mining tags from a Minecraft client
jar (names and texture references only: nothing of Minecraft's art is copied), and writes into src/main/resources:

  assets/countercraft/models/block/surf_ramp.json      the shared slope model (the 45 degree surface, a stepped body)
  assets/countercraft/models/block/<id>.json           each ramp: the shared model with its stair's textures
  assets/countercraft/blockstates/<id>.json            facing north / east / south / west
  assets/countercraft/items/<id>.json                  the item shows the block model
  data/countercraft/recipe/<id>.json                   stair + feather, shapeless
  data/countercraft/loot_table/blocks/<id>.json        drops itself
  data/minecraft/tags/block/{mineable/*,needs_*}.json  the same tools as the stair
  assets/countercraft/lang/en_us.json                  "Oak Surf Ramp" (merged into the existing file)

The mod registers one ramp for each vanilla stair block (SurfRamps.java), the same list this reads (blockstates named
*_stairs). <id> = <stair without "_stairs">_surf_ramp.

usage: python scripts/gen_surf_ramps.py <minecraft client jar>
"""
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources"
NS = "countercraft"
TOOL_TAGS = ["mineable/axe", "mineable/pickaxe", "mineable/shovel", "mineable/hoe", "needs_stone_tool", "needs_iron_tool", "needs_diamond_tool"]


def write(rel, obj):
    p = OUT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def slope_model():
    """Facing north: the surface rises from the south edge (z 16, y 0) to the north edge (z 0, y 16). Under it, a body
    of eight 2-pixel steps (seen from the side, a pixel staircase under the slope) and the back wall."""
    elements = [{
        "from": [0, 8, 0], "to": [16, 8, 16],
        "rotation": {"origin": [8, 8, 8], "axis": "x", "angle": 45, "rescale": True},
        "faces": {"up": {"texture": "#top"}, "down": {"texture": "#bottom"}},
    }, {
        "from": [0, 0, 0], "to": [16, 16, 0],
        "faces": {"north": {"texture": "#side", "cullface": "north"}},
    }]
    for i in range(8):
        top = 14 - 2 * i
        faces = {"down": {"texture": "#bottom", "cullface": "down"}}
        if top > 0:
            faces["east"] = {"texture": "#side", "cullface": "east"}
            faces["west"] = {"texture": "#side", "cullface": "west"}
        elements.append({"from": [0, 0, 2 * i], "to": [16, top, 2 * i + 2], "faces": faces})
    return {"parent": "minecraft:block/stairs", "textures": {"particle": "#side"}, "elements": elements}


def resolve_tag(z, tag, seen=None):
    """Every block id in a vanilla block tag (nested tags followed)."""
    seen = seen if seen is not None else set()
    if tag in seen:
        return set()
    seen.add(tag)
    try:
        values = json.loads(z.read(f"data/minecraft/tags/block/{tag}.json"))["values"]
    except KeyError:
        return set()
    out = set()
    for v in values:
        v = v["id"] if isinstance(v, dict) else v
        if v.startswith("#"):
            out |= resolve_tag(z, v[1:].split(":", 1)[-1], seen)
        else:
            out.add(v if ":" in v else "minecraft:" + v)
    return out


def main():
    z = zipfile.ZipFile(sys.argv[1])
    lang_mc = json.loads(z.read("assets/minecraft/lang/en_us.json"))
    stairs = sorted(m.group(1) for n in z.namelist() if (m := re.fullmatch(r"assets/minecraft/blockstates/([a-z0-9_]+_stairs)\.json", n)))
    write(f"assets/{NS}/models/block/surf_ramp.json", slope_model())
    tags = {t: resolve_tag(z, t) for t in TOOL_TAGS}
    tagged = {t: [] for t in TOOL_TAGS}
    names = {}
    for stair in stairs:
        rid = stair[: -len("_stairs")] + "_surf_ramp"
        state = json.loads(z.read(f"assets/minecraft/blockstates/{stair}.json"))
        model = state["variants"]["facing=east,half=bottom,shape=straight"]["model"].split(":", 1)[-1]
        textures = json.loads(z.read(f"assets/minecraft/models/{model}.json"))["textures"]
        write(f"assets/{NS}/models/block/{rid}.json", {"parent": f"{NS}:block/surf_ramp", "textures": textures})
        write(f"assets/{NS}/blockstates/{rid}.json", {"variants": {
            f"facing={f}": ({"model": f"{NS}:block/{rid}", "y": y} if y else {"model": f"{NS}:block/{rid}"})
            for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
        write(f"assets/{NS}/items/{rid}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:block/{rid}"}})
        write(f"data/{NS}/recipe/{rid}.json", {
            "type": "minecraft:crafting_shapeless", "category": "building", "group": "surf_ramps",
            "ingredients": [f"minecraft:{stair}", "minecraft:feather"], "result": {"count": 1, "id": f"{NS}:{rid}"}})
        write(f"data/{NS}/loot_table/blocks/{rid}.json", {
            "type": "minecraft:block", "random_sequence": f"{NS}:blocks/{rid}",
            "pools": [{"rolls": 1, "condition": {"type": "minecraft:survives_explosion"},
                       "entries": [{"type": "minecraft:item", "name": f"{NS}:{rid}"}]}]})
        for t in TOOL_TAGS:
            if f"minecraft:{stair}" in tags[t]:
                tagged[t].append(f"{NS}:{rid}")
        name = lang_mc.get(f"block.minecraft.{stair}", stair.replace("_", " ").title())
        names[f"block.{NS}.{rid}"] = re.sub(r"Stairs$", "Surf Ramp", name)
    for t, ids in tagged.items():
        if ids:
            write(f"data/minecraft/tags/block/{t}.json", {"replace": False, "values": sorted(ids)})
    lang_path = OUT / "assets" / NS / "lang" / "en_us.json"
    lang = json.loads(lang_path.read_text(encoding="utf-8"))
    lang = {k: v for k, v in lang.items() if not re.fullmatch(rf"block\.{NS}\.[a-z0-9_]+_surf_ramp", k)}
    lang.update(names)
    lang_path.write_text(json.dumps(lang, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(stairs)} surf ramps; tags: " + ", ".join(f"{t} {len(v)}" for t, v in tagged.items() if v))


if __name__ == "__main__":
    main()
