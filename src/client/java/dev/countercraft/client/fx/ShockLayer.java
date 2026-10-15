package dev.countercraft.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.mixin.ModelPartAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The Zeus's shock on a creature's own model, as CS2 shows a Zeus victim (from CS2 gameplay footage): a net of bright
 * jagged bolts laid over the body like an electric blanket, up the legs, across the body and over the shoulders to the
 * head, along the arms and round the middle, a little off the surface, with forks. The net is made once per zap and
 * stays on the body's parts (it moves as they move, and as the creature falls); only its brightness shimmers. All of it
 * is there at first, then bolt by bolt it goes out (MiniLightning's timing). Each bolt is built like Minecraft's
 * lightning bolt ({@link BoltFx}). Players are CS2 agents, not Minecraft models: MiniLightning wraps their box the same
 * way.
 */
public final class ShockLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends RenderLayer<S, M> {
	/** Which creature this render state is, and when it was zapped (-1: it isn't); set in ShockStateMixin. */
	public record Zap(int id, long at) {
		public static final Zap NONE = new Zap(-1, -1);
	}

	/**
	 * A posed cube: its box in its part's space (blocks), the part's pose (and its inverse), its middle in model space,
	 * its surface area.
	 */
	private record Cube(ModelPart.Cube src, double[] min, double[] max, Matrix4f pose, Matrix4f inverse, Vec3 mid, double area) {
	}

	/**
	 * A bolt's point, on a cube: {@code u} round its cross-section across {@code axis}, {@code h} along it (its part's
	 * space, blocks). It stays there on the body however the body moves.
	 */
	private record Anchor(ModelPart.Cube cube, int axis, double u, double h) {
	}

	/** One bolt of a zap's net: its points, its own random (its zig-zag), when it goes out. */
	private record Bolt(List<Anchor> anchors, long seed, float end) {
	}

	public static final RenderStateDataKey<Zap> ZAP = RenderStateDataKey.create(() -> "countercraft:zap");
	/** Dev: -Dcountercraft.zapDemo=true keeps every creature but players zapped, and screenshots it every 0.1 s for 0.8 s after joining. */
	public static final boolean DEMO = Boolean.getBoolean("countercraft.zapDemo");
	/**
	 * Bolts' middles sit this far off the surface (blocks): their thickest glow layer (BoltFx) and their zig-zag just
	 * clear of it, so a bolt lies on the body instead of dipping in and out of it.
	 */
	private static final double LIFT = 0.04;
	/** A bolt's path is pulled onto the body every this far (blocks): round a part, and over the body between parts. */
	private static final double SURFACE_STEP = 0.05;
	/** Each zap's net, made the first time it's drawn. */
	private static final Map<Zap, List<Bolt>> NETS = new HashMap<>();

	private ShockLayer(RenderLayerParent<S, M> parent) {
		super(parent);
	}

	/** On every creature's renderer. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void register() {
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> helper.register(new ShockLayer(renderer)));
		if (DEMO) {
			int[] ticks = {0};
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
				if (mc.player != null && mc.gui.screen() == null && ++ticks[0] >= 100 && ticks[0] <= 114 && ticks[0] % 2 == 0) {
					net.minecraft.client.Screenshot.grab(mc.gameDirectory, "cc2-shock-demo-" + ticks[0] + ".png", mc.gameRenderer.mainRenderTarget(), 1,
							msg -> CounterCraft.LOG.info("[countercraft] zap demo: {}", msg.getString()));
				}
			});
		}
	}

	@Override
	public void submit(PoseStack ps, SubmitNodeCollector collector, int light, S state, float yRot, float xRot) {
		Zap zap = ((FabricRenderState) state).getDataOrDefault(ZAP, Zap.NONE);
		if (zap.at() < 0 || state.isInvisible) {
			return;
		}
		long now = System.nanoTime();
		float t = (now - zap.at()) / 1e9f;
		if (t >= MiniLightning.WRAP_LIFE) {
			return;
		}
		// the cubes as the model is posed for this creature now
		getParentModel().setupAnim(state);
		List<Cube> cubes = new ArrayList<>();
		parts(getParentModel().root(), new PoseStack(), cubes);
		if (cubes.isEmpty()) {
			return;
		}
		double total = 0;
		double cx = 0;
		double cy = 0;
		double cz = 0;
		Map<ModelPart.Cube, Cube> posed = new IdentityHashMap<>();
		for (Cube c : cubes) {
			total += c.area();
			cx += c.mid().x * c.area();
			cy += c.mid().y * c.area();
			cz += c.mid().z * c.area();
			posed.put(c.src(), c);
		}
		Vec3 centre = new Vec3(cx / total, cy / total, cz / total);
		if (NETS.size() > 64) {
			NETS.keySet().removeIf(z -> (now - z.at()) / 1e9f >= MiniLightning.WRAP_LIFE);
		}
		double area = total;
		List<Bolt> net = NETS.computeIfAbsent(zap, z -> net(z, cubes, centre, area));
		List<float[]> steps = new ArrayList<>();
		for (Bolt bolt : net) {
			float alpha = MiniLightning.boltAlpha(t, bolt.end()) * MiniLightning.shimmer(bolt.seed(), now);
			if (alpha <= 0f) {
				continue;
			}
			List<Vec3> path = new ArrayList<>();
			List<Anchor> anchors = bolt.anchors();
			for (int i = 0; i < anchors.size(); i++) {
				Anchor a = anchors.get(i);
				Cube c = posed.get(a.cube());
				if (c == null) {
					continue;
				}
				path.add(at(c, a.axis(), a.u(), a.h()));
				// on to the next point on the same part: round its surface (over its corners), not across it
				Anchor b = i + 1 < anchors.size() ? anchors.get(i + 1) : null;
				if (b != null && b.cube() == a.cube() && b.axis() == a.axis()) {
					double per = perimeter(c, a.axis());
					double du = b.u() - a.u();
					du -= per * Math.round(du / per); // the shorter way round
					double dh = b.h() - a.h();
					int n = (int) Math.ceil(Math.abs(du) / SURFACE_STEP + Math.abs(dh) / SURFACE_STEP);
					for (int k = 1; k < n; k++) {
						path.add(at(c, a.axis(), a.u() + du * k / n, a.h() + dh * k / n));
					}
				}
			}
			if (path.size() > 1) {
				BoltFx.bolt(steps, shrinkWrap(path, cubes), RandomSource.create(bolt.seed()), alpha, 1f);
			}
		}
		if (!steps.isEmpty()) {
			collector.submitCustomGeometry(ps, RenderTypes.lightning(), (pose, vc) -> {
				for (float[] s : steps) {
					BoltFx.draw(pose.pose(), vc, s);
				}
			});
		}
		MiniLightning.drawnOnModel(zap.id());
	}

	/** A zap's net: a 2-block creature gets ~9 bolts, laid over the whole body, each going out at its own moment. */
	private static List<Bolt> net(Zap zap, List<Cube> cubes, Vec3 centre, double total) {
		RandomSource r = RandomSource.create(zap.id() * 7919L ^ zap.at());
		int n = (int) Math.round(3 + Math.sqrt(total) * 2);
		List<Bolt> out = new ArrayList<>();
		for (int b = 0; b < n; b++) {
			List<Anchor> anchors = r.nextInt(4) == 0 ? loop(cubes, r) : across(cubes, r, centre, total);
			out.add(new Bolt(anchors, r.nextLong(), MiniLightning.boltEnd(r)));
		}
		return out;
	}

	/**
	 * A bolt across the body: from a cube (picked by size) on to the nearest ones, three to five of them, through a point on
	 * each (the outside of the body); a long cube (a limb) is crossed end to end.
	 */
	private static List<Anchor> across(List<Cube> cubes, RandomSource r, Vec3 centre, double total) {
		List<Anchor> path = new ArrayList<>();
		Cube c = pick(cubes, r, total);
		List<Cube> seen = new ArrayList<>();
		int n = 3 + r.nextInt(3);
		for (int i = 0; i < n && c != null; i++) {
			seen.add(c);
			int axis = longest(c);
			double len = c.max()[axis] - c.min()[axis];
			boolean limb = len > 2 * Math.min(c.max()[(axis + 1) % 3] - c.min()[(axis + 1) % 3], c.max()[(axis + 2) % 3] - c.min()[(axis + 2) % 3]);
			path.add(onSurface(c, r, centre, limb ? 0.05 + 0.15 * r.nextDouble() : -1));
			if (limb) {
				path.add(onSurface(c, r, centre, 0.8 + 0.15 * r.nextDouble()));
			}
			Anchor last = path.get(path.size() - 1);
			c = nearest(cubes, seen, point(c.pose(), onCube(c, last.axis(), last.u(), last.h())), r);
		}
		return path;
	}

	/** A loop round the body's biggest part at some height: CS2's ring round the middle. */
	private static List<Anchor> loop(List<Cube> cubes, RandomSource r) {
		Cube c = cubes.get(0);
		for (Cube k : cubes) {
			if (k.area() > c.area()) {
				c = k;
			}
		}
		int axis = longest(c);
		int p = (axis + 1) % 3;
		int q = (axis + 2) % 3;
		double h = c.min()[axis] + (c.max()[axis] - c.min()[axis]) * (0.15 + 0.7 * r.nextDouble());
		double per = 2 * ((c.max()[p] - c.min()[p]) + (c.max()[q] - c.min()[q]));
		double u = r.nextDouble() * per;
		double reach = per * (0.55 + 0.45 * r.nextDouble());
		List<Anchor> path = new ArrayList<>();
		for (double done = 0; done <= reach; done += per / 8) {
			path.add(new Anchor(c.src(), axis, u + done, h + (r.nextDouble() - 0.5) * 0.06));
		}
		return path;
	}

	/** A point on the cube's surface ({@code along}: that far along its long axis, 0-1; -1: anywhere), on its outer side. */
	private static Anchor onSurface(Cube c, RandomSource r, Vec3 centre, double along) {
		int axis = longest(c);
		double h = along < 0 ? c.min()[axis] + (c.max()[axis] - c.min()[axis]) * r.nextDouble() : c.min()[axis] + (c.max()[axis] - c.min()[axis]) * along;
		double per = 2 * ((c.max()[(axis + 1) % 3] - c.min()[(axis + 1) % 3]) + (c.max()[(axis + 2) % 3] - c.min()[(axis + 2) % 3]));
		// the side facing out from the body's middle, mostly (CS2's bolts run round the outside of the body)
		double best = 0;
		double bestOut = -Double.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			double u = r.nextDouble() * per;
			double out = point(c.pose(), onCube(c, axis, u, h)).subtract(c.mid()).dot(c.mid().subtract(centre));
			if (out > bestOut) {
				bestOut = out;
				best = u;
			}
		}
		return new Anchor(c.src(), axis, best, h);
	}

	/** The point {@code u} round / {@code h} along the posed cube, on its surface (model space). */
	private static Vec3 at(Cube c, int axis, double u, double h) {
		return point(c.pose(), onCube(c, axis, u, h));
	}

	private static double perimeter(Cube c, int axis) {
		return 2 * ((c.max()[(axis + 1) % 3] - c.min()[(axis + 1) % 3]) + (c.max()[(axis + 2) % 3] - c.min()[(axis + 2) % 3]));
	}

	/**
	 * The path shrink-wrapped onto the body: every SURFACE_STEP along it pulled onto the nearest outside surface of the
	 * model (so between two parts it goes over the body, not through it or out round it), then LIFT off it.
	 */
	private static List<Vec3> shrinkWrap(List<Vec3> path, List<Cube> cubes) {
		List<Vec3> out = new ArrayList<>();
		for (int i = 0; i < path.size(); i++) {
			Vec3 a = path.get(i);
			out.add(snap(a, cubes));
			if (i + 1 < path.size()) {
				Vec3 b = path.get(i + 1);
				int n = (int) Math.ceil(a.distanceTo(b) / SURFACE_STEP);
				for (int k = 1; k < n; k++) {
					out.add(snap(a.add(b.subtract(a).scale(k / (double) n)), cubes));
				}
			}
		}
		return out;
	}

	/**
	 * The nearest point of the model's outside surface to {@code p}, LIFT off it: on whichever cube's surface is nearest,
	 * pushed out of a cube it's inside, and not one another cube covers (a head's hat layer, an arm against the body).
	 */
	private static Vec3 snap(Vec3 p, List<Cube> cubes) {
		Vec3 best = p;
		double bestD = Double.MAX_VALUE;
		Vec3 covered = p;
		double coveredD = Double.MAX_VALUE;
		for (Cube c : cubes) {
			Vector3f l = c.inverse().transformPosition((float) p.x, (float) p.y, (float) p.z, new Vector3f());
			double[] q = {l.x, l.y, l.z};
			double[] n = new double[3];
			double d;
			boolean inside = true;
			for (int i = 0; i < 3; i++) {
				inside &= q[i] > c.min()[i] && q[i] < c.max()[i];
			}
			if (inside) {
				// out through the nearest face
				int axis = 0;
				double side = -1;
				double least = Double.MAX_VALUE;
				for (int i = 0; i < 3; i++) {
					if (q[i] - c.min()[i] < least) {
						least = q[i] - c.min()[i];
						axis = i;
						side = -1;
					}
					if (c.max()[i] - q[i] < least) {
						least = c.max()[i] - q[i];
						axis = i;
						side = 1;
					}
				}
				q[axis] = side < 0 ? c.min()[axis] : c.max()[axis];
				n[axis] = side;
				d = least;
			} else {
				double[] o = new double[3];
				double len = 0;
				for (int i = 0; i < 3; i++) {
					double v = Math.max(c.min()[i], Math.min(c.max()[i], q[i]));
					o[i] = q[i] - v;
					q[i] = v;
					len += o[i] * o[i];
				}
				len = Math.sqrt(len);
				for (int i = 0; i < 3; i++) {
					n[i] = len > 1e-9 ? o[i] / len : 0;
				}
				d = len;
			}
			Vec3 s = point(c.pose(), new double[] {q[0] + n[0] * LIFT, q[1] + n[1] * LIFT, q[2] + n[2] * LIFT});
			if (insideAnother(s, c, cubes)) {
				if (d < coveredD) {
					coveredD = d;
					covered = s;
				}
			} else if (d < bestD) {
				bestD = d;
				best = s;
			}
		}
		return bestD < Double.MAX_VALUE ? best : covered;
	}

	private static boolean insideAnother(Vec3 p, Cube self, List<Cube> cubes) {
		for (Cube c : cubes) {
			if (c != self) {
				Vector3f l = c.inverse().transformPosition((float) p.x, (float) p.y, (float) p.z, new Vector3f());
				if (l.x > c.min()[0] && l.x < c.max()[0] && l.y > c.min()[1] && l.y < c.max()[1] && l.z > c.min()[2] && l.z < c.max()[2]) {
					return true;
				}
			}
		}
		return false;
	}

	private static Cube pick(List<Cube> cubes, RandomSource r, double total) {
		double x = r.nextDouble() * total;
		for (Cube c : cubes) {
			x -= c.area();
			if (x <= 0) {
				return c;
			}
		}
		return cubes.get(cubes.size() - 1);
	}

	/** One of the two cubes nearest to {@code from} not visited yet, or null. */
	private static Cube nearest(List<Cube> cubes, List<Cube> seen, Vec3 from, RandomSource r) {
		Cube a = null;
		Cube b = null;
		double da = Double.MAX_VALUE;
		double db = Double.MAX_VALUE;
		for (Cube c : cubes) {
			if (seen.contains(c)) {
				continue;
			}
			double d = c.mid().distanceToSqr(from);
			if (d < da) {
				b = a;
				db = da;
				a = c;
				da = d;
			} else if (d < db) {
				b = c;
				db = d;
			}
		}
		return b != null && r.nextBoolean() ? b : a;
	}

	private static int longest(Cube c) {
		double x = c.max()[0] - c.min()[0];
		double y = c.max()[1] - c.min()[1];
		double z = c.max()[2] - c.min()[2];
		return x >= y && x >= z ? 0 : y >= z ? 1 : 2;
	}

	/** The point {@code u} round the cube's cross-section (across its {@code axis}), at {@code h} along the axis (local). */
	private static double[] onCube(Cube c, int axis, double u, double h) {
		double[] min = c.min();
		double[] max = c.max();
		int p = (axis + 1) % 3;
		int q = (axis + 2) % 3;
		double sp = max[p] - min[p];
		double sq = max[q] - min[q];
		double per = 2 * (sp + sq);
		u = ((u % per) + per) % per;
		double[] out = new double[3];
		out[axis] = Math.max(min[axis], Math.min(max[axis], h));
		if (u < sp) {
			out[p] = min[p] + u;
			out[q] = min[q];
		} else if ((u -= sp) < sq) {
			out[p] = max[p];
			out[q] = min[q] + u;
		} else if ((u -= sq) < sp) {
			out[p] = max[p] - u;
			out[q] = max[q];
		} else {
			u -= sp;
			out[p] = min[p];
			out[q] = max[q] - u;
		}
		return out;
	}

	/** Each shown part's cubes, posed as ModelPart.render poses them (from the model's root). */
	private static void parts(ModelPart part, PoseStack ps, List<Cube> out) {
		if (!part.visible) {
			return;
		}
		ps.pushPose();
		part.translateAndRotate(ps);
		if (!part.skipDraw) {
			for (ModelPart.Cube c : ((ModelPartAccessor) (Object) part).countercraft$cubes()) {
				double[] min = {c.minX / 16.0, c.minY / 16.0, c.minZ / 16.0};
				double[] max = {c.maxX / 16.0, c.maxY / 16.0, c.maxZ / 16.0};
				double sx = max[0] - min[0];
				double sy = max[1] - min[1];
				double sz = max[2] - min[2];
				double area = 2 * (sx * sy + sy * sz + sx * sz);
				if (area > 0.004) { // not a sliver (a flat plane's edge, a whisker)
					Matrix4f m = new Matrix4f(ps.last().pose());
					if (Math.abs(m.determinant()) > 1e-9) { // a part scaled flat can't be wrapped
						out.add(new Cube(c, min, max, m, new Matrix4f(m).invert(),
								point(m, new double[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2}), area));
					}
				}
			}
		}
		for (ModelPart child : ((ModelPartAccessor) (Object) part).countercraft$children().values()) {
			parts(child, ps, out);
		}
		ps.popPose();
	}

	private static Vec3 point(Matrix4f m, double[] p) {
		Vector3f v = m.transformPosition((float) p[0], (float) p[1], (float) p[2], new Vector3f());
		return new Vec3(v.x, v.y, v.z);
	}
}
