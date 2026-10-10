package dev.countercraft.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The Zeus's lightning (Counter-Craft's look, in Minecraft's lightning style: its render type, its 0.45 / 0.45 / 0.5
 * colour, layered glowing strands):
 * <ul>
 * <li>Two arcs from the Zeus's prongs to where the shot landed, re-crackling every {@link #FLICKER_NS}, fading over
 * {@link #ARC_LIFE}.</li>
 * <li>A creature it hits is wrapped in crawling strands, as CS2 shows a zapped player: jagged lines hugging the body
 * (loops around it at random heights and runs down its sides), re-drawn every flicker, for {@link #WRAP_LIFE}, fading
 * at the end. Minecraft creatures are boxes to the game, so the strands follow the creature's box.</li>
 * </ul>
 * Worked out on each client from the shot (ShotFxPayload), so everyone who sees the shot sees it.
 */
public final class MiniLightning {
	private record Arc(Vec3 from, Vec3 to, Vec3 side, long seed, long born) {
	}

	private static final List<Arc> ARCS = new ArrayList<>();
	/** Zapped entity id -> when. */
	private static final Map<Integer, Long> WRAPS = new HashMap<>();
	private static final float ARC_LIFE = 0.3f;
	private static final float WRAP_LIFE = 2.0f;
	private static final long FLICKER_NS = 45_000_000L;
	/** The Zeus's prongs: the arcs leave this far either side of the muzzle. */
	private static final double PRONG = 0.035;
	private static final float R = 0.45f;
	private static final float G = 0.45f;
	private static final float B = 0.5f;

	private MiniLightning() {
	}

	/** A Zeus shot: two arcs, one from each prong, meeting where it landed; a creature there is wrapped. */
	public static void shot(Entity shooter, Vec3 from, Vec3 to) {
		Vec3 look = shooter.getViewVector(1f);
		Vec3 side = look.cross(new Vec3(0, 1, 0));
		side = side.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : side.normalize();
		long seed = RandomSource.create().nextLong();
		long now = System.nanoTime();
		ARCS.add(new Arc(from.add(side.scale(PRONG)), to, side, seed, now));
		ARCS.add(new Arc(from.add(side.scale(-PRONG)), to, side, seed * 31 + 7, now));
		var level = Minecraft.getInstance().level;
		if (level != null) {
			Entity hit = null;
			double best = Double.MAX_VALUE;
			// dying counts too: the Zeus usually kills, and the creature is still there for its death animation
			for (Entity e : level.getEntities(shooter, new AABB(to, to).inflate(1.0), e -> e instanceof LivingEntity)) {
				AABB box = e.getBoundingBox().inflate(0.3);
				if (box.contains(to) && e.getBoundingBox().getCenter().distanceToSqr(to) < best) {
					best = e.getBoundingBox().getCenter().distanceToSqr(to);
					hit = e;
				}
			}
			if (hit != null) {
				WRAPS.put(hit.getId(), now);
			}
		}
	}

	public static void submit(LevelRenderContext ctx) {
		if (ARCS.isEmpty() && WRAPS.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = mc.gameRenderer.mainCamera().position();
		long now = System.nanoTime();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<float[]> segs = new ArrayList<>();
		for (Iterator<Arc> it = ARCS.iterator(); it.hasNext(); ) {
			Arc a = it.next();
			float t = (now - a.born()) / 1e9f;
			if (t >= ARC_LIFE) {
				it.remove();
				continue;
			}
			arc(segs, a.from().subtract(eye), a.to().subtract(eye), a.side(), a.seed() + (now - a.born()) / FLICKER_NS, 1f - t / ARC_LIFE, 1f);
		}
		for (Iterator<Map.Entry<Integer, Long>> it = WRAPS.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Long> w = it.next();
			float t = (now - w.getValue()) / 1e9f;
			Entity e = mc.level == null ? null : mc.level.getEntity(w.getKey());
			if (t >= WRAP_LIFE || e == null || e.isRemoved()) {
				it.remove();
				continue;
			}
			float fade = t < WRAP_LIFE - 0.5f ? 1f : (WRAP_LIFE - t) / 0.5f;
			// the box where the creature is drawn this frame
			Vec3 pos = e.getPosition(partial);
			AABB box = e.getBoundingBox().move(pos.subtract(e.position())).inflate(0.04).move(eye.scale(-1));
			wrap(segs, box, w.getKey() * 7919L + (now - w.getValue()) / FLICKER_NS, fade);
		}
		if (segs.isEmpty()) {
			return;
		}
		ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.lightning(), (pose, vc) -> {
			for (float[] s : segs) {
				segment(pose.pose(), vc, s);
			}
		});
	}

	/** An arc: a jagged line from a to b, its kinks largest mid-way (the ends stay put). */
	private static void arc(List<float[]> out, Vec3 a, Vec3 b, Vec3 side, long seed, float fade, float width) {
		Vec3 d = b.subtract(a);
		double len = d.length();
		if (len < 0.05) {
			return;
		}
		int n = (int) Math.max(6, Math.min(60, len * 4));
		RandomSource r = RandomSource.create(seed);
		Vec3 dir = d.scale(1 / len);
		Vec3 up = dir.cross(side);
		up = up.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : up.normalize();
		Vec3 sd = up.cross(dir).normalize();
		double jag = Math.min(0.25, 0.04 + len * 0.02);
		Vec3 prev = a;
		for (int i = 1; i <= n; i++) {
			double k = i / (double) n;
			double env = Math.sin(Math.PI * k);
			Vec3 p = i == n ? b
					: a.add(d.scale(k)).add(sd.scale((r.nextDouble() - 0.5) * 2 * jag * env)).add(up.scale((r.nextDouble() - 0.5) * 2 * jag * env));
			add(out, prev, p, fade, width);
			prev = p;
		}
	}

	/**
	 * Strands over a box: loops part-way round it at random heights and runs down its sides, each a jagged walk on the
	 * box's surface.
	 */
	private static void wrap(List<float[]> out, AABB box, long seed, float fade) {
		RandomSource r = RandomSource.create(seed);
		double w = box.getXsize();
		double d = box.getZsize();
		double h = box.getYsize();
		double per = 2 * (w + d);
		double step = Math.max(0.06, Math.min(0.14, per / 24));
		int strands = 5 + (int) Math.min(6, h * 2.5);
		for (int s = 0; s < strands; s++) {
			Vec3 prev = null;
			if (r.nextInt(3) > 0) {
				// round the body: along the perimeter at a height, wandering up and down
				double u = r.nextDouble() * per;
				double y = box.minY + h * (0.08 + 0.84 * r.nextDouble());
				int n = (int) (per * (0.3 + 0.4 * r.nextDouble()) / step);
				for (int i = 0; i <= n; i++) {
					Vec3 p = onBox(box, u + i * step, Math.max(box.minY, Math.min(box.maxY, y)));
					if (prev != null) {
						add(out, prev, p, fade, 0.7f);
					}
					prev = p;
					y += (r.nextDouble() - 0.5) * step * 1.6;
				}
			} else {
				// down a side, zig-zagging
				double u = r.nextDouble() * per;
				double y = box.maxY - h * 0.15 * r.nextDouble();
				int n = (int) (h * (0.4 + 0.5 * r.nextDouble()) / step);
				for (int i = 0; i <= n; i++) {
					Vec3 p = onBox(box, u, y);
					if (prev != null) {
						add(out, prev, p, fade, 0.7f);
					}
					prev = p;
					y -= step;
					u += (r.nextDouble() - 0.5) * step * 2;
					if (y < box.minY) {
						break;
					}
				}
			}
		}
	}

	/** The point {@code u} blocks round the box's perimeter (anticlockwise from its min corner), at height y. */
	private static Vec3 onBox(AABB b, double u, double y) {
		double w = b.getXsize();
		double d = b.getZsize();
		double per = 2 * (w + d);
		u = ((u % per) + per) % per;
		if (u < w) {
			return new Vec3(b.minX + u, y, b.minZ);
		}
		u -= w;
		if (u < d) {
			return new Vec3(b.maxX, y, b.minZ + u);
		}
		u -= d;
		if (u < w) {
			return new Vec3(b.maxX - u, y, b.maxZ);
		}
		u -= w;
		return new Vec3(b.minX, y, b.maxZ - u);
	}

	private static void add(List<float[]> out, Vec3 a, Vec3 b, float fade, float width) {
		Vec3 dir = b.subtract(a);
		if (dir.lengthSqr() < 1e-8) {
			return;
		}
		dir = dir.normalize();
		Vec3 ax1 = dir.cross(Math.abs(dir.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
		Vec3 ax2 = dir.cross(ax1).normalize();
		out.add(new float[] {(float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, (float) ax1.x, (float) ax1.y, (float) ax1.z,
				(float) ax2.x, (float) ax2.y, (float) ax2.z, fade, width});
	}

	/** One segment, as Minecraft draws lightning: layered glowing ribbons (a bright core, wider fainter glow), crossed. */
	private static void segment(Matrix4f m, VertexConsumer vc, float[] s) {
		for (int pass = 0; pass < 3; pass++) {
			float w = (0.005f + pass * 0.009f) * s[13];
			float a = (pass == 0 ? 0.9f : pass == 1 ? 0.55f : 0.3f) * s[12];
			for (int axis = 0; axis < 2; axis++) {
				float ox = (axis == 0 ? s[6] : s[9]) * w;
				float oy = (axis == 0 ? s[7] : s[10]) * w;
				float oz = (axis == 0 ? s[8] : s[11]) * w;
				vc.addVertex(m, s[0] - ox, s[1] - oy, s[2] - oz).setColor(R, G, B, a);
				vc.addVertex(m, s[3] - ox, s[4] - oy, s[5] - oz).setColor(R, G, B, a);
				vc.addVertex(m, s[3] + ox, s[4] + oy, s[5] + oz).setColor(R, G, B, a);
				vc.addVertex(m, s[0] + ox, s[1] + oy, s[2] + oz).setColor(R, G, B, a);
			}
		}
	}
}
