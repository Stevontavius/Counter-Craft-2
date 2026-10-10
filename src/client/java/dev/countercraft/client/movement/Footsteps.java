package dev.countercraft.client.movement;

import dev.countercraft.CounterCraft;
import dev.countercraft.movement.Surfaces;
import dev.countercraft.net.StepFxPayload;
import dev.countercraft.net.StepPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Player sounds: footsteps, jumps and landings, with CS2's sound events (from the asset pack) and their distance-volume
 * curves. When they play is Counter-Craft 2's own rule, not CS2's code:
 *
 * <ul>
 * <li>A footstep for every {@link #STRIDE} units walked on the ground, when not ducked and faster than
 * {@link #SILENT_BELOW} u/s (just above walking speed, so shift-walking and crouching are silent).</li>
 * <li>A jump plays the surface's step plus the jump sound.</li>
 * <li>Landing from a fall of about half a block or more ({@link #LAND_MIN} u/s) plays the surface's landing sound and
 * starts a new stride.</li>
 * </ul>
 * The local player's own movement drives them exactly ({@link CsPlayerMovement}); each is also sent to the server,
 * which passes it to nearby players. Team: CT for now (no teams yet).
 */
public final class Footsteps {
	/** Units walked per footstep (at a 250 u/s run, one every 0.3 s). */
	private static final double STRIDE = 75;
	/** Silent below this speed (walking with shift is about 135 u/s). */
	private static final double SILENT_BELOW = 140;
	/** Fall speed of a drop of about half a block (20 units at 800 u/s² gravity). */
	private static final double LAND_MIN = 180;
	/** CS2 plays footsteps 20 units above the feet (position_offset). */
	private static final double OFFSET = 20 / 39.37;
	private static final String TEAM = "ct_";
	/** Distance (units) -> volume curves from CS2's Base.Footstep, Base.Land and Default.WalkJump soundevents. */
	private static final double[][] STEP_CURVE = {{49.591427, 0.45}, {116.563492, 1.0}, {402.285736, 0.488971}, {1095.0, 0.03}, {1100.0, 0.0}};
	private static final double[][] LAND_CURVE = {{0.0, 1.0}, {37.42857, 1.0}, {429.10892, 0.445141}, {1090.0, 0.05}, {1100.0, 0.0}};
	private static final double[][] JUMP_CURVE = {{40.0, 1.0}, {155.7, 0.264}, {493.7, 0.0}};
	private static final RandomSource RANDOM = RandomSource.create();

	private static double walked; // units since the last footstep

	private Footsteps() {
	}

	/** One CS movement sub-step of the local player (after its move). */
	static void update(LocalPlayer p, double dt, Vec3 vel, boolean onGround, double duck, boolean jumped, boolean landed, double landSpeed) {
		int surface = Surfaces.of(p.getBlockStateOn());
		if (jumped) {
			emit(p, StepPayload.JUMP, surface);
			return;
		}
		if (landed) {
			if (landSpeed >= LAND_MIN) {
				emit(p, StepPayload.LAND, surface);
				walked = 0;
			}
			return;
		}
		double speed = Math.hypot(vel.x, vel.z);
		if (!onGround || duck > 0.5 || speed <= SILENT_BELOW) {
			walked = STRIDE * 0.5; // the first step comes half a stride after starting to run
			return;
		}
		walked += speed * dt;
		if (walked >= STRIDE) {
			walked -= STRIDE;
			emit(p, StepPayload.STEP, surface);
		}
	}

	private static void emit(LocalPlayer p, int kind, int surface) {
		play(kind, surface, p.position());
		if (ClientPlayNetworking.canSend(StepPayload.TYPE)) {
			ClientPlayNetworking.send(new StepPayload(kind, surface));
		}
	}

	/** Someone else's step (from the server). */
	public static void remote(StepFxPayload s) {
		play(s.kind(), s.surface(), new Vec3(s.x(), s.y(), s.z()));
	}

	private static void play(int kind, int surface, Vec3 feet) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Vec3 at = feet.add(0, OFFSET, 0);
		double units = mc.gameRenderer.mainCamera().position().distanceTo(at) * 39.37;
		String surf = Surfaces.name(surface).toLowerCase();
		switch (kind) {
			case StepPayload.JUMP -> {
				sound(TEAM + surf + ".stepleft", at, curve(STEP_CURVE, units), 1f);
				sound("default.walkjump", at, curve(JUMP_CURVE, units), 1f);
			}
			// Base.Land: pitch 1.01 +- 0.06
			case StepPayload.LAND -> sound("land_" + surf + ".stepleft", at, curve(LAND_CURVE, units), 1f + (RANDOM.nextFloat() - 0.5f) * 0.12f);
			default -> sound(TEAM + surf + ".stepleft", at, curve(STEP_CURVE, units) * (1.0 - 0.1 * RANDOM.nextDouble()), 1f);
		}
	}

	/**
	 * Played without Minecraft's distance attenuation (still positioned for direction): the volume is CS2's curve for
	 * this distance, times the soundevent's own volume (in the pack's sounds.json).
	 */
	private static void sound(String id, Vec3 at, double volume, float pitch) {
		if (volume <= 0.001) {
			return;
		}
		Identifier sid = CounterCraft.id(id);
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sid, SoundSource.PLAYERS, (float) Math.min(1.0, volume), pitch,
				RANDOM, false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false));
	}

	/** Piecewise-linear CS2 distance-volume curve (flat beyond the end points). */
	static double curve(double[][] c, double units) {
		if (units <= c[0][0]) {
			return c[0][1];
		}
		for (int i = 1; i < c.length; i++) {
			if (units <= c[i][0]) {
				double t = (units - c[i - 1][0]) / (c[i][0] - c[i - 1][0]);
				return c[i - 1][1] + (c[i][1] - c[i - 1][1]) * t;
			}
		}
		return c[c.length - 1][1];
	}
}
