"""Minimal KV3 text reader (VRF's decompiled .vdata / .vsndevts): objects, arrays, strings, numbers, bools, null,
typed strings like resource_name:"..." / soundevent:"...", and multi-line \"\"\" strings."""
import re

_TOKEN = re.compile(r'''
    (?P<ws>\s+|//[^\n]*|/\*.*?\*/|<!--.*?-->)
  | (?P<mstr>"""(?:.|\n)*?""")
  | (?P<typed>[A-Za-z_][\w]*:"(?:[^"\\]|\\.)*")
  | (?P<str>"(?:[^"\\]|\\.)*")
  | (?P<punct>[{}\[\]=,])
  | (?P<word>[^\s{}\[\]=,"]+)
''', re.S | re.X)


def _tokens(text):
    pos = 0
    while pos < len(text):
        m = _TOKEN.match(text, pos)
        if not m:
            raise ValueError(f"kv3: unexpected text at {pos}: {text[pos:pos + 40]!r}")
        pos = m.end()
        kind = m.lastgroup
        if kind == "ws":
            continue
        yield kind, m.group(kind)


def _atom(kind, tok):
    if kind == "mstr":
        return tok[3:-3]
    if kind == "str":
        return bytes(tok[1:-1], "utf-8").decode("unicode_escape") if "\\" in tok else tok[1:-1]
    if kind == "typed":
        return tok[tok.index(":") + 2:-1]
    if tok == "true":
        return True
    if tok == "false":
        return False
    if tok == "null":
        return None
    try:
        return int(tok)
    except ValueError:
        try:
            return float(tok)
        except ValueError:
            return tok


def loads(text):
    toks = list(_tokens(text))
    i = 0

    def value():
        nonlocal i
        kind, tok = toks[i]
        i += 1
        if tok == "{" and kind == "punct":
            obj = {}
            while toks[i][1] != "}":
                key = _atom(*toks[i])
                i += 1
                assert toks[i][1] == "=", f"expected = after {key}"
                i += 1
                obj[str(key)] = value()
                if toks[i][1] == ",":
                    i += 1
            i += 1
            return obj
        if tok == "[" and kind == "punct":
            arr = []
            while toks[i][1] != "]":
                arr.append(value())
                if toks[i][1] == ",":
                    i += 1
            i += 1
            return arr
        return _atom(kind, tok)

    return value()


def resolve(root, name, _seen=None):
    """Entry `name` with its `_base` chain merged in (child keys win; nested objects merge)."""
    _seen = _seen or set()
    if name in _seen:
        raise ValueError(f"kv3: _base cycle at {name}")
    _seen.add(name)
    entry = root[name]
    base = entry.get("_base")
    out = resolve(root, base, _seen) if base and base in root else {}
    return _merge(out, entry)


def _merge(a, b):
    out = dict(a)
    for k, v in b.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _merge(out[k], v)
        else:
            out[k] = v
    return out
