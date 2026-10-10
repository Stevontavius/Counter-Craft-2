package dev.countercraft.server;

import java.util.List;

/**
 * What the dedicated server's window ({@link CcServerGui}) shows, collected once a second on the server thread
 * ({@link StatusCollector}). Plain data, no game types: the window never touches the game from Swing's thread.
 *
 * @param msptHistory milliseconds per tick, oldest first, two samples a second
 */
public record ServerStatus(String world, String mode, String version, String motd, int port, int online, int maxPlayers, long uptimeMillis,
		double mspt, double tps, double[] msptHistory, long memUsed, long memMax, Round round, List<Player> players, List<Mod> mods, int hiddenMods,
		List<Ban> bans) {
	public static final ServerStatus STARTING = new ServerStatus("", "", "", "", 0, 0, 0, 0, 0, 0, new double[0], 0, 0, null, List.of(), List.of(), 0,
			List.of());

	/**
	 * Counter-Craft's round (hardcore only, else null): the day, its phase ("BUY PHASE", "DAY", "NIGHT", "BOMB") and the
	 * seconds left in it, the players in the round and those waiting for Another Chance, and whether the run is over.
	 */
	public record Round(int day, String phase, int secondsLeft, int alive, int down, boolean over) {
	}

	/**
	 * @param state "Alive", "Creative", "Spectating", "Dead", "Picking team", Another Chance's "Down", "Respawning", "Out"
	 * @param health 0..1
	 * @param money -1: free buying (no money)
	 */
	public record Player(String name, String team, String state, float health, int money, int kills, int deaths, int streak, int ping, String where,
			boolean bot) {
	}

	public record Mod(String id, String name, String version, String authors) {
	}

	/**
	 * A ban: a player (by name) or an address ({@code ip}), why, who banned them, when (epoch ms) and until when (0:
	 * for good).
	 */
	public record Ban(String name, boolean ip, String reason, String source, long created, long expires) {
	}
}
