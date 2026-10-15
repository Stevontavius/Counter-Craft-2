package dev.countercraft.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Lightning bolts built like Minecraft's lightning bolt entity (LightningBoltRenderer), at the size of a creature: a
 * zig-zag of straight steps with forks off it, each step drawn as four nested square tubes (half-sizes in Minecraft's
 * 1 : 3 : 5 : 7), in its colour (0.45, 0.45, 0.5) and per-layer alpha, additive (RenderTypes.lightning), so the layers
 * add up to a white-hot core in a pale halo. Used to wrap a zapped creature as CS2 wraps a Zeus victim.
 */
final class BoltFx {
	private static final float R = 0.45f;
	private static final float G = 0.45f;
	private static final float B = 0.5f;
	/**
	 * Minecraft's per-layer alpha is 0.3 on a sky-sized bolt; a creature-sized one needs more to read in daylight
	 * (Counter-Craft's choice).
	 */
	private static final float LAYER_ALPHA = 0.5f;
	/** The innermost tube's half-size (blocks); the others are 3, 5 and 7 times it, as Minecraft's 0.1 + 0.2 per layer. */
	private static final double CORE = 0.0045;
	/** A zig-zag step (blocks), and how far each step may wander sideways (Minecraft's +-5 in 16 on its bolt). */
	private static final double STEP = 0.07;
	private static final double WANDER = 0.018;

	private BoltFx() {
	}

	/**
	 * A bolt along these points: even zig-zag steps along the line through them, forks now and then. Adds its steps
	 * to {@code out}: {ax, ay, az, bx, by, bz, alpha, width,
	 * fx, fy, fz}, f being the tube's side: carried along the bolt (turned only as much as each step turns), so the tube
	 * doesn't twist from step to step, as Minecraft's straight-up bolt never does.
	 */
	static void bolt(List<float[]> out, List<Vec3> points, RandomSource r, float alpha, float width) {
		// even steps along the line (however densely it was given), as Minecraft's bolt's: long enough that its kinks
		// stay mild, so the tubes don't overlap and gap at every one
		List<Vec3> line = resample(points, STEP);
		if (line.size() < 2) {
			return;
		}
		Vec3 frame = null;
		Vec3 prev = line.get(0);
		double wx = 0;
		double wy = 0;
		for (int k = 1; k < line.size(); k++) {
			Vec3 base = line.get(k);
			Vec3 dir = base.subtract(line.get(k - 1)).normalize();
			Vec3[] axes = axes(dir);
			// the zig-zag: sideways offsets that wander but keep coming back (the bolt stays on its line), none at the end
			wx = wx * 0.55 + (r.nextDouble() - 0.5) * 2 * WANDER;
			wy = wy * 0.55 + (r.nextDouble() - 0.5) * 2 * WANDER;
			Vec3 p = k == line.size() - 1 ? base : base.add(axes[0].scale(wx)).add(axes[1].scale(wy));
			if (p.distanceToSqr(prev) < 1e-8) {
				continue;
			}
			frame = carry(frame, p.subtract(prev));
			out.add(step(prev, p, alpha, width, frame));
			if (k < line.size() - 1 && r.nextFloat() < 0.09f) {
				fork(out, p, dir, axes, frame, r, alpha * 0.8f, width * 0.7f);
			}
			prev = p;
		}
	}

	/** The polyline through {@code points}, cut into steps of {@code step} (the last one shorter), ends kept. */
	private static List<Vec3> resample(List<Vec3> points, double step) {
		List<Vec3> out = new ArrayList<>();
		if (points.isEmpty()) {
			return out;
		}
		out.add(points.get(0));
		double carry = 0; // how far along the current piece the next cut is
		for (int i = 0; i + 1 < points.size(); i++) {
			Vec3 a = points.get(i);
			Vec3 b = points.get(i + 1);
			double len = a.distanceTo(b);
			double at = step - carry;
			while (at < len) {
				out.add(a.add(b.subtract(a).scale(at / len)));
				at += step;
			}
			carry = len - (at - step);
		}
		Vec3 end = points.get(points.size() - 1);
		if (out.get(out.size() - 1).distanceToSqr(end) > 1e-8) {
			out.add(end);
		}
		return out;
	}

	/** A fork: two or three steps off to the side, wandering three times as far (Minecraft's branches: +-15 for +-5). */
	private static void fork(List<float[]> out, Vec3 from, Vec3 dir, Vec3[] axes, Vec3 frame, RandomSource r, float alpha, float width) {
		Vec3 side = axes[0].scale(r.nextDouble() - 0.5).add(axes[1].scale(r.nextDouble() - 0.5)).normalize();
		Vec3 go = dir.scale(r.nextBoolean() ? 0.6 : -0.3).add(side).normalize();
		Vec3 p = from;
		int n = 2 + r.nextInt(2);
		for (int k = 0; k < n; k++) {
			Vec3 q = p.add(go.scale(STEP * 0.8)).add(axes[0].scale((r.nextDouble() - 0.5) * 6 * WANDER)).add(axes[1].scale((r.nextDouble() - 0.5) * 6 * WANDER));
			frame = carry(frame, q.subtract(p));
			out.add(step(p, q, alpha * (1 - k / (float) n * 0.5f), width, frame));
			p = q;
		}
	}

	/** The tube's side for a step going {@code d}: the last one's, turned onto it (first step: any side). */
	private static Vec3 carry(Vec3 frame, Vec3 d) {
		Vec3 dir = d.normalize();
		if (frame != null) {
			Vec3 f = frame.subtract(dir.scale(frame.dot(dir)));
			if (f.lengthSqr() > 1e-6) {
				return f.normalize();
			}
		}
		return axes(dir)[0];
	}

	private static float[] step(Vec3 a, Vec3 b, float alpha, float width, Vec3 frame) {
		return new float[] {(float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, alpha, width,
				(float) frame.x, (float) frame.y, (float) frame.z};
	}

	/** Two unit vectors across {@code dir} (unit). */
	private static Vec3[] axes(Vec3 dir) {
		Vec3 ax1 = dir.cross(Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
		return new Vec3[] {ax1, dir.cross(ax1).normalize()};
	}

	/** One step as Minecraft draws a bolt's: four nested square tubes, the inner ones brightest where they all overlap. */
	static void draw(Matrix4f m, VertexConsumer vc, float[] s) {
		float dx = s[3] - s[0];
		float dy = s[4] - s[1];
		float dz = s[5] - s[2];
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-5f) {
			return;
		}
		Vec3 dir = new Vec3(dx / len, dy / len, dz / len);
		Vec3 across = new Vec3(s[8], s[9], s[10]);
		Vec3[] ax = {across, dir.cross(across).normalize()};
		float a = LAYER_ALPHA * s[6];
		for (int layer = 0; layer < 4; layer++) {
			float h = (float) (CORE * (1 + 2 * layer) * s[7]);
			// the tube's four sides: corners at +-ax0 +-ax1
			for (int side = 0; side < 4; side++) {
				float c0 = side == 0 || side == 3 ? 1 : -1;
				float c1 = side < 2 ? 1 : -1;
				float n0 = side == 0 || side == 1 ? 1 : -1;
				float n1 = side == 1 || side == 2 ? 1 : -1;
				float ux = (float) (ax[0].x * c0 + ax[1].x * c1) * h;
				float uy = (float) (ax[0].y * c0 + ax[1].y * c1) * h;
				float uz = (float) (ax[0].z * c0 + ax[1].z * c1) * h;
				float vx = (float) (ax[0].x * n0 + ax[1].x * n1) * h;
				float vy = (float) (ax[0].y * n0 + ax[1].y * n1) * h;
				float vz = (float) (ax[0].z * n0 + ax[1].z * n1) * h;
				vc.addVertex(m, s[0] + ux, s[1] + uy, s[2] + uz).setColor(R, G, B, a);
				vc.addVertex(m, s[3] + ux, s[4] + uy, s[5] + uz).setColor(R, G, B, a);
				vc.addVertex(m, s[3] + vx, s[4] + vy, s[5] + vz).setColor(R, G, B, a);
				vc.addVertex(m, s[0] + vx, s[1] + vy, s[2] + vz).setColor(R, G, B, a);
			}
		}
	}
}
