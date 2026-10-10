package dev.countercraft.client.agent;

/**
 * Aim and hand IK on model-space matrices (CS2 does this in code in its AimCS graph node, which is not in the game
 * files, so this is an approximation): the aim yaw is spread over the four spine bones (the torso twists towards the
 * aim), the aim pitch only tilts the head (neck and head share it) and the weapon (wpnPivot and everything on it,
 * including the hand targets wpnHand_L / wpnHand_R, about the shoulders) so the torso stays upright, and a two-bone
 * IK puts each hand back on its target.
 */
final class AgentAim {
	/** Share of the aim each spine bone adds (1.0 in total at the chest; neck and head follow). */
	private static final String[] CHAIN = {"spine_0", "spine_1", "spine_2", "spine_3"};
	private static final float[] SHARE = {0.2f, 0.25f, 0.25f, 0.3f};
	/** Bones that each take half of the aim pitch (the head looks fully up / down). */
	private static final String[] HEAD = {"neck_0", "head_0"};
	/**
	 * How far down (degrees) the head and the gun follow the look; further down only the view goes on (at 89 the neck
	 * folded and the arms reached for the floor). An approximation: CS2's limit is in its AimCS code, not its files.
	 */
	static final float DOWN_LIMIT = 50f;

	private AgentAim() {
	}

	static void apply(AgentSkeleton skel, float[] m, float pitchUp, float yawLeft) {
		apply(skel, m, pitchUp, yawLeft, 0f);
	}

	/** {@code punch}: metres the right hand's target is pushed along the aim first (a jab). */
	static void apply(AgentSkeleton skel, float[] m, float pitchUp, float yawLeft, float punch) {
		pitchUp = Math.max(-DOWN_LIMIT, Math.min(89f, pitchUp));
		float[] delta = new float[12];
		float[] tmp = new float[12];
		int pivot = skel.index("wpnPivot");
		for (int k = 0; k < CHAIN.length; k++) {
			int b = skel.index(CHAIN[k]);
			if (b < 0) {
				continue;
			}
			rotation(yawLeft * SHARE[k], 0f, m, b, delta);
			subtree(skel, m, b, delta, tmp);
			// the weapon (and the hand targets on it) turns with the chest, so the hands stay on it
			if (pivot >= 0) {
				subtree(skel, m, pivot, delta, tmp);
			}
		}
		// pitch, about the aim's left axis: the head looks up / down, the gun swings about the shoulders
		double y = Math.toRadians(yawLeft);
		float[] left = {(float) Math.cos(y), 0f, (float) -Math.sin(y)};
		float a = (float) Math.toRadians(-pitchUp);
		for (String bone : HEAD) {
			int b = skel.index(bone);
			if (b >= 0) {
				axisAngle(left, a * 0.5f, pos(m, b), delta);
				subtree(skel, m, b, delta, tmp);
			}
		}
		int sl = skel.index("arm_upper_L");
		int sr = skel.index("arm_upper_R");
		if (pivot >= 0 && sl >= 0 && sr >= 0) {
			float[] l = pos(m, sl), r = pos(m, sr);
			axisAngle(left, a, new float[] {(l[0] + r[0]) / 2, (l[1] + r[1]) / 2, (l[2] + r[2]) / 2}, delta);
			subtree(skel, m, pivot, delta, tmp);
		}
		int target = skel.index("wpnHand_R");
		if (punch > 0f && target >= 0) {
			// model space is glTF (x left, y up, z forward): the aim direction after the yaw and pitch above
			double p = Math.toRadians(pitchUp);
			float cp = (float) Math.cos(p);
			m[target * 12 + 3] += punch * (float) Math.sin(y) * cp;
			m[target * 12 + 7] += punch * (float) Math.sin(p) + punch * 0.25f;
			m[target * 12 + 11] += punch * (float) Math.cos(y) * cp;
		}
		ik(skel, m, "L", tmp);
		ik(skel, m, "R", tmp);
	}

	/** Rotation by yaw (about model up) then pitch (about model left), about bone b's position. */
	private static void rotation(float yawDeg, float pitchDeg, float[] m, int b, float[] out) {
		double y = Math.toRadians(yawDeg);
		double p = Math.toRadians(-pitchDeg);
		float cy = (float) Math.cos(y), sy = (float) Math.sin(y);
		float cp = (float) Math.cos(p), sp = (float) Math.sin(p);
		// Ry * Rx
		float r00 = cy, r01 = sy * sp, r02 = sy * cp;
		float r10 = 0, r11 = cp, r12 = -sp;
		float r20 = -sy, r21 = cy * sp, r22 = cy * cp;
		about(r00, r01, r02, r10, r11, r12, r20, r21, r22, m[b * 12 + 3], m[b * 12 + 7], m[b * 12 + 11], out);
	}

	/** out = T(p) R T(-p). */
	private static void about(float r00, float r01, float r02, float r10, float r11, float r12, float r20, float r21, float r22, float px,
			float py, float pz, float[] out) {
		out[0] = r00;
		out[1] = r01;
		out[2] = r02;
		out[3] = px - (r00 * px + r01 * py + r02 * pz);
		out[4] = r10;
		out[5] = r11;
		out[6] = r12;
		out[7] = py - (r10 * px + r11 * py + r12 * pz);
		out[8] = r20;
		out[9] = r21;
		out[10] = r22;
		out[11] = pz - (r20 * px + r21 * py + r22 * pz);
	}

	/** Pre-multiply bone b and all its descendants by d (bones are ordered parents first). */
	static void subtree(AgentSkeleton skel, float[] m, int b, float[] d, float[] tmp) {
		int n = skel.bones();
		boolean[] in = new boolean[n];
		in[b] = true;
		for (int i = b; i < n; i++) {
			if (i != b) {
				int p = skel.parents[i];
				if (p < 0 || !in[p]) {
					continue;
				}
				in[i] = true;
			}
			Mat.mul(d, 0, m, i * 12, tmp, 0);
			System.arraycopy(tmp, 0, m, i * 12, 12);
		}
	}

	private static void ik(AgentSkeleton skel, float[] m, String side, float[] tmp) {
		int upper = skel.index("arm_upper_" + side);
		int lower = skel.index("arm_lower_" + side);
		int hand = skel.index("hand_" + side);
		int target = skel.index("wpnHand_" + side);
		if (upper < 0 || lower < 0 || hand < 0 || target < 0) {
			return;
		}
		float[] s = pos(m, upper), e = pos(m, lower), h = pos(m, hand), t = pos(m, target);
		float a = dist(s, e), b = dist(e, h);
		float c = Math.max(1e-3f, Math.min(dist(s, t), a + b - 1e-4f));
		// 1. bend the elbow to the length the target needs
		float[] u = sub(s, e), v = sub(h, e);
		float[] axis = cross(u, v);
		if (len(axis) < 1e-6f) {
			axis = new float[] {1, 0, 0};
		}
		normalize(axis);
		float cur = angle(u, v);
		float want = (float) Math.acos(Math.max(-1f, Math.min(1f, (a * a + b * b - c * c) / (2 * a * b))));
		float[] d = new float[12];
		axisAngle(axis, want - cur, e, d);
		subtree(skel, m, lower, d, tmp);
		// 2. swing the upper arm so the hand lands on the target
		h = pos(m, hand);
		float[] hs = sub(h, s), ts = sub(t, s);
		float[] ax2 = cross(hs, ts);
		if (len(ax2) > 1e-6f) {
			normalize(ax2);
			axisAngle(ax2, angle(hs, ts), s, d);
			subtree(skel, m, upper, d, tmp);
		}
		// 3. the hand takes the target's orientation and position
		float[] inv = new float[12];
		Mat.invertRigid(m, hand * 12, inv, 0);
		Mat.mul(m, target * 12, inv, 0, d, 0);
		subtree(skel, m, hand, d, tmp);
	}

	private static void axisAngle(float[] n, float ang, float[] p, float[] out) {
		float c = (float) Math.cos(ang), s = (float) Math.sin(ang), k = 1 - c;
		float x = n[0], y = n[1], z = n[2];
		about(c + x * x * k, x * y * k - z * s, x * z * k + y * s,
				y * x * k + z * s, c + y * y * k, y * z * k - x * s,
				z * x * k - y * s, z * y * k + x * s, c + z * z * k, p[0], p[1], p[2], out);
	}

	private static float[] pos(float[] m, int b) {
		return new float[] {m[b * 12 + 3], m[b * 12 + 7], m[b * 12 + 11]};
	}

	private static float[] sub(float[] a, float[] b) {
		return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static float[] cross(float[] a, float[] b) {
		return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static float len(float[] a) {
		return (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
	}

	private static float dist(float[] a, float[] b) {
		return len(sub(a, b));
	}

	private static void normalize(float[] a) {
		float l = len(a);
		a[0] /= l;
		a[1] /= l;
		a[2] /= l;
	}

	private static float angle(float[] a, float[] b) {
		float d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / Math.max(1e-9f, len(a) * len(b));
		return (float) Math.acos(Math.max(-1f, Math.min(1f, d)));
	}
}
