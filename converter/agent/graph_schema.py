"""Union of fields per node class across compiled graphs, with example values (interpreter reference)."""
import sys
from collections import defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from graph_print import load

fields = defaultdict(dict)
count = defaultdict(int)
for path in sys.argv[1:]:
    try:
        d = load(path)
    except Exception as e:
        print("skip", path, e); continue
    for n in d["m_nodes"]:
        c = n["_class"].replace("CNm", "").replace("Node::CDefinition", "")
        count[c] += 1
        for k, v in n.items():
            if k == "_class":
                continue
            s = str(v)
            vals = fields[c].setdefault(k, set())
            if len(vals) < 4:
                vals.add(s[:140])
for c in sorted(fields, key=lambda c: -count[c]):
    print(f"== {c} ({count[c]})")
    for k, v in fields[c].items():
        print(f"   {k}: {' | '.join(sorted(v))}")
