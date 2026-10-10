"""List each control parameter of compiled CS2 graphs with its type and every ID / threshold it is compared with."""
import sys
from collections import defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from graph_print import load

usage = defaultdict(set)
types = {}
for path in sys.argv[1:]:
    d = load(path)
    nodes, params = d["m_nodes"], d["m_controlParameterIDs"]
    for i, p in enumerate(params):
        types[p] = nodes[i]["_class"].replace("CNmControlParameter", "").replace("Node::CDefinition", "")
    for n in nodes:
        c = n["_class"]
        src = n.get("m_nInputValueNodeIdx", -1)
        if src is None or src < 0 or src >= len(params):
            continue
        p = params[src]
        if "IDComparison" in c:
            usage[p] |= {f"{n['m_comparison'][:1]}:{x}" for x in n["m_comparisionIDs"]}
        elif "FloatComparison" in c:
            usage[p].add(f"{n.get('m_comparison')} {n.get('m_flComparisonValue')}")
        elif "FloatRangeComparison" in c:
            usage[p].add(f"range {n.get('m_range')} incl={n.get('m_bIsInclusiveCheck')}")
        else:
            usage[p].add(c.replace("CNm", "").replace("Node::CDefinition", ""))
for p in sorted(types):
    print(f"{p} ({types[p]}): {sorted(usage.get(p, []))}")
