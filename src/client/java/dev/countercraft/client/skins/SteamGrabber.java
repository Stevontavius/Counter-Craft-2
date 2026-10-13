package dev.countercraft.client.skins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.loadout.MusicKit;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inventory Grabber: reads a Steam profile's public CS2 inventory without an API key.
 *
 * <ul>
 * <li>Profile: steamcommunity.com/profiles/&lt;id64&gt;/?xml=1 or /id/&lt;vanity&gt;/?xml=1 (name, SteamID64, avatar).</li>
 * <li>Steam level: the profile page's friendPlayerLevelNum.</li>
 * <li>Inventory: steamcommunity.com/inventory/&lt;id64&gt;/730/2, and /730/16 for trade-protected items (public inventories
 * only; Steam rate-limits it).</li>
 * <li>Value: Skinport's public price list (api.skinport.com/v1/items, one Brotli request for every CS2 item;
 * suggested price in USD), cached for 10 minutes. Skinport's prices, not the Steam Community Market's.</li>
 * </ul>
 * Items map to unlocks ({@link SkinsConfig} keys) by their market names. Doppler / Gamma Doppler phases share one
 * market name, so all phases of the name unlock (approximation: the phase isn't in the public inventory data).
 */
public final class SteamGrabber {
	public record Item(String marketName, String type, int count) {
	}

	/** note: why the inventory couldn't be read (null if it was); PRIVATE_INVENTORY for a private one. */
	public record Profile(String id64, String name, String avatarUrl, int level, List<Item> items, String note) {
		public boolean inventoryPrivate() {
			return PRIVATE_INVENTORY.equals(note);
		}
	}

	public static final String PRIVATE_INVENTORY = "private inventory";

	/** The profile itself is private (or friends-only): nothing is fetched. */
	public static final class PrivateProfileException extends RuntimeException {
		public PrivateProfileException(String name) {
			super(name);
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final String UA = "Mozilla/5.0 (CounterCraft2 Minecraft mod; inventory viewer)";
	private static Map<String, Double> prices;
	private static long pricesAt;

	private SteamGrabber() {
	}

	private static HttpResponse<byte[]> get(String url, String accept) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", UA);
		if (accept != null) {
			b.header("Accept-Encoding", accept);
		}
		return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String text(String url) throws Exception {
		HttpResponse<byte[]> r = get(url, null);
		if (r.statusCode() == 429) {
			throw new IllegalStateException("Steam is rate-limiting requests: try again in a minute");
		}
		if (r.statusCode() / 100 != 2) {
			throw new IllegalStateException("HTTP " + r.statusCode() + " from " + URI.create(url).getHost());
		}
		return new String(r.body(), StandardCharsets.UTF_8);
	}

	/** SteamID64, profile URL (/profiles/ or /id/) or custom URL name -> the ?xml=1 profile URL. */
	static String profileUrl(String input) {
		String s = input.trim();
		Matcher m = Pattern.compile("profiles/(\\d{17})").matcher(s);
		if (m.find()) {
			return "https://steamcommunity.com/profiles/" + m.group(1);
		}
		if (s.matches("\\d{17}")) {
			return "https://steamcommunity.com/profiles/" + s;
		}
		m = Pattern.compile("/id/([^/?#]+)").matcher(s);
		String vanity = m.find() ? m.group(1) : s;
		return "https://steamcommunity.com/id/" + URLEncoder.encode(vanity, StandardCharsets.UTF_8);
	}

	private static String tag(String xml, String name) {
		Matcher m = Pattern.compile("<" + name + ">(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</" + name + ">", Pattern.DOTALL).matcher(xml);
		return m.find() ? m.group(1).trim() : null;
	}

	/** Fetch a profile and its CS2 inventory (off the render thread). */
	public static CompletableFuture<Profile> fetch(String input) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String base = profileUrl(input);
				String xml = text(base + "/?xml=1");
				String err = tag(xml, "error");
				if (err != null) {
					throw new IllegalStateException("Steam: " + err);
				}
				String id64 = tag(xml, "steamID64");
				String name = tag(xml, "steamID");
				String avatar = tag(xml, "avatarFull");
				if (id64 == null) {
					throw new IllegalStateException("No such Steam profile");
				}
				String privacy = tag(xml, "privacyState");
				if (privacy != null && !privacy.equalsIgnoreCase("public")) {
					throw new PrivateProfileException(name == null ? id64 : name);
				}
				int level = -1;
				try {
					Matcher lm = Pattern.compile("friendPlayerLevelNum\">(\\d+)<").matcher(text("https://steamcommunity.com/profiles/" + id64 + "/"));
					if (lm.find()) {
						level = Integer.parseInt(lm.group(1));
					}
				} catch (Exception e) {
					CounterCraft.LOG.info("[countercraft] steam level unavailable: {}", e.toString());
				}
				List<Item> items = new ArrayList<>();
				String note = null;
				try {
					items = inventory(id64);
				} catch (Exception e) {
					note = "private".equals(e.getMessage()) ? PRIVATE_INVENTORY : "Inventory unavailable (" + e.getMessage() + ")";
				}
				return new Profile(id64, name, avatar, level, items, note);
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		});
	}

	/** The inventory with its trade-protected items (context 16, see SteamInventory.CONTEXTS); those are optional. */
	private static List<Item> inventory(String id64) throws Exception {
		Map<String, Integer> counts = new LinkedHashMap<>();
		Map<String, String> types = new HashMap<>();
		for (String ctx : dev.countercraft.steam.SteamInventory.CONTEXTS) {
			try {
				inventory(id64, ctx, counts, types);
			} catch (IllegalStateException e) {
				if (ctx.equals(dev.countercraft.steam.SteamInventory.CONTEXTS[0])) {
					throw e;
				}
				CounterCraft.LOG.info("[countercraft] trade-protected items not read: {}", e.getMessage());
			}
		}
		List<Item> out = new ArrayList<>();
		counts.forEach((n, c) -> out.add(new Item(n, types.getOrDefault(n, ""), c)));
		return out;
	}

	private static void inventory(String id64, String ctx, Map<String, Integer> counts, Map<String, String> types) throws Exception {
		String start = null;
		for (int page = 0; page < 10; page++) {
			String url = "https://steamcommunity.com/inventory/" + id64 + "/730/" + ctx + "?l=english&count=1000" + (start == null ? "" : "&start_assetid=" + start);
			HttpResponse<byte[]> r = get(url, null);
			if (r.statusCode() == 403) {
				throw new IllegalStateException("private");
			}
			if (r.statusCode() == 429) {
				throw new IllegalStateException("Steam rate limit, try again later");
			}
			if (r.statusCode() / 100 != 2) {
				throw new IllegalStateException("HTTP " + r.statusCode());
			}
			JsonObject j = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> byClass = new HashMap<>();
			if (j.has("descriptions")) {
				for (JsonElement e : j.getAsJsonArray("descriptions")) {
					JsonObject d = e.getAsJsonObject();
					String key = d.get("classid").getAsString() + "_" + d.get("instanceid").getAsString();
					byClass.put(key, d.get("market_hash_name").getAsString());
					types.put(d.get("market_hash_name").getAsString(), d.has("type") ? d.get("type").getAsString() : "");
				}
			}
			if (j.has("assets")) {
				for (JsonElement e : j.getAsJsonArray("assets")) {
					JsonObject a = e.getAsJsonObject();
					String name = byClass.get(a.get("classid").getAsString() + "_" + a.get("instanceid").getAsString());
					if (name != null) {
						counts.merge(name, a.has("amount") ? a.get("amount").getAsInt() : 1, Integer::sum);
					}
				}
			}
			if (j.has("more_items") && j.get("more_items").getAsInt() == 1 && j.has("last_assetid")) {
				start = j.get("last_assetid").getAsString();
			} else {
				break;
			}
		}
	}

	/** Skinport's suggested prices (USD) by market name, fetched once per 10 minutes. */
	public static CompletableFuture<Map<String, Double>> prices() {
		if (prices != null && System.currentTimeMillis() - pricesAt < 600_000) {
			return CompletableFuture.completedFuture(prices);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				HttpResponse<byte[]> r = get("https://api.skinport.com/v1/items?app_id=730&currency=USD", "br");
				if (r.statusCode() / 100 != 2) {
					throw new IllegalStateException("Skinport HTTP " + r.statusCode());
				}
				byte[] body = r.body();
				var in = r.headers().firstValue("content-encoding").orElse("").contains("br")
						? new org.brotli.dec.BrotliInputStream(new ByteArrayInputStream(body)) : new ByteArrayInputStream(body);
				JsonArray a = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
				Map<String, Double> m = new HashMap<>();
				for (JsonElement e : a) {
					JsonObject o = e.getAsJsonObject();
					JsonElement p = o.get("suggested_price");
					if (p != null && !p.isJsonNull()) {
						m.put(o.get("market_hash_name").getAsString(), p.getAsDouble());
					}
				}
				prices = m;
				pricesAt = System.currentTimeMillis();
				return m;
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		});
	}

	/** Total value of the items at these prices (items without a price count 0). */
	public static double value(List<Item> items, Map<String, Double> prices) {
		double v = 0;
		for (Item i : items) {
			v += prices.getOrDefault(i.marketName(), 0.0) * i.count();
		}
		return v;
	}

	// ---------------------------------------------------------------- unlocks

	private static String strip(String market) {
		String s = market.replace("StatTrak™ ", "").replace("Souvenir ", "").replace("★ ", "").trim();
		int wear = s.lastIndexOf(" (");
		return wear > 0 && s.endsWith(")") ? s.substring(0, wear) : s;
	}

	/** The unlock keys these items give: weapon / knife finishes, plain knives, agents, music kits (gloves: by name). */
	public static Set<String> unlocks(List<Item> items) {
		Map<String, String> weaponIds = new HashMap<>();
		for (String id : CounterCraft.GUNS.keySet()) {
			weaponIds.put(CsUi.name("weapon_" + id).toLowerCase(Locale.ROOT), id);
		}
		for (String id : CounterCraft.KNIVES.keySet()) {
			weaponIds.put(CsUi.name("weapon_" + id).toLowerCase(Locale.ROOT), id);
		}
		Set<String> out = new LinkedHashSet<>();
		for (Item it : items) {
			String s = strip(it.marketName());
			int bar = s.indexOf(" | ");
			String head = bar < 0 ? s : s.substring(0, bar);
			String tail = bar < 0 ? null : s.substring(bar + 3);
			if (head.equalsIgnoreCase("Music Kit") && tail != null) {
				for (Map.Entry<String, String> k : MusicKit.kits().entrySet()) {
					if (k.getValue().equalsIgnoreCase(tail)) {
						out.add("m:" + k.getKey());
					}
				}
				continue;
			}
			String weapon = weaponIds.get(head.toLowerCase(Locale.ROOT));
			if (weapon != null) {
				if (tail == null) {
					out.add("k:" + weapon); // a knife with no finish
				} else {
					for (Skins.Skin sk : Skins.forItem(weapon)) {
						if (sk.name().equalsIgnoreCase(tail)) {
							out.add("w:" + weapon + ":" + sk.id());
						}
					}
				}
				continue;
			}
			// agents: "<name> | <faction>"
			for (String team : new String[] {"t", "ct"}) {
				for (Agents.Agent a : Agents.forTeam(team)) {
					if (a.name().equalsIgnoreCase(s) || a.name().equalsIgnoreCase(head) || s.toLowerCase(Locale.ROOT).startsWith(a.name().toLowerCase(Locale.ROOT))) {
						out.add("a:" + a.id());
					}
				}
			}
			// gloves: "<glove type> | <finish>"
			if (tail != null) {
				for (Map.Entry<String, String> gt : Skins.gloveTypes().entrySet()) {
					if (gt.getValue().equalsIgnoreCase(head)) {
						for (Skins.Glove gl : Skins.gloves(gt.getKey())) {
							if (gl.name().equalsIgnoreCase(tail)) {
								out.add("g:" + gl.type() + ":" + gl.id());
							}
						}
					}
				}
			}
		}
		return out;
	}
}
