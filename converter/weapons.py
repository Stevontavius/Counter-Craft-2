# /// script
# dependencies = []
# ///
"""Every CS2 gun: stats from the user's weapons.vdata (+ spray patterns we measured in CS2, converter/measured), and the
asset config the converter needs (model, skeleton, viewmodel clip folder, sounds, icon).

    uv run weapons.py --out <pack>/assets/countercraft/weapons.json   # the asset pack build (build_pack.py's step)
    uv run weapons.py            # dev: run/config/countercraft/weapons.json, and the item models / names in the sources

The weapon table is made on the player's PC from their own CS2, like the rest of the pack: none of Valve's data ships with
the mod. The mod reads it from config/countercraft/weapons.json (the installer copies it there from the pack).
"""
from paths import EXTRACT
import glob
import json
import os
import re
import sys

import kv3
import muzzle

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
VDATA = os.path.join(EXTRACT, "weapons.vdata")
RESULTS = os.path.join(HERE, "measured")  # spray patterns and view punch measured in CS2 (our own measurements)
DUMPS = os.path.join(EXTRACT, "cache", "dump")  # vnmclip DATA dumps written by convert.py
OUT = os.path.join(ROOT, "run", "config", "countercraft", "weapons.json")

NAMES = {
    "ak47": "AK-47", "m4a1": "M4A4", "m4a1_silencer": "M4A1-S", "famas": "FAMAS", "galilar": "Galil AR",
    "aug": "AUG", "sg556": "SG 553", "awp": "AWP", "ssg08": "SSG 08", "scar20": "SCAR-20", "g3sg1": "G3SG1",
    "mac10": "MAC-10", "mp9": "MP9", "mp7": "MP7", "mp5sd": "MP5-SD", "ump45": "UMP-45", "p90": "P90",
    "bizon": "PP-Bizon", "nova": "Nova", "xm1014": "XM1014", "sawedoff": "Sawed-Off", "mag7": "MAG-7",
    "m249": "M249", "negev": "Negev", "glock": "Glock-18", "hkp2000": "P2000", "usp_silencer": "USP-S",
    "elite": "Dual Berettas", "p250": "P250", "tec9": "Tec-9", "fiveseven": "Five-SeveN", "cz75a": "CZ75-Auto",
    "deagle": "Desert Eagle", "revolver": "R8 Revolver", "taser": "Zeus x27",
}
# viewmodel clip folder under animation/anims/viewmodel/ (guns without their own use the shared default clips)
CLIP_DIRS = {
    "ak47": "rifle/rifle_ak", "m4a1": "rifle/rifle_m4a4", "m4a1_silencer": "rifle/_default_rifle", "glock": "pistol/pistol_glock18",
    "usp_silencer": "pistol/_default_pistol",
}
ORDER = ["glock", "hkp2000", "usp_silencer", "elite", "p250", "tec9", "fiveseven", "cz75a", "deagle", "revolver",
         "nova", "xm1014", "sawedoff", "mag7", "m249", "negev",
         "mac10", "mp9", "mp7", "mp5sd", "ump45", "p90", "bizon",
         "galilar", "famas", "ak47", "m4a1", "m4a1_silencer", "ssg08", "sg556", "aug", "awp", "g3sg1", "scar20", "taser"]


def pair(v):
    return [float(v[0]), float(v[1])] if isinstance(v, list) else [float(v), float(v)]


def sound_id(event):
    """Weapon_AK47.Single -> weapon.ak47.single (same rule as convert.mc_sound_id)."""
    return re.sub(r"[^a-z0-9_.]", "_", event.lower().replace("weapon_", "weapon."))


def clip_dir(gid, w):
    if gid in CLIP_DIRS:
        return CLIP_DIRS[gid]
    cat = "pistol" if w.get("m_GearSlot") == "GEAR_SLOT_PISTOL" or gid == "taser" else "rifle"
    return f"{cat}/{cat}_{gid}"


def load():
    root = kv3.loads(open(VDATA, encoding="utf-8").read())
    out = []
    for gid in ORDER:
        w = kv3.resolve(root, f"weapon_{gid}_prefab")
        snd = w.get("m_aShootSounds", {})
        silencer = {"WEAPONSILENCER_NONE": "none", "WEAPONSILENCER_DETACHABLE": "detachable",
                    "WEAPONSILENCER_INTEGRATED": "integrated"}.get(w.get("m_eSilencerType"), "none")
        fire = snd.get("WEAPON_SOUND_SPECIAL1") if silencer == "detachable" else snd.get("WEAPON_SOUND_SINGLE")
        clip = int(w["m_iMaxClip1"])
        res = int(w.get("m_nPrimaryReserveAmmoMax") or 0)
        reserve = clip * res if w.get("m_bReserveAmmoAsClips") else res
        wt = w.get("m_WeaponType", "").replace("WEAPONTYPE_", "").lower()
        g = {
            "id": gid,
            "name": NAMES[gid],
            "type": wt,
            "slot": "secondary" if w.get("m_GearSlot") == "GEAR_SLOT_PISTOL" else ("taser" if gid == "taser" else "primary"),
            "price": w.get("m_nPrice", 0),
            "killAward": w.get("m_nKillAward", 300),
            "damage": w["m_nDamage"],
            "pellets": w["m_nNumBullets"],
            "armorRatio": w["m_flArmorRatio"],
            "penetration": w["m_flPenetration"],
            "range": w["m_flRange"],
            "rangeModifier": w["m_flRangeModifier"],
            "headshot": w["m_flHeadshotMultiplier"],
            "cycle": pair(w["m_flCycleTime"]),
            "auto": bool(w["m_bIsFullAuto"]),
            "clip": clip,
            "reserve": reserve,
            "reloadTime": w.get("m_flDisallowAttackAfterReloadStartDuration", 2.5),
            "singleShellReload": bool(w.get("m_bReloadsSingleShells", False)),
            "deploy": w.get("m_flDeployDuration", 1.0),
            "maxSpeed": pair(w["m_flMaxSpeed"]),
            "attackMoveSpeedFactor": w.get("m_flAttackMovespeedFactor", 1.0),
            "spread": pair(w["m_flSpread"]),
            "inaccuracy": {k: pair(w[f"m_flInaccuracy{K}"]) for k, K in
                           (("stand", "Stand"), ("crouch", "Crouch"), ("move", "Move"), ("jump", "Jump"),
                            ("land", "Land"), ("ladder", "Ladder"), ("fire", "Fire"))} | {"jumpInitial": w.get("m_flInaccuracyJumpInitial", 0.0)},
            "recovery": {"stand": w["m_flRecoveryTimeStand"], "crouch": w["m_flRecoveryTimeCrouch"],
                         "standFinal": w["m_flRecoveryTimeStandFinal"], "crouchFinal": w["m_flRecoveryTimeCrouchFinal"],
                         "transitionStart": w["m_nRecoveryTransitionStartBullet"], "transitionEnd": w["m_nRecoveryTransitionEndBullet"]},
            "recoilParams": {"angle": pair(w["m_flRecoilAngle"]), "angleVariance": pair(w["m_flRecoilAngleVariance"]),
                             "magnitude": pair(w["m_flRecoilMagnitude"]), "magnitudeVariance": pair(w["m_flRecoilMagnitudeVariance"]),
                             "seed": w.get("m_nRecoilSeed", 0)},
            "zoom": {"levels": w["m_nZoomLevels"], "fov": [w["m_nZoomFOV1"], w["m_nZoomFOV2"]],
                     "unzoomAfterShot": bool(w["m_bUnzoomsAfterShot"]), "hideViewmodel": bool(w["m_bHideViewModelWhenZoomed"]),
                     "crosshair": bool(w.get("m_bShowCrosshair", True))},
            "silencer": silencer,
            "burst": {"enabled": bool(w["m_bHasBurstMode"]), "between": w["m_flTimeBetweenBurstShots"],
                      "cycle": w["m_flCycleTimeWhenInBurstMode"]},
            "zoomTime": [w.get("m_flZoomTime0", 0.0), w.get("m_flZoomTime1", 0.0), w.get("m_flZoomTime2", 0.0)],
            # AUG / SG 553: the zoom is aiming down the scope (viewmodel pose blend), not a scope overlay
            "ironsight": {"pullUp": w["m_flIronSightPullUpSpeed"], "putDown": w["m_flIronSightPutDownSpeed"], "fov": w["m_flIronSightFOV"]}
            if w.get("m_flIronSightFOV", 0) > 0 and w["m_nZoomLevels"] > 0 else None,
            "revolver": bool(w["m_bIsRevolver"]),
            "exhaustible": "EXHAUSTIBLE" in str(w.get("m_iFlags", "")),
            "sounds": {"fire": sound_id(fire) if fire else None,
                       "fireUnsilenced": sound_id(snd["WEAPON_SOUND_SINGLE"]) if silencer == "detachable" else None,
                       "zoomIn": sound_id(snd["WEAPON_SOUND_ZOOM_IN"]) if "WEAPON_SOUND_ZOOM_IN" in snd else None,
                       "zoomOut": sound_id(snd["WEAPON_SOUND_ZOOM_OUT"]) if "WEAPON_SOUND_ZOOM_OUT" in snd else None},
            # converter-only (assets)
            "_asset": {"model": w["m_szWorldModel"] + "_c", "skeleton": os.path.basename(w["m_szAnimSkeleton"]),
                       "clipDir": f"animation/anims/viewmodel/{clip_dir(gid, w)}/",
                       "icon": f"panorama/images/econ/weapons/base_weapons/weapon_{gid}_png.vtex_c",
                       "soundEvents": [e for e in (fire, snd.get("WEAPON_SOUND_SINGLE"), snd.get("WEAPON_SOUND_ZOOM_IN"),
                                                   snd.get("WEAPON_SOUND_ZOOM_OUT")) if e]},
        }
        # shotgun pellets scatter the impact markers, so their spray measurements aren't trustworthy
        g["recoil"] = measured_recoil(gid) if g["pellets"] <= 1 else None
        g["punch"] = camera_punch(gid, g)
        if g["singleShellReload"]:
            g["shellReload"] = shell_reload(clip_dir(gid, w))
        anim_times(g, clip_dir(gid, w))
        g["fx"] = shot_fx(w, clip_dir(gid, w))
        out.append(g)
    return out


KNIVES = [  # (item id, vdata name, display name); same stats, different model and animations
    ("knife_ct", "weapon_knife", "Knife (CT)"), ("knife_t", "weapon_knife_t", "Knife (T)"),
    ("bayonet", "weapon_bayonet", "Bayonet"), ("knife_css", "weapon_knife_css", "Classic Knife"),
    ("knife_flip", "weapon_knife_flip", "Flip Knife"), ("knife_gut", "weapon_knife_gut", "Gut Knife"),
    ("knife_karambit", "weapon_knife_karambit", "Karambit"), ("knife_m9_bayonet", "weapon_knife_m9_bayonet", "M9 Bayonet"),
    ("knife_tactical", "weapon_knife_tactical", "Huntsman Knife"), ("knife_falchion", "weapon_knife_falchion", "Falchion Knife"),
    ("knife_bowie", "weapon_knife_survival_bowie", "Bowie Knife"), ("knife_butterfly", "weapon_knife_butterfly", "Butterfly Knife"),
    ("knife_push", "weapon_knife_push", "Shadow Daggers"), ("knife_cord", "weapon_knife_cord", "Paracord Knife"),
    ("knife_canis", "weapon_knife_canis", "Survival Knife"), ("knife_ursus", "weapon_knife_ursus", "Ursus Knife"),
    ("knife_navaja", "weapon_knife_gypsy_jackknife", "Navaja Knife"), ("knife_outdoor", "weapon_knife_outdoor", "Nomad Knife"),
    ("knife_stiletto", "weapon_knife_stiletto", "Stiletto Knife"), ("knife_talon", "weapon_knife_widowmaker", "Talon Knife"),
    ("knife_skeleton", "weapon_knife_skeleton", "Skeleton Knife"), ("knife_kukri", "weapon_knife_kukri", "Kukri Knife"),
]
GRENADES = [  # (id, display name, clip folder)
    ("hegrenade", "HE Grenade", "grenade/grenade_hegrenade"), ("flashbang", "Flashbang", "grenade/grenade_flashbang"),
    ("smokegrenade", "Smoke Grenade", "grenade/grenade_smokegrenade"), ("molotov", "Molotov", "grenade/grenade_molotov"),
    ("incgrenade", "Incendiary Grenade", "grenade/grenade_incendiary"), ("decoy", "Decoy Grenade", "grenade/_default_grenade"),
]
GRENADE_SOUNDS = {  # CS2 sound events per grenade
    "hegrenade": {"pullpin": "HEGrenade.PullPin", "throw": "HEGrenade.Throw", "bounce": "HEGrenade.Bounce", "detonate": "BaseGrenade.Explode"},
    "flashbang": {"pullpin": "Flashbang.PullPin", "throw": "Flashbang.Throw", "bounce": "Flashbang.Bounce", "detonate": "Flashbang.Explode", "ring": "Flashbang.Ring.Long"},
    "smokegrenade": {"pullpin": "SmokeGrenade.PullPin", "throw": "SmokeGrenade.Throw", "bounce": "SmokeGrenade.Bounce", "detonate": "BaseSmokeEffect.Sound", "clear": "SmokeGrenade.Clear"},
    "molotov": {"pullpin": "Molotov.PreThrow", "throw": "Molotov.Throw", "bounce": "Molotov.Bounce", "detonate": "Molotov.Start", "loop": "Inferno.Loop", "fail": "Molotov.StartFailed", "fadeout": "Inferno.FadeOut", "extinguish": "Molotov.Extinguish"},
    "incgrenade": {"pullpin": "HEGrenade.PullPin", "throw": "HEGrenade.Throw", "bounce": "HEGrenade.Bounce", "detonate": "Molotov.Start", "loop": "Inferno.Loop", "fail": "Molotov.StartFailed", "fadeout": "Inferno.FadeOut", "extinguish": "Molotov.Extinguish"},
    "decoy": {"pullpin": "Decoy.PullPin", "throw": "Decoy.Throw", "bounce": "HEGrenade.Bounce", "detonate": "BaseGrenade.Explode"},
}
KNIFE_SOUNDS = ["Weapon_Knife.Slash", "Weapon_Knife.Hit", "Weapon_Knife.HitWall", "Weapon_Knife.Stab", "Weapon_Knife.Deploy"]


def throw_anims(folder):
    """Seconds of the overhand and underhand throw clips (the next grenade is drawn once the throw has played)."""
    name = folder.split("/")[-1]
    out = []
    for kind in ("throw_overhand_", "throw_underhand_"):
        path = next(iter(sorted(glob.glob(os.path.join(DUMPS, f"{name}__{kind}*.clip.txt")))), None)
        m = re.search(r"^	m_flDuration = ([\d.e-]+)", open(path, encoding="utf-8").read(), re.M) if path else None
        out.append(round(float(m.group(1)), 4) if m else 0.75)
    return out


def load_extra():
    """Knives and grenades: ids, names, prices, and the converter's asset config."""
    root = kv3.loads(open(VDATA, encoding="utf-8").read())
    by_name = {}
    for k, v in root.items():
        if isinstance(v, dict) and k.isdigit():
            w = kv3.resolve(root, k)
            if w.get("m_szName"):
                by_name[w["m_szName"]] = w
    melee = []
    for kid, vname, name in KNIVES:
        w = by_name[vname]
        skel = os.path.basename(w["m_szAnimSkeleton"])
        folder = "knife/_default_knife" if skel == "knife_default_ct.vnmskel" else "knife/" + skel.replace(".vnmskel", "")
        melee.append({"id": kid, "name": name, "kind": "knife",
                      "_asset": {"model": w["m_szWorldModel"] + "_c", "skeleton": skel,
                                 "clipDir": f"animation/anims/viewmodel/{folder}/",
                                 "icon": f"panorama/images/econ/weapons/base_weapons/{vname}_png.vtex_c",
                                 "soundEvents": KNIFE_SOUNDS}})
    grenades = []
    for gid, name, folder in GRENADES:
        w = kv3.resolve(root, f"weapon_{gid}_prefab")
        snd = GRENADE_SOUNDS[gid]
        grenades.append({"id": gid, "name": name, "kind": "grenade", "price": w.get("m_nPrice", 0),
                         "deploy": w.get("m_flDeployDuration", 1.0), "throwAnim": throw_anims(folder),
                         "damage": w.get("m_nDamage", 0), "radius": w.get("m_flRange", 0),
                         "throwVelocity": w.get("m_flThrowVelocity", 750.0), "maxSpeed": pair(w["m_flMaxSpeed"])[0],
                         "sounds": {k: sound_id(v) for k, v in snd.items()},
                         "_asset": {"model": w["m_szWorldModel"] + "_c", "skeleton": os.path.basename(w["m_szAnimSkeleton"]),
                                    "clipDir": f"animation/anims/viewmodel/{folder}/",
                                    "icon": f"panorama/images/econ/weapons/base_weapons/weapon_{gid}_png.vtex_c",
                                    "soundEvents": list(snd.values())}})
    return melee, grenades


# Guns whose shots are far enough apart that the punch recovers in between: the view kick is replayed from the camera
# curve measured in CS2 (measure/punch_track.py). MAG-7 / Sawed-Off: their recordings' shot detection failed, so they
# use the Nova curve scaled by recoil magnitude (camera kick = 0.038 x magnitude on every gun measured cleanly).
CAMERA_CURVES = {"awp": "awp", "ssg08": "ssg08", "nova": "nova", "xm1014": "xm1014", "revolver": "revolver",
                 "deagle": "deagle", "mag7": "nova", "sawedoff": "nova"}
# the Deagle's tap-fire spray was measured cleanly, so its bullets keep the measured pattern (camera from the curve)
SPRAY_BULLETS = {"deagle"}


def camera_punch(gid, g):
    """{fps, curve [[up, right] camera degrees per frame from the shot], bullets: offsets follow the curve too}."""
    src = CAMERA_CURVES.get(gid)
    path = os.path.join(RESULTS, f"{src}_punch.json") if src else None
    if not path or not os.path.exists(path):
        return None
    d = json.load(open(path))
    fps = 60.0
    curve = [list(c) for c in d["mean_curve"][d["peak_frame_after_shot"]:]]
    # the averaged curve runs into the next shot once the gap is shorter than the window: cut there and continue the
    # recovery at its last slope; also stop at the first return to ~zero (later drift is the view, not the punch)
    gap = int(g["cycle"][0] * fps) - 1
    up0 = curve[0][0]
    end = next((i for i, c in enumerate(curve) if c[0] <= 0.03 * up0), len(curve))
    if gap < end:
        curve = curve[:gap]
        k = min(3, len(curve) - 1)
        du = (curve[-1][0] - curve[-1 - k][0]) / k if k else -up0 / 10
        dr = (curve[-1][1] - curve[-1 - k][1]) / k if k else 0.0
        du = min(du, -0.02 * up0)
        while curve[-1][0] + du > 0:
            curve.append([curve[-1][0] + du, curve[-1][1] + dr])
    else:
        curve = curve[:end]
    # averaged over a handful of shots the curve is a little noisy, and the sideways part picks up view drift: keep the
    # peak, smooth, never rise again (a recovering punch doesn't), and let the sideways part follow the vertical one
    ups = [c[0] for c in curve]
    sm = [ups[0]] + [sum(ups[max(0, i - 1):i + 2]) / len(ups[max(0, i - 1):i + 2]) for i in range(1, len(ups))]
    for i in range(1, len(sm)):
        sm[i] = min(sm[i], sm[i - 1])
    r0 = sum(c[1] for c in curve[:3]) / min(3, len(curve))
    slope = min(sm[-1] - sm[-2], -0.02 * ups[0]) if len(sm) > 1 else -0.1 * ups[0]
    while sm[-1] + slope > 0:
        sm.append(sm[-1] + slope)  # finish the recovery at its last rate instead of snapping to zero
    curve = [[u, r0 * u / ups[0]] for u in sm]
    curve.append([0.0, 0.0])
    if src != gid:
        mags = {x["id"]: x for x in load_cached()}
        scale = g["recoilParams"]["magnitude"][0] / mags[src]["recoilParams"]["magnitude"][0]
        curve = [[u * scale, r * scale] for u, r in curve]
    return {"fps": fps, "curve": [[round(u, 4), round(r, 4)] for u, r in curve], "bullets": gid not in SPRAY_BULLETS,
            "source": src}


_cache = []


def load_cached():
    if not _cache:
        root = kv3.loads(open(VDATA, encoding="utf-8").read())
        for gid in CAMERA_CURVES.values():
            w = kv3.resolve(root, f"weapon_{gid}_prefab")
            _cache.append({"id": gid, "recoilParams": {"magnitude": pair(w["m_flRecoilMagnitude"])}})
    return _cache


def shell_reload(folder):
    """CS2 shotgun reload phases from the reload clip's sync track: intro, a loop that loads one shell (ammo added at
    the WPN_RELOAD_ADD_AMMO event), outro. Seconds; None if convert.py hasn't dumped the clip yet."""
    name = folder.split("/")[-1]
    path = next((p for p in sorted(glob.glob(os.path.join(DUMPS, f"{name}__reload_*.clip.txt")))
                 if "empty" not in p and "lgcy" not in p), None)
    if not path:
        return None
    t = open(path, encoding="utf-8").read()
    dur = float(re.search(r"^	m_flDuration = ([\d.e-]+)", t, re.M).group(1))
    sync = {m.group(1): (float(m.group(2)), float(m.group(3))) for m in re.finditer(
        r'm_ID = "(WPN_RELOAD_\w+)"\s*m_startTime =\s*\{\s*m_flValue = ([\d.e-]+)\s*\}\s*m_duration =\s*\{\s*m_flValue = ([\d.e-]+)', t)}
    add = None
    for ev in t.split("_class = ")[1:]:
        if 'm_ID = "WPN_RELOAD_ADD_AMMO"' in ev:
            add = float(re.search(r"m_flStartTime =\s*\{\s*m_flValue = ([\d.e-]+)", ev).group(1))
    if not {"WPN_RELOAD_INTRO", "WPN_RELOAD_LOOP", "WPN_RELOAD_OUTRO"} <= sync.keys() or add is None:
        return None
    loop0, loop = sync["WPN_RELOAD_LOOP"]
    return {"clip": os.path.basename(path).split("__")[1].replace(".clip.txt", ""), "duration": round(dur, 4),
            "intro": round(loop0 * dur, 4), "loop": round(loop * dur, 4), "add": round((add - loop0) * dur, 4),
            "outro": round(sync["WPN_RELOAD_OUTRO"][1] * dur, 4)}


# the clips anim_times reads, as convert.ROLE_RULES picks them: (dump name prefixes, disqualifying substrings)
RELOAD_CLIP = (("reload_",), ("empty", "reload2", "lgcy"))
RELOAD_EMPTY_CLIP = (("reload_empty_", "empty_reload_"), ("reload2",))
SILENCER_CLIPS = ((("silencer_attach_",), ()), (("silencer_detach_",), ()))


def _dump(name, rule):
    """A clip's dump text (and its duration) by its role's naming rule, or (None, 0)."""
    prefixes, bad = rule
    for pre in prefixes:
        for p in sorted(glob.glob(os.path.join(DUMPS, f"{name}__{pre}*.clip.txt"))):
            if not any(b in os.path.basename(p).split("__")[1] for b in bad):
                t = open(p, encoding="utf-8").read()
                return t, float(re.search(r"^	m_flDuration = ([\d.e-]+)", t, re.M).group(1))
    return None, 0.0


def _event(text, event_id):
    """Where a clip's ID event starts, as a fraction of the clip, or None."""
    for ev in (text or "").split("_class = ")[1:]:
        if f'm_ID = "{event_id}"' in ev:
            return float(re.search(r"m_flStartTime =\s*\{\s*m_flValue = ([\d.e-]+)", ev).group(1))
    return None


def anim_times(g, folder):
    """What the mod times by the viewmodel animation, from CS2's own clip events: a magazine reload puts the rounds in
    at its WPN_RELOAD_ADD_AMMO event ("reloadAmmoAt": fractions of the reload, [reload, empty reload]; the mod plays the
    clip over reloadTime), and a silencer goes on / off at WPN_SILENCER_ATTACH / _DETACH ("silencerAt": seconds,
    [attach, detach]). Left out when the clips or events aren't there (the mod then waits for the end)."""
    name = folder.split("/")[-1]
    if not g["singleShellReload"] and not g["exhaustible"]:
        full = _event(_dump(name, RELOAD_CLIP)[0], "WPN_RELOAD_ADD_AMMO")
        empty_text = _dump(name, RELOAD_EMPTY_CLIP)[0]
        empty = _event(empty_text, "WPN_RELOAD_ADD_AMMO") if empty_text else full  # no empty clip: the reload one plays
        if full is not None or empty is not None:
            g["reloadAmmoAt"] = [None if f is None else round(f, 4) for f in (full, empty)]
    if g["silencer"] == "detachable":
        at = []
        for rule, ev in zip(SILENCER_CLIPS, ("WPN_SILENCER_ATTACH", "WPN_SILENCER_DETACH")):
            text, dur = _dump(name, rule)
            f = _event(text, ev)
            at.append(round(f * dur, 4) if f is not None else None)
        if any(a is not None for a in at):
            g["silencerAt"] = at


# Weapon table versions (build_pack.STEPS "weapontable"): what each changed, for an update that patches the pack's own
# table instead of making it again (that needs every clip and particle the full build exported). 2: anim_times.
def _patch_anim_times(table, cs2):
    """Dump only the clips anim_times reads (a few per gun, straight from the VPK), then add its fields."""
    import cosmetics
    root = kv3.loads(open(VDATA, encoding="utf-8").read())
    files = cosmetics.listing(cs2)
    os.makedirs(DUMPS, exist_ok=True)
    for g in table["weapons"]:
        folder = clip_dir(g["id"], kv3.resolve(root, f"weapon_{g['id']}_prefab"))
        prefix = f"animation/anims/viewmodel/{folder}/"
        name = folder.split("/")[-1]
        clips = sorted(f[len(prefix):-len(".vnmclip_c")] for f in files
                       if f.startswith(prefix) and f.endswith(".vnmclip_c") and "/" not in f[len(prefix):] and "+" not in f)
        for prefixes, bad in (RELOAD_CLIP, RELOAD_EMPTY_CLIP, *SILENCER_CLIPS):
            hit = next((c for pre in prefixes for c in clips if c.startswith(pre) and not any(b in c for b in bad)), None)
            out = os.path.join(DUMPS, f"{name}__{hit}.clip.txt")
            if hit and not os.path.exists(out):
                with open(out, "w", encoding="utf-8") as f:
                    f.write(cosmetics.vrf(cs2, "-b", "DATA", "-f", prefix + hit + ".vnmclip_c"))
        anim_times(g, folder)


CHANGED = {2: _patch_anim_times}


def patch(out, since, cs2):
    """An update's weapon table: the pack's own, with what each newer version changed added to it."""
    old, new = (int(v) for v in since.split(":"))
    table = json.load(open(out, encoding="utf-8"))
    for v in range(old + 1, new + 1):
        if v in CHANGED:
            CHANGED[v](table, cs2)
    json.dump(table, open(out, "w", encoding="utf-8"), indent=1)
    print(f"patched {out}: weapon table version {old} -> {new}")


# Flash systems whose fire layer the extractor can't read (built from other operators): drawn like the closest family
FLASH_FALLBACK = {"weapon_muzzleflash_snip_fps": "uweapon_muzflsh_riffle_lrg_fps", "weapon_muzzleflash_snip_ar_fps": "uweapon_muzflsh_riffle_lrg_fps",
                  "uweapon_muzflsh_mach_fps": "uweapon_muzflsh_riffle_lrg_fps", "uweapon_muzflsh_aug_fps": "uweapon_muzflsh_riffle_fps",
                  "uweapon_muzzleflash_pist_revolver_fps": "uweapon_muzflsh_deagle_fps", "weapon_taser_glow": None}
FLASHES, TRACERS = {}, {}
# Not CS2's values (by request): the SG 553 / AUG tracer, hard to see in Minecraft, made four times as thick as CS2's
# (radius 2: a "rope" bright only in the middle fifth of its width and the last fifth of its length, so in first person
# only its far head shows), never thinner than 1.5% of half the screen height (a bright core of ~4 px at 1440p at any
# range), and as long as the other rifles' streak (1200 units; CS2's is 100).
TRACER_OVERRIDES = {"assrifle_aug": {"radius": 8.0, "maxLength": 1200.0, "minSize": 0.015}}


def shot_fx(w, folder):
    """Tracer type and frequency, muzzle points (third person, units from the eye: forward, left, up) and the
    first-person muzzle flash per silencer state, from the vdata and the shoot clip's particle events."""
    name = folder.split("/")[-1]
    shoot = next((os.path.basename(p).split("__")[1][:-len(".clip.txt")] for pre in ("shoot1_", "shoot_right1_")
                  for p in sorted(glob.glob(os.path.join(DUMPS, f"{name}__{pre}*.clip.txt"))) if "lgcy" not in p), None)
    flashes = muzzle.shoot_flashes(DUMPS, name, shoot) if shoot else {}
    out = {}
    for state, vpcf in flashes.items():
        key = os.path.basename(vpcf)[:-len(".vpcf")]
        src = FLASH_FALLBACK.get(key, key)
        if src is None:
            continue
        if src not in FLASHES:
            FLASHES[src] = muzzle.summary(os.path.dirname(vpcf) + "/" + src + ".vpcf")
            FLASHES[src]["silenced"] = "muzsilenced" in src
        out["flash" if state == "unsilenced" else "flashSilenced"] = src
    tr = w.get("m_szTracerParticle")
    if tr:
        key = os.path.basename(tr)[:-len(".vpcf")].replace("weapon_tracers_", "")
        if key == "taser":  # the Zeus fires a wire, not a tracer
            return out | {"tracer": None, "tracerFrequency": [0, 0], "muzzle": [w.get("m_vecMuzzlePos0", [0.0, 0.0, 0.0])] * 2}
        if key not in TRACERS:
            TRACERS[key] = muzzle.tracer(tr)
            if TRACERS[key]:
                TRACERS[key].update(TRACER_OVERRIDES.get(key, {}))
        out["tracer"] = key if TRACERS[key] else None
    out["tracerFrequency"] = [int(x) for x in pair(w.get("m_nTracerFrequency", 0))]
    out["muzzle"] = [w.get("m_vecMuzzlePos0", [0.0, 0.0, 0.0]), w.get("m_vecMuzzlePos1") or w.get("m_vecMuzzlePos0", [0.0, 0.0, 0.0])]
    return out


def measured_recoil(gid):
    """Measured spray (measure/results/<gid>_spray.json) or None."""
    p = os.path.join(RESULTS, f"{gid}_spray.json")
    if not os.path.exists(p):
        return None
    d = json.load(open(p))
    shots = d["shots"]
    if not shots or len(shots) != d.get("expected", len(shots)):
        return None
    shots = [[0.0, 0.0]] + shots[1:]  # shot 1 is the crosshair by definition
    times = d.get("shot_times") or [i * 0.1 for i in range(len(shots))]
    rec = d.get("recovery") or {}
    return {"pattern": shots, "times": times,
            "decayExp": rec.get("exp", 1.41), "decayLinCamera": rec.get("lin_camera", 10.64)}


ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "countercraft")


def write_item_assets(guns):
    """Lang names and placeholder item models (the converted CS2 pack overrides the models with real icons)."""
    lang_path = os.path.join(ASSETS, "lang", "en_us.json")
    lang = json.load(open(lang_path, encoding="utf-8")) if os.path.exists(lang_path) else {}
    lang["itemGroup.countercraft.weapons"] = "Counter-Craft 2"
    for g in guns:
        lang[f"item.countercraft.{g['id']}"] = g["name"]
        os.makedirs(os.path.join(ASSETS, "items"), exist_ok=True)
        os.makedirs(os.path.join(ASSETS, "models", "item"), exist_ok=True)
        json.dump({"model": {"type": "minecraft:model", "model": f"countercraft:item/{g['id']}"}},
                  open(os.path.join(ASSETS, "items", f"{g['id']}.json"), "w"), indent=2)
        placeholder = {"primary": "minecraft:item/crossbow_standby", "knife": "minecraft:item/iron_sword",
                       "grenade": "minecraft:item/firework_star"}.get(g.get("slot") or g.get("kind"), "minecraft:item/iron_hoe")
        json.dump({"parent": "minecraft:item/handheld", "textures": {"layer0": placeholder}},
                  open(os.path.join(ASSETS, "models", "item", f"{g['id']}.json"), "w"), indent=2)
    json.dump(lang, open(lang_path, "w", encoding="utf-8"), indent=2, ensure_ascii=False)


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None, help="write the weapon table here (the asset pack build); default: the dev copy, with "
                                                "the item models and names in the mod's sources")
    ap.add_argument("--since", default=None, help="OLD:NEW: an update: patch the table at --out from version OLD to NEW (CHANGED)")
    ap.add_argument("--cs2", default=None, help="Counter-Strike 2's folder (what --since reads clips from)")
    args = ap.parse_args()
    out = args.out or OUT
    if args.since:
        return patch(out, args.since, os.path.join(args.cs2, "game", "csgo", "pak01_dir.vpk"))
    guns = load()
    melee, grenades = load_extra()
    if out == OUT:
        write_item_assets(guns + melee + grenades)
    os.makedirs(os.path.dirname(out), exist_ok=True)

    def strip(items):
        return [{k: v for k, v in g.items() if not k.startswith("_")} for g in items]

    json.dump({"source": "CS2 weapons.vdata (+ measure/results)", "weapons": strip(guns), "melee": strip(melee),
               "grenades": strip(grenades), "flashes": FLASHES, "tracers": TRACERS}, open(out, "w"), indent=1)
    measured = [g["id"] for g in guns if g["recoil"]]
    print(f"wrote {out}: {len(guns)} weapons, measured recoil for {len(measured)}: {measured}")


if __name__ == "__main__":
    sys.exit(main())
