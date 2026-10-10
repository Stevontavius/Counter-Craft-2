"""Minimal Valve KeyValues (KV1 text) parser: items_game.txt, csgo_english.txt.

Blocks become dicts; a key that appears more than once in a block keeps every value in a list (items_game repeats
section names, e.g. several "items" blocks, and loot lists repeat item keys). Conditionals ([$WIN32]) are dropped.
"""


def _tokens(text):
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in " \t\r\n":
            i += 1
        elif c == "/" and text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c in "{}":
            yield c
            i += 1
        elif c == "[":
            j = text.find("]", i)  # conditional, ignored
            i = n if j < 0 else j + 1
        elif c == '"':
            j = i + 1
            buf = []
            while j < n and text[j] != '"':
                if text[j] == "\\" and j + 1 < n:
                    buf.append({"n": "\n", "t": "\t", "\\": "\\", '"': '"'}.get(text[j + 1], text[j + 1]))
                    j += 2
                else:
                    buf.append(text[j])
                    j += 1
            yield "".join(buf)
            i = j + 1
        else:
            j = i
            while j < n and text[j] not in " \t\r\n{}\"":
                j += 1
            yield text[i:j]
            i = j


def _put(d, k, v):
    if k in d:
        if isinstance(d[k], list) and getattr(d[k], "_multi", False):
            d[k].append(v)
        else:
            m = _Multi([d[k], v])
            d[k] = m
    else:
        d[k] = v


class _Multi(list):
    _multi = True


def loads(text):
    root = {}
    stack = [root]
    key = None
    for t in _tokens(text):
        if t == "{":
            d = {}
            _put(stack[-1], key, d)
            stack.append(d)
            key = None
        elif t == "}":
            stack.pop()
            key = None
        elif key is None:
            key = t
        else:
            _put(stack[-1], key, t)
            key = None
    return root


def each(v):
    """A value as a list (one value, or every value of a repeated key)."""
    return list(v) if getattr(v, "_multi", False) else [v]


def merged(v):
    """Repeated blocks merged into one dict (later keys win)."""
    out = {}
    for d in each(v):
        if isinstance(d, dict):
            out.update(d)
    return out
