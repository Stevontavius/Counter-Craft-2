package dev.countercraft.server;

import dev.countercraft.SurvivalRecord;
import dev.countercraft.bot.TestBots;
import dev.countercraft.team.AnotherChance;
import dev.countercraft.team.DayCycle;
import dev.countercraft.team.Economy;
import dev.countercraft.team.TeamLock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * Fills {@link ServerStatus} for the server window on the server thread: tick time twice a second (the graph), the rest
 * once a second. Does nothing unless the window is open.
 */
public final class StatusCollector {
	private static final int HISTORY = 120;
	private static final double[] MSPT = new double[HISTORY];
	private static int samples;
	private static long started;
	private static List<ServerStatus.Mod> mods;
	private static int hiddenMods;
	private static volatile ServerStatus status = ServerStatus.STARTING;

	private StatusCollector() {
	}

	public static ServerStatus status() {
		return status;
	}

	/** Every server tick (only while the window is open). */
	public static void tick(MinecraftServer server) {
		if (!CcServerGui.isOpen() || server.getTickCount() % 10 != 0) {
			return;
		}
		if (started == 0) {
			started = System.currentTimeMillis();
		}
		System.arraycopy(MSPT, 1, MSPT, 0, HISTORY - 1);
		MSPT[HISTORY - 1] = server.getAverageTickTimeNanos() / 1e6;
		samples = Math.min(HISTORY, samples + 1);
		if (server.getTickCount() % 20 == 0) {
			status = collect(server);
		}
	}

	private static ServerStatus collect(MinecraftServer server) {
		double mspt = server.getAverageTickTimeNanos() / 1e6;
		float rate = server.tickRateManager().tickrate();
		double tps = mspt <= 0 ? rate : Math.min(rate, 1000.0 / mspt);
		boolean hardcore = server.getWorldData().isHardcore();
		String mode = hardcore ? "Counter-Craft (hardcore)" : cap(server.getDefaultGameType().getName());
		List<ServerStatus.Player> players = new ArrayList<>();
		int alive = 0;
		int down = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			players.add(player(p));
			if (AnotherChance.inRound(p)) {
				alive++;
			} else if (AnotherChance.waiting(p)) {
				down++;
			}
		}
		players.sort(Comparator.comparing(ServerStatus.Player::team).thenComparing(ServerStatus.Player::name, String.CASE_INSENSITIVE_ORDER));
		ServerStatus.Round round = null;
		if (hardcore) {
			long t = DayCycle.timeOfDay(server.overworld());
			int day = (int) Math.max(0, Math.floorDiv(server.overworld().getOverworldClockTime(), DayCycle.DAY));
			String phase;
			long left;
			if (t < DayCycle.BUY_TICKS) {
				phase = "BUY PHASE";
				left = DayCycle.BUY_TICKS - t;
			} else if (t >= DayCycle.DAY - DayCycle.BOMB_TICKS) {
				phase = "BOMB";
				left = DayCycle.DAY - t;
			} else if (t < 13000) {
				phase = "DAY";
				left = 13000 - t;
			} else {
				phase = "NIGHT";
				left = DayCycle.DAY - DayCycle.BOMB_TICKS - t;
			}
			round = new ServerStatus.Round(day, phase, (int) (left / 20), alive, down, AnotherChance.runOver(server));
		}
		Runtime rt = Runtime.getRuntime();
		double[] history = new double[samples];
		System.arraycopy(MSPT, HISTORY - samples, history, 0, samples);
		return new ServerStatus(server.getWorldData().getLevelName(), mode, version(), server.getMotd(), server.getPort(), server.getPlayerCount(),
				server.getMaxPlayers(), System.currentTimeMillis() - started, mspt, tps, history, rt.totalMemory() - rt.freeMemory(), rt.maxMemory(), round,
				players, mods(), hiddenMods, bans(server));
	}

	/** The ban lists (players, then addresses), still in force. */
	private static List<ServerStatus.Ban> bans(MinecraftServer server) {
		List<ServerStatus.Ban> out = new ArrayList<>();
		for (net.minecraft.server.players.UserBanListEntry e : server.getPlayerList().getBans().getEntries()) {
			if (e.getUser() != null && !e.hasExpired()) {
				out.add(new ServerStatus.Ban(e.getUser().name(), false, e.getReason(), e.getSource(), ms(e.getCreated()), ms(e.getExpires())));
			}
		}
		for (net.minecraft.server.players.IpBanListEntry e : server.getPlayerList().getIpBans().getEntries()) {
			if (e.getUser() != null && !e.hasExpired()) {
				out.add(new ServerStatus.Ban(e.getUser(), true, e.getReason(), e.getSource(), ms(e.getCreated()), ms(e.getExpires())));
			}
		}
		out.sort(Comparator.comparingLong(ServerStatus.Ban::created).reversed());
		return out;
	}

	private static long ms(java.util.Date d) {
		return d == null ? 0 : d.getTime();
	}

	private static ServerStatus.Player player(ServerPlayer p) {
		AnotherChance.State chance = AnotherChance.of(p);
		String state;
		if (TeamLock.pending(p)) {
			state = "Picking team";
		} else if (chance.state() == AnotherChance.OUT) {
			state = "Out";
		} else if (chance.state() == AnotherChance.READY) {
			state = "Respawning";
		} else if (chance.state() == AnotherChance.DOWN) {
			state = "Down";
		} else if (!p.isAlive()) {
			state = "Dead";
		} else if (p.isSpectator()) {
			state = "Spectating";
		} else if (p.isCreative()) {
			state = "Creative";
		} else {
			state = "Alive";
		}
		var s = p.getStats();
		int kills = s.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)) + s.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS));
		String where = p.level().dimension().identifier().getPath() + "  " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ();
		return new ServerStatus.Player(p.getGameProfile().name(), Objects.requireNonNullElse(p.getAttached(dev.countercraft.team.Teams.TEAM), ""), state,
				p.getMaxHealth() > 0 ? Math.max(0, p.getHealth() / p.getMaxHealth()) : 0, Economy.freeBuy(p) ? -1 : Economy.money(p), kills,
				s.getValue(Stats.CUSTOM.get(Stats.DEATHS)), SurvivalRecord.of(p).streak(), p.connection.latency(), where, TestBots.isBot(p));
	}

	private static String version() {
		FabricLoader f = FabricLoader.getInstance();
		return "Minecraft " + f.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
				+ "  ·  Fabric Loader " + f.getModContainer("fabricloader").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
				+ "  ·  Counter-Craft 2 " + dev.countercraft.CounterCraft.RELEASE
				+ " (" + f.getModContainer("countercraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + ")";
	}

	/** The mods loaded, top level only (the modules bundled inside them, like Fabric API's, are only counted). */
	private static List<ServerStatus.Mod> mods() {
		if (mods == null) {
			List<ServerStatus.Mod> out = new ArrayList<>();
			int hidden = 0;
			for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
				ModMetadata md = m.getMetadata();
				if (m.getContainingMod().isPresent() || md.getId().equals("java")) {
					hidden++;
					continue;
				}
				List<String> authors = new ArrayList<>();
				for (Person a : md.getAuthors()) {
					authors.add(a.getName());
				}
				out.add(new ServerStatus.Mod(md.getId(), md.getName(), md.getVersion().getFriendlyString(), String.join(", ", authors)));
			}
			out.sort(Comparator.comparing(ServerStatus.Mod::name, String.CASE_INSENSITIVE_ORDER));
			hiddenMods = hidden;
			mods = List.copyOf(out);
		}
		return mods;
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
