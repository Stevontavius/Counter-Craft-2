package dev.countercraft.client.agent;

/**
 * A skeleton pose in bone-local space: per bone a rotation quaternion (x, y, z, w), a translation (metres) and a
 * uniform scale. Additive poses hold deltas (applied as base * delta, translations added), as CS2 compiles them.
 */
public final class Pose {
	public final int bones;
	public final float[] r;
	public final float[] t;
	public final float[] s;
	public boolean additive;

	public Pose(int bones) {
		this.bones = bones;
		r = new float[bones * 4];
		t = new float[bones * 3];
		s = new float[bones];
	}

	public void copyFrom(Pose o) {
		System.arraycopy(o.r, 0, r, 0, r.length);
		System.arraycopy(o.t, 0, t, 0, t.length);
		System.arraycopy(o.s, 0, s, 0, s.length);
		additive = o.additive;
	}

	public void setReference(AgentSkeleton skel) {
		System.arraycopy(skel.refR, 0, r, 0, r.length);
		System.arraycopy(skel.refT, 0, t, 0, t.length);
		java.util.Arrays.fill(s, 1f);
		additive = false;
	}

	/** Identity everywhere: as an additive layer this changes nothing (CS2's zero pose). */
	public void setZero() {
		for (int i = 0; i < bones; i++) {
			r[i * 4] = r[i * 4 + 1] = r[i * 4 + 2] = 0f;
			r[i * 4 + 3] = 1f;
			t[i * 3] = t[i * 3 + 1] = t[i * 3 + 2] = 0f;
			s[i] = 1f;
		}
		additive = true;
	}

	/** out = a blended toward b by w (per bone w * mask), in local space. out may alias a or b. */
	public static void blend(Pose a, Pose b, float w, float[] mask, Pose out) {
		for (int i = 0; i < a.bones; i++) {
			float k = mask == null ? w : w * mask[i];
			int q = i * 4;
			if (k <= 0f) {
				if (out != a) {
					copyBone(a, out, i);
				}
				continue;
			}
			if (k >= 1f) {
				if (out != b) {
					copyBone(b, out, i);
				}
				continue;
			}
			nlerp(a.r, q, b.r, q, k, out.r, q);
			int p = i * 3;
			out.t[p] = a.t[p] + (b.t[p] - a.t[p]) * k;
			out.t[p + 1] = a.t[p + 1] + (b.t[p + 1] - a.t[p + 1]) * k;
			out.t[p + 2] = a.t[p + 2] + (b.t[p + 2] - a.t[p + 2]) * k;
			out.s[i] = a.s[i] + (b.s[i] - a.s[i]) * k;
		}
		out.additive = a.additive && b.additive;
	}

	/** out = base with the additive pose applied at weight w (per bone w * mask): rotation base * delta^k, translation + delta * k. */
	public static void addOnto(Pose base, Pose delta, float w, float[] mask, Pose out) {
		for (int i = 0; i < base.bones; i++) {
			float k = mask == null ? w : w * mask[i];
			if (out != base) {
				copyBone(base, out, i);
			}
			if (k <= 0f) {
				continue;
			}
			int q = i * 4;
			float dx = delta.r[q], dy = delta.r[q + 1], dz = delta.r[q + 2], dw = delta.r[q + 3];
			if (k < 1f) {
				// nlerp from identity
				if (dw < 0) {
					dx = -dx;
					dy = -dy;
					dz = -dz;
					dw = -dw;
				}
				dx *= k;
				dy *= k;
				dz *= k;
				dw = 1f - k + dw * k;
				float n = (float) Math.sqrt(dx * dx + dy * dy + dz * dz + dw * dw);
				dx /= n;
				dy /= n;
				dz /= n;
				dw /= n;
			}
			float bx = out.r[q], by = out.r[q + 1], bz = out.r[q + 2], bw = out.r[q + 3];
			out.r[q] = bw * dx + bx * dw + by * dz - bz * dy;
			out.r[q + 1] = bw * dy - bx * dz + by * dw + bz * dx;
			out.r[q + 2] = bw * dz + bx * dy - by * dx + bz * dw;
			out.r[q + 3] = bw * dw - bx * dx - by * dy - bz * dz;
			int p = i * 3;
			out.t[p] += delta.t[p] * k;
			out.t[p + 1] += delta.t[p + 1] * k;
			out.t[p + 2] += delta.t[p + 2] * k;
		}
		out.additive = base.additive;
	}

	/**
	 * Model-space blend (CS2's ModelSpace layers): each masked bone takes the layer's model-space rotation blended with
	 * the base's, so an upper-body action keeps its orientation whatever the legs and pelvis do.
	 */
	public static void blendModelSpace(Pose base, Pose layer, float w, float[] mask, AgentSkeleton skel, Pose out, float[] scratchA,
			float[] scratchB, float[] scratchO) {
		int n = base.bones;
		float[] target = new float[4];
		float[] inv = new float[4];
		// model-space rotations of base and layer
		for (int i = 0; i < n; i++) {
			int p = skel.parents[i];
			int q = i * 4;
			if (p < 0) {
				System.arraycopy(base.r, q, scratchA, q, 4);
				System.arraycopy(layer.r, q, scratchB, q, 4);
			} else {
				mul(scratchA, p * 4, base.r, q, scratchA, q);
				mul(scratchB, p * 4, layer.r, q, scratchB, q);
			}
		}
		for (int i = 0; i < n; i++) {
			float k = mask == null ? w : w * mask[i];
			int q = i * 4;
			int p = skel.parents[i];
			if (k <= 0f) {
				if (out != base) {
					copyBone(base, out, i);
				}
				// the output model rotation of this bone, for its children
				if (p < 0) {
					System.arraycopy(out.r, q, scratchO, q, 4);
				} else {
					mul(scratchO, p * 4, out.r, q, scratchO, q);
				}
				continue;
			}
			nlerp(scratchA, q, scratchB, q, Math.min(1f, k), target, 0);
			// local = inverse(parent output model rotation) * target
			if (p < 0) {
				System.arraycopy(target, 0, out.r, q, 4);
			} else {
				inv[0] = -scratchO[p * 4];
				inv[1] = -scratchO[p * 4 + 1];
				inv[2] = -scratchO[p * 4 + 2];
				inv[3] = scratchO[p * 4 + 3];
				mul(inv, 0, target, 0, out.r, q);
			}
			System.arraycopy(target, 0, scratchO, q, 4);
			int t3 = i * 3;
			float kk = Math.min(1f, k);
			out.t[t3] = base.t[t3] + (layer.t[t3] - base.t[t3]) * kk;
			out.t[t3 + 1] = base.t[t3 + 1] + (layer.t[t3 + 1] - base.t[t3 + 1]) * kk;
			out.t[t3 + 2] = base.t[t3 + 2] + (layer.t[t3 + 2] - base.t[t3 + 2]) * kk;
			out.s[i] = base.s[i] + (layer.s[i] - base.s[i]) * kk;
		}
		out.additive = base.additive;
	}

	private static void copyBone(Pose a, Pose out, int i) {
		System.arraycopy(a.r, i * 4, out.r, i * 4, 4);
		System.arraycopy(a.t, i * 3, out.t, i * 3, 3);
		out.s[i] = a.s[i];
	}

	static void nlerp(float[] a, int ao, float[] b, int bo, float k, float[] out, int oo) {
		float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
		if (a[ao] * bx + a[ao + 1] * by + a[ao + 2] * bz + a[ao + 3] * bw < 0) {
			bx = -bx;
			by = -by;
			bz = -bz;
			bw = -bw;
		}
		float x = a[ao] + (bx - a[ao]) * k;
		float y = a[ao + 1] + (by - a[ao + 1]) * k;
		float z = a[ao + 2] + (bz - a[ao + 2]) * k;
		float w = a[ao + 3] + (bw - a[ao + 3]) * k;
		float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		out[oo] = x / n;
		out[oo + 1] = y / n;
		out[oo + 2] = z / n;
		out[oo + 3] = w / n;
	}

	/** out = a * b (quaternions); out may alias neither when offsets overlap. */
	static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
		float ax = a[ao], ay = a[ao + 1], az = a[ao + 2], aw = a[ao + 3];
		float bx = b[bo], by = b[bo + 1], bz = b[bo + 2], bw = b[bo + 3];
		out[oo] = aw * bx + ax * bw + ay * bz - az * by;
		out[oo + 1] = aw * by - ax * bz + ay * bw + az * bx;
		out[oo + 2] = aw * bz + ax * by - ay * bx + az * bw;
		out[oo + 3] = aw * bw - ax * bx - ay * by - az * bz;
	}
}
