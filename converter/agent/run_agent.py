# /// script
# dependencies = ["numpy", "pillow"]
# ///
"""Convert just the agent data into the existing pack (cs2-pack/CounterCraft-CS2-Assets), optionally installing it."""
import argparse
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import convert  # noqa: E402
import weapons  # noqa: E402
import agent  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--cs2", type=Path, default=Path(r"D:\Steam\steamapps\common\Counter-Strike Global Offensive"))
ap.add_argument("--install", type=Path)
args = ap.parse_args()
cs2 = convert.Cs2(args.cs2, convert.ROOT / "cs2-extract" / "cache")
pack = convert.ROOT / "cs2-pack" / convert.PACK_NAME
assets = pack / "assets" / convert.NS
if (assets / "agents").exists():
    shutil.rmtree(assets / "agents")
melee, grenades = weapons.load_extra()
agent.convert(cs2, assets, convert.write_texture, convert.log, weapons.load() + melee + grenades)
if args.install:
    dst = args.install / convert.PACK_NAME / "assets" / convert.NS / "agents"
    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(assets / "agents", dst)
    for t in (assets / "textures" / "agent").glob("*.png"):
        (args.install / convert.PACK_NAME / "assets" / convert.NS / "textures" / "agent").mkdir(parents=True, exist_ok=True)
        shutil.copy2(t, args.install / convert.PACK_NAME / "assets" / convert.NS / "textures" / "agent" / t.name)
    convert.log(f"installed agents to {dst}")
