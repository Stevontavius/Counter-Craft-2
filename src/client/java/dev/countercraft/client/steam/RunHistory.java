package dev.countercraft.client.steam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.countercraft.CounterCraft;
import dev.countercraft.SurvivalRecord;
import dev.countercraft.team.Teams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * This player's Counter-Craft (hardcore) runs, kept on this computer (config/countercraft_runs.json) so ongoing and
 * finished runs stay listed after the world is deleted. A run starts when a team is picked in a hardcore world and
 * finishes when the player dies; other modes aren't recorded.
 */
public final class RunHistory {
	/** One run. {@code world} names the world (or server), {@code key} tells worlds apart (save folder and seed, or address). */
	public static final class Run {
		public String key;
		public String world;
		public String team;
		public long started;
		public long lastPlayed;
		public int days;
		public boolean finished;
		/** The agent model equipped for the run's team (empty: CS2's default). */
		public String agent = "";
		/** The deep dive's latest stats from the server ({@link dev.countercraft.RunStats}). */
		public java.util.Map<String, Long> counts = new java.util.HashMap<>();
		public java.util.Map<String, Integer> weapons = new java.util.HashMap<>();
		public java.util.Map<String, Integer> mobs = new java.util.HashMap<>();

		/** How the run ended (empty until it has): the death message, what killed you and with what. */
		public String deathMessage = "";
		public String deathCause = "";
		public String killerName = "";
		public String killerType = "";
		public boolean killerPlayer;
		public String weaponItem = "";
		public String csWeapon = "";

		public long count(String key) {
			return counts == null ? 0 : counts.getOrDefault(key, 0L);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static List<Run> runs;
	private static int tick;

	private RunHistory() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_runs.json");
	}

	/** Every run, newest first. */
	public static List<Run> runs() {
		load();
		List<Run> out = new ArrayList<>(runs);
		out.sort((a, b) -> Long.compare(b.started, a.started));
		return out;
	}

	private static void load() {
		if (runs != null) {
			return;
		}
		runs = new ArrayList<>();
		try {
			if (Files.exists(file())) {
				List<Run> read = GSON.fromJson(Files.readString(file()), new TypeToken<List<Run>>() {
				}.getType());
				if (read != null) {
					runs.addAll(read);
				}
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] run history unreadable: {}", e.toString());
		}
	}

	private static void save() {
		try {
			Files.writeString(file(), GSON.toJson(runs));
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] run history not saved: {}", e.toString());
		}
	}

	/** Which world this is: the save folder's name and seed in singleplayer, the address on a server. */
	private static String[] world(Minecraft mc) {
		IntegratedServer sp = mc.getSingleplayerServer();
		if (sp != null) {
			String name = sp.getWorldData().getLevelName();
			return new String[] {"sp:" + name + ":" + sp.overworld().getSeed(), name};
		}
		var server = mc.getCurrentServer();
		return server != null ? new String[] {"mp:" + server.ip, server.name} : null;
	}

	/** The latest run in the world being played, or null. */
	private static Run current(Minecraft mc) {
		String[] w = world(mc);
		if (w == null) {
			return null;
		}
		load();
		Run run = null;
		for (Run x : runs) {
			if (x.key.equals(w[0]) && (run == null || x.started > run.started)) {
				run = x;
			}
		}
		return run;
	}

	/** You died: note how (and picture the killer) on the run being played, if this is a Counter-Craft world. */
	public static void onDeath(Minecraft mc, dev.countercraft.net.DeathInfoPayload p) {
		if (mc.level == null || !mc.level.getLevelData().isHardcore()) {
			return;
		}
		Run run = current(mc);
		if (run == null || run.finished && !run.deathMessage.isEmpty()) {
			return;
		}
		run.deathMessage = p.deathMessage().getString();
		run.deathCause = p.cause();
		run.killerName = p.killerName().getString();
		run.killerType = p.killerType();
		run.killerPlayer = p.killerPlayer();
		run.weaponItem = p.weapon().isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(p.weapon().getItem()).toString();
		run.csWeapon = p.csWeapon();
		run.finished = !p.anotherChance(); // Another Chance: the run goes on while someone's alive
		if (!run.counts.containsKey("days")) {
			run.days = Math.max(run.days, p.daysSurvived()); // a run from before the day count was kept
		}
		run.lastPlayed = System.currentTimeMillis();
		save();
		KillerPip.request(run, p);
	}

	/** A killer picture was written: drop any cached copy so the deep dive shows the new one. */
	static void pictureSaved(Path file) {
		Minecraft.getInstance().execute(() -> RunScreen.forget(file));
	}

	/** Delete a run's record (and its killer picture). The world itself isn't touched. */
	public static void delete(Run run) {
		load();
		runs.remove(run);
		save();
		try {
			Files.deleteIfExists(KillerPip.file(run));
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] killer picture not deleted: {}", e.toString());
		}
		RunScreen.forget(KillerPip.file(run));
	}

	/** The server's latest stats for this run (kept as they were when the run ended). */
	public static void onStats(Minecraft mc, dev.countercraft.net.RunStatsPayload p) {
		Run run = current(mc);
		if (run == null || run.finished && System.currentTimeMillis() - run.lastPlayed > 15_000) {
			return;
		}
		run.counts = new java.util.HashMap<>(p.counts());
		if (run.counts.containsKey("days")) {
			run.days = (int) run.count("days");
		}
		run.weapons = new java.util.HashMap<>(p.weapons());
		run.mobs = new java.util.HashMap<>(p.mobs());
		save();
	}

	/** Once a second: keep the current hardcore run up to date. */
	public static void tick(Minecraft mc) {
		if (++tick % 20 != 0 || mc.player == null || mc.level == null || !mc.level.getLevelData().isHardcore()) {
			return;
		}
		String team = mc.player.getAttached(Teams.TEAM);
		String[] w = world(mc);
		if (team == null || w == null) {
			return; // the run starts with the team pick
		}
		SurvivalRecord r = mc.player.getAttached(SurvivalRecord.RECORD);
		// the run ends when the server says so (Another Chance: when the last player alive dies); before it kept that
		// state, the first death
		dev.countercraft.team.AnotherChance.State chance = mc.player.getAttached(dev.countercraft.team.AnotherChance.STATE);
		boolean dead = chance != null ? chance.state() == dev.countercraft.team.AnotherChance.OUT
				: mc.player.isDeadOrDying() || r != null && r.deaths() > 0;
		Run run = current(mc);
		if (run == null || run.finished && !dead && !mc.player.isSpectator()) {
			if (dead || mc.player.isSpectator()) {
				return; // joined a world this run already ended in, before it was recorded: nothing to start
			}
			run = new Run();
			run.key = w[0];
			run.started = System.currentTimeMillis();
			runs.add(run);
		} else if (run.finished) {
			return;
		}
		// the dawns this run survived (counted by the server at each Counter-Craft dawn); older runs: the survival record
		int days = run.counts != null && run.counts.containsKey("days") ? (int) run.count("days") : Math.max(run.days, r != null ? r.total() : 0);
		String agent = dev.countercraft.client.loadout.LoadoutConfig.side(team).agent();
		boolean changed = !agent.equals(run.agent) || run.days != days || run.finished != dead || !team.equals(run.team) || !w[1].equals(run.world)
				|| System.currentTimeMillis() - run.lastPlayed > 60_000;
		run.world = w[1];
		run.team = team;
		run.days = days;
		run.agent = agent;
		run.finished = dead;
		if (changed) {
			run.lastPlayed = System.currentTimeMillis();
			save();
		}
	}
}
