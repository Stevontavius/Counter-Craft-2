# /// script
# dependencies = []
# ///
"""Decompile a CS2 particle system (.vpcf) and its children from the user's install, and summarise what each one
draws: renderer, textures, particle count, lifetime, radius, colour and alpha. Used to rebuild muzzle flashes and
tracers as a few billboards in the mod (the full particle runtime isn't ported).

usage: uv run particles.py <vpk path of a .vpcf, without _c> [--cs2 <CS2 folder>]
"""
from paths import EXTRACT
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VRF = ROOT / "tools" / "vrf" / "Source2Viewer-CLI.exe"
CS2 = Path(r"D:\Steam\steamapps\common\Counter-Strike Global Offensive")
OUT = EXTRACT / "cache" / "fx"


def export(vpk_path):
    f = OUT / vpk_path
    if not f.exists():
        pak = CS2 / "game" / "csgo" / "pak01_dir.vpk"
        subprocess.run([str(VRF), "-i", str(pak), "-o", str(OUT), "-d", "-f", vpk_path + "_c,__never__/"], capture_output=True)
    return f.read_text(encoding="utf-8", errors="replace") if f.exists() else ""


def blocks(text, key):
    """Top-level list blocks like m_Renderers = [ ... ]."""
    m = re.search(r"\n\t" + key + r" =\s*\n\t\[(.*?)\n\t\]", text, re.S)
    return m.group(1) if m else ""


def classes(block):
    return re.findall(r'_class = "(\w+)"', block)


def nums(text, key):
    return re.findall(key + r" =\s*([-\d.e]+)", text)


def summarise(vpk_path, depth=0, seen=None):
    seen = seen if seen is not None else set()
    if vpk_path in seen:
        return
    seen.add(vpk_path)
    t = export(vpk_path)
    pad = "  " * depth
    if not t:
        print(pad + "?? " + vpk_path)
        return
    print(pad + "== " + vpk_path)
    print(pad + "   max particles", nums(t, "m_nMaxParticles")[:1], "constant radius", nums(t, r"m_flConstantRadius")[:1],
          "constant lifetime", nums(t, r"m_flConstantLifespan")[:1])
    r = blocks(t, "m_Renderers")
    if r:
        print(pad + "   renderers", classes(r))
        for tex in re.findall(r'm_hTexture = resource:"([^"]+)"', r):
            print(pad + "     texture", tex)
        for k in ("m_flOverbrightFactor", "m_flAddSelfAmount", "m_nOrientationType", "m_flLengthScale", "m_flMaxLength", "m_flMinLength",
                  "m_flRadiusScale", "m_flAnimationRate", "m_nSequenceCombineMode", "m_bAdditive"):
            v = re.findall(k + r" =\s*([^\n]+)", r)
            if v:
                print(pad + f"     {k} {v[:2]}")
    for sect in ("m_Emitters", "m_Initializers", "m_Operators"):
        b = blocks(t, sect)
        if not b:
            continue
        print(pad + f"   {sect}: {classes(b)}")
        for k in ("m_flEmitRate", "m_nParticlesToEmit", "m_flEmissionDuration", "m_flLifetimeMin", "m_flLifetimeMax", "m_flRadiusMin",
                  "m_flRadiusMax", "m_ColorMin", "m_ColorMax", "m_nAlphaMin", "m_nAlphaMax", "m_flStartScale", "m_flEndScale",
                  "m_flStartTime", "m_flEndTime", "m_flFadeInTime", "m_flFadeOutTime", "m_vecOutputMin", "m_vecOutputMax", "m_fSpeedMin",
                  "m_fSpeedMax", "m_flOutputMin", "m_flOutputMax"):
            v = re.findall(k + r" =\s*(\[[^\]]*\]|[^\n]+)", b)
            if v:
                v = [re.sub(r"\s+", " ", x)[:120] for x in v]
                print(pad + f"     {k} {v[:4]}")
    for child in re.findall(r'm_ChildRef = resource:"([^"]+)"', t):
        summarise(child, depth + 1, seen)


if __name__ == "__main__":
    summarise(sys.argv[1])
