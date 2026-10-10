package dev.countercraft.client.viewmodel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * A baked first-person viewmodel (.cvm, written by converter/convert.py): skinned mesh parts plus, per animation
 * clip, one 3x4 skinning matrix per bone per frame, already in Minecraft view space (metres, -Z forward), and
 * optionally the bone names.
 */
public final class ViewmodelAsset {
	public final int boneCount;
	public final Part[] parts;
	public final Map<String, ClipData> clips = new HashMap<>();
	/** Bone name -> index; empty for files converted before the BONE trailer existed. */
	public final Map<String, Integer> bones = new HashMap<>();
	/**
	 * An ironsight scope's lens (viewmodels/&lt;gun&gt;_lens.json): {bone, centre xyz, axis xyz (away from the eye),
	 * bore radius} in the gun's model space, or null. While aiming, the gun behind it isn't drawn (CS2's lens shows the
	 * view through the whole eyecup).
	 */
	public float[] lens;
	private final Map<String, float[]> centroids = new HashMap<>();

	public record Part(Identifier texture, int vertexCount, float[] pos, float[] nrm, float[] uv, byte[] joints, byte[] weights, int[] indices) {
	}

	/** {@code mats} is frames x bones x 12 floats (row-major 3x4). */
	public record ClipData(String name, float duration, float fps, int frames, float[] mats, Event[] events) {
	}

	public record Event(float time, Identifier sound) {
	}

	private ViewmodelAsset(int boneCount, Part[] parts) {
		this.boneCount = boneCount;
		this.parts = parts;
	}

	public static ViewmodelAsset read(InputStream in) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		b.get(magic);
		if (!"CVM1".equals(new String(magic, StandardCharsets.US_ASCII))) {
			throw new IOException("not a CVM1 viewmodel");
		}
		int bones = b.getInt();
		Part[] parts = readParts(b);
		ViewmodelAsset asset = new ViewmodelAsset(bones, parts);
		int clipCount = b.getInt();
		for (int c = 0; c < clipCount; c++) {
			String name = str(b);
			float duration = b.getFloat();
			float fps = b.getFloat();
			int frames = b.getInt();
			float[] mats = floats(b, frames * bones * 12);
			Event[] events = new Event[b.getInt()];
			for (int e = 0; e < events.length; e++) {
				float t = b.getFloat();
				events[e] = new Event(t, Identifier.parse(str(b)));
			}
			asset.clips.put(name, new ClipData(name, duration, fps, frames, mats, events));
		}
		if (b.remaining() >= 8) {
			byte[] tag = bytes(b, 4);
			if ("BONE".equals(new String(tag, StandardCharsets.US_ASCII))) {
				int n = b.getInt();
				for (int i = 0; i < n; i++) {
					asset.bones.put(str(b), i);
				}
			}
		}
		return asset;
	}

	private static Part[] readParts(ByteBuffer b) {
		Part[] parts = new Part[b.getInt()];
		for (int p = 0; p < parts.length; p++) {
			Identifier tex = Identifier.parse(str(b));
			int n = b.getInt();
			float[] pos = floats(b, n * 3);
			float[] nrm = floats(b, n * 3);
			float[] uv = floats(b, n * 2);
			byte[] joints = bytes(b, n * 4);
			byte[] weights = bytes(b, n * 4);
			int[] idx = new int[b.getInt()];
			b.asIntBuffer().get(idx);
			b.position(b.position() + idx.length * 4);
			parts[p] = new Part(tex, n, pos, nrm, uv, joints, weights, idx);
		}
		return parts;
	}

	/**
	 * An agent's first-person arms (viewmodels/arms_&lt;agent&gt;.cva): CVM parts skinned to the viewmodel arms bones, drawn
	 * in place of the .cvm's own arms (CS2 draws the agent's firstperson gloves + sleeves).
	 */
	public static Part[] readArms(InputStream in) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		b.get(magic);
		if (!"CVA1".equals(new String(magic, StandardCharsets.US_ASCII))) {
			throw new IOException("not CVA1 arms");
		}
		return readParts(b);
	}

	/** Whether a .cvm part is the baked-in arms (textures viewmodel/arms_*), which an agent's arms replace. */
	public static boolean isArms(Part p) {
		return p.texture().getPath().startsWith("textures/viewmodel/arms_");
	}

	/**
	 * Rest-pose centre of the vertices a bone mostly drives (e.g. the molotov's rag), or null if it drives none. Move it
	 * with the bone's pose matrix to get where that part is now.
	 */
	public float[] boneCentroid(String bone) {
		return centroids.computeIfAbsent(bone, name -> {
			Integer bi = bones.get(name);
			if (bi == null) {
				return null;
			}
			double x = 0, y = 0, z = 0;
			int n = 0;
			for (Part p : parts) {
				for (int v = 0; v < p.vertexCount(); v++) {
					int best = 0;
					for (int k = 1; k < 4; k++) {
						if (Byte.toUnsignedInt(p.weights()[v * 4 + k]) > Byte.toUnsignedInt(p.weights()[v * 4 + best])) {
							best = k;
						}
					}
					if (Byte.toUnsignedInt(p.joints()[v * 4 + best]) == bi) {
						x += p.pos()[v * 3];
						y += p.pos()[v * 3 + 1];
						z += p.pos()[v * 3 + 2];
						n++;
					}
				}
			}
			return n == 0 ? null : new float[] {(float) (x / n), (float) (y / n), (float) (z / n)};
		});
	}

	/** Where a rest-pose point driven by {@code bone} is in this pose (view space), or null. */
	public float[] posed(float[] pose, String bone, float[] rest) {
		Integer bi = bones.get(bone);
		if (bi == null || rest == null) {
			return null;
		}
		int o = bi * 12;
		return new float[] {
				pose[o] * rest[0] + pose[o + 1] * rest[1] + pose[o + 2] * rest[2] + pose[o + 3],
				pose[o + 4] * rest[0] + pose[o + 5] * rest[1] + pose[o + 6] * rest[2] + pose[o + 7],
				pose[o + 8] * rest[0] + pose[o + 9] * rest[1] + pose[o + 10] * rest[2] + pose[o + 11]};
	}

	private static String str(ByteBuffer b) {
		byte[] s = new byte[Short.toUnsignedInt(b.getShort())];
		b.get(s);
		return new String(s, StandardCharsets.UTF_8);
	}

	private static float[] floats(ByteBuffer b, int n) {
		float[] f = new float[n];
		b.asFloatBuffer().get(f);
		b.position(b.position() + n * 4);
		return f;
	}

	private static byte[] bytes(ByteBuffer b, int n) {
		byte[] a = new byte[n];
		b.get(a);
		return a;
	}
}
