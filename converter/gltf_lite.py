"""Minimal GLB reader: JSON + binary chunk, accessors as numpy arrays, node TRS and animation sampling."""
import json
import struct

import numpy as np

_COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class Glb:
    def __init__(self, path):
        data = open(path, "rb").read()
        n = struct.unpack_from("<I", data, 12)[0]
        self.j = json.loads(data[20:20 + n])
        off = 20 + n
        self.bin = data[off + 8:] if off < len(data) else b""
        self._cache = {}

    def acc(self, i):
        if i in self._cache:
            return self._cache[i]
        a = self.j["accessors"][i]
        bv = self.j["bufferViews"][a["bufferView"]]
        dt = np.dtype(_COMP[a["componentType"]])
        nc = _NCOMP[a["type"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0)
        if stride and stride != dt.itemsize * nc:
            raw = np.frombuffer(self.bin, np.uint8, count=stride * a["count"], offset=start).reshape(a["count"], stride)
            arr = raw[:, :dt.itemsize * nc].copy().view(dt).reshape(a["count"], nc)
        else:
            arr = np.frombuffer(self.bin, dt, count=a["count"] * nc, offset=start).reshape(a["count"], nc).copy()
        if a.get("normalized"):
            arr = arr.astype(np.float32) / np.iinfo(dt).max
        if nc == 16:
            arr = arr.reshape(-1, 4, 4).transpose(0, 2, 1)  # glTF is column-major
        self._cache[i] = arr
        return arr

    def node_name(self, i):
        return self.j["nodes"][i].get("name", str(i))

    def parents(self):
        p = [-1] * len(self.j["nodes"])
        for i, nd in enumerate(self.j["nodes"]):
            for c in nd.get("children", []):
                p[c] = i
        return p


def quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ], dtype=np.float64)


def trs_to_mat(t, r, s):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(r) * np.asarray(s)[None, :]
    m[:3, 3] = t
    return m


def node_trs(nd):
    if "matrix" in nd:
        raise NotImplementedError("matrix nodes")
    return (np.array(nd.get("translation", [0, 0, 0]), float),
            np.array(nd.get("rotation", [0, 0, 0, 1]), float),
            np.array(nd.get("scale", [1, 1, 1]), float))


def sample(times, values, t, path):
    """Linear sample of one channel (slerp-ish nlerp for rotations)."""
    if len(times) == 1 or t <= times[0]:
        return values[0]
    if t >= times[-1]:
        return values[-1]
    k = int(np.searchsorted(times, t)) - 1
    a = (t - times[k]) / (times[k + 1] - times[k])
    v0, v1 = values[k], values[k + 1]
    if path == "rotation":
        if np.dot(v0, v1) < 0:
            v1 = -v1
        v = v0 * (1 - a) + v1 * a
        return v / np.linalg.norm(v)
    return v0 * (1 - a) + v1 * a


class Clip:
    """One animation from a clip GLB: per-node TRS tracks."""

    def __init__(self, glb, index=0):
        self.glb = glb
        a = glb.j["animations"][index]
        self.name = a.get("name")
        self.tracks = {}  # node -> {path: (times, values)}
        self.duration = 0.0
        for ch in a["channels"]:
            s = a["samplers"][ch["sampler"]]
            times = glb.acc(s["input"])[:, 0].astype(float)
            values = glb.acc(s["output"]).astype(float)
            self.tracks.setdefault(ch["target"]["node"], {})[ch["target"]["path"]] = (times, values)
            self.duration = max(self.duration, float(times[-1]))

    def world_matrices(self, t):
        nodes = self.glb.j["nodes"]
        parents = self.glb.parents()
        local = []
        for i, nd in enumerate(nodes):
            tr, ro, sc = node_trs(nd)
            for path, (times, values) in self.tracks.get(i, {}).items():
                v = sample(times, values, t, path)
                if path == "translation":
                    tr = v
                elif path == "rotation":
                    ro = v
                elif path == "scale":
                    sc = v
            local.append(trs_to_mat(tr, ro, sc))
        world = [None] * len(nodes)

        def w(i):
            if world[i] is None:
                world[i] = local[i] if parents[i] < 0 else w(parents[i]) @ local[i]
            return world[i]

        for i in range(len(nodes)):
            w(i)
        return world
