package dev.countercraft.client.agent;

import java.util.List;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A CS2 ragdoll as rigid bodies, solved with XPBD (Mueller et al., "Detailed Rigid Body Simulation with Extended
 * Position Based Dynamics"): CS2's bodies (mass, damping, capsule / sphere shapes -> inertia), its joints (anchors held
 * together; ragdoll joints keep the twist about the frame's z axis and the swing off it within CS2's limits, revolute
 * joints (knees) only twist), CS2's gravity (sv_gravity 800 u/s^2), and the shapes colliding with Minecraft's block
 * collision boxes with friction. World space: Minecraft axes, metres. Approximations: no self-collision (CS2 disables
 * it on the jointed pairs only), shapes collide as spheres along each capsule, no bounce.
 */
final class RagdollSim {
	static final double GRAVITY = 800 * 0.0254;
	private static final int SUBSTEPS = 10;
	private static final double STATIC_FRICTION = 0.9;
	private static final double DYNAMIC_FRICTION = 0.6;
	/** CS2 bodies' m_flInertiaScale (7): heavier inertia keeps thin limbs stable. */
	private static final double INERTIA_SCALE = 7;

	static final class Body {
		final int bone;
		final double invMass;
		final Matrix3d invInertia = new Matrix3d();
		/** Centre of mass in the bone's frame. */
		final Vector3d com = new Vector3d();
		/** Collision spheres: centre relative to the centre of mass (body frame) and radius. */
		final double[][] spheres;
		final double linearDamping;
		final double angularDamping;
		final Vector3d x = new Vector3d();
		final Vector3d xp = new Vector3d();
		final Vector3d v = new Vector3d();
		final Vector3d w = new Vector3d();
		final Quaterniond q = new Quaterniond();
		final Quaterniond qp = new Quaterniond();
		/** State at the start of the current tick (for interpolated drawing). */
		final Vector3d xTick = new Vector3d();
		final Quaterniond qTick = new Quaterniond();

		Body(RagdollDef.Body d) {
			bone = d.bone();
			linearDamping = d.linearDamping();
			angularDamping = d.angularDamping();
			double mass = Math.max(0.5, d.mass());
			invMass = 1 / mass;
			// centre of mass: the middle of the shapes
			int n = 0;
			for (RagdollDef.Shape s : d.shapes()) {
				com.add((s.a()[0] + s.b()[0]) / 2, (s.a()[1] + s.b()[1]) / 2, (s.a()[2] + s.b()[2]) / 2);
				n++;
			}
			if (n > 0) {
				com.div(n);
			}
			// inertia (solid cylinder along a capsule / solid sphere), x CS2's inertia scale
			Matrix3d inertia = new Matrix3d().zero();
			double share = mass / Math.max(1, n);
			java.util.List<double[]> sph = new java.util.ArrayList<>();
			for (RagdollDef.Shape s : d.shapes()) {
				Vector3d a = new Vector3d(s.a()[0], s.a()[1], s.a()[2]);
				Vector3d b = new Vector3d(s.b()[0], s.b()[1], s.b()[2]);
				double r = s.r();
				Vector3d axis = new Vector3d(b).sub(a);
				double len = axis.length();
				if (s.capsule() && len > 1e-6) {
					axis.div(len);
					double iAxis = 0.5 * share * r * r;
					double iPerp = share * (3 * r * r + len * len) / 12;
					Matrix3d m = new Matrix3d().identity().scale(iPerp);
					Matrix3d uu = new Matrix3d(axis.x * axis.x, axis.x * axis.y, axis.x * axis.z, axis.y * axis.x, axis.y * axis.y, axis.y * axis.z,
							axis.z * axis.x, axis.z * axis.y, axis.z * axis.z).scale(iAxis - iPerp);
					inertia.add(m.add(uu));
					int k = Math.max(2, (int) Math.ceil(len / r) + 1);
					for (int i = 0; i < k; i++) {
						Vector3d c = new Vector3d(a).lerp(b, i / (double) (k - 1)).sub(com);
						sph.add(new double[] {c.x, c.y, c.z, r});
					}
				} else {
					inertia.add(new Matrix3d().identity().scale(0.4 * share * r * r));
					Vector3d c = new Vector3d(a).sub(com);
					sph.add(new double[] {c.x, c.y, c.z, r});
				}
			}
			inertia.scale(INERTIA_SCALE);
			invInertia.set(inertia).invert();
			spheres = sph.toArray(new double[0][]);
		}

		/** World inverse inertia applied to a world vector. */
		Vector3d invI(Vector3d vWorld) {
			Vector3d loc = new Quaterniond(q).conjugate().transform(new Vector3d(vWorld));
			invInertia.transform(loc);
			return q.transform(loc);
		}

		/** Generalized inverse mass for a correction along n at world offset r (from the centre of mass). */
		double weight(Vector3d r, Vector3d n) {
			Vector3d rn = new Vector3d(r).cross(n);
			return invMass + rn.dot(invI(rn));
		}

		void rotate(Vector3d rotWorld, double sign) {
			// q += 0.5 * [rot, 0] * q
			Quaterniond dq = new Quaterniond(rotWorld.x, rotWorld.y, rotWorld.z, 0).mul(q);
			q.x += 0.5 * sign * dq.x;
			q.y += 0.5 * sign * dq.y;
			q.z += 0.5 * sign * dq.z;
			q.w += 0.5 * sign * dq.w;
			q.normalize();
		}

		/** Bone frame in the world: rotation q, origin = centre of mass - R * com. */
		Vector3d boneOrigin(Vector3d pos, Quaterniond rot) {
			return new Vector3d(pos).sub(rot.transform(new Vector3d(com)));
		}
	}

	static final class Joint {
		final Body a;
		final Body b;
		final Vector3d pa;
		final Vector3d pb;
		final Quaterniond fa;
		final Quaterniond fb;
		final boolean hinge;
		final float[] swing;
		final float[] twist;

		Joint(RagdollDef.Joint d, Body a, Body b) {
			this.a = a;
			this.b = b;
			pa = new Vector3d(d.p1()[0], d.p1()[1], d.p1()[2]).sub(a.com);
			pb = new Vector3d(d.p2()[0], d.p2()[1], d.p2()[2]).sub(b.com);
			fa = new Quaterniond(d.q1()[0], d.q1()[1], d.q1()[2], d.q1()[3]).normalize();
			fb = new Quaterniond(d.q2()[0], d.q2()[1], d.q2()[2], d.q2()[3]).normalize();
			hinge = d.type() == 3;
			swing = d.swing();
			twist = d.twist();
		}
	}

	/** The world's collision boxes: every box overlapping the given bounds goes to {@code out} (minX, minY, minZ, maxX, maxY, maxZ). */
	interface World {
		void boxes(double x0, double y0, double z0, double x1, double y1, double z1, java.util.function.Consumer<double[]> out);
	}

	final Body[] bodies;
	final Joint[] joints;
	private final World level;
	private double still;
	boolean asleep;

	/**
	 * @param boneWorld bone index -> world matrix (3x4 row-major, Minecraft axes, metres) at the moment of death
	 */
	RagdollSim(RagdollDef def, World level, float[] boneWorld) {
		this.level = level;
		bodies = new Body[def.bodies.size()];
		for (int i = 0; i < bodies.length; i++) {
			Body b = new Body(def.bodies.get(i));
			int o = b.bone * 12;
			Matrix3d r = new Matrix3d(boneWorld[o], boneWorld[o + 4], boneWorld[o + 8], boneWorld[o + 1], boneWorld[o + 5], boneWorld[o + 9],
					boneWorld[o + 2], boneWorld[o + 6], boneWorld[o + 10]);
			b.q.setFromUnnormalized(r).normalize();
			Vector3d t = new Vector3d(boneWorld[o + 3], boneWorld[o + 7], boneWorld[o + 11]);
			b.x.set(t).add(b.q.transform(new Vector3d(b.com)));
			b.xTick.set(b.x);
			b.qTick.set(b.q);
			bodies[i] = b;
		}
		List<RagdollDef.Joint> jd = def.joints;
		joints = new Joint[jd.size()];
		for (int i = 0; i < joints.length; i++) {
			joints[i] = new Joint(jd.get(i), bodies[jd.get(i).body1()], bodies[jd.get(i).body2()]);
		}
	}

	/**
	 * Set every body moving (m/s) with some of {@code push}, then give {@code hit} an extra push. A blast ({@code whole})
	 * throws every body with all of it; a hit pushes the upper body more than the legs (15 % at the feet to 65 % at the
	 * head), so the body topples over away from it rather than sliding.
	 */
	void launch(Vector3d velocity, Body hit, Vector3d push, boolean whole) {
		double low = Double.MAX_VALUE;
		double high = -Double.MAX_VALUE;
		for (Body b : bodies) {
			low = Math.min(low, b.x.y);
			high = Math.max(high, b.x.y);
		}
		for (Body b : bodies) {
			double up = high - low < 1e-3 ? 0.5 : (b.x.y - low) / (high - low);
			b.v.set(velocity).add(new Vector3d(push).mul(whole ? 1 : 0.15 + 0.5 * up));
		}
		if (hit != null) {
			hit.v.add(push);
			hit.w.add(new Vector3d(push.z, 0, -push.x).mul(1.5)); // a little spin about the push
		}
	}

	/** Advance one Minecraft tick (0.05 s). */
	void tick() {
		for (Body b : bodies) {
			b.xTick.set(b.x);
			b.qTick.set(b.q);
		}
		if (asleep) {
			return;
		}
		double h = 0.05 / SUBSTEPS;
		for (int s = 0; s < SUBSTEPS; s++) {
			substep(h);
		}
		// asleep once nothing has moved more than 5 mm a tick for a second
		double moved = 0;
		for (Body b : bodies) {
			moved = Math.max(moved, b.x.distance(b.xTick));
		}
		still = moved < 0.005 ? still + 0.05 : 0;
		if (still > 1.0) {
			asleep = true;
		}
	}

	private void substep(double h) {
		for (Body b : bodies) {
			b.v.y -= GRAVITY * h;
			b.v.mul(1 / (1 + h * b.linearDamping));
			b.w.mul(1 / (1 + h * b.angularDamping));
			b.xp.set(b.x);
			b.qp.set(b.q);
			b.x.fma(h, b.v);
			Vector3d rot = new Vector3d(b.w).mul(h);
			b.rotate(rot, 1);
		}
		for (Joint j : joints) {
			solveAnchor(j);
			solveLimits(j);
		}
		for (Body b : bodies) {
			collide(b);
		}
		for (Body b : bodies) {
			b.v.set(b.x).sub(b.xp).div(h);
			Quaterniond dq = new Quaterniond(b.q).mul(new Quaterniond(b.qp).conjugate());
			double sgn = dq.w >= 0 ? 1 : -1;
			b.w.set(dq.x, dq.y, dq.z).mul(2 * sgn / h);
		}
	}

	/** Positional correction between two bodies: moves a's anchor towards b's by c along n. */
	private static void positional(Body a, Body b, Vector3d ra, Vector3d rb, Vector3d n, double c) {
		double wa = a.weight(ra, n);
		double wb = b == null ? 0 : b.weight(rb, n);
		if (wa + wb <= 1e-12) {
			return;
		}
		double lambda = c / (wa + wb);
		Vector3d p = new Vector3d(n).mul(lambda);
		a.x.fma(a.invMass, p);
		a.rotate(a.invI(new Vector3d(ra).cross(p)), 1);
		if (b != null) {
			b.x.fma(-b.invMass, p);
			b.rotate(b.invI(new Vector3d(rb).cross(p)), -1);
		}
	}

	/** Angular correction: turns b back relative to a by {@code angle} about the world axis. */
	private static void angular(Body a, Body b, Vector3d axis, double angle) {
		double wa = axis.dot(a.invI(axis));
		double wb = axis.dot(b.invI(axis));
		if (wa + wb <= 1e-12) {
			return;
		}
		Vector3d p = new Vector3d(axis).mul(angle / (wa + wb));
		a.rotate(a.invI(p), 1);
		b.rotate(b.invI(p), -1);
	}

	private static void solveAnchor(Joint j) {
		Vector3d ra = j.a.q.transform(new Vector3d(j.pa));
		Vector3d rb = j.b.q.transform(new Vector3d(j.pb));
		Vector3d d = new Vector3d(j.b.x).add(rb).sub(j.a.x).sub(ra);
		double c = d.length();
		if (c > 1e-6) {
			positional(j.a, j.b, ra, rb, d.div(c), c);
		}
	}

	private static void solveLimits(Joint j) {
		Quaterniond qa = new Quaterniond(j.a.q).mul(j.fa);
		Quaterniond qb = new Quaterniond(j.b.q).mul(j.fb);
		Quaterniond rel = new Quaterniond(qa).conjugate().mul(qb);
		if (rel.w < 0) {
			rel.set(-rel.x, -rel.y, -rel.z, -rel.w);
		}
		// twist about the frame's z axis (CS2's: a knee's or an elbow's hinge line, the hip's and spine's bend, the head's
		// turn), swing = the rest
		double tl = Math.hypot(rel.z, rel.w);
		Quaterniond twistQ = tl < 1e-9 ? new Quaterniond() : new Quaterniond(0, 0, rel.z / tl, rel.w / tl);
		double twist = 2 * Math.atan2(twistQ.z, twistQ.w);
		Quaterniond swingQ = new Quaterniond(rel).mul(new Quaterniond(twistQ).conjugate());
		if (swingQ.w < 0) {
			swingQ.set(-swingQ.x, -swingQ.y, -swingQ.z, -swingQ.w);
		}
		double swingSin = Math.sqrt(swingQ.x * swingQ.x + swingQ.y * swingQ.y + swingQ.z * swingQ.z);
		double swing = 2 * Math.atan2(swingSin, swingQ.w);
		double maxSwing = j.hinge ? 0 : j.swing != null ? j.swing[1] : Double.MAX_VALUE;
		if (swing > maxSwing && swingSin > 1e-9) {
			Vector3d axis = qa.transform(new Vector3d(swingQ.x / swingSin, swingQ.y / swingSin, swingQ.z / swingSin));
			angular(j.a, j.b, axis, swing - maxSwing);
		}
		if (j.twist != null) {
			double over = twist > j.twist[1] ? twist - j.twist[1] : twist < j.twist[0] ? twist - j.twist[0] : 0;
			if (over != 0) {
				Vector3d axis = qb.transform(new Vector3d(0, 0, 1)); // (the twist's axis: b's z, wherever the swing put it)
				angular(j.a, j.b, axis, over);
			}
		}
	}


	/** Each collision sphere against the block collision boxes around it. */
	private void collide(Body b) {
		for (double[] s : b.spheres) {
			Vector3d c = new Vector3d(b.x).add(b.q.transform(new Vector3d(s[0], s[1], s[2])));
			double rad = s[3];
			level.boxes(c.x - rad, c.y - rad, c.z - rad, c.x + rad, c.y + rad, c.z + rad, box -> contact(b, s, box));
		}
	}

	private void contact(Body b, double[] s, double[] box) {
		Vector3d r = b.q.transform(new Vector3d(s[0], s[1], s[2]));
		Vector3d c = new Vector3d(b.x).add(r);
		double rad = s[3];
		double px = Math.max(box[0], Math.min(c.x, box[3]));
		double py = Math.max(box[1], Math.min(c.y, box[4]));
		double pz = Math.max(box[2], Math.min(c.z, box[5]));
		Vector3d n = new Vector3d(c.x - px, c.y - py, c.z - pz);
		double dist = n.length();
		double depth;
		if (dist > 1e-9) {
			if (dist >= rad) {
				return;
			}
			n.div(dist);
			depth = rad - dist;
		} else {
			// the centre is inside the box: out along the shallowest face
			double[] pen = {c.x - box[0], box[3] - c.x, c.y - box[1], box[4] - c.y, c.z - box[2], box[5] - c.z};
			int k = 0;
			for (int i = 1; i < 6; i++) {
				if (pen[i] < pen[k]) {
					k = i;
				}
			}
			n.set(k == 0 ? -1 : k == 1 ? 1 : 0, k == 2 ? -1 : k == 3 ? 1 : 0, k == 4 ? -1 : k == 5 ? 1 : 0);
			depth = pen[k] + rad;
		}
		Vector3d contact = new Vector3d(c).fma(-rad, n);
		Vector3d rc = new Vector3d(contact).sub(b.x);
		positional(b, null, rc, null, n, depth);
		// friction: undo the contact point's sliding this substep (all of it while it sticks)
		Vector3d rNow = b.q.transform(new Vector3d(s[0], s[1], s[2]));
		Vector3d rPrev = b.qp.transform(new Vector3d(s[0], s[1], s[2]));
		Vector3d now = new Vector3d(b.x).add(rNow);
		Vector3d before = new Vector3d(b.xp).add(rPrev);
		Vector3d slide = now.sub(before);
		slide.fma(-slide.dot(n), n);
		double len = slide.length();
		if (len > 1e-9) {
			double fix = len < STATIC_FRICTION * depth ? len : Math.min(len, DYNAMIC_FRICTION * depth);
			Vector3d rc2 = new Vector3d(b.x).add(rNow).fma(-rad, n).sub(b.x);
			positional(b, null, rc2, null, slide.div(-len), fix);
		}
	}
}
