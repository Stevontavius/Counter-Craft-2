"""CS2 player ragdolls: each agent model's physics (the vmdl's PHYS block: bodies on bones with capsule / sphere shapes,
masses and damping, and the ragdoll joints with their swing / twist limits) as agents/ragdoll/<agent>.json for the
mod's ragdoll simulation (dev.countercraft.client.ragdoll).

Units: CS2 physics is in inches in each body's bone frame; the mod's agent skeleton keeps those bone frames (only the
root axes differ: glTF = Source y, z, x), so shapes and joint frames convert by x 0.0254 alone. Bones are matched to the
animation skeleton by name, case-insensitively (physics "arm_upper_l" = skeleton "arm_upper_L")."""
import json
from pathlib import Path

import kv3

IN = 0.0254


def _block(dump):
    """The KV3 text of a `-b PHYS` dump."""
    start = dump.find('Data for block')
    head = dump.find("-->", start)
    i = dump.find("{", head if head >= 0 else start)
    return dump[i:]


def _frame(f):
    # [x, y, z, scale, qx, qy, qz, qw]: a position (inches) and a rotation in the body's frame
    return {"p": [f[0] * IN, f[1] * IN, f[2] * IN], "q": [f[4], f[5], f[6], f[7]]}


def convert_one(cs2, model, skel_bones):
    dump = cs2._run("-b", "PHYS", "-f", model)
    root = kv3.loads(_block(dump))
    data = root.get("m_parts") and root or root.get("m_partitions") or root
    names = data["m_boneNames"]
    lower = {b.lower(): b for b in skel_bones}
    bodies = []
    for i, part in enumerate(data["m_parts"]):
        bone = lower.get(names[i].lower())
        shape = part["m_rnShape"]
        shapes = []
        for c in shape.get("m_capsules") or []:
            a, b = c["m_Capsule"]["m_vCenter"]
            shapes.append({"type": "capsule", "a": [x * IN for x in a], "b": [x * IN for x in b], "r": c["m_Capsule"]["m_flRadius"] * IN})
        for s in shape.get("m_spheres") or []:
            shapes.append({"type": "sphere", "c": [x * IN for x in s["m_Sphere"]["m_vCenter"]], "r": s["m_Sphere"]["m_flRadius"] * IN})
        for h in shape.get("m_hulls") or []:
            # a convex hull: its bounds as a sphere (no hulls on CS2's player ragdolls so far)
            pts = h.get("m_Hull", {}).get("m_VertexPositions") or []
            if pts:
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                zs = [p[2] for p in pts]
                cx, cy, cz = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2
                r = max(((p[0] - cx) ** 2 + (p[1] - cy) ** 2 + (p[2] - cz) ** 2) ** 0.5 for p in pts)
                shapes.append({"type": "sphere", "c": [cx * IN, cy * IN, cz * IN], "r": r * IN})
        bodies.append({"bone": bone or names[i], "mass": part.get("m_flMass", 1.0), "linearDamping": part.get("m_flLinearDamping", 0.0),
                       "angularDamping": part.get("m_flAngularDamping", 0.0), "shapes": shapes})
    joints = []
    for j in data.get("m_joints") or []:
        joints.append({
            "type": j["m_nType"],  # 3 = revolute (hinge about the frame's x axis), 4 = ragdoll (cone + twist)
            "body1": j["m_nBody1"], "body2": j["m_nBody2"],
            "frame1": _frame(j["m_Frame1"]), "frame2": _frame(j["m_Frame2"]),
            "swing": [j["m_SwingLimit"]["m_flMin"], j["m_SwingLimit"]["m_flMax"]] if j.get("m_bEnableSwingLimit") else None,
            "twist": [j["m_TwistLimit"]["m_flMin"], j["m_TwistLimit"]["m_flMax"]] if j.get("m_bEnableTwistLimit") else None,
            "friction": j.get("m_flFriction", 0.0),
        })
    return {"bodies": bodies, "joints": joints}


def convert(cs2, assets, models, log):
    """models: {agent name: vmdl_c path}."""
    skel = json.loads((assets / "agents" / "skeleton.json").read_text(encoding="utf-8"))["bones"]
    out_dir = assets / "agents" / "ragdoll"
    out_dir.mkdir(parents=True, exist_ok=True)
    n = 0
    for name, model in models.items():
        if not (assets / "agents" / f"{name}.cam").exists():
            continue
        try:
            rd = convert_one(cs2, model, skel)
        except Exception as e:  # noqa: BLE001
            log(f"  ! ragdoll {name}: {e!r}")
            continue
        missing = [b["bone"] for b in rd["bodies"] if b["bone"] not in skel]
        if missing:
            log(f"  ! ragdoll {name}: bones not in the skeleton {missing}")
        (out_dir / f"{name}.json").write_text(json.dumps(rd), encoding="utf-8")
        n += 1
    log(f"ragdolls: {n} agents")
