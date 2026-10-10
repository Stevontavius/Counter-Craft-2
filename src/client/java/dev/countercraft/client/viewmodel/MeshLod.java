package dev.countercraft.client.viewmodel;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Distance level of detail for the CPU-drawn third-person guns: a mesh simplified by vertex clustering.
 * Vertices that share a grid cell (and the same texture spot and facing, so UV seams and hard edges survive) become one,
 * and triangles that collapse go. Only the triangle list changes: the kept vertices are the mesh's own, so skinning and
 * texturing are untouched. Used only at distances where a cell is under about a pixel on screen (third-person guns;
 * agents are already so even that clustering hardly thins them).
 */
public final class MeshLod {
	/** Grid cells (metres) of the simplified levels; level 0 is the full mesh. */
	public static final float[] CELLS = {0f, 0.012f, 0.03f, 0.07f};
	/** From how far (blocks) each level is used: a cell is under ~1 px at 1080p and a 70 degree FOV there. */
	public static final float[] FROM = {0f, 10f, 24f, 50f};

	/** A level's triangle list and the vertices it uses (only those get transformed). */
	public record Lod(int[] indices, int[] used) {
	}

	private static final Map<int[], Lod[]> CACHE = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

	private MeshLod() {
	}

	/** The level for a mesh drawn {@code distance} blocks away. */
	public static int level(double distance) {
		int l = 0;
		for (int i = 1; i < FROM.length; i++) {
			if (distance >= FROM[i]) {
				l = i;
			}
		}
		return l;
	}

	/** The mesh {@code idx} at {@code level} (level 0: the mesh itself, every vertex), cached per mesh. */
	public static Lod of(int[] idx, float[] pos, float[] nrm, float[] uv, int vertexCount, int level) {
		Lod[] levels = CACHE.computeIfAbsent(idx, k -> new Lod[CELLS.length]);
		Lod lod = levels[level];
		if (lod == null) {
			int[] tris = level <= 0 ? idx : simplify(idx, pos, nrm, uv, vertexCount, CELLS[level]);
			lod = new Lod(tris, level <= 0 ? null : used(tris, vertexCount));
			levels[level] = lod;
		}
		return lod;
	}

	/** The triangle list of {@code idx} at {@code level}. */
	public static int[] indices(int[] idx, float[] pos, float[] nrm, float[] uv, int vertexCount, int level) {
		return of(idx, pos, nrm, uv, vertexCount, level).indices();
	}

	private static int[] used(int[] tris, int vertexCount) {
		boolean[] on = new boolean[vertexCount];
		int n = 0;
		for (int i : tris) {
			if (!on[i]) {
				on[i] = true;
				n++;
			}
		}
		int[] out = new int[n];
		int k = 0;
		for (int v = 0; v < vertexCount; v++) {
			if (on[v]) {
				out[k++] = v;
			}
		}
		return out;
	}

	/** Vertex clustering on a {@code cell}-metre grid. */
	public static int[] simplify(int[] idx, float[] pos, float[] nrm, float[] uv, int vertexCount, float cell) {
		int[] rep = new int[vertexCount];
		Map<Key, Integer> first = new HashMap<>();
		float inv = 1f / cell;
		for (int v = 0; v < vertexCount; v++) {
			Key k = new Key((int) Math.floor(pos[v * 3] * inv), (int) Math.floor(pos[v * 3 + 1] * inv), (int) Math.floor(pos[v * 3 + 2] * inv),
					// texture spot: 1/128 of the texture, so UV seams stay where they are
					(int) Math.floor(uv[v * 2] * 128f), (int) Math.floor(uv[v * 2 + 1] * 128f),
					// facing: one of 26 directions, so hard edges and thin double-sided parts don't fold together
					dir(nrm[v * 3]) * 9 + dir(nrm[v * 3 + 1]) * 3 + dir(nrm[v * 3 + 2]));
			Integer r = first.putIfAbsent(k, v);
			rep[v] = r == null ? v : r;
		}
		int[] out = new int[idx.length];
		int n = 0;
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int a = rep[idx[t]], b = rep[idx[t + 1]], c = rep[idx[t + 2]];
			if (a == b || b == c || a == c) {
				continue; // collapsed
			}
			// the same triangle twice (two that merged into one): keep one
			int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), mid = a + b + c - lo - hi;
			long key = ((long) lo * 1_000_003L + mid) * 1_000_003L + hi;
			if (!seen.add(key * 2 + (orientation(a, b, c) ? 1 : 0))) {
				continue;
			}
			out[n++] = a;
			out[n++] = b;
			out[n++] = c;
		}
		return Arrays.copyOf(out, n);
	}

	/** Whether (a, b, c) is an even permutation of its sorted order (keeps opposite-facing duplicates apart). */
	private static boolean orientation(int a, int b, int c) {
		return a < b ? (b < c || c < a) : (b < c && c < a);
	}

	private static int dir(float n) {
		return n > 0.5f ? 2 : n < -0.5f ? 0 : 1;
	}

	private record Key(int x, int y, int z, int u, int v, int n) {
	}
}
