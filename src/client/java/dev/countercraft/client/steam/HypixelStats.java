package dev.countercraft.client.steam;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Your Hypixel stats for MC Stats, from Hypixel's own API (api.hypixel.net/v2/player). Hypixel only answers with an API
 * key, and its rules don't allow a key inside a shared mod, so each player pastes their own developer key
 * (developer.hypixel.net; it expires after a few days). The key is saved only in config/countercraft_hypixel.json and
 * sent only to api.hypixel.net. Answers are kept for five minutes (Hypixel limits how often a key may ask).
 */
public final class HypixelStats {
	/** What the screen shows: the player object, or why there's none. */
	public record Result(JsonObject player, String error) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final long TTL_MILLIS = 5 * 60 * 1000L;
	private static String key;
	private static boolean loaded;
	private static CompletableFuture<Result> pending;
	private static long fetchedAt;

	private HypixelStats() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_hypixel.json");
	}

	public static String key() {
		if (!loaded) {
			loaded = true;
			try {
				if (Files.exists(file())) {
					JsonObject j = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
					key = j.has("api_key") ? j.get("api_key").getAsString() : null;
				}
			} catch (Exception e) {
				CounterCraft.LOG.warn("[countercraft] Hypixel settings unreadable: {}", e.toString());
			}
		}
		return key == null || key.isBlank() ? null : key;
	}

	/** Save (or, with null / blank, forget) the key; the next fetch uses it. */
	public static void setKey(String k) {
		key = k == null || k.isBlank() ? null : k.trim();
		loaded = true;
		pending = null;
		try {
			if (key == null) {
				Files.deleteIfExists(file());
			} else {
				JsonObject j = new JsonObject();
				j.addProperty("api_key", key);
				Files.writeString(file(), j.toString());
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] Hypixel settings not saved: {}", e.toString());
		}
	}

	/** Hypixel's keys are UUIDs: catch a bad paste before asking Hypixel. */
	public static boolean looksLikeKey(String k) {
		try {
			UUID.fromString(k.trim());
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** The stats for this player (cached five minutes), or null with no key. */
	public static CompletableFuture<Result> get(UUID player) {
		String k = key();
		if (k == null) {
			return null;
		}
		if (pending != null && System.currentTimeMillis() - fetchedAt < TTL_MILLIS) {
			return pending;
		}
		fetchedAt = System.currentTimeMillis();
		HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.hypixel.net/v2/player?uuid=" + player.toString().replace("-", "")))
				.header("API-Key", k).timeout(Duration.ofSeconds(15)).GET().build();
		pending = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
			if (r.statusCode() == 403) {
				return new Result(null, "Hypixel didn't accept the key (keys expire after a few days): paste a new one.");
			}
			if (r.statusCode() == 429) {
				return new Result(null, "Hypixel says the key asked too often: try again in a minute.");
			}
			if (r.statusCode() != 200) {
				return new Result(null, "Hypixel couldn't be reached (" + r.statusCode() + ").");
			}
			JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
			if (!j.has("player") || j.get("player").isJsonNull()) {
				return new Result(null, "Hypixel has no record of you yet: join mc.hypixel.net once.");
			}
			return new Result(j.getAsJsonObject("player"), null);
		}).exceptionally(t -> new Result(null, "Hypixel couldn't be reached."));
		return pending;
	}

	// ---------------------------------------------------------------- reading the player object

	public static JsonObject obj(JsonObject o, String... path) {
		JsonObject cur = o;
		for (String p : path) {
			if (cur == null || !cur.has(p) || !cur.get(p).isJsonObject()) {
				return new JsonObject();
			}
			cur = cur.getAsJsonObject(p);
		}
		return cur;
	}

	public static double num(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsDouble() : 0;
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
	}

	/** Network level from network experience (Hypixel's published formula). */
	public static double networkLevel(JsonObject p) {
		return Math.sqrt(2 * num(p, "networkExp") + 30625) / 50 - 2.5;
	}

	/** Bed Wars level: 487,000 XP a prestige of 100 levels; the first four cost 500, 1,000, 2,000 and 3,500, the rest 5,000. */
	public static int bedWarsLevel(long exp) {
		int level = (int) (exp / 487_000) * 100;
		long rest = exp % 487_000;
		for (long cost : new long[] {500, 1000, 2000, 3500}) {
			if (rest < cost) {
				return level;
			}
			rest -= cost;
			level++;
		}
		return level + (int) (rest / 5000);
	}

	/** SkyWars' own level text without colour codes ("12⋆"), or "-". */
	public static String skyWarsLevel(JsonObject sw) {
		String s = str(sw, "levelFormatted").replaceAll("§.", "");
		return s.isEmpty() ? "-" : s;
	}

	/** The rank tag and its colour: staff and YouTube first, then MVP++, then the bought rank. */
	public static String[] rank(JsonObject p) {
		String staff = str(p, "rank");
		if (!staff.isEmpty() && !staff.equals("NORMAL")) {
			return new String[] {staff.equals("YOUTUBER") ? "YOUTUBE" : staff, "FF5555"};
		}
		if (str(p, "monthlyPackageRank").equals("SUPERSTAR")) {
			return new String[] {"MVP++", "FFAA00"};
		}
		return switch (str(p, "newPackageRank")) {
			case "MVP_PLUS" -> new String[] {"MVP+", "55FFFF"};
			case "MVP" -> new String[] {"MVP", "55FFFF"};
			case "VIP_PLUS" -> new String[] {"VIP+", "55FF55"};
			case "VIP" -> new String[] {"VIP", "55FF55"};
			default -> new String[] {"", "AAAAAA"};
		};
	}

	/** a / b, with 2 decimals (a alone when b is 0). */
	public static String ratio(double a, double b) {
		return String.format(java.util.Locale.ROOT, "%.2f", b <= 0 ? a : a / b);
	}
}
