package dev.countercraft.client.agent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.Identifier;

/**
 * An agent's third-person mesh (.cam, written by converter/agent/agent.py): the model's own bones (name, parent, the
 * animation-skeleton bone that drives it or -1, rest world matrix in model space) and its skinned mesh parts.
 */
public final class AgentModel {
	public final String[] names;
	public final int[] parents;
	/** Animation skeleton bone driving each model bone, or -1 for helper bones (twist, scapula, eyes...). */
	public final int[] anim;
	/** Rest world matrices, 3x4 row-major, model space (metres, X left, Y up, Z forward). */
	public final float[] rest;
	/** Inverse rest matrices. */
	public final float[] invRest;
	public final Part[] parts;

	public record Part(Identifier texture, String mesh, int vertexCount, float[] pos, float[] nrm, float[] uv, byte[] joints, byte[] weights,
			int[] indices) {
	}

	private AgentModel(String[] names, int[] parents, int[] anim, float[] rest, Part[] parts) {
		this.names = names;
		this.parents = parents;
		this.anim = anim;
		this.rest = rest;
		this.parts = parts;
		invRest = new float[rest.length];
		for (int i = 0; i < names.length; i++) {
			Mat.invertRigid(rest, i * 12, invRest, i * 12);
		}
	}

	static AgentModel read(byte[] data) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		b.get(magic);
		if (!"CAM1".equals(new String(magic, StandardCharsets.US_ASCII))) {
			throw new IOException("not a CAM1 agent model");
		}
		int n = b.getInt();
		String[] names = new String[n];
		int[] parents = new int[n];
		int[] anim = new int[n];
		float[] rest = new float[n * 12];
		for (int i = 0; i < n; i++) {
			names[i] = AgentClip.str(b);
			parents[i] = b.getInt();
			anim[i] = b.getInt();
			for (int k = 0; k < 12; k++) {
				rest[i * 12 + k] = b.getFloat();
			}
		}
		Part[] parts = new Part[b.getInt()];
		for (int p = 0; p < parts.length; p++) {
			Identifier tex = Identifier.parse(AgentClip.str(b));
			String mesh = AgentClip.str(b);
			int nv = b.getInt();
			float[] pos = floats(b, nv * 3);
			float[] nrm = floats(b, nv * 3);
			float[] uv = floats(b, nv * 2);
			byte[] jn = new byte[nv * 4];
			b.get(jn);
			byte[] wt = new byte[nv * 4];
			b.get(wt);
			int[] idx = new int[b.getInt()];
			b.asIntBuffer().get(idx);
			b.position(b.position() + idx.length * 4);
			parts[p] = new Part(tex, mesh, nv, pos, nrm, uv, jn, wt, idx);
		}
		return new AgentModel(names, parents, anim, rest, parts);
	}

	public int index(String name) {
		for (int i = 0; i < names.length; i++) {
			if (names[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	private static float[] floats(ByteBuffer b, int n) {
		float[] f = new float[n];
		b.asFloatBuffer().get(f);
		b.position(b.position() + n * 4);
		return f;
	}
}
