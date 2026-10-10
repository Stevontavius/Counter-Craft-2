package dev.countercraft.gun;

/**
 * Weapon inaccuracy: Counter-Craft 2's own model. The values it works with (each weapon's standing, crouching, moving,
 * jumping, landing, ladder and firing inaccuracy and its recovery times) come from CS2's weapon data, read from the
 * player's own install; how they're combined is Counter-Craft's own tuning, not CS2's code. To be matched to CS2 by
 * measuring it in game. Shared by the server (bullets) and the client (crosshair, scope blur) so both agree.
 *
 * <ul>
 * <li>A lasting penalty settles towards the stance's value (on the ground: standing to crouching by how far down; in
 * the air: standing + jumping; on a ladder: standing + ladder). It rises at once and falls back exponentially, with
 * {@link #LEFT_AFTER_RECOVERY} of the extra left after the weapon's recovery time. Each shot adds the firing value; a
 * landing adds the landing value per unit/s of fall speed (its size in the weapon data is per unit/s).</li>
 * <li>On top of it: moving adds the moving value, rising in a straight line from {@link #MOVE_START} of the weapon's
 * top speed to all of it (half as much when walking), and being in the air adds the jump-initial value, scaled by how
 * fast the player rises or falls (all of it from {@link #AIR_FULL} units/s).</li>
 * </ul>
 * Speeds are CS units per second.
 */
public final class Inaccuracy {
	/** Moving adds nothing below this share of the weapon's top speed (so stopping before a shot pays off). */
	private static final double MOVE_START = 0.25;
	/** Walking (shift) scales the moving penalty by this. */
	private static final double WALK_SCALE = 0.5;
	/** Vertical speed (units/s) from which the air penalty is all of jump-initial. */
	private static final double AIR_FULL = 300;
	/** The share of the extra penalty still there after the recovery time. */
	private static final double LEFT_AFTER_RECOVERY = 0.05;
	private static final double LN_LEFT = Math.log(LEFT_AFTER_RECOVERY);

	/** The lasting penalty, radians. */
	public float penalty;

	/** The lasting penalty's value for the stance (ground: by how far down; air: stand + jump; ladder: stand + ladder). */
	public static float target(AccuracySpec a, boolean alt, boolean onGround, float duck, boolean ladder) {
		float stand = AccuracySpec.pick(a.standing(), alt);
		if (ladder) {
			return stand + AccuracySpec.pick(a.ladder(), alt);
		}
		if (!onGround) {
			return stand + AccuracySpec.pick(a.jumping(), alt);
		}
		float crouch = AccuracySpec.pick(a.crouching(), alt);
		float d = Math.max(0f, Math.min(1f, duck));
		return stand + (crouch - stand) * d;
	}

	/** Recovery time (s): stand / crouch, going from the first-shot value to the final one over the weapon's transition shots. */
	public static float recoveryTime(AccuracySpec a, boolean crouched, int shotIndex) {
		float t = a.transitionEnd() > a.transitionStart()
				? Math.max(0f, Math.min(1f, (shotIndex - a.transitionStart()) / (float) (a.transitionEnd() - a.transitionStart())))
				: 0f;
		return crouched ? a.recoveryCrouch() + (a.recoveryCrouchFinal() - a.recoveryCrouch()) * t
				: a.recoveryStand() + (a.recoveryStandFinal() - a.recoveryStand()) * t;
	}

	/** Advance the penalty by {@code dt} seconds towards the stance's value. */
	public void update(AccuracySpec a, boolean alt, boolean onGround, float duck, boolean ladder, int shotIndex, double dt) {
		float target = target(a, alt, onGround, duck, ladder);
		if (target > penalty) {
			penalty = target;
			return;
		}
		float rec = recoveryTime(a, onGround && duck > 0.5f, shotIndex);
		if (rec <= 0f) {
			penalty = target;
			return;
		}
		penalty = (float) (target + (penalty - target) * Math.exp(dt * LN_LEFT / rec));
	}

	public void fire(AccuracySpec a, boolean alt) {
		penalty += AccuracySpec.pick(a.firing(), alt);
	}

	/** Touchdown at {@code fallSpeed} units/s. */
	public void land(AccuracySpec a, boolean alt, double fallSpeed) {
		penalty += (float) (AccuracySpec.pick(a.landing(), alt) * Math.max(0, fallSpeed));
	}

	/** The part that comes and goes with motion: moving, and rising or falling in the air. */
	public static float motion(AccuracySpec a, boolean alt, double speed2d, double verticalSpeed, boolean onGround, boolean walking) {
		float max = AccuracySpec.pick(a.maxSpeed(), alt);
		double s = max <= 0 ? 0 : (speed2d - max * MOVE_START) / (max * (1 - MOVE_START));
		s = Math.max(0, Math.min(1, s)) * (walking ? WALK_SCALE : 1);
		double out = s * AccuracySpec.pick(a.moving(), alt);
		if (!onGround) {
			out += a.jumpInitial() * Math.min(1, Math.abs(verticalSpeed) / AIR_FULL);
		}
		return (float) out;
	}

	/** Total inaccuracy now (radians, without the always-on spread), at most 1. */
	public float total(AccuracySpec a, boolean alt, double speed2d, double verticalSpeed, boolean onGround, boolean walking) {
		return Math.min(1f, penalty + motion(a, alt, speed2d, verticalSpeed, onGround, walking));
	}
}
