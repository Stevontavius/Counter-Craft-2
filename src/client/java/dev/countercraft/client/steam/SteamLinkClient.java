package dev.countercraft.client.steam;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.SteamLinkPayload;
import dev.countercraft.net.SteamStatusPayload;
import dev.countercraft.steam.SteamOpenId;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * The client half of Steam linking ({@link dev.countercraft.steam.GenuineSkins} has the rules). Sign-in happens on
 * steamcommunity.com in the player's own browser ("Sign in through Steam", OpenID): CC2 opens Steam's page and listens
 * on this computer only (127.0.0.1) for Steam to send the browser back with its signed answer. No password, Steam
 * Guard code or anything else secret ever reaches CC2. In a world, the server checks the answer with Steam itself;
 * from the menus, this client does. The linked SteamID (public, nothing secret) is remembered in
 * config/countercraft_steam.json for My CS2 Stats.
 */
public final class SteamLinkClient {
	private static final String STEAM_LOGIN = "https://steamcommunity.com/openid/login";
	private static String steamId;
	private static boolean loaded;
	/** What the current server knows: its linked SteamID ("" = none), inventory state, and its last message. */
	private static String serverSteamId = "";
	private static String serverInventory = "";
	private static String message = "";
	private static HttpServer listener;
	/** Stops the sign-in listener later (a daemon: never keeps the game running). */
	private static final java.util.concurrent.ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "countercraft steam sign-in");
		t.setDaemon(true);
		return t;
	});

	private SteamLinkClient() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_steam.json");
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			if (Files.exists(file())) {
				JsonObject j = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
				steamId = j.has("steam_id") ? j.get("steam_id").getAsString() : null;
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] steam link settings unreadable: {}", e.toString());
		}
	}

	private static void save() {
		try {
			JsonObject j = new JsonObject();
			if (steamId != null) {
				j.addProperty("steam_id", steamId);
			}
			Files.writeString(file(), j.toString());
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] steam link settings not saved: {}", e.toString());
		}
	}

	/** The linked SteamID64 on this computer, or null. */
	public static String steamId() {
		load();
		return steamId;
	}

	public static String serverSteamId() {
		return serverSteamId;
	}

	public static String serverInventory() {
		return serverInventory;
	}

	public static String message() {
		return message;
	}

	public static boolean inWorld() {
		return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(SteamLinkPayload.TYPE);
	}

	public static void onStatus(SteamStatusPayload p) {
		serverSteamId = p.steamId();
		serverInventory = p.inventory();
		if (!p.message().isEmpty()) {
			message = p.message();
		}
		if (!p.steamId().isEmpty()) {
			load();
			boolean changed = !p.steamId().equals(steamId);
			steamId = p.steamId();
			save();
			if (changed) {
				LinkedInventory.copy(false); // just linked (here, in a world): copy the account's inventory
			}
		}
	}

	/**
	 * The Steam sign-in page to open (after the player confirms the link): it sends the browser back to this computer's
	 * listener when they've signed in. Starts the listener (it gives up after ten minutes).
	 */
	public static URI begin() throws java.io.IOException {
		stopListener();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		int port = server.getAddress().getPort();
		server.createContext("/cc2/steam", exchange -> {
			Map<String, String> params = query(exchange.getRequestURI().getRawQuery());
			byte[] page = PAGE.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
			exchange.sendResponseHeaders(200, page.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(page);
			}
			Minecraft.getInstance().execute(() -> received(params));
			TIMER.schedule(SteamLinkClient::stopListener, 1, TimeUnit.SECONDS);
		});
		server.start();
		listener = server;
		TIMER.schedule(SteamLinkClient::stopListener, 10, TimeUnit.MINUTES);
		String back = "http://127.0.0.1:" + port + "/cc2/steam";
		message = "Waiting for you to sign in through Steam in your browser...";
		return URI.create(STEAM_LOGIN + "?openid.ns=" + enc("http://specs.openid.net/auth/2.0") + "&openid.mode=checkid_setup"
				+ "&openid.return_to=" + enc(back) + "&openid.realm=" + enc("http://127.0.0.1:" + port + "/")
				+ "&openid.identity=" + enc("http://specs.openid.net/auth/2.0/identifier_select")
				+ "&openid.claimed_id=" + enc("http://specs.openid.net/auth/2.0/identifier_select"));
	}

	private static synchronized void stopListener() {
		if (listener != null) {
			listener.stop(0);
			listener = null;
		}
	}

	/** Steam sent the browser back: hand the answer to the server, or check it here from the menus. */
	private static void received(Map<String, String> params) {
		if (!"id_res".equals(params.get("openid.mode"))) {
			message = "Sign-in was cancelled. Nothing was linked.";
			return;
		}
		if (inWorld()) {
			message = "Checking with Steam...";
			ClientPlayNetworking.send(new SteamLinkPayload(params));
			return;
		}
		message = "Checking with Steam...";
		SteamOpenId.verify(params).thenAccept(id -> Minecraft.getInstance().execute(() -> {
			if (id.isEmpty()) {
				message = "Steam didn't confirm that sign-in. Nothing was linked.";
				return;
			}
			load();
			steamId = id.get();
			save();
			message = "Linked to Steam account " + steamId + ". Servers you join check it with Steam again before showing a ✓.";
			LinkedInventory.copy(false);
		}));
	}

	public static void setMessage(String m) {
		message = m;
	}

	/**
	 * Refresh inventory: reset the copied inventory and copy the linked account's again (skins sold or bought since), and
	 * in a world, have it read the inventory again for the Genuine Skins tick.
	 */
	public static void refreshInventory() {
		LinkedInventory.copy(true);
		if (inWorld()) {
			ClientPlayNetworking.send(new SteamLinkPayload(Map.of(SteamLinkPayload.REFRESH, "")));
		}
	}

	/** Forget the link here, and on the current server. */
	public static void unlink() {
		load();
		steamId = null;
		save();
		if (inWorld()) {
			ClientPlayNetworking.send(new SteamLinkPayload(Map.of()));
		}
		message = "Unlinked. CC2 no longer reads anything from that Steam account.";
	}

	private static Map<String, String> query(String raw) {
		Map<String, String> out = new HashMap<>();
		if (raw == null) {
			return out;
		}
		for (String kv : raw.split("&")) {
			int eq = kv.indexOf('=');
			if (eq > 0) {
				out.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return out;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static final String PAGE = """
			<!doctype html><html><head><meta charset="utf-8"><title>Counter-Craft 2</title>
			<style>body{background:#101214;color:#e6e6e6;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0}
			div{max-width:520px;text-align:center}h1{color:#7ed37e;font-size:22px}p{color:#a0a0a0}</style></head>
			<body><div><h1>Steam sign-in received</h1><p>You can close this tab and go back to Minecraft.</p>
			<p>Counter-Craft 2 only received Steam's confirmation of your SteamID. It never sees your password or Steam Guard codes.</p></div></body></html>
			""";
}
