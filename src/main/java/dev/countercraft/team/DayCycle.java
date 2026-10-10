package dev.countercraft.team;

import dev.countercraft.net.DaySurvivedPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Counter-Craft (hardcore co-op): every Minecraft day is a CS2 round. Dawn opens a one-minute buy phase (buying only
 * then, as CS2's buy time); the day plays out as the round (the client plays the kit's round music, see
 * {@code RoundCycle}); the last 40 s of the night are the bomb (CS2's bomb timer is 40 s); and surviving to dawn wins the
 * round: a ROUND WON style banner and the day's reward for every player still alive. Other modes keep free buying.
 */
public final class DayCycle {
	public static final long DAY = 24000L;
	/** One minute of buy phase from dawn. */
	public static final long BUY_TICKS = 60 * 20;
	/** The bomb: the last 40 s before dawn, its 10 second warning. */
	public static final long BOMB_TICKS = 40 * 20;
	public static final long WARNING_TICKS = 10 * 20;

	private DayCycle() {
	}

	/** Ticks into the current day (0 = dawn). */
	public static long timeOfDay(Level level) {
		return Math.floorMod(level.getOverworldClockTime(), DAY);
	}

	public static boolean buyPhase(Level level) {
		return timeOfDay(level) < BUY_TICKS;
	}

	/**
	 * The reward for surviving day {@code day} (1 = the first day survived): days 1-3 $1,000, 4-6 $1,250, 7-10 $1,750,
	 * 11-25 $2,500, 26 and on $5,000. Where the listed ranges overlap (6, 10) the day goes to the lower bracket.
	 */
	public static int reward(int day) {
		if (day <= 3) {
			return 1000;
		}
		if (day <= 6) {
			return 1250;
		}
		if (day <= 10) {
			return 1750;
		}
		if (day <= 25) {
			return 2500;
		}
		return 5000;
	}

	private static long lastDay = -1;
	/** In the buy phase as of the last tick (a buy phase already under way when the world opens still ends with a pep talk). */
	private static boolean wasBuy;

	/**
	 * Every server tick: at each new dawn the round is won by every player alive and on a team (the day's number is the
	 * world's: the first dawn ends day 1). Time set backwards just restarts the count.
	 */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		if (!server.overworld().getLevelData().isHardcore()) {
			return;
		}
		boolean buy = buyPhase(server.overworld());
		if (wasBuy && !buy) {
			dev.countercraft.AutoRadio.buyPhaseOver(inRound(server)); // one of them gives a pep talk
		}
		wasBuy = buy;
		long d = Math.floorDiv(server.overworld().getOverworldClockTime(), DAY);
		if (lastDay < 0 || d < lastDay) {
			lastDay = d;
			return;
		}
		if (d == lastDay) {
			return;
		}
		lastDay = d;
		int day = (int) Math.min(Integer.MAX_VALUE, d);
		java.util.List<ServerPlayer> survivors = inRound(server);
		for (ServerPlayer p : survivors) {
			daySurvived(p, day, 1f);
		}
		AnotherChance.dawn(server, day, survivors); // the players who died today may come back, on half the reward
		dev.countercraft.AutoRadio.dawn(survivors); // one of them cheers
	}

	/** The players in the round: alive and on a team. */
	private static java.util.List<ServerPlayer> inRound(net.minecraft.server.MinecraftServer server) {
		java.util.List<ServerPlayer> out = new java.util.ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.isAlive() && !p.isSpectator() && p.hasAttached(Teams.TEAM) && !TeamLock.pending(p)) {
				out.add(p);
			}
		}
		return out;
	}

	/** World closed: the next world counts its own days. */
	public static void reset() {
		lastDay = -1;
		wasBuy = false;
	}

	/**
	 * Counter-Craft, a dawn: the round is won. {@code share} of the day's reward: 1 for the players alive, less for those
	 * who died that day (Another Chance).
	 */
	public static void daySurvived(ServerPlayer p, int day, float share) {
		if (!p.level().getLevelData().isHardcore()) {
			return;
		}
		int reward = Math.round(reward(day) * share);
		int before = Economy.money(p);
		Economy.add(p, reward);
		int got = Economy.money(p) - before;
		p.sendSystemMessage(Component.literal("+$" + got + ": Award for surviving day " + day + (share < 1f ? " (halved: you died)." : "."))
				.withColor(0x6FB86F));
		if (ServerPlayNetworking.canSend(p, DaySurvivedPayload.TYPE)) {
			ServerPlayNetworking.send(p, new DaySurvivedPayload(day, got));
		}
	}

	/** Counter-Craft buying is only during the buy phase; true if this purchase is refused (and says why). */
	public static boolean refuseBuy(ServerPlayer p) {
		if (!p.level().getLevelData().isHardcore() || p.isCreative() || buyPhase(p.level())) {
			return false;
		}
		p.sendSystemMessage(Component.literal("The buy time has expired.").withColor(0xE05050), true);
		return true;
	}
}
