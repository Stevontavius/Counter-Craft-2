package dev.countercraft.client.viewmodel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Emits a triangle mesh into an entity render type quickly. Entity render types draw quads, so each triangle goes out
 * as a quad with its last vertex repeated. Every unique vertex is transformed once (instead of once per use, with the
 * pose and the normal matrix, as {@code addVertex(pose, ...)} / {@code setNormal(pose, ...)} do), then written with
 * the bulk {@code addVertex}. CS2 rifles are 50-70k triangles, so this is the difference between a few hundred
 * thousand matrix multiplies a frame and a fifth of that.
 *
 * <p>Runs inside submitCustomGeometry callbacks, which the render thread runs one after another, so the scratch
 * arrays are shared.</p>
 */
public final class FastMesh {
	private static float[] tp = new float[0];
	private static float[] tn = new float[0];
	private static final java.util.Map<int[], Float> WINDING = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

	private FastMesh() {
	}

	/**
	 * @param pre optional rigid 3x4 row-major matrix applied before the pose (a mesh placed on a bone), or null
	 */
	/** Vertex colour (ARGB) for the next emits: set it around one emit, then put it back to white. */
	public static int color = 0xFFFFFFFF;

	public static void emit(int[] idx, float[] uv, float[] pos, float[] nrm, int vertexCount, float[] pre, PoseStack.Pose p, VertexConsumer vc,
			int light, int overlay) {
		emit(idx, uv, pos, nrm, vertexCount, pre, p, vc, light, overlay, true);
	}

	/** As above; {@code cull} false draws back faces too (a mesh cut open, seen from inside). */
	public static void emit(int[] idx, float[] uv, float[] pos, float[] nrm, int vertexCount, float[] pre, PoseStack.Pose p, VertexConsumer vc,
			int light, int overlay, boolean cull) {
		emit(idx, null, uv, pos, nrm, vertexCount, pre, p, vc, light, overlay, cull);
	}

	/** As above; {@code used}, if given, lists the only vertices {@code idx} refers to (the rest aren't transformed). */
	public static void emit(int[] idx, int[] used, float[] uv, float[] pos, float[] nrm, int vertexCount, float[] pre, PoseStack.Pose p,
			VertexConsumer vc, int light, int overlay, boolean cull) {
		if (tp.length < vertexCount * 3) {
			tp = new float[vertexCount * 3];
			tn = new float[vertexCount * 3];
		}
		Matrix4f m = p.pose();
		Matrix3f nm = p.normal();
		float a00 = m.m00(), a01 = m.m10(), a02 = m.m20(), a03 = m.m30();
		float a10 = m.m01(), a11 = m.m11(), a12 = m.m21(), a13 = m.m31();
		float a20 = m.m02(), a21 = m.m12(), a22 = m.m22(), a23 = m.m32();
		float n00 = nm.m00(), n01 = nm.m10(), n02 = nm.m20();
		float n10 = nm.m01(), n11 = nm.m11(), n12 = nm.m21();
		float n20 = nm.m02(), n21 = nm.m12(), n22 = nm.m22();
		if (pre != null) {
			// fold the bone placement in: pose x pre (positions), normal x rot(pre) (normals; pre is rigid)
			float b00 = a00 * pre[0] + a01 * pre[4] + a02 * pre[8];
			float b01 = a00 * pre[1] + a01 * pre[5] + a02 * pre[9];
			float b02 = a00 * pre[2] + a01 * pre[6] + a02 * pre[10];
			float b03 = a00 * pre[3] + a01 * pre[7] + a02 * pre[11] + a03;
			float b10 = a10 * pre[0] + a11 * pre[4] + a12 * pre[8];
			float b11 = a10 * pre[1] + a11 * pre[5] + a12 * pre[9];
			float b12 = a10 * pre[2] + a11 * pre[6] + a12 * pre[10];
			float b13 = a10 * pre[3] + a11 * pre[7] + a12 * pre[11] + a13;
			float b20 = a20 * pre[0] + a21 * pre[4] + a22 * pre[8];
			float b21 = a20 * pre[1] + a21 * pre[5] + a22 * pre[9];
			float b22 = a20 * pre[2] + a21 * pre[6] + a22 * pre[10];
			float b23 = a20 * pre[3] + a21 * pre[7] + a22 * pre[11] + a23;
			a00 = b00; a01 = b01; a02 = b02; a03 = b03;
			a10 = b10; a11 = b11; a12 = b12; a13 = b13;
			a20 = b20; a21 = b21; a22 = b22; a23 = b23;
			float c00 = n00 * pre[0] + n01 * pre[4] + n02 * pre[8];
			float c01 = n00 * pre[1] + n01 * pre[5] + n02 * pre[9];
			float c02 = n00 * pre[2] + n01 * pre[6] + n02 * pre[10];
			float c10 = n10 * pre[0] + n11 * pre[4] + n12 * pre[8];
			float c11 = n10 * pre[1] + n11 * pre[5] + n12 * pre[9];
			float c12 = n10 * pre[2] + n11 * pre[6] + n12 * pre[10];
			float c20 = n20 * pre[0] + n21 * pre[4] + n22 * pre[8];
			float c21 = n20 * pre[1] + n21 * pre[5] + n22 * pre[9];
			float c22 = n20 * pre[2] + n21 * pre[6] + n22 * pre[10];
			n00 = c00; n01 = c01; n02 = c02;
			n10 = c10; n11 = c11; n12 = c12;
			n20 = c20; n21 = c21; n22 = c22;
		}
		int count = used != null ? used.length : vertexCount;
		for (int i = 0; i < count; i++) {
			int v = used != null ? used[i] : i;
			int o = v * 3;
			float x = pos[o], y = pos[o + 1], z = pos[o + 2];
			tp[o] = a00 * x + a01 * y + a02 * z + a03;
			tp[o + 1] = a10 * x + a11 * y + a12 * z + a13;
			tp[o + 2] = a20 * x + a21 * y + a22 * z + a23;
			float nx = nrm[o], ny = nrm[o + 1], nz = nrm[o + 2];
			float qx = n00 * nx + n01 * ny + n02 * nz;
			float qy = n10 * nx + n11 * ny + n12 * nz;
			float qz = n20 * nx + n21 * ny + n22 * nz;
			float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz);
			if (len > 1e-6f) {
				float inv = 1f / len;
				qx *= inv;
				qy *= inv;
				qz *= inv;
			} else {
				qy = 1f;
			}
			tn[o] = qx;
			tn[o + 1] = qy;
			tn[o + 2] = qz;
		}
		// the mesh's own winding, flipped when this transform mirrors (a negative determinant reverses every triangle)
		float det = a00 * (a11 * a22 - a12 * a21) - a01 * (a10 * a22 - a12 * a20) + a02 * (a10 * a21 - a11 * a20);
		float front = cull ? winding(idx, pos, nrm) * Math.signum(det) : 0f;
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int i0 = idx[t] * 3, i1 = idx[t + 1] * 3, i2 = idx[t + 2] * 3;
			// back-face cull on the CPU (entity render types draw both sides): the camera is at the origin of this space
			float ex = tp[i1] - tp[i0], ey = tp[i1 + 1] - tp[i0 + 1], ez = tp[i1 + 2] - tp[i0 + 2];
			float fx = tp[i2] - tp[i0], fy = tp[i2 + 1] - tp[i0 + 1], fz = tp[i2 + 2] - tp[i0 + 2];
			float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
			if (cull && front * (nx * tp[i0] + ny * tp[i0 + 1] + nz * tp[i0 + 2]) > 0f) {
				continue;
			}
			for (int k = 0; k < 4; k++) {
				int v = idx[t + Math.min(k, 2)];
				int o = v * 3;
				vc.addVertex(tp[o], tp[o + 1], tp[o + 2], color, uv[v * 2], uv[v * 2 + 1], overlay, light, tn[o], tn[o + 1], tn[o + 2]);
			}
		}
	}

	/**
	 * +1 if the mesh's triangle winding agrees with its vertex normals (counter-clockwise = outside), -1 if reversed;
	 * decided once per mesh in its own (untransformed) space, so no particular draw's transform can bake a mirror in.
	 */
	private static float winding(int[] idx, float[] pos, float[] nrm) {
		Float w = WINDING.get(idx);
		if (w != null) {
			return w;
		}
		double agree = 0;
		for (int t = 0; t + 2 < idx.length; t += 3) {
			int i0 = idx[t] * 3, i1 = idx[t + 1] * 3, i2 = idx[t + 2] * 3;
			float ex = pos[i1] - pos[i0], ey = pos[i1 + 1] - pos[i0 + 1], ez = pos[i1 + 2] - pos[i0 + 2];
			float fx = pos[i2] - pos[i0], fy = pos[i2 + 1] - pos[i0 + 1], fz = pos[i2 + 2] - pos[i0 + 2];
			float nx = ey * fz - ez * fy, ny = ez * fx - ex * fz, nz = ex * fy - ey * fx;
			agree += nx * (nrm[i0] + nrm[i1] + nrm[i2]) + ny * (nrm[i0 + 1] + nrm[i1 + 1] + nrm[i2 + 1]) + nz * (nrm[i0 + 2] + nrm[i1 + 2] + nrm[i2 + 2]);
		}
		float r = agree >= 0 ? 1f : -1f;
		WINDING.put(idx, r);
		return r;
	}
}
