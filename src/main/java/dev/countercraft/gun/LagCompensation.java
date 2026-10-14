package dev.countercraft.gun;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's lag compensation, for players and mobs alike. A shooter sees everything where it was a moment ago: the network
 * round trip, plus Minecraft drawing entities a few ticks behind their latest position to smooth them (and sending most
 * mobs' positions only every few ticks). The server traced shots against where things are now, so shots on anything
 * moving, a head above all, missed what the shooter had in the crosshair. Now the server keeps where every living thing
 * was over the last second, and a shot is traced against where its shooter saw it: the shooter's ping (a round trip) plus
 * {@link #INTERP} ticks ago, then the ticks around that, then now.
 * APPROXIMATION: CS2 rewinds to the exact tick the client drew; this estimates that moment (the ticks around it cover the
 * error) and moves the target only, not its turn or crouch.
 */
public final class LagCompensation {
	/** Ticks of history kept (1.2 s). */
	private static final int KEEP = 24;
	/** Minecraft's drawing delay on top of the ping: entity updates every 2-3 ticks, smoothed over 3. */
	private static final int INTERP = 3;
	/** Around the estimated tick, nearest first. */
	private static final int[] AROUND = {0, -1, 1, -2, 2};
	/** How far a rewound target can be from where it is now (blocks): the shot looks for targets this far off its line. */
	static final double REACH = 8.0;

	private static final class Track {
		final double[] xyz = new double[KEEP * 3];
		int head = -1;
		int count;

		void push(double x, double y, double z) {
			head = (head + 1) % KEEP;
			xyz[head * 3] = x;
			xyz[head * 3 + 1] = y;
			xyz[head * 3 + 2] = z;
			count = Math.min(KEEP, count + 1);
		}

		/** Where it was {@code k} ticks before the last one recorded (0: the last one). */
		int at(int k) {
			return ((head - k) % KEEP + KEEP) % KEEP * 3;
		}
	}

	/** Server thread only. Weak: an entity that's gone takes its track with it. */
	private static final Map<Entity, Track> TRACKS = new WeakHashMap<>();

	private LagCompensation() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity e : level.getAllEntities()) {
					if (e instanceof LivingEntity && e.isAlive()) {
						TRACKS.computeIfAbsent(e, k -> new Track()).push(e.getX(), e.getY(), e.getZ());
					}
				}
			}
		});
	}

	/**
	 * How far {@code target} has moved since {@code shooter} saw it, at each moment a shot tries in turn (the estimated one
	 * first, now last): trace the shot with its origin moved by this, against the target as it is.
	 */
	static List<Vec3> shifts(Entity target, Entity shooter) {
		Entity e = Ballistics.owner(target); // a dragon's part moves with the dragon
		Track t = TRACKS.get(e);
		List<Vec3> out = new ArrayList<>();
		if (t != null && shooter instanceof ServerPlayer sp) {
			int back = Math.min(KEEP - 1, Math.round(sp.connection.latency() / 50f) + INTERP);
			for (int d : AROUND) {
				int k = back + d;
				if (k >= 0 && k < t.count) {
					int i = t.at(k);
					out.add(new Vec3(e.getX() - t.xyz[i], e.getY() - t.xyz[i + 1], e.getZ() - t.xyz[i + 2]));
				}
			}
		}
		out.add(Vec3.ZERO);
		return out;
	}
}
