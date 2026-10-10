"""CS2 muzzle flash and tracer parameters, read from the particle systems (.vpcf) in the user's install.

The mod doesn't run CS2's particle system; it rebuilds each flash from the parts that matter in first person: the
fire sprites (count, lifetime, spread along the barrel, size, alpha, roll, colour, overbright), the sparks and the
smoke puff. Numbers only; the textures go into the converted pack.
"""
import glob
import os
import re

import kv3
import particles

FX = particles.OUT


def _load(path):
    particles.export(path)
    f = FX / path
    return kv3.loads(f.read_text(encoding="utf-8", errors="replace")) if f.exists() else None


def _val(v):
    """(min, max) of a CParticleFloatInput, or None if it isn't a literal / uniform random."""
    if not isinstance(v, dict):
        return (float(v), float(v)) if isinstance(v, (int, float)) else None
    t = v.get("m_nType")
    if t == "PF_TYPE_LITERAL":
        return (v["m_flLiteralValue"], v["m_flLiteralValue"])
    if t == "PF_TYPE_RANDOM_UNIFORM":
        return (v["m_flRandomMin"], v["m_flRandomMax"])
    return None


def _init(d, field, default_field=3):
    """The value an InitFloat initializer writes to `field` (radius is the default output)."""
    for i in d.get("m_Initializers", []):
        if i.get("_class") == "C_INIT_InitFloat" and i.get("m_nOutputField", default_field) == field \
                and i.get("m_nSetMethod", "PARTICLE_SET_REPLACE_VALUE") == "PARTICLE_SET_REPLACE_VALUE":
            v = _val(i.get("m_InputValue"))
            if v:
                return v
    return None


def _count(d):
    for e in d.get("m_Emitters", []):
        if e.get("_class") == "C_OP_InstantaneousEmitter":
            v = _val(e.get("m_nParticlesToEmit"))
            if v:
                return int(round(v[1]))
    return int(d.get("m_nMaxParticles", 0))


def _textures(d):
    return [t.get("m_hTexture", "") for r in d.get("m_Renderers", []) for t in r.get("m_vecTexturesInput", [])]


def _renderer(d):
    r = (d.get("m_Renderers") or [{}])[0]
    return r.get("_class", ""), r


def _color(d):
    for i in d.get("m_Initializers", []):
        if i.get("_class") == "C_INIT_RandomColor":
            a, b = i.get("m_ColorMin", [255] * 3), i.get("m_ColorMax", [255] * 3)
            return [[int(x or 255) for x in a[:3]], [int(x or 255) for x in b[:3]]]
    return [[255, 255, 255], [255, 255, 255]]


def _along(d):
    """Spread along the barrel (units): C_INIT_RemapScalarToVector writing position x."""
    for i in d.get("m_Initializers", []):
        if i.get("_class") == "C_INIT_RemapScalarToVector":
            return [i.get("m_vecOutputMin", [0])[0], i.get("m_vecOutputMax", [0])[0]]
    return [0.0, 0.0]


def _speed(d):
    for i in d.get("m_Initializers", []):
        if i.get("_class") == "C_INIT_VelocityRandom":
            a, b = _val(i.get("m_fSpeedMin")), _val(i.get("m_fSpeedMax"))
            if a and b:
                return [a[0], b[1]]
        if i.get("_class") == "C_INIT_InitialVelocityNoise":
            lo, hi = i.get("m_vecOutputMin"), i.get("m_vecOutputMax")
            if isinstance(lo, dict) or isinstance(hi, dict) or lo is None:
                continue
            return [float(min(lo)), float(max(hi))]
    return [0.0, 0.0]


def _children(d):
    return [c["m_ChildRef"] for c in d.get("m_Children", []) if c.get("m_ChildRef")]


def flash(vpcf):
    """{fire: [variants], sparks, smoke} for a muzzle flash system (walks its child systems)."""
    out = {"fire": [], "sparks": None, "smoke": None}
    seen = set()

    def walk(path):
        if path in seen:
            return
        seen.add(path)
        d = _load(path)
        if not d:
            return
        tex = " ".join(_textures(d))
        kind, r = _renderer(d)
        life = _init(d, 1) or (d.get("m_flConstantLifespan", 1.0),) * 2
        if kind == "C_OP_RenderSprites" and ("fire_gas" in tex or "wispy_steam_burst" in tex or "flame" in tex):
            out["fire"].append({"count": _count(d), "life": round(life[1], 4), "radius": list(_init(d, 3) or (5.0, 5.0)),
                                "alpha": [round(a, 3) for a in (_init(d, 7) or (1.0, 1.0))], "along": _along(d),
                                "roll": list(_init(d, 4) or (0.0, 360.0)), "color": _color(d),
                                "overbright": (_val(r.get("m_flOverbrightFactor")) or (1.0, 1.0))[0],
                                "radiusScale": (_val(r.get("m_flRadiusScale")) or (1.0, 1.0))[0],
                                "texture": "steam" if "wispy_steam" in tex else "fire"})
        elif kind == "C_OP_RenderTrails" and "spark" in tex and out["sparks"] is None:
            out["sparks"] = {"count": _count(d), "life": round(life[1], 4), "speed": _speed(d), "color": _color(d),
                             "lengthScale": r.get("m_flLengthScale", 1.0), "maxLength": r.get("m_flMaxLength", 2000.0)}
        elif kind == "C_OP_RenderSprites" and "smoke" in tex and out["smoke"] is None:
            out["smoke"] = {"count": _count(d), "life": round(life[1], 4), "radius": list(_init(d, 3) or (5.0, 5.0)),
                            "alpha": [round(a, 3) for a in (_init(d, 7) or (1.0, 1.0))], "color": _color(d)}
        for c in _children(d):
            walk(c)

    walk(vpcf)
    return out


def tracer(vpcf):
    """Speed (u/s), visible length (u), length fade-in (s), width limits (fraction of the screen), alpha, colour."""
    d = _load(vpcf)
    if not d:
        return None
    speed = None
    for i in d.get("m_Initializers", []):
        if i.get("_class") == "C_INIT_MoveBetweenPoints":
            speed = (_val(i.get("m_flSpeedMax")) or (20500.0,))[0]
    trails = [r for r in d.get("m_Renderers", []) if r.get("_class") == "C_OP_RenderTrails"]
    ropes = [r for r in d.get("m_Renderers", []) if r.get("_class") == "C_OP_RenderRopes"]
    if not trails and ropes:
        # SMG / MG / AUG: a rope along the whole path whose tracer texture scrolls toward the target; the streak is one
        # texture tile (m_flTextureVWorldSize units) moving at the scroll rate
        r = ropes[0]
        tile = (_val(r.get("m_flTextureVWorldSize")) or (100.0,))[0]
        scroll = abs((_val(r.get("m_flTextureVScrollRate")) or (-20500.0,))[0])
        col = next((o.get("m_ColorFade") for o in d.get("m_Operators", []) if o.get("_class") == "C_OP_ColorInterpolate"), None)
        col = [int(x) for x in (col or [255, 255, 255])[:3]]
        return {"speed": scroll, "maxLength": tile, "fadeIn": 0.0, "minSize": 0.0, "maxSize": 1.0,
                "radius": (_init(d, 3) or (2.0, 2.0))[0] * (_val(r.get("m_flRadiusScale")) or (1.0, 1.0))[0],
                "alpha": [1.0, 1.0], "color": [col, col], "rope": True}
    if not trails:
        # a wrapper system: the tracer itself is a child
        for c in _children(d):
            t = tracer(c)
            if t:
                return t
        return None
    r = trails[0]
    return {"speed": speed or 20500.0, "maxLength": r.get("m_flMaxLength", 1200.0), "fadeIn": r.get("m_flLengthFadeInTime", 0.0),
            "minSize": r.get("m_flMinSize", 0.0), "maxSize": r.get("m_flMaxSize", 1.0),
            "radius": (_init(d, 3) or (1.0, 1.0))[0] * (_val(r.get("m_flRadiusScale")) or (1.0, 1.0))[0],
            "alpha": [round(a, 3) for a in (_init(d, 7) or (1.0, 1.0))], "color": _color(d)}


def shoot_flashes(dumps, folder, clip_name):
    """{'unsilenced': vpcf, 'silenced': vpcf} from a shoot clip's particle events (DATA dump written by convert.py)."""
    path = os.path.join(dumps, f"{folder}__{clip_name}.clip.txt")
    if not os.path.exists(path):
        return {}
    t = open(path, encoding="utf-8").read()
    out = {}
    for ev in t.split('_class = "CNmParticleEvent"')[1:]:
        m = re.search(r'm_hParticleSystem = resource:"([^"]+)"', ev)
        tags = re.search(r'm_tags = "([^"]*)"', ev).group(1)
        if not m or "shell_casing" in m.group(1) or "ground_smoke" in m.group(1):
            continue
        key = "silenced" if "silenced" in tags.split() else "unsilenced"
        out.setdefault(key, m.group(1))
    return out


if __name__ == "__main__":
    import json
    import sys
    for v in sys.argv[1:]:
        print(v, json.dumps(flash(v) if "tracer" not in v else tracer(v), indent=1))


def summary(vpcf):
    """What the mod draws for one flash: the main fire layer (sprites spread along the barrel), sparks, and whether it
    is a silenced puff (no fire). Lifetimes the extractor can't see (set by other operators) fall back to CS2's
    primary-flash 0.05 s."""
    f = flash(vpcf)
    fires = [x for x in f["fire"] if x["texture"] == "fire" and x["along"][1] > 0]
    out = {"silenced": not fires, "sparks": f["sparks"]["count"] if f["sparks"] else 0}
    if fires:
        main = max(fires, key=lambda x: (x["count"] * x["along"][1], -x["life"]))
        out.update({k: main[k] for k in ("count", "radius", "alpha", "along", "roll", "color", "overbright", "radiusScale")})
        out["life"] = main["life"] if main["life"] < 0.5 else 0.05
    if f["smoke"]:
        out["smoke"] = {"count": f["smoke"]["count"], "alpha": f["smoke"]["alpha"], "color": f["smoke"]["color"]}
    return out
