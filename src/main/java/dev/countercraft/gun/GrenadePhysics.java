package dev.countercraft.gun;

import net.minecraft.world.phys.Vec3;

/**
 * CS's grenade physics, independent of Minecraft (units and units/s, Minecraft axes: y up). One {@link #step} is one
 * 1/64 s tick of CBaseEntity::PhysicsToss for MOVETYPE_FLYGRAVITY with CBaseCSGrenadeProjectile's collision response:
 *
 * <ul>
 * <li>gravity 0.4 x sv_gravity 800; the move uses the average of the old and new vertical speed (exact for constant
 * gravity), velocity capped at sv_maxvelocity 3500;</li>
 * <li>a 4-unit hull is swept along the move; on a hit the velocity is clipped against the surface with a backoff of
 * 2 (a mirror reflection; components under 0.1 snap to 0) and scaled by the elasticity 0.45 (x 0.3 off players);</li>
 * <li>on a floor (normal.y &gt; 0.7) the grenade stops if slower than 30 u/s, else finishes the step's remaining time
 * along the bounce; off walls and ceilings it just drops if slower than 30 u/s.</li>
 * </ul>
 */
public final class GrenadePhysics {
	public static final double TICK = 1.0 / 64.0;
	public static final double GRAVITY = 0.4 * 800.0;
	public static final double ELASTICITY = 0.45;
	public static final double PLAYER_ELASTICITY = 0.3;
	public static final double STOP_SPEED = 30.0;
	public static final double MAX_VELOCITY = 3500.0;
	/** Floor test of CBaseCSGrenadeProjectile::ResolveFlyCollisionCustom. */
	public static final double FLOOR_NORMAL_Y = 0.7;

	/** Gravity (u/s^2), elasticity (x the player factor off players) and stop speed of a tossed object. */
	public record Params(double gravity, double elasticity, double playerElasticity, double stopSpeed) {
	}

	/** CS grenades (the constants above). */
	public static final Params GRENADE = new Params(GRAVITY, ELASTICITY, PLAYER_ELASTICITY, STOP_SPEED);

	/** A swept-hull hit: where the hull stops, the surface normal, the fraction of the move done, and whether a player. */
	public record Hit(Vec3 pos, Vec3 normal, double fraction, boolean player) {
	}

	/** The world, as CS's hull trace: null when the move from {@code a} to {@code b} is clear. */
	public interface Trace {
		Hit trace(Vec3 a, Vec3 b);
	}

	/** One grenade: hull centre (units), velocity (u/s), resting on a floor; what the last step hit. */
	public static final class State {
		public Vec3 pos;
		public Vec3 vel;
		public boolean stopped;
		/** Set by {@link #step}: the surface hit this step (null if none) and the speed just before it. */
		public Hit hit;
		public double impactSpeed;

		public State(Vec3 pos, Vec3 vel) {
			this.pos = pos;
			this.vel = vel;
		}
	}

	private GrenadePhysics() {
	}

	/**
	 * CS throw from {@link GrenadeItem}: the start velocity (u/s) for a view (Minecraft yaw / pitch, degrees), strength
	 * 0..1, the weapon's throw velocity and the thrower's velocity.
	 */
	public static Vec3 throwVelocity(float yaw, float pitch, float strength, double throwVelocity, Vec3 playerVel) {
		float s = Math.max(0f, Math.min(1f, strength));
		Vec3 dir = direction(throwPitch(Math.max(-90f, Math.min(90f, pitch))), yaw);
		double speed = Math.max(15.0, Math.min(throwVelocity * 0.9, 750.0)) * (0.3 + 0.7 * s);
		return dir.scale(speed).add(playerVel.scale(1.25));
	}

	/**
	 * CS throw pitch (Source pitch, + = down): looking level throws 10 degrees up; looking down maps [0, 90] onto
	 * [-10, 70]; looking up maps [0, 90] onto [-10, -110] (straight up goes slightly backwards).
	 */
	public static float throwPitch(float pitch) {
		return pitch >= 0f ? -10f + pitch * (80f / 90f) : -10f + pitch * (100f / 90f);
	}

	/** Minecraft rotation (degrees; pitch + = down) to a unit vector (same as Ballistics.direction). */
	public static Vec3 direction(float pitch, float yaw) {
		double p = Math.toRadians(pitch);
		double y = Math.toRadians(yaw);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	public static void step(State s, Trace world) {
		step(s, world, GRENADE);
	}

	public static void step(State s, Trace world, Params k) {
		s.hit = null;
		if (s.stopped) {
			// lying on a floor: stays until the floor goes away
			if (world.trace(s.pos, s.pos.add(0, -2.0, 0)) != null) {
				return;
			}
			s.stopped = false;
		}
		double vy = s.vel.y - k.gravity() * TICK;
		Vec3 move = new Vec3(s.vel.x, (s.vel.y + vy) * 0.5, s.vel.z).scale(TICK);
		s.vel = new Vec3(s.vel.x, vy, s.vel.z);
		double max = dev.countercraft.movement.CsMove.maxVelocity;
		if (s.vel.length() > max) {
			s.vel = s.vel.normalize().scale(max);
		}
		Hit h = world.trace(s.pos, s.pos.add(move));
		if (h == null) {
			s.pos = s.pos.add(move);
			return;
		}
		s.pos = h.pos();
		s.hit = h;
		s.impactSpeed = s.vel.length();
		collide(s, h, world, k);
	}

	private static void collide(State s, Hit h, Trace world, Params k) {
		double e = Math.min(0.9, k.elasticity() * (h.player() ? k.playerElasticity() : 1.0));
		Vec3 n = h.normal();
		double backoff = s.vel.dot(n) * 2.0;
		Vec3 out = s.vel.subtract(n.scale(backoff));
		out = new Vec3(snap(out.x), snap(out.y), snap(out.z)).scale(e);
		boolean slow = out.lengthSqr() < k.stopSpeed() * k.stopSpeed();
		if (n.y > FLOOR_NORMAL_Y) {
			if (slow) {
				s.vel = Vec3.ZERO;
				s.stopped = !h.player();
			} else {
				s.vel = out;
				// the rest of this step continues along the bounce (PhysicsPushEntity: stops at anything it meets)
				Vec3 rest = out.scale((1.0 - h.fraction()) * TICK);
				Hit h2 = world.trace(s.pos, s.pos.add(rest));
				s.pos = h2 == null ? s.pos.add(rest) : h2.pos();
			}
		} else {
			s.vel = slow ? Vec3.ZERO : out;
		}
	}

	/** Source's STOP_EPSILON. */
	private static double snap(double v) {
		return Math.abs(v) < 0.1 ? 0 : v;
	}
}
