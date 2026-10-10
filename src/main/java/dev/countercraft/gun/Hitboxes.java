package dev.countercraft.gun;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * CS2 hitboxes: the agent model's own "cstrike" hitbox set (19 capsules on bones, converter/agent/agent.py
 * write_hitboxes), posed once in CS2's standing and crouching rifle idles by the client (which has the animation data)
 * and handed over here in model space: metres, X = the agent's left, Y = up, Z = forward, feet at the origin.
 *
 * <p>Players use them as they are; humanoid mobs (zombies, skeletons, illagers, villagers, piglins, witches) use them
 * scaled to their height. Everything else keeps its Minecraft box (head = the top part, see
 * {@link Ballistics#headshot}). Without the CS2 pack (or on a dedicated server, which never sees the client's
 * assets) every entity keeps its box.</p>
 *
 * <p>Approximations: the pose is fixed (idle, standing or crouched) and turns with the body, so it doesn't follow
 * the target's running / aiming animation; mobs get the agent's proportions.</p>
 */
public final class Hitboxes {
	/** CS2 hit groups (HITGROUP_*). */
	public static final int GENERIC = 0;
	public static final int HEAD = 1;
	public static final int CHEST = 2;
	public static final int STOMACH = 3;
	public static final int LEFT_ARM = 4;
	public static final int RIGHT_ARM = 5;
	public static final int LEFT_LEG = 6;
	public static final int RIGHT_LEG = 7;
	public static final int NECK = 8;
	/** CS2 agent height (72 units), metres: mobs scale the capsules by their height over this. */
	private static final double AGENT_HEIGHT = 72 / 39.37;

	/** A capsule in model space: segment a-b, radius, hit group. */
	public record Capsule(float ax, float ay, float az, float bx, float by, float bz, float r, int group) {
	}

	/** A hit: distance along the ray (blocks), the point, the hit group. */
	public record Hit(double distance, Vec3 point, int group) {
	}

	private static volatile Capsule[] stand;
	private static volatile Capsule[] crouch;

	private Hitboxes() {
	}

	public static void set(Capsule[] standing, Capsule[] crouching) {
		stand = standing;
		crouch = crouching;
	}

	public static boolean available() {
		return stand != null;
	}

	/** CS2 damage multiplier of a hit group (CS's GetHitgroupDamageMultiplier). */
	public static float damageMultiplier(int group, float headshotMultiplier) {
		return switch (group) {
			case HEAD -> headshotMultiplier;
			case STOMACH -> 1.25f;
			case LEFT_LEG, RIGHT_LEG -> 0.75f;
			default -> 1f;
		};
	}

	/** CS2 armor covers every hit group but the legs (the head only with a helmet). */
	public static boolean armorCovers(int group) {
		return group != LEFT_LEG && group != RIGHT_LEG;
	}

	/** This entity is hit-tested with CS2 capsules. */
	public static boolean uses(Entity e) {
		if (!available()) {
			return false;
		}
		if (e instanceof Player) {
			return true; // drawn as CS2 agents
		}
		if (MobHeads.has(e)) {
			return false; // drawn as Minecraft models: their own heads (MobHeads)
		}
		return (e instanceof Zombie || e instanceof AbstractSkeleton || e instanceof AbstractIllager
				|| e instanceof AbstractVillager || e instanceof AbstractPiglin || e instanceof Witch);
	}

	/**
	 * The nearest capsule the ray from {@code from} along unit {@code dir} hits within {@code maxDist} blocks, or null.
	 */
	public static Hit trace(Entity e, Vec3 from, Vec3 dir, double maxDist) {
		boolean crouched = e instanceof Player p ? p.isCrouching() : e.isCrouching();
		Capsule[] set = crouched && crouch != null ? crouch : stand;
		if (set == null) {
			return null;
		}
		double scale = e instanceof Player ? 1.0 : e.getBbHeight() / AGENT_HEIGHT;
		// world -> model: undo the position, the body yaw (model is drawn rotated by -yaw about Y) and the scale
		float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
		double c = Math.cos(Math.toRadians(yaw));
		double s = Math.sin(Math.toRadians(yaw));
		double ox = from.x - e.getX(), oy = from.y - e.getY(), oz = from.z - e.getZ();
		double rox = (ox * c + oz * s) / scale, roy = oy / scale, roz = (-ox * s + oz * c) / scale;
		double rdx = dir.x * c + dir.z * s, rdy = dir.y, rdz = -dir.x * s + dir.z * c;
		double best = Double.MAX_VALUE;
		int group = -1;
		for (Capsule k : set) {
			double t = capsule(rox, roy, roz, rdx, rdy, rdz, k.ax, k.ay, k.az, k.bx, k.by, k.bz, k.r);
			if (t >= 0 && t < best) {
				best = t;
				group = k.group;
			}
		}
		if (group < 0) {
			return null;
		}
		double dist = best * scale;
		if (dist > maxDist) {
			return null;
		}
		return new Hit(dist, from.add(dir.scale(dist)), group);
	}

	/** For the hitbox debug view: this entity's capsules in world space (a, b, radius as x of a third Vec3), with groups. */
	public static java.util.List<Capsule> worldCapsules(Entity e) {
		java.util.List<Capsule> out = new java.util.ArrayList<>();
		boolean crouched = e instanceof Player p ? p.isCrouching() : e.isCrouching();
		Capsule[] set = crouched && crouch != null ? crouch : stand;
		if (set == null) {
			return out;
		}
		double scale = e instanceof Player ? 1.0 : e.getBbHeight() / AGENT_HEIGHT;
		float yaw = e instanceof LivingEntity le ? le.yBodyRot : e.getYRot();
		double c = Math.cos(Math.toRadians(yaw));
		double s = Math.sin(Math.toRadians(yaw));
		for (Capsule k : set) {
			// model -> world: the inverse of trace's turn (x' = x c - z s, z' = x s + z c), scaled, at the entity
			out.add(new Capsule((float) (e.getX() + scale * (k.ax * c - k.az * s)), (float) (e.getY() + scale * k.ay), (float) (e.getZ() + scale * (k.ax * s + k.az * c)),
					(float) (e.getX() + scale * (k.bx * c - k.bz * s)), (float) (e.getY() + scale * k.by), (float) (e.getZ() + scale * (k.bx * s + k.bz * c)),
					(float) (k.r * scale), k.group));
		}
		return out;
	}

	/**
	 * Ray (origin o, unit direction d) against a capsule (segment a-b, radius r): distance to the entry point, 0 if the
	 * origin is inside, or -1 for a miss (after Inigo Quilez's capsule intersection).
	 */
	public static double capsule(double ox, double oy, double oz, double dx, double dy, double dz, double ax, double ay, double az, double bx,
			double by, double bz, double r) {
		double bax = bx - ax, bay = by - ay, baz = bz - az;
		double oax = ox - ax, oay = oy - ay, oaz = oz - az;
		double baba = bax * bax + bay * bay + baz * baz;
		double bard = bax * dx + bay * dy + baz * dz;
		double baoa = bax * oax + bay * oay + baz * oaz;
		double rdoa = dx * oax + dy * oay + dz * oaz;
		double oaoa = oax * oax + oay * oay + oaz * oaz;
		// inside: the origin is within r of the segment
		double h0 = baba > 1e-12 ? Math.max(0, Math.min(1, baoa / baba)) : 0;
		double px = oax - bax * h0, py = oay - bay * h0, pz = oaz - baz * h0;
		if (px * px + py * py + pz * pz <= r * r) {
			return 0;
		}
		double a = baba - bard * bard;
		double b = baba * rdoa - baoa * bard;
		double c = baba * oaoa - baoa * baoa - r * r * baba;
		double h = b * b - a * c;
		if (a > 1e-12 && h >= 0) {
			double t = (-b - Math.sqrt(h)) / a;
			double y = baoa + t * bard;
			if (y > 0 && y < baba) {
				return t >= 0 ? t : -1;
			}
		}
		// the end caps: spheres at a and b
		double best = -1;
		for (int k = 0; k < 2; k++) {
			double cx = k == 0 ? oax : ox - bx, cy = k == 0 ? oay : oy - by, cz = k == 0 ? oaz : oz - bz;
			double bb = dx * cx + dy * cy + dz * cz;
			double cc = cx * cx + cy * cy + cz * cz - r * r;
			double hh = bb * bb - cc;
			if (hh >= 0) {
				double t = -bb - Math.sqrt(hh);
				if (t >= 0 && (best < 0 || t < best)) {
					best = t;
				}
			}
		}
		return best;
	}
}
