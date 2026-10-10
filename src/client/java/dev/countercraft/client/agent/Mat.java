package dev.countercraft.client.agent;

/** 3x4 row-major rigid matrices (rotation + translation) in flat float arrays, and quaternion helpers. */
final class Mat {
	private Mat() {
	}

	/** out = a * b. */
	static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
		float a00 = a[ao], a01 = a[ao + 1], a02 = a[ao + 2], a03 = a[ao + 3];
		float a10 = a[ao + 4], a11 = a[ao + 5], a12 = a[ao + 6], a13 = a[ao + 7];
		float a20 = a[ao + 8], a21 = a[ao + 9], a22 = a[ao + 10], a23 = a[ao + 11];
		float b00 = b[bo], b01 = b[bo + 1], b02 = b[bo + 2], b03 = b[bo + 3];
		float b10 = b[bo + 4], b11 = b[bo + 5], b12 = b[bo + 6], b13 = b[bo + 7];
		float b20 = b[bo + 8], b21 = b[bo + 9], b22 = b[bo + 10], b23 = b[bo + 11];
		out[oo] = a00 * b00 + a01 * b10 + a02 * b20;
		out[oo + 1] = a00 * b01 + a01 * b11 + a02 * b21;
		out[oo + 2] = a00 * b02 + a01 * b12 + a02 * b22;
		out[oo + 3] = a00 * b03 + a01 * b13 + a02 * b23 + a03;
		out[oo + 4] = a10 * b00 + a11 * b10 + a12 * b20;
		out[oo + 5] = a10 * b01 + a11 * b11 + a12 * b21;
		out[oo + 6] = a10 * b02 + a11 * b12 + a12 * b22;
		out[oo + 7] = a10 * b03 + a11 * b13 + a12 * b23 + a13;
		out[oo + 8] = a20 * b00 + a21 * b10 + a22 * b20;
		out[oo + 9] = a20 * b01 + a21 * b11 + a22 * b21;
		out[oo + 10] = a20 * b02 + a21 * b12 + a22 * b22;
		out[oo + 11] = a20 * b03 + a21 * b13 + a22 * b23 + a23;
	}

	/** Inverse of a rotation+translation matrix (rotation part orthonormal, possibly uniformly scaled). */
	static void invertRigid(float[] m, int mo, float[] out, int oo) {
		// general 3x3 inverse to tolerate scale
		float a = m[mo], b = m[mo + 1], c = m[mo + 2];
		float d = m[mo + 4], e = m[mo + 5], f = m[mo + 6];
		float g = m[mo + 8], h = m[mo + 9], i = m[mo + 10];
		float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
		float inv = det == 0 ? 0 : 1f / det;
		float r00 = (e * i - f * h) * inv, r01 = (c * h - b * i) * inv, r02 = (b * f - c * e) * inv;
		float r10 = (f * g - d * i) * inv, r11 = (a * i - c * g) * inv, r12 = (c * d - a * f) * inv;
		float r20 = (d * h - e * g) * inv, r21 = (b * g - a * h) * inv, r22 = (a * e - b * d) * inv;
		float tx = m[mo + 3], ty = m[mo + 7], tz = m[mo + 11];
		out[oo] = r00;
		out[oo + 1] = r01;
		out[oo + 2] = r02;
		out[oo + 3] = -(r00 * tx + r01 * ty + r02 * tz);
		out[oo + 4] = r10;
		out[oo + 5] = r11;
		out[oo + 6] = r12;
		out[oo + 7] = -(r10 * tx + r11 * ty + r12 * tz);
		out[oo + 8] = r20;
		out[oo + 9] = r21;
		out[oo + 10] = r22;
		out[oo + 11] = -(r20 * tx + r21 * ty + r22 * tz);
	}

	/** Matrix from quaternion (x, y, z, w), translation and uniform scale. */
	static void fromTrs(float[] q, int qo, float[] t, int to, float s, float[] out, int oo) {
		float x = q[qo], y = q[qo + 1], z = q[qo + 2], w = q[qo + 3];
		out[oo] = (1 - 2 * (y * y + z * z)) * s;
		out[oo + 1] = 2 * (x * y - z * w) * s;
		out[oo + 2] = 2 * (x * z + y * w) * s;
		out[oo + 3] = t[to];
		out[oo + 4] = 2 * (x * y + z * w) * s;
		out[oo + 5] = (1 - 2 * (x * x + z * z)) * s;
		out[oo + 6] = 2 * (y * z - x * w) * s;
		out[oo + 7] = t[to + 1];
		out[oo + 8] = 2 * (x * z - y * w) * s;
		out[oo + 9] = 2 * (y * z + x * w) * s;
		out[oo + 10] = (1 - 2 * (x * x + y * y)) * s;
		out[oo + 11] = t[to + 2];
	}

	/** Quaternion of a rotation matrix's 3x3 part (assumed orthonormal). */
	static void toQuat(float[] m, int mo, float[] q, int qo) {
		float m00 = m[mo], m01 = m[mo + 1], m02 = m[mo + 2];
		float m10 = m[mo + 4], m11 = m[mo + 5], m12 = m[mo + 6];
		float m20 = m[mo + 8], m21 = m[mo + 9], m22 = m[mo + 10];
		float tr = m00 + m11 + m22;
		float x, y, z, w;
		if (tr > 0) {
			float s = (float) Math.sqrt(tr + 1.0) * 2;
			w = 0.25f * s;
			x = (m21 - m12) / s;
			y = (m02 - m20) / s;
			z = (m10 - m01) / s;
		} else if (m00 > m11 && m00 > m22) {
			float s = (float) Math.sqrt(1.0 + m00 - m11 - m22) * 2;
			w = (m21 - m12) / s;
			x = 0.25f * s;
			y = (m01 + m10) / s;
			z = (m02 + m20) / s;
		} else if (m11 > m22) {
			float s = (float) Math.sqrt(1.0 + m11 - m00 - m22) * 2;
			w = (m02 - m20) / s;
			x = (m01 + m10) / s;
			y = 0.25f * s;
			z = (m12 + m21) / s;
		} else {
			float s = (float) Math.sqrt(1.0 + m22 - m00 - m11) * 2;
			w = (m10 - m01) / s;
			x = (m02 + m20) / s;
			y = (m12 + m21) / s;
			z = 0.25f * s;
		}
		float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
		q[qo] = x / n;
		q[qo + 1] = y / n;
		q[qo + 2] = z / n;
		q[qo + 3] = w / n;
	}
}
