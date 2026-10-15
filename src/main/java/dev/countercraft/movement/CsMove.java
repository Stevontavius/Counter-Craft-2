package dev.countercraft.movement;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.player.Player;

/**
 * CS2 movement constants (Source units: 1 block = 39.37 units = 1 m) and who uses CS movement.
 *
 * <p>Values are CS2's own (read from the game's console, 2026-10-03) or measured on CS2 itself (measure/move_*.py:
 * per-tick positions from CSTV demos, cl_showpos read off the video). See MODDING_PLAN.md "Movement".</p>
 */
public final class CsMove {
	public static final double UNITS_PER_BLOCK = 39.37;
	public static final double TICK = 1 / 64.0;

	public static final double ACCELERATE = 5.5;          // sv_accelerate
	public static final double AIR_ACCELERATE = 12;       // sv_airaccelerate default
	/** The world's sv_airaccelerate (server: ServerCvars; client: CvarsPayload); surf servers raise it. */
	public static volatile double airAccelerate = AIR_ACCELERATE;
	public static final double AIR_MAX_WISHSPEED = 30;    // sv_air_max_wishspeed
	public static final double FRICTION = 5.2;            // sv_friction
	public static final double STOP_SPEED = 80;           // sv_stopspeed
	public static final double GRAVITY = 800;             // sv_gravity
	public static final double MAX_VELOCITY = 3500;       // sv_maxvelocity default
	/** The world's sv_maxvelocity (server: ServerCvars; client: CvarsPayload). */
	public static volatile double maxVelocity = MAX_VELOCITY;
	public static final double JUMP_IMPULSE = 301.99338;  // sv_jump_impulse
	/** Upward speed a jump actually leaves the ground with (measured 298.87: a quarter tick of gravity less). */
	public static final double JUMP_TAKEOFF = JUMP_IMPULSE - GRAVITY / 256.0;
	public static final double RUN_SPEED = 250;           // knife / empty hands
	public static final double WALK_MULTIPLIER = 0.52;    // +sprint (walk) caps speed at 52%
	public static final double DUCK_MULTIPLIER = 0.34;    // crouched
	/** Keyboard move speed (cl_forwardspeed / cl_sidespeed): always above any max speed, so any key = full wish. */
	public static final double MOVE_INPUT = 450;

	/**
	 * CS2 2026 jump: right after landing a jump is weaker. impulse x min(1, 1 - BHOP_PENALTY x landing speed /
	 * JUMP_IMPULSE + BHOP_RECOVERY x seconds since landing); fitted on 12 landings, rms 0.007.
	 */
	public static final double BHOP_PENALTY = 0.142;
	public static final double BHOP_RECOVERY = 0.596;

	/**
	 * CS2's stamina: that weaker jump, set by its cvars (ServerCvars; the client's from CvarsPayload). Stamina (0 to
	 * sv_staminamax, CS2 80) weakens a jump to (100 - stamina)% of its height and recovers at BHOP_RECOVERY x 100 a second
	 * (59.6 measured; CS2's sv_staminarecoveryrate is 60). A landing adds sv_staminalandcost x the landing speed, scaled so
	 * CS2's 0.05 gives exactly the measured penalty above; a jump adds sv_staminajumpcost x the jump speed (APPROXIMATION,
	 * not measured: at CS2's 0.08 it has recovered before a normal landing, so the measured jumps stay as they were). Both
	 * costs at 0: no penalty, as bunny hop servers run.
	 */
	public static volatile double staminaMax = 80;
	public static volatile double staminaJumpCost = 0.08;
	public static volatile double staminaLandCost = 0.05;
	private static final double STAMINA_LAND_COST = 0.05;
	public static final double STAMINA_RECOVERY = BHOP_RECOVERY * 100;

	public static void stamina(double max, double jumpCost, double landCost) {
		staminaMax = max;
		staminaJumpCost = jumpCost;
		staminaLandCost = landCost;
	}

	/** Stamina a landing at {@code speed} (u/s, downwards) adds. */
	public static double landStamina(double speed) {
		return BHOP_PENALTY * 100 * speed / JUMP_IMPULSE * (staminaLandCost / STAMINA_LAND_COST);
	}

	/** Stamina a jump adds. */
	public static double jumpStamina() {
		return staminaJumpCost * JUMP_IMPULSE;
	}
	/**
	 * CS2 2026 landing slow-down: the same stamina also caps ground speed at (1 - STAMINA_SPEED x stamina / 100) of the max
	 * speed, so a full jump's landing drops a runner from 250 to ~184 u/s and the cap rises back as the stamina recovers
	 * (~0.4 s), in the air too (see BHOP_TIME_WINDOW). Fitted on 9 landings (cs2-extract/movement/ticks.csv), rms 1.4
	 * u/s; the free fit (1 - 0.00094 x fall speed, +1.16/s) lands on exactly 2 x the jump penalty. Falls higher than a jump
	 * aren't measured (APPROXIMATION: the same rule, down to a stop).
	 */
	public static final double STAMINA_SPEED = 2;
	/**
	 * A jump pressed at most sv_bhop_time_window (half a tick) after the landing keeps the speed it landed with (CS2's
	 * stashed speed: jumps 0.16 and 0.22 ticks after landing kept 250 u/s). Any other jump leaves with the landing
	 * slow-down above (one tick late: 250 -> 190.8 for the whole hop, hold_space). A press before the landing counts
	 * as late (CS2 held space through a landing: it jumped a tick later, slowed). sv_autobunnyhopping 1 jumps on the
	 * landing itself, so its hops keep their speed (CS2's auto hop chain: 250, 255, 255, 260; still capped at 1.1x).
	 * Ours times the press against the landing as drawn, a tick after the movement made it.
	 */
	public static final double BHOP_TIME_WINDOW = 0.0078125;
	/**
	 * The world's sv_autobunnyhopping / sv_enablebunnyhopping (server: ServerCvars; client: CvarsPayload). CS2's are the
	 * server's too: a player can't turn the speed cap off for themselves.
	 */
	public static volatile boolean autoBhop;
	public static volatile boolean enableBhop;
	/** sv_enablebunnyhopping 0: a jump can't carry more than 1.1x max speed. */
	public static final double BHOP_SPEED_CAP = 1.1;

	/** Crouch: 0 -> 1 in 0.214 s, back in 0.164 s (m_flDuckAmount per tick). */
	public static final double DUCK_DOWN_SECONDS = 0.214;
	public static final double DUCK_UP_SECONDS = 0.164;
	/** Ducking in the air pulls the feet up 9 units (crouch jump: 55.8 -> 64.8 units). */
	public static final double AIR_DUCK_LIFT = 9;
	/** Eye heights: standing 64 units (Minecraft's 1.62 kept), crouched 46 units. */
	public static final float STAND_EYE = 1.62f;
	public static final float CROUCH_EYE = (float) (46 / UNITS_PER_BLOCK);

	/** Landing faster than this rolls the camera by LAND_PUNCH_PER_SPEED degrees per unit/s (view_punch_decay 18). */
	public static final double LAND_PUNCH_MIN_SPEED = 350;
	public static final double LAND_PUNCH_PER_SPEED = 0.013;
	public static final double VIEW_PUNCH_DECAY = 18;
	/** Fall damage: (impact speed - 580) x 100 / (1024 - 580) CS HP (measured 13 / 26 / 40 HP at 640 / 697 / 762 u/s). */
	public static final double FALL_SAFE_SPEED = 580;
	public static final double FALL_FATAL_SPEED = 1024;

	/** Server side: players whose client runs CS movement (MovementModePayload). */
	private static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
	/** Client side: this client's own setting. */
	public static volatile boolean clientEnabled = true;

	private CsMove() {
	}

	public static boolean enabled(Player player) {
		if (player.level().isClientSide()) {
			return clientEnabled;
		}
		return SERVER.contains(player.getUUID());
	}

	public static void setServer(UUID player, boolean on) {
		if (on) {
			SERVER.add(player);
		} else {
			SERVER.remove(player);
		}
	}

	/** CS fall damage (CS HP) for a fall of {@code blocks}. */
	public static double fallDamage(double blocks) {
		double v = Math.sqrt(2 * GRAVITY * blocks * UNITS_PER_BLOCK);
		return v <= FALL_SAFE_SPEED ? 0 : (v - FALL_SAFE_SPEED) * 100.0 / (FALL_FATAL_SPEED - FALL_SAFE_SPEED);
	}
}
