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
 * <li>A creature it hits (or the water it's in shocks: ShockPayload) is wrapped in bolts as CS2 wraps a Zeus victim,
 * built like Minecraft's lightning bolt ({@link BoltFx}): a net laid once per zap that stays on the body (only its
 * brightness shimmers), all of it at first, then bolt by bolt going out, gone after {@link #WRAP_LIFE}. A mob's runs
 * over its own model ({@link ShockLayer}); a player's (a CS2 agent, not a Minecraft model) over its box: up the body and
 * in rings round it.</li>
 * </ul>
 * Worked out on each client from the shot (ShotFxPayload), so everyone who sees the shot sees it.
 */
public final class MiniLightning {
	private record Arc(Vec3 from, Vec3 to, Vec3 side, long seed, long born) {
	}

	private static final List<Arc> ARCS = new ArrayList<>();
	/** Zapped entity id -> when. */
	private static final Map<Integer, Long> WRAPS = new HashMap<>();
	/** Entity id -> when ShockLayer last drew its strands (on its model, so not on its box). */
	private static final Map<Integer, Long> ON_MODEL = new HashMap<>();
	private static final float ARC_LIFE = 0.3f;
	/**
	 * A wrap's life, as CS2's (from footage of Zeus kills: the whole net for about a third of a second, then bolt by bolt
	 * it goes out), over Minecraft's one-second death animation.
	 */
	static final float WRAP_LIFE = 1.0f;
	/** All of a wrap's bolts are there this long; then each goes out at its own moment, the last at WRAP_LIFE. */
	private static final float WRAP_FULL = 0.33f;
	/** A bolt fades in over this long, and out over FADE_OUT. */
	private static final float FADE_IN = 0.04f;
	private static final float FADE_OUT = 0.12f;
	static final long FLICKER_NS = 45_000_000L;
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

	/** The server shocked these creatures (WaterShock): wrapped as if hit. */
	public static void shocked(int[] ids) {
		long now = System.nanoTime();
		for (int id : ids) {
			WRAPS.put(id, now);
		}
	}

	/** When this entity was zapped (System.nanoTime), or -1 if it isn't wrapped now. */
	public static long zappedAt(int id) {
		Long at = WRAPS.get(id);
		return at == null || (System.nanoTime() - at) / 1e9f >= WRAP_LIFE ? -1 : at;
	}

	/** When a wrap's bolt goes out (seconds in): after WRAP_FULL, at its own moment. */
	static float boltEnd(RandomSource r) {
		return WRAP_FULL + FADE_OUT + (WRAP_LIFE - WRAP_FULL - FADE_OUT) * r.nextFloat();
	}

	/** A bolt's strength {@code t} seconds into its wrap: fading in, then full until it fades out by {@code end}. */
	static float boltAlpha(float t, float end) {
		return Math.max(0f, Math.min(1f, Math.min(t / FADE_IN, (end - t) / FADE_OUT)));
	}

	/** A bolt's shimmer: its brightness wavers a little (its shape stays put), 0.82-1, changing every flicker. */
	static float shimmer(long seed, long now) {
		return 0.82f + 0.18f * RandomSource.create(seed + now / FLICKER_NS).nextFloat();
	}

	static void drawnOnModel(int id) {
		ON_MODEL.put(id, System.nanoTime());
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
		List<float[]> bolts = new ArrayList<>();
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
				ON_MODEL.remove(w.getKey());
				continue;
			}
			Long model = ON_MODEL.get(w.getKey());
			if (model != null && now - model < 100_000_000L) {
				continue; // ShockLayer draws it on its model
			}
			// the box where the creature is drawn this frame
			Vec3 pos = e.getPosition(partial);
			AABB box = e.getBoundingBox().move(pos.subtract(e.position())).inflate(0.04).move(eye.scale(-1));
			wrap(bolts, box, w.getKey() * 7919L ^ w.getValue(), t, now);
		}
		if (segs.isEmpty() && bolts.isEmpty()) {
			return;
		}
		ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), RenderTypes.lightning(), (pose, vc) -> {
			for (float[] s : bolts) {
				BoltFx.draw(pose.pose(), vc, s);
			}
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
	 * Bolts round a box (a player, a CS2 agent: no Minecraft model to wrap), as ShockLayer's round a model: up the body
	 * from near the feet to the shoulders, and rings round it at some height. The same net every frame of the wrap
	 * ({@code seed}: the zap's), each bolt going out at its own moment.
	 */
	private static void wrap(List<float[]> out, AABB box, long seed, float t, long now) {
		RandomSource net = RandomSource.create(seed);
		double h = box.getYsize();
		double per = 2 * (box.getXsize() + box.getZsize());
		int bolts = (int) Math.round(3 + h * 2.5);
		for (int b = 0; b < bolts; b++) {
			long boltSeed = net.nextLong();
			float alpha = boltAlpha(t, boltEnd(net)) * shimmer(boltSeed, now);
			if (alpha <= 0f) {
				continue;
			}
			RandomSource r = RandomSource.create(boltSeed);
			List<Vec3> path = new ArrayList<>();
			double u = r.nextDouble() * per;
			if (r.nextInt(3) == 0) {
				// a ring round the body
				double y = box.minY + h * (0.2 + 0.65 * r.nextDouble());
				double reach = per * (0.5 + 0.5 * r.nextDouble());
				for (double done = 0; done <= reach; done += per / 8) {
					path.add(onBox(box, u + done, y + (r.nextDouble() - 0.5) * 0.08));
				}
			} else {
				// up the body, wandering round it
				double y = box.minY + h * 0.1 * r.nextDouble();
				double top = box.minY + h * (0.6 + 0.4 * r.nextDouble());
				for (; y < top; y += h / 5) {
					path.add(onBox(box, u, y));
					u += (r.nextDouble() - 0.5) * per * 0.3;
				}
			}
			BoltFx.bolt(out, path, r, alpha, 1f);
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

	static void add(List<float[]> out, Vec3 a, Vec3 b, float fade, float width) {
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
