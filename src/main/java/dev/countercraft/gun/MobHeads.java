package dev.countercraft.gun;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.Vec3;

/**
 * Head hitboxes from Minecraft's own entity models: every cube of a mob's head part(s) (the "head" part with its hat,
 * nose, ears..., the Wither's three heads), read by the client from the baked models
 * ({@code dev.countercraft.client.MobHeadsClient}) and shared with the server. The main head turns with the mob's head
 * yaw and pitch, as its model does; a bullet through any head cube is a headshot, even where the head sticks out of the
 * mob's Minecraft box (a cow's or spider's head). The rest of the box is the body: legs below the model's leg tops
 * (CS2's leg multiplier), generic above.
 *
 * <p>Model space here: metres, Y up, the mob facing -Z, feet at the origin, before the body yaw; scaled at runtime by the
 * mob's box height over the model's height (babies, slime sizes, the scale attribute). Approximations: limbs other than
 * the head stay in the model's rest pose for the leg split; extra animation on the head (a ravager's roar) isn't
 * followed.</p>
 */
public final class MobHeads {
	/** One head part: its pivot and its cubes (min / max xyz, relative to the pivot); {@code follows}: turns with head yaw / pitch. */
	public record Part(float px, float py, float pz, float[] boxes, boolean follows) {
	}

	/** A mob type's model: its height (for scaling), the top of its legs, its head parts. */
	public record Shape(float modelHeight, float legTop, List<Part> parts) {
	}

	/** A head hit: distance along the ray (blocks), the point. */
	public record Hit(double distance, Vec3 point) {
	}

	private static volatile Map<String, Shape> shapes = Map.of();

	public static final StreamCodec<ByteBuf, Map<String, Shape>> CODEC = new StreamCodec<>() {
		@Override
		public Map<String, Shape> decode(ByteBuf b) {
			int n = Math.min(ByteBufCodecs.VAR_INT.decode(b), 1024);
			Map<String, Shape> m = new HashMap<>();
			for (int i = 0; i < n; i++) {
				String key = ByteBufCodecs.stringUtf8(128).decode(b);
				float h = b.readFloat();
				float leg = b.readFloat();
				int np = Math.min(ByteBufCodecs.VAR_INT.decode(b), 16);
				List<Part> parts = new ArrayList<>();
				for (int p = 0; p < np; p++) {
					float px = b.readFloat(), py = b.readFloat(), pz = b.readFloat();
					boolean follows = b.readBoolean();
					int nb = Math.min(ByteBufCodecs.VAR_INT.decode(b), 64 * 6);
					float[] boxes = new float[nb];
					for (int k = 0; k < nb; k++) {
						boxes[k] = b.readFloat();
					}
					parts.add(new Part(px, py, pz, boxes, follows));
				}
				m.put(key, new Shape(h, leg, parts));
			}
			return m;
		}

		@Override
		public void encode(ByteBuf b, Map<String, Shape> m) {
			ByteBufCodecs.VAR_INT.encode(b, m.size());
			m.forEach((key, s) -> {
				ByteBufCodecs.stringUtf8(128).encode(b, key);
				b.writeFloat(s.modelHeight());
				b.writeFloat(s.legTop());
				ByteBufCodecs.VAR_INT.encode(b, s.parts().size());
				for (Part p : s.parts()) {
					b.writeFloat(p.px());
					b.writeFloat(p.py());
					b.writeFloat(p.pz());
					b.writeBoolean(p.follows());
					ByteBufCodecs.VAR_INT.encode(b, p.boxes().length);
					for (float f : p.boxes()) {
						b.writeFloat(f);
					}
				}
			});
		}
	};

	private MobHeads() {
	}

	/** The client's table (singleplayer: directly; a server: the first player's, from {@code MobHeadsPayload}). */
	public static void set(Map<String, Shape> table) {
		shapes = Map.copyOf(table);
	}

	public static boolean known() {
		return !shapes.isEmpty();
	}

	private static Shape shape(Entity e) {
		if (e instanceof EnderDragonPart) {
			return null; // the dragon has its own head part
		}
		return shapes.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
	}

	/** This mob type has a model head to aim at. */
	public static boolean has(Entity e) {
		Shape s = shape(e);
		return s != null && !s.parts().isEmpty();
	}

	private static double scale(Entity e, Shape s) {
		return Math.max(0.05, Math.min(20, e.getBbHeight() / Math.max(0.05f, s.modelHeight())));
	}

	/** The nearest head cube the ray hits within {@code maxDist}, or null. */
	public static Hit trace(Entity e, Vec3 from, Vec3 dir, double maxDist) {
		Shape s = shape(e);
		if (s == null || s.parts().isEmpty()) {
			return null;
		}
		double sc = scale(e, s);
		float body = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
		// world -> model: undo the position and scale, then the body turn (the model is drawn turned by 180 - body yaw)
		double a = Math.toRadians(body - 180f);
		double ca = Math.cos(a), sa = Math.sin(a);
		double ox = (from.x - e.getX()) / sc, oy = (from.y - e.getY()) / sc, oz = (from.z - e.getZ()) / sc;
		double mox = ox * ca + oz * sa, moz = -ox * sa + oz * ca;
		double mdx = dir.x * ca + dir.z * sa, mdz = -dir.x * sa + dir.z * ca;
		double yaw = e instanceof LivingEntity le ? Math.toRadians(net.minecraft.util.Mth.wrapDegrees(le.getYHeadRot() - le.yBodyRot)) : 0;
		double pitch = Math.toRadians(e.getXRot());
		double best = Double.MAX_VALUE;
		for (Part p : s.parts()) {
			// model -> head: about the pivot, undo the head's turn (in this flipped frame it's Ry(-yaw) Rx(-pitch))
			double hx = mox - p.px(), hy = oy - p.py(), hz = moz - p.pz();
			double dx = mdx, dy = dir.y, dz = mdz;
			if (p.follows()) {
				double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
				// Ry(yaw)
				double tx = hx * cy + hz * sy, tz = -hx * sy + hz * cy;
				hx = tx;
				hz = tz;
				tx = dx * cy + dz * sy;
				tz = -dx * sy + dz * cy;
				dx = tx;
				dz = tz;
				// Rx(pitch)
				double ty = hy * cp - hz * sp;
				tz = hy * sp + hz * cp;
				hy = ty;
				hz = tz;
				ty = dy * cp - dz * sp;
				tz = dy * sp + dz * cp;
				dy = ty;
				dz = tz;
			}
			float[] b = p.boxes();
			for (int k = 0; k + 5 < b.length; k += 6) {
				double t = slab(hx, hy, hz, dx, dy, dz, b[k], b[k + 1], b[k + 2], b[k + 3], b[k + 4], b[k + 5]);
				if (t >= 0 && t < best) {
					best = t;
				}
			}
		}
		if (best == Double.MAX_VALUE) {
			return null;
		}
		double dist = best * sc;
		return dist > maxDist ? null : new Hit(dist, from.add(dir.scale(dist)));
	}

	/**
	 * For the hitbox debug view: each head box's 8 corners in world space (corner k: bit 0 = max x, bit 1 = max y,
	 * bit 2 = max z, in the head's own frame), exactly as {@link #trace} tests them.
	 */
	public static List<Vec3[]> worldBoxes(Entity e) {
		List<Vec3[]> out = new ArrayList<>();
		Shape s = shape(e);
		if (s == null) {
			return out;
		}
		double sc = scale(e, s);
		float body = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
		double a = Math.toRadians(180f - body);
		double ca = Math.cos(a), sa = Math.sin(a);
		double yaw = e instanceof LivingEntity le ? Math.toRadians(net.minecraft.util.Mth.wrapDegrees(le.getYHeadRot() - le.yBodyRot)) : 0;
		double pitch = Math.toRadians(e.getXRot());
		for (Part p : s.parts()) {
			float[] b = p.boxes();
			for (int k = 0; k + 5 < b.length; k += 6) {
				Vec3[] c = new Vec3[8];
				for (int i = 0; i < 8; i++) {
					double hx = (i & 1) == 0 ? b[k] : b[k + 3], hy = (i & 2) == 0 ? b[k + 1] : b[k + 4], hz = (i & 4) == 0 ? b[k + 2] : b[k + 5];
					if (p.follows()) {
						// head -> model: Ry(-yaw) Rx(-pitch)
						double cp = Math.cos(-pitch), sp = Math.sin(-pitch);
						double ty = hy * cp - hz * sp, tz = hy * sp + hz * cp;
						hy = ty;
						hz = tz;
						double cy = Math.cos(-yaw), sy = Math.sin(-yaw);
						double tx = hx * cy + hz * sy;
						tz = -hx * sy + hz * cy;
						hx = tx;
						hz = tz;
					}
					double mx = p.px() + hx, my = p.py() + hy, mz = p.pz() + hz;
					// model -> world: turned 180 - body yaw, scaled, at the mob
					c[i] = new Vec3(e.getX() + sc * (mx * ca + mz * sa), e.getY() + sc * my, e.getZ() + sc * (-mx * sa + mz * ca));
				}
				out.add(c);
			}
		}
		return out;
	}

	/** For the debug view: the height (blocks above the feet) below which body hits are leg hits, or -1. */
	public static double legHeight(Entity e) {
		Shape s = shape(e);
		return s == null || s.legTop() <= 0 ? -1 : s.legTop() * scale(e, s);
	}

	/** Hit group of a body hit (not the head): legs below the model's leg tops, otherwise generic. */
	public static int bodyGroup(Entity e, Vec3 point) {
		Shape s = shape(e);
		if (s == null || s.legTop() <= 0) {
			return Hitboxes.GENERIC;
		}
		return (point.y - e.getY()) / scale(e, s) < s.legTop() ? Hitboxes.LEFT_LEG : Hitboxes.GENERIC;
	}

	/** Ray (o, d) against a box: entry distance, 0 if inside, -1 for a miss. */
	private static double slab(double ox, double oy, double oz, double dx, double dy, double dz, double x0, double y0, double z0, double x1, double y1,
			double z1) {
		double tmin = 0, tmax = Double.MAX_VALUE;
		double[] o = {ox, oy, oz};
		double[] d = {dx, dy, dz};
		double[] lo = {x0, y0, z0};
		double[] hi = {x1, y1, z1};
		for (int i = 0; i < 3; i++) {
			if (Math.abs(d[i]) < 1e-12) {
				if (o[i] < lo[i] || o[i] > hi[i]) {
					return -1;
				}
			} else {
				double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
				tmin = Math.max(tmin, Math.min(t1, t2));
				tmax = Math.min(tmax, Math.max(t1, t2));
				if (tmin > tmax) {
					return -1;
				}
			}
		}
		return tmin;
	}
}
