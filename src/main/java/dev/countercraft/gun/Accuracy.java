package dev.countercraft.gun;

import dev.countercraft.movement.CsMove;
import dev.countercraft.net.FirePayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * CS2 inaccuracy on the server ({@link Inaccuracy}): the per-player penalty is advanced every tick from the
 * player's stance, and each shot adds the instantaneous movement / air part.
 *
 * <p>Motion comes from the shooter's own client with each trigger pull ({@link FirePayload.Motion}: velocity, ground,
 * walk, duck at that tick). Measuring it here from position packets lags a tick and jitters (two packets in one tick
 * double the speed), which punished shots right after a counter-strafe. Without it (old client) the server falls back
 * to its own measurement. With vanilla movement, Minecraft's base walk (4.317 b/s) maps to CS2's base run (250 u/s).</p>
 */
public final class Accuracy {
	/** Minecraft base walking speed (blocks/s) = CS2 base run speed (units/s). */
	private static final double MC_WALK = 4.317;
	private static final double CS_RUN = 250.0;
	private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

	private Accuracy() {
	}

	private static final class State {
		final Inaccuracy acc = new Inaccuracy();
		Vec3 lastPos;
		double speed; // blocks per second, horizontal
		double vy; // units per second, vertical
		boolean wasOnGround = true;
		double fallSpeed;
		int shotIndex;
	}

	/** Call at the end of every server tick: tracks speed and advances each gun holder's penalty. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			State s = STATES.computeIfAbsent(p.getUUID(), u -> new State());
			Vec3 pos = p.position();
			if (s.lastPos != null) {
				double dx = pos.x - s.lastPos.x;
				double dz = pos.z - s.lastPos.z;
				double v = Math.sqrt(dx * dx + dz * dz) * 20.0;
				boolean teleport = v > 30.0;
				s.speed = teleport ? 0.0 : v; // teleports aren't movement
				s.vy = teleport ? 0.0 : (pos.y - s.lastPos.y) * 20.0 * CsMove.UNITS_PER_BLOCK;
			}
			s.lastPos = pos;
			boolean onGround = p.onGround();
			if (!onGround) {
				s.fallSpeed = Math.max(0, -s.vy);
			}
			if (p.getMainHandItem().getItem() instanceof GunItem gun) {
				AccuracySpec a = gun.spec().accuracy();
				boolean alt = gun.altMode(p.getMainHandItem());
				if (onGround && !s.wasOnGround) {
					s.acc.land(a, alt, s.fallSpeed);
				}
				s.acc.update(a, alt, onGround, p.isShiftKeyDown() ? 1f : 0f, p.onClimbable() && !onGround, s.shotIndex, 0.05);
			}
			s.wasOnGround = onGround;
		}
	}

	public static void forget(UUID player) {
		STATES.remove(player);
	}

	/** Horizontal speed in CS2 units per second (for logs and tests). */
	public static double speedUnits(ServerPlayer player) {
		State s = STATES.get(player.getUUID());
		return s == null ? 0 : s.speed * unitsPerBlock(player);
	}

	/**
	 * Blocks/s -> CS units/s: with CS2 movement the player moves at true CS speeds (1 block = 39.37 units); with
	 * vanilla movement, Minecraft's base walk stands in for CS2's base run.
	 */
	private static double unitsPerBlock(ServerPlayer player) {
		return CsMove.enabled(player) ? CsMove.UNITS_PER_BLOCK : CS_RUN / MC_WALK;
	}

	/**
	 * Inaccuracy for a shot fired now (before this shot's own penalty), then adds the shot's firing penalty.
	 * {@code shotIndex} is the 0-based bullet number in the current spray (drives the recovery-time transition);
	 * {@code motion} is the client's movement this tick, or null to use the server's measurement.
	 */
	public static float shoot(ServerPlayer player, AccuracySpec a, boolean alt, int shotIndex, FirePayload.Motion motion) {
		State s = STATES.computeIfAbsent(player.getUUID(), u -> new State());
		s.shotIndex = shotIndex + 1;
		float inaccuracy;
		if (motion != null) {
			inaccuracy = s.acc.total(a, alt, Math.hypot(motion.vx(), motion.vz()), motion.vy(), motion.onGround(), motion.walking());
		} else {
			inaccuracy = s.acc.total(a, alt, s.speed * unitsPerBlock(player), s.vy, player.onGround(), false);
		}
		s.acc.fire(a, alt);
		return inaccuracy;
	}

	/**
	 * CS-style cone: a random point within {@code inaccuracy} plus one within {@code spread}, as tangent offsets
	 * {right, up} to apply to the aim direction.
	 */
	public static float[] offset(float inaccuracy, float spread, RandomSource random) {
		double r1 = random.nextDouble() * inaccuracy;
		double a1 = random.nextDouble() * Math.PI * 2;
		double r2 = random.nextDouble() * spread;
		double a2 = random.nextDouble() * Math.PI * 2;
		return new float[] {(float) (Math.cos(a1) * r1 + Math.cos(a2) * r2), (float) (Math.sin(a1) * r1 + Math.sin(a2) * r2)};
	}
}
