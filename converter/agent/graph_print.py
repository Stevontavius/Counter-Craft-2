"""Print a compiled CS2 animation graph (VRF `-b DATA` dump of a .vnmgraph_c) as an indented tree from the root,
with node paths, clip names, parameters and transition settings. Reading aid for converter/agent."""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import kv3  # noqa: E402

CHILD_KEYS = ("m_nChildNodeIdx", "m_nBaseNodeIdx", "m_nInputNodeIdx", "m_nInputValueNodeIdx", "m_nSourceNodeIdx",
              "m_nTargetNodeIdx", "m_nTransitionNodeIdx", "m_nStateNodeIdx", "m_nConditionNodeIdx", "m_nParameterNodeIdx",
              "m_nValueNodeIdx", "m_nVerticalAngleNodeIdx", "m_nHorizontalAngleNodeIdx", "m_nWeightValueNodeIdx",
              "m_nBoneMaskValueNodeIdx", "m_nLeftTargetNodeIdx", "m_nRightTargetNodeIdx")


def load(path):
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    text = text[text.index('--- Data for block "DATA" ---'):]
    text = text[text.index("-->") + 3:]
    return kv3.loads(text)


def main():
    d = load(sys.argv[1])
    nodes = d["m_nodes"]
    paths = d["m_nodePaths"]
    res = d.get("m_resources", [])
    params = d["m_controlParameterIDs"]
    maxdepth = int(sys.argv[2]) if len(sys.argv) > 2 else 40
    seen = set()

    def short(v):
        if isinstance(v, float):
            return f"{v:g}"
        return v

    def desc(i):
        n = nodes[i]
        cls = n["_class"].replace("CNm", "").replace("Node::CDefinition", "")
        extra = []
        for k, v in n.items():
            if k in ("_class", "m_nNodeIdx") or k in CHILD_KEYS:
                continue
            if k == "m_nDataSlotIdx" and isinstance(v, int) and 0 <= v < len(res):
                extra.append("clip=" + str(res[v]).split("/")[-1])
                continue
            if isinstance(v, (list, dict)) and len(str(v)) > 160:
                continue
            extra.append(f"{k[2:] if k.startswith('m_') else k}={short(v)}")
        p = paths[i] if i < len(paths) else ""
        if i < len(params):
            p = "PARAM " + p
        return f"[{i}] {cls} '{p}' " + " ".join(str(e) for e in extra)

    def kids(n):
        out = []
        for k in CHILD_KEYS:
            v = n.get(k)
            if isinstance(v, int) and v >= 0:
                out.append((k[3:], v))
        for k, v in n.items():
            if isinstance(v, list):
                for j, e in enumerate(v):
                    if isinstance(e, int) and k in ("m_childNodeIndices", "m_optionNodeIndices", "m_conditionNodeIndices",
                                                      "m_sourceNodeIndices", "m_inputNodeIndices"):
                        out.append((f"{k[3:]}[{j}]", e))
                    elif isinstance(e, dict):
                        for kk, vv in e.items():
                            if kk.endswith("NodeIdx") and isinstance(vv, int) and vv >= 0:
                                out.append((f"{k[2:]}[{j}].{kk[3:]}", vv))
                            if kk == "m_transitionDefinitions":
                                for t, tr in enumerate(vv):
                                    info = {a[2:]: short(b) for a, b in tr.items() if not a.endswith("Idx")}
                                    out.append((f"state[{j}]->T{t} {info}", tr.get("m_nTargetStateIdx", -1) * 0 - 1))
                                    for a in ("m_nConditionNodeIdx", "m_nTransitionNodeIdx"):
                                        if tr.get(a, -1) >= 0:
                                            out.append((f"state[{j}]->T{t}->state{tr.get('m_nTargetStateIdx')}.{a[3:]}", tr[a]))
        return out

    def walk(i, depth, label):
        pad = "  " * depth
        if i < 0:
            print(f"{pad}{label}")
            return
        print(f"{pad}{label}: {desc(i)}")
        if i in seen or depth > maxdepth:
            if i in seen and kids(nodes[i]):
                print(f"{pad}  (see above)")
            return
        seen.add(i)
        for lab, c in kids(nodes[i]):
            walk(c, depth + 1, lab)

    print("resources:", len(res), "params:", len(params))
    walk(d["m_nRootNodeIdx"], 0, "ROOT")
    if "m_referencedGraphSlots" in d:
        print("referenced graph slots:", [(s.get("m_nNodeIdx"), s.get("m_dataSlotIdx")) for s in d["m_referencedGraphSlots"]][:80])


if __name__ == "__main__":
    main()
