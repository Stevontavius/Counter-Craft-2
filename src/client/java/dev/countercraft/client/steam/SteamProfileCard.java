package dev.countercraft.client.steam;

import com.mojang.blaze3d.platform.NativeImage;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.skins.SteamGrabber;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * The linked Steam account's card on the title screen (top right): avatar, name, Steam level, hours in CS2. Clicking
 * it opens My CS2 Stats (or, unlinked, the optional Steam link screen).
 *
 * <p>Hours in CS2: Steam only shows playtime to an API key or a signed-in session, so CC2 reads it where it's already on
 * this computer: the Steam client's own record of the account (userdata/&lt;account&gt;/config/localconfig.vdf, CS2's
 * Playtime), read-only, and only for the linked account. If that account isn't signed in on this computer, the
 * public profile's recent playtime is used when it lists CS2; otherwise the hours are left out.</p>
 */
public final class SteamProfileCard {
	public static final int W = 200;
	public static final int H = 44;
	private static final Identifier AVATAR = CounterCraft.id("dynamic/steam_card_avatar");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

	private static String loadedFor;
	private static long loadedAt;
	private static String name;
	private static int level = -1;
	private static int hours = -1;
	private static boolean avatarReady;

	private SteamProfileCard() {
	}

	/** Load again the next time the card shows. */
	public static void refresh() {
		loadedFor = null;
	}

	/** Load (again after five minutes, or for another account). */
	private static void ensure(String id) {
		if (id.equals(loadedFor) && System.currentTimeMillis() - loadedAt < 5 * 60 * 1000L) {
			return;
		}
		loadedFor = id;
		loadedAt = System.currentTimeMillis();
		name = null;
		level = -1;
		hours = -1;
		avatarReady = false;
		Minecraft mc = Minecraft.getInstance();
		SteamGrabber.fetch(id).thenAccept(p -> mc.execute(() -> {
			if (!id.equals(loadedFor)) {
				return;
			}
			name = p.name();
			level = p.level();
			loadAvatar(p.avatarUrl(), id);
		})).exceptionally(t -> null);
		CompletableFuture.supplyAsync(() -> {
			int h = localHours(id);
			return h >= 0 ? h : publicHours(id);
		}).thenAccept(h -> mc.execute(() -> {
			if (id.equals(loadedFor)) {
				hours = h;
			}
		}));
	}

	/** CS2's playtime from this computer's Steam client, for this account (-1 if it isn't signed in here). */
	private static int localHours(String id64) {
		try {
			long account = Long.parseLong(id64) - 76561197960265728L;
			for (Path steam : steamDirs()) {
				Path cfg = steam.resolve("userdata").resolve(Long.toString(account)).resolve("config").resolve("localconfig.vdf");
				if (Files.exists(cfg)) {
					int minutes = playtime(Files.readString(cfg, StandardCharsets.UTF_8));
					if (minutes >= 0) {
						return minutes / 60;
					}
				}
			}
		} catch (Exception e) {
			CounterCraft.LOG.debug("[countercraft] local CS2 playtime unreadable: {}", e.toString());
		}
		return -1;
	}

	/** The Playtime (minutes) inside the "730" app block of a Steam localconfig.vdf, or -1. */
	static int playtime(String vdf) {
		Matcher m = Pattern.compile("\"730\"\\s*\\{").matcher(vdf);
		while (m.find()) {
			int depth = 1;
			int i = m.end();
			int start = i;
			while (i < vdf.length() && depth > 0) {
				char c = vdf.charAt(i++);
				if (c == '{') {
					depth++;
				} else if (c == '}') {
					depth--;
				}
			}
			Matcher p = Pattern.compile("\"Playtime\"\\s*\"(\\d+)\"").matcher(vdf.substring(start, i));
			if (p.find()) {
				return Integer.parseInt(p.group(1));
			}
		}
		return -1;
	}

	/** Where Steam is installed: the registry's SteamPath on Windows, the usual places elsewhere. */
	private static java.util.List<Path> steamDirs() {
		java.util.List<Path> out = new java.util.ArrayList<>();
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) {
			try {
				Process pr = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
				String outText = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(outText);
				if (m.find()) {
					out.add(Path.of(m.group(1).trim()));
				}
			} catch (Exception ignored) {
				// no registry entry: fall through to the defaults
			}
			out.add(Path.of("C:/Program Files (x86)/Steam"));
		} else {
			String home = System.getProperty("user.home");
			out.add(Path.of(home, ".steam", "steam"));
			out.add(Path.of(home, ".local", "share", "Steam"));
			out.add(Path.of(home, "Library", "Application Support", "Steam"));
		}
		return out;
	}

	/** CS2 hours from the public profile, when it lists CS2 among recently played games (-1 otherwise). */
	private static int publicHours(String id) {
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://steamcommunity.com/profiles/" + id + "/?xml=1"))
					.timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
			Matcher m = Pattern.compile("<gameName><!\\[CDATA\\[Counter-Strike 2]]></gameName>.*?<hoursOnRecord>([\\d,.]+)</hoursOnRecord>", Pattern.DOTALL)
					.matcher(r.body());
			if (m.find()) {
				return (int) Double.parseDouble(m.group(1).replace(",", ""));
			}
		} catch (Exception e) {
			CounterCraft.LOG.debug("[countercraft] public CS2 playtime unreadable: {}", e.toString());
		}
		return -1;
	}

	private static void loadAvatar(String url, String id) {
		if (url == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		HTTP.sendAsync(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray()).thenAccept(r -> mc.execute(() -> {
			if (!id.equals(loadedFor)) {
				return;
			}
			try {
				NativeImage img = NativeImage.read(toPng(r.body()));
				mc.getTextureManager().register(AVATAR, new DynamicTexture(() -> "steam card avatar", img));
				avatarReady = true;
			} catch (Exception e) {
				CounterCraft.LOG.debug("[countercraft] card avatar not loaded: {}", e.toString());
			}
		}));
	}

	/** Steam avatars are JPEGs; Minecraft reads PNG. */
	private static byte[] toPng(byte[] data) throws java.io.IOException {
		if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P') {
			return data;
		}
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
		if (img == null) {
			throw new java.io.IOException("unreadable image");
		}
		java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		rgb.getGraphics().drawImage(img, 0, 0, null);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(rgb, "png", out);
		return out.toByteArray();
	}

	/** The account's avatar at (x, y), size px square (a grey square until it has loaded). */
	public static void avatar(GuiGraphicsExtractor g, String id, int x, int y, int size) {
		ensure(id);
		if (avatarReady) {
			g.blit(RenderPipelines.GUI_TEXTURED, AVATAR, x, y, 0f, 0f, size, size, 184, 184, 184, 184);
		} else {
			g.fill(x, y, x + size, y + size, 0x40FFFFFF);
		}
	}

	/** CS2 hours for the account (-1 while unknown). */
	public static int hours(String id) {
		ensure(id);
		return hours;
	}

	/** Draw the card with its top-left at (x, y); hovered brightens it. */
	public static void draw(GuiGraphicsExtractor g, Font font, int x, int y, boolean hovered) {
		String id = SteamLinkClient.steamId();
		g.fill(x, y, x + W, y + H, 0x90000000);
		CsUi.card(g, x, y, W, H, hovered ? 1f : 0f, 0x66C0F4, false);
		if (id == null) {
			g.text(font, "Steam account (optional)", x + 8, y + 10, 0xFFFFFFFF, true);
			g.text(font, "Link it for your CS2 stats", x + 8, y + 24, 0xFF8A8A8A, false);
			return;
		}
		ensure(id);
		int ax = x + 6;
		int ay = y + 6;
		if (avatarReady) {
			g.blit(RenderPipelines.GUI_TEXTURED, AVATAR, ax, ay, 0f, 0f, 32, 32, 184, 184, 184, 184);
		} else {
			g.fill(ax, ay, ax + 32, ay + 32, 0x40FFFFFF);
		}
		int tx = ax + 40;
		String shown = name != null ? name : "Loading...";
		g.text(font, font.plainSubstrByWidth(shown, W - 48 - 18), tx, y + 6, 0xFFFFFFFF, true);
		if (level >= 0) {
			String lv = Integer.toString(level);
			int lw = font.width(lv) + 6;
			// Steam's level badge: a small ring with the number
			g.outline(x + W - lw - 6, y + 5, lw, 11, 0xFF66C0F4);
			g.text(font, lv, x + W - lw - 3, y + 7, 0xFFFFFFFF, false);
		}
		g.text(font, "Steam level " + (level >= 0 ? level : "-"), tx, y + 18, 0xFF8A8A8A, false);
		g.text(font, hours >= 0 ? String.format("%,d hours in CS2", hours) : "CS2 hours not shown", tx, y + 29, hours >= 0 ? 0xFFEABE54 : 0xFF6E6E6E, false);
	}
}
