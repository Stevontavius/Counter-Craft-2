package dev.countercraft.steam;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A linked player's public CS2 inventory, read by the server itself (steamcommunity.com/inventory/&lt;id&gt;/730/2 and /16, no
 * key, public inventories only) so the Genuine Skins check can't be fooled by a client. Kept as the items' market names
 * without wear, StatTrak, Souvenir or the star ("karambit | fade"), refreshed at most every ten minutes per account.
 */
public final class SteamInventory {
	/** What's known of an account's inventory. */
	public record Owned(Set<String> names, Status status, long at) {
	}

	public enum Status { OK, PRIVATE, UNAVAILABLE }

	private static final long TTL_MILLIS = 10 * 60 * 1000L;
	private static final Map<String, Owned> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, CompletableFuture<Owned>> LOADING = new ConcurrentHashMap<>();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

	private SteamInventory() {
	}

	/** The cached inventory (possibly stale), or null if none yet. */
	public static Owned cached(String steamId) {
		return CACHE.get(steamId);
	}

	/** The inventory, fetched again if older than ten minutes ({@code force}: now). */
	public static CompletableFuture<Owned> get(String steamId, boolean force) {
		Owned c = CACHE.get(steamId);
		if (c != null && !force && System.currentTimeMillis() - c.at() < TTL_MILLIS) {
			return CompletableFuture.completedFuture(c);
		}
		return LOADING.computeIfAbsent(steamId, id -> fetch(id).whenComplete((o, t) -> {
			LOADING.remove(id);
			if (o != null) {
				CACHE.put(id, o);
			}
		}));
	}

	/**
	 * CS2's inventory contexts: 2 is the inventory, 16 holds trade-protected items (traded in the last 7 days), which
	 * Steam leaves out of context 2. Context 16 failing (it's often empty) doesn't make the inventory unavailable.
	 */
	public static final String[] CONTEXTS = {"2", "16"};

	private static CompletableFuture<Owned> fetch(String steamId) {
		return CompletableFuture.supplyAsync(() -> {
			Set<String> names = new HashSet<>();
			for (String ctx : CONTEXTS) {
				Owned failed = fetch(steamId, ctx, names);
				if (failed != null && ctx.equals(CONTEXTS[0])) {
					return failed;
				}
			}
			return new Owned(Set.copyOf(names), Status.OK, System.currentTimeMillis());
		});
	}

	/** One context's items into {@code names}; null when read, else why not. */
	private static Owned fetch(String steamId, String ctx, Set<String> names) {
		String start = null;
		for (int page = 0; page < 10; page++) {
			String url = "https://steamcommunity.com/inventory/" + steamId + "/730/" + ctx + "?l=english&count=2000"
					+ (start == null ? "" : "&start_assetid=" + start);
			try {
				HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				if (r.statusCode() == 403) {
					return new Owned(Set.of(), Status.PRIVATE, System.currentTimeMillis());
				}
				if (r.statusCode() != 200) {
					return new Owned(Set.of(), Status.UNAVAILABLE, System.currentTimeMillis());
				}
				JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
				JsonArray desc = j.has("descriptions") ? j.getAsJsonArray("descriptions") : new JsonArray();
				for (JsonElement e : desc) {
					JsonObject d = e.getAsJsonObject();
					String name = d.has("market_hash_name") ? d.get("market_hash_name").getAsString() : d.has("market_name") ? d.get("market_name").getAsString() : null;
					if (name != null) {
						names.add(base(name));
					}
				}
				if (j.has("more_items") && j.get("more_items").getAsInt() == 1 && j.has("last_assetid")) {
					start = j.get("last_assetid").getAsString();
				} else {
					break;
				}
			} catch (Exception ex) {
				CounterCraft.LOG.warn("[countercraft] couldn't read a Steam inventory (context {}): {}", ctx, ex.toString());
				return new Owned(Set.of(), Status.UNAVAILABLE, System.currentTimeMillis());
			}
		}
		return null;
	}

	/** "StatTrak™ ★ Karambit | Fade (Factory New)" -> "karambit | fade". */
	public static String base(String market) {
		String s = market.replace("StatTrak™ ", "").replace("Souvenir ", "").replace("★ ", "").trim();
		int wear = s.lastIndexOf(" (");
		if (wear > 0 && s.endsWith(")")) {
			s = s.substring(0, wear);
		}
		return s.toLowerCase(Locale.ROOT);
	}
}
