package dev.countercraft.client.steam;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.skins.SteamGrabber;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * My CS2 Stats, in pages: Overview (the headline numbers, skills, recent form, most played maps), Ranks (Premier,
 * Wingman, every Competitive map's skill group with its win rate), Performance (Leetify's aim, utility, duel and trade
 * numbers, each explained on hover), Matches (the last 100, filterable by mode) and Counter-Craft (every hardcore run on
 * this computer, {@link RunHistory}). Sources: the public Steam profile and Leetify's public profile (no key; Leetify
 * must know the player). Map pictures come from the converted CS2 pack (textures/gui/maps) when it has them.
 */
public final class CsStatsScreen extends StatsScreen {
	private static final String LEETIFY = "https://api-public.cs-prod.leetify.com/v3/profile?steam64_id=";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final String[] TABS = {"Overview", "Ranks", "Performance", "Matches", "Counter-Craft"};
	private static final String[] TAB_HELP = {
			"The big picture: ratings, win rate, skills and how your last games went.",
			"Your Premier rating, Wingman rank and skill group on every Competitive map.",
			"How you play, from Leetify: aim, utility, opening duels and trades. Hover any row for what it means.",
			"Your last 100 matches. Click a mode to filter them.",
			"Every Counter-Craft hardcore run on this computer. Click one for its deep dive."};
	private static final String[] MODES = {"All", "Premier", "Competitive", "Wingman", "FACEIT"};

	private SteamGrabber.Profile profile;
	private JsonObject leetify;
	private String leetifyNote = "Loading your stats...";
	private long loadedAt;
	private static int mode;
	/** When "Refresh stats" was last pressed: screens loaded before it load again. */
	private static long refreshRequested;
	private static final Map<Identifier, Boolean> HAVE = new HashMap<>();

	/** Fetch Steam and Leetify again (the next time the stats show). */
	public static void refresh() {
		refreshRequested = System.currentTimeMillis();
	}

	public CsStatsScreen(Screen parent) {
		super(Component.literal("My CS2 Stats"), parent);
	}

	private String id() {
		String server = SteamLinkClient.serverSteamId();
		return server.isEmpty() ? SteamLinkClient.steamId() : server;
	}

	@Override
	String[] tabs() {
		return TABS;
	}

	@Override
	String[] tabHelp() {
		return TAB_HELP;
	}

	@Override
	String source(int page) {
		return page == 4 ? "Counter-Craft runs are kept on this computer, even after the world is deleted."
				: "CS2 stats from Leetify's public profile (leetify.com) and your public Steam profile.";
	}

	// ---------------------------------------------------------------- widgets and loading

	@Override
	protected void init() {
		super.init();
		String id = id();
		if (id == null) {
			addRenderableWidget(Button.builder(Component.literal("Link Steam..."), b -> minecraft.gui.setScreen(new SteamLinkScreen(this)))
					.bounds(width / 2 - 60, height / 2 + 10, 120, 20).build());
			return;
		}
		int x = width - PAD;
		String[][] top = {
				{"Copy stats card", "Copies a short text summary of your stats, for a chat, a post or a video description"},
				{"Refresh", "Load your Steam and Leetify stats again"},
				{"Steam Account", "Link, unlink or refresh your Steam account"}};
		for (String[] t : top) {
			int w = font.width(t[0]) + 14;
			x -= w;
			Button.OnPress press = switch (t[0]) {
				case "Refresh" -> btn -> {
					refresh();
					SteamProfileCard.refresh();
					rebuildWidgets();
				};
				case "Steam Account" -> btn -> minecraft.gui.setScreen(new SteamLinkScreen(this));
				default -> btn -> {
					minecraft.keyboardHandler.setClipboard(card());
					leetifyNote = "Stats card copied: paste it anywhere.";
				};
			};
			Button b = Button.builder(Component.literal(t[0]), press).bounds(x, 8, w, 18).build();
			b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(t[1])));
			addRenderableWidget(b);
			x -= 4;
		}
		x = width - PAD;
		String name = java.net.URLEncoder.encode(profile != null ? profile.name() : "", java.nio.charset.StandardCharsets.UTF_8);
		String[][] links = {{"HLTV", "https://www.hltv.org/search?query=" + name}, {"csstats.gg", "https://csstats.gg/player/" + id},
				{"Leetify", "https://leetify.com/app/profile/" + id}, {"Steam", "https://steamcommunity.com/profiles/" + id}};
		for (String[] l : links) {
			int w = font.width(l[0]) + 12;
			x -= w;
			String url = l[1];
			addRenderableWidget(Button.builder(Component.literal(l[0]), b -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(url))).bounds(x, 32, w, 16).build());
			x -= 3;
		}
		if (profile == null && leetify == null || loadedAt < refreshRequested) {
			loadedAt = System.currentTimeMillis();
			profile = null;
			leetify = null;
			leetifyNote = "Loading your stats...";
			load(id);
		}
	}

	private void load(String id) {
		SteamGrabber.fetch(id).thenAccept(p -> minecraft.execute(() -> {
			profile = p;
			rebuildWidgets(); // the HLTV link searches by name
		})).exceptionally(t -> null);
		HTTP.sendAsync(HttpRequest.newBuilder(URI.create(LEETIFY + id)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString())
				.thenAccept(r -> minecraft.execute(() -> {
					if (r.statusCode() == 200) {
						leetify = JsonParser.parseString(r.body()).getAsJsonObject();
						leetifyNote = "";
					} else if (r.statusCode() == 404) {
						leetifyNote = "Leetify doesn't know this account yet. Sign in once at leetify.com and your CS2 stats appear here.";
					} else {
						leetifyNote = "Leetify couldn't be reached (" + r.statusCode() + "). Press Refresh to try again.";
					}
				})).exceptionally(t -> {
					minecraft.execute(() -> leetifyNote = "Leetify couldn't be reached. Press Refresh to try again.");
					return null;
				});
	}

	// ---------------------------------------------------------------- header

	@Override
	void header(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		String id = id();
		if (id == null) {
			big(g, "MY CS2 STATS", PAD, 18, 0xFFFFFFFF, 2f);
			return;
		}
		SteamProfileCard.avatar(g, id, PAD, 10, 40);
		g.outline(PAD - 1, 9, 42, 42, 0x40FFFFFF);
		String name = profile != null ? profile.name() : leetify != null ? str(leetify, "name") : "Loading...";
		boolean genuine = minecraft.player != null && dev.countercraft.steam.GenuineSkins.genuine(minecraft.player);
		int nx = PAD + 50;
		name = font.plainSubstrByWidth(name, (width - 300 - nx) / 2); // clear of the buttons on the right
		big(g, name, nx, 12, 0xFFFFFFFF, 2f);
		if (genuine) {
			int gx = nx + font.width(name) * 2 + 6;
			g.text(font, "✓", gx, 16, GOOD, true);
			if (mouseX >= gx && mouseX < gx + 8 && mouseY >= 12 && mouseY < 28) {
				hint("Genuine Skins: this Steam account is linked and plays with its own inventory.");
			}
		}
		List<String> sub = new ArrayList<>();
		if (profile != null && profile.level() > 0) {
			sub.add("Steam level " + profile.level());
		}
		int hours = SteamProfileCard.hours(id);
		if (hours >= 0) {
			sub.add(String.format(Locale.ROOT, "%,d hours in CS2", hours));
		}
		if (leetify != null && !str(leetify, "first_match_date").isEmpty()) {
			sub.add("tracked since " + monthYear(str(leetify, "first_match_date")));
		}
		g.text(font, String.join("   ·   ", sub), nx, 36, SOFT, false);
		if (leetify != null && leetify.has("bans") && leetify.getAsJsonArray("bans").size() > 0) {
			chip(g, "BANS ON RECORD", nx, 47, BAD);
		}
	}

	// ---------------------------------------------------------------- pages

	@Override
	int page(GuiGraphicsExtractor g, int page, int x, int y, int w, int mouseX, int mouseY) {
		if (id() == null) {
			g.centeredText(font, Component.literal("Link your Steam account (optional) to see your CS2 stats here."), width / 2, height / 2 - 14, SOFT);
			return y;
		}
		if (page == 4) {
			return runs(g, x, y, w, mouseX, mouseY);
		}
		if (leetify == null) {
			g.centeredText(font, Component.literal(leetifyNote), width / 2, y + 30, SOFT);
			return y + 50;
		}
		if (!leetifyNote.isEmpty()) {
			g.text(font, leetifyNote, x, y, 0xFF8FC8FF, false);
			y += 14;
		}
		return switch (page) {
			case 0 -> overview(g, x, y, w, mouseX, mouseY);
			case 1 -> ranks(g, x, y, w, mouseX, mouseY);
			case 2 -> performance(g, x, y, w, mouseX, mouseY);
			default -> matches(g, x, y, w, mouseX, mouseY);
		};
	}

	private int overview(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		JsonObject ranks = obj(leetify, "ranks");
		int premier = (int) num(ranks, "premier");
		int faceit = (int) num(ranks, "faceit");
		double lr = num(ranks, "leetify");
		int matches = (int) num(leetify, "total_matches");
		double wr = num(leetify, "winrate");
		int cw = (w - 4 * 6) / 5;
		int ch = 48;
		stat(g, x, y, cw, ch, "PREMIER", premier > 0 ? String.format(Locale.ROOT, "%,d", premier) : "Unranked", premier > 0 ? premierColor(premier) : DIM,
				"CS Rating", "Premier's CS Rating: the number CS2 shows next to your name in Premier. Coloured by its tier.", mouseX, mouseY);
		stat(g, x + (cw + 6), y, cw, ch, "FACEIT", faceit > 0 ? "Level " + faceit : "-", 0xFFFF5500, faceit > 0 ? (int) num(ranks, "faceit_elo") + " Elo" : "No FACEIT games",
				"Your FACEIT skill level (1 to 10) and Elo, if you play on FACEIT.", mouseX, mouseY);
		stat(g, x + 2 * (cw + 6), y, cw, ch, "LEETIFY RATING", String.format(Locale.ROOT, "%+.2f", lr), lr >= 0 ? GOOD : BAD, "0 is average",
				"Leetify's rating: how much you help your team win rounds. 0 is an average player; above 0 is better.", mouseX, mouseY);
		int wins = (int) Math.round(wr * matches);
		stat(g, x + 3 * (cw + 6), y, cw, ch, "WIN RATE", String.format(Locale.ROOT, "%.0f%%", wr * 100), wr >= 0.5 ? GOOD : BAD,
				wins + " W  " + (matches - wins) + " L", "The share of your tracked matches that you won.", mouseX, mouseY);
		stat(g, x + 4 * (cw + 6), y, cw, ch, "MATCHES", String.format(Locale.ROOT, "%,d", matches), TEXT,
				leetify.has("first_match_date") ? "since " + monthYear(str(leetify, "first_match_date")) : "",
				"How many of your matches Leetify has analysed.", mouseX, mouseY);
		y += ch + 12;
		// skills (left) and recent form (right)
		int half = (w - 12) / 2;
		int top = y;
		section(g, "SKILLS", x, y, half);
		y += 14;
		JsonObject rating = obj(leetify, "rating");
		String[][] skills = {
				{"Aim", "aim", "How well you hit your shots: accuracy, headshots, reaction time and crosshair placement. 50 is average."},
				{"Positioning", "positioning", "Whether you're in the right place: surviving, trading and not dying alone. 50 is average."},
				{"Utility", "utility", "How much your grenades help: flashes that blind enemies, HE damage, smokes and molotovs. 50 is average."}};
		for (String[] s : skills) {
			double v = num(rating, s[1]);
			boolean on = over(mouseX, mouseY, x, y - 1, half, 15);
			g.text(font, s[0], x, y + 2, on ? 0xFFFFFFFF : SOFT, false);
			meter(g, x + 74, y + 2, half - 74 - 34, 7, v / 100, skillColor(v));
			String val = String.format(Locale.ROOT, "%.0f", v);
			g.text(font, val, x + half - font.width(val), y + 1, TEXT, true);
			if (on) {
				hint(s[2]);
			}
			y += 15;
		}
		y += 2;
		String[][] sides = {
				{"CT side", String.format(Locale.ROOT, "%+.2f", num(rating, "ct_leetify") * 100), "Your Leetify rating on the Counter-Terrorist side."},
				{"T side", String.format(Locale.ROOT, "%+.2f", num(rating, "t_leetify") * 100), "Your Leetify rating on the Terrorist side."},
				{"Clutches", String.format(Locale.ROOT, "%+.2f", num(rating, "clutch") * 100), "How often you win rounds when you're the last one alive, compared to average."},
				{"Openings", String.format(Locale.ROOT, "%+.2f", num(rating, "opening") * 100), "How much the first duel of each round goes your way, compared to average."}};
		int sw = half / 4;
		for (int i = 0; i < sides.length; i++) {
			int sx = x + i * sw;
			g.text(font, sides[i][0], sx, y, DIM, false);
			g.text(font, sides[i][1], sx, y + 11, sides[i][1].startsWith("-") ? BAD : GOOD, true);
			if (over(mouseX, mouseY, sx, y, sw, 22)) {
				hint(sides[i][2]);
			}
		}
		int leftEnd = y + 24;
		y = top;
		int rx = x + half + 12;
		section(g, "RECENT FORM", rx, y, half);
		y += 14;
		JsonArray recent = recent();
		int n = Math.min(20, recent.size());
		int box = Math.max(8, Math.min(16, (half - 19 * 3) / 20));
		double sum = 0;
		int w20 = 0;
		for (int i = 0; i < n; i++) {
			JsonObject m = recent.get(i).getAsJsonObject();
			String o = str(m, "outcome");
			int bx = rx + (n - 1 - i) * (box + 3); // oldest on the left
			int color = "win".equals(o) ? 0xFF4CAF50 : "loss".equals(o) ? 0xFFC62828 : 0xFF757575;
			g.fill(bx, y, bx + box, y + box, color);
			g.fill(bx, y, bx + box, y + 1, 0x40FFFFFF);
			if (over(mouseX, mouseY, bx, y, box, box)) {
				g.outline(bx - 1, y - 1, box + 2, box + 2, 0xFFFFFFFF);
				hint(matchLine(m));
			}
			sum += num(m, "leetify_rating");
			w20 += "win".equals(o) ? 1 : 0;
		}
		y += box + 6;
		if (n > 0) {
			g.text(font, "Last " + n + ": " + w20 + " wins, " + (n - w20) + " losses", rx, y, SOFT, false);
			String avg = String.format(Locale.ROOT, "avg rating %+.2f", sum / n * 100);
			g.text(font, avg, rx + half - font.width(avg), y, sum >= 0 ? GOOD : BAD, false);
			y += 14;
		}
		// best and toughest map over the recent games (3 games or more)
		Map<String, int[]> per = perMap(recent, null);
		String best = null;
		String worst = null;
		double bestR = -1;
		double worstR = 2;
		for (var e : per.entrySet()) {
			int games = games(e.getValue());
			if (games < 3) {
				continue;
			}
			double r = e.getValue()[0] / (double) games;
			if (r > bestR) {
				bestR = r;
				best = e.getKey();
			}
			if (r < worstR) {
				worstR = r;
				worst = e.getKey();
			}
		}
		if (best != null) {
			g.text(font, "Best map", rx, y, DIM, false);
			g.text(font, mapName(best) + String.format(Locale.ROOT, "  %.0f%% won", bestR * 100), rx + 70, y, GOOD, false);
			y += 11;
		}
		if (worst != null && !worst.equals(best)) {
			g.text(font, "Toughest map", rx, y, DIM, false);
			g.text(font, mapName(worst) + String.format(Locale.ROOT, "  %.0f%% won", worstR * 100), rx + 70, y, BAD, false);
			y += 11;
		}
		y = Math.max(leftEnd, y) + 10;
		// most played maps, as picture cards
		section(g, "MOST PLAYED MAPS", x, y, w);
		y += 14;
		List<Map.Entry<String, int[]>> maps = new ArrayList<>(per.entrySet());
		maps.sort((a, b) -> Integer.compare(games(b.getValue()), games(a.getValue())));
		int mw = (w - 3 * 6) / 4;
		int mh = Math.max(56, Math.round(mw * 9 / 16f * 0.6f));
		for (int i = 0; i < Math.min(4, maps.size()); i++) {
			var e = maps.get(i);
			int mx = x + i * (mw + 6);
			int[] r = e.getValue();
			int games = games(r);
			boolean on = over(mouseX, mouseY, mx, y, mw, mh);
			mapCard(g, e.getKey(), mx, y, mw, mh, on);
			g.text(font, mapName(e.getKey()), mx + 6, y + 6, 0xFFFFFFFF, true);
			g.text(font, games + (games == 1 ? " game" : " games") + "   " + r[0] + "-" + r[1], mx + 6, y + mh - 22, SOFT, true);
			meter(g, mx + 6, y + mh - 10, mw - 12, 4, r[0] / (double) Math.max(1, games), r[0] * 2 >= games ? 0xFF4CAF50 : 0xFFC62828);
			if (on) {
				hint(mapName(e.getKey()) + ": " + r[0] + " wins, " + r[1] + " losses" + (r[2] > 0 ? ", " + r[2] + " ties" : "") + " in your recent matches.");
			}
		}
		return y + mh + 8;
	}

	private int ranks(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		JsonObject ranks = obj(leetify, "ranks");
		JsonArray recent = recent();
		// Premier and Wingman side by side
		int half = (w - 12) / 2;
		int premier = (int) num(ranks, "premier");
		boolean onP = over(mouseX, mouseY, x, y, half, 52);
		CsUi.card(g, x, y, half, 52, CsUi.hover("rk-premier", onP), 0x66C0F4, false);
		g.text(font, "PREMIER", x + 8, y + 7, DIM, false);
		big(g, premier > 0 ? String.format(Locale.ROOT, "%,d", premier) : "Unranked", x + 8, y + 22, premier > 0 ? premierColor(premier) : DIM, 2f);
		recordText(g, record(recent, 11), x + half - 8, y + 18);
		if (onP) {
			hint("Premier: CS2's main ranked mode, with one CS Rating for all maps. Record over your recent Premier games.");
		}
		int wingman = (int) num(ranks, "wingman");
		int wx = x + half + 12;
		boolean onW = over(mouseX, mouseY, wx, y, half, 52);
		CsUi.card(g, wx, y, half, 52, CsUi.hover("rk-wingman", onW), 0xEABE54, false);
		g.text(font, "WINGMAN", wx + 8, y + 7, DIM, false);
		badge(g, "wingman", wingman, wx + 8, y + 20, 1.5f);
		g.text(font, SKILL_GROUPS[Math.max(0, Math.min(18, wingman))], wx + 72, y + 27, wingman > 0 ? 0xFFEABE54 : DIM, true);
		recordText(g, record(recent, 7), wx + half - 8, y + 18);
		if (onW) {
			hint("Wingman: 2 versus 2 on small maps, with its own skill group.");
		}
		y += 64;
		// Competitive: one card per map
		section(g, "COMPETITIVE  ·  a skill group for each map", x, y, w);
		y += 14;
		Map<String, int[]> rec = perMap(recent, 12);
		List<Object[]> rows = new ArrayList<>();
		if (ranks.has("competitive") && ranks.get("competitive").isJsonArray()) {
			for (JsonElement e : ranks.getAsJsonArray("competitive")) {
				JsonObject c = e.getAsJsonObject();
				int rank = c.has("rank") && !c.get("rank").isJsonNull() ? c.get("rank").getAsInt() : 0;
				rows.add(new Object[] {str(c, "map_name"), rank});
			}
		}
		rows.sort((a, b) -> (int) b[1] != (int) a[1] ? Integer.compare((int) b[1], (int) a[1])
				: Integer.compare(games(rec.get((String) b[0])), games(rec.get((String) a[0]))));
		if (rows.isEmpty()) {
			g.text(font, "No Competitive skill groups on Leetify yet. Play a Competitive match and press Refresh.", x, y + 4, SOFT, false);
			return y + 20;
		}
		int cols = w >= 560 ? 4 : w >= 400 ? 3 : 2;
		int cw = (w - (cols - 1) * 6) / cols;
		int ch = 62;
		for (int i = 0; i < rows.size(); i++) {
			int cx = x + (i % cols) * (cw + 6);
			int cy = y + (i / cols) * (ch + 6);
			String map = (String) rows.get(i)[0];
			int rank = (int) rows.get(i)[1];
			boolean on = over(mouseX, mouseY, cx, cy, cw, ch);
			mapCard(g, map, cx, cy, cw, ch, on);
			g.text(font, mapName(map), cx + 6, cy + 5, 0xFFFFFFFF, true);
			badge(g, "skillgroup", rank, cx + 6, cy + 18, 1.5f);
			g.text(font, font.plainSubstrByWidth(SKILL_GROUPS[Math.max(0, Math.min(18, rank))], cw - 72), cx + 68, cy + 25, rank > 0 ? 0xFFEABE54 : DIM, true);
			int[] r = rec.get(map);
			if (games(r) > 0) {
				String t = r[0] + "-" + r[1] + (r[2] > 0 ? "-" + r[2] : "") + String.format(Locale.ROOT, "   %.0f%% won", 100.0 * r[0] / games(r));
				g.text(font, t, cx + 6, cy + ch - 17, r[0] * 2 >= games(r) ? GOOD : BAD, true);
				meter(g, cx + 6, cy + ch - 6, cw - 12, 3, r[0] / (double) games(r), r[0] * 2 >= games(r) ? 0xFF4CAF50 : 0xFFC62828);
			} else {
				g.text(font, "no recent games", cx + 6, cy + ch - 17, DIM, true);
			}
			if (on) {
				hint(mapName(map) + ": " + (rank > 0 ? SKILL_GROUPS[Math.min(18, rank)] : "no skill group yet (CS2 gives one after 10 wins on the map)")
						+ (r != null ? ". Recent record " + r[0] + " wins, " + r[1] + " losses." : "."));
			}
		}
		return y + ((rows.size() + cols - 1) / cols) * (ch + 6);
	}

	private int performance(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		JsonObject s = obj(leetify, "stats");
		// {label, value text, meter 0..1 (-1: none), help}
		Object[][] aim = {
				row("Headshot accuracy", pctT(num(s, "accuracy_head")), num(s, "accuracy_head") / 40,
						"Of your shots that hit an enemy, how many hit the head."),
				row("Spray accuracy", pctT(num(s, "spray_accuracy")), num(s, "spray_accuracy") / 60,
						"How many bullets land when you hold down fire (after the first few)."),
				row("Accuracy when spotted", pctT(num(s, "accuracy_enemy_spotted")), num(s, "accuracy_enemy_spotted") / 60,
						"How many of your shots hit while an enemy is visible."),
				row("Counter-strafing", pctT(num(s, "counter_strafing_good_shots_ratio")), num(s, "counter_strafing_good_shots_ratio") / 100,
						"How often you were standing still when you shot (tapping the opposite key to stop). Higher is better."),
				row("Reaction time", String.format(Locale.ROOT, "%.0f ms", num(s, "reaction_time_ms")), 1 - (num(s, "reaction_time_ms") - 300) / 700,
						"How long you take to shoot after an enemy appears. Lower is better (the bar is fuller when faster)."),
				row("Crosshair placement", String.format(Locale.ROOT, "%.1f°", num(s, "preaim")), 1 - num(s, "preaim") / 30,
						"How far your crosshair had to move to reach an enemy when they appeared. Lower is better (the bar is fuller when closer).")};
		Object[][] util = {
				row("Flashes thrown", String.format(Locale.ROOT, "%.1f a match", num(s, "flashbang_thrown")), num(s, "flashbang_thrown") / 15,
						"How many flashbangs you throw in an average match."),
				row("Enemies blinded", String.format(Locale.ROOT, "%.2f a flash", num(s, "flashbang_hit_foe_per_flashbang")), num(s, "flashbang_hit_foe_per_flashbang") / 1.5,
						"How many enemies each of your flashes blinds, on average."),
				row("Blind time", String.format(Locale.ROOT, "%.1f s", num(s, "flashbang_hit_foe_avg_duration")), num(s, "flashbang_hit_foe_avg_duration") / 4,
						"How long the enemies you flash stay blind."),
				row("Flashes into kills", pctT(num(s, "flashbang_leading_to_kill")), num(s, "flashbang_leading_to_kill") / 15,
						"How many of your flashes lead to a kill by your team."),
				row("Teammates blinded", String.format(Locale.ROOT, "%.2f a flash", num(s, "flashbang_hit_friend_per_flashbang")), num(s, "flashbang_hit_friend_per_flashbang") / 1.5,
						"How many teammates each of your flashes blinds. Lower is better."),
				row("HE damage", String.format(Locale.ROOT, "%.1f a grenade", num(s, "he_foes_damage_avg")), num(s, "he_foes_damage_avg") / 30,
						"Damage each of your HE grenades does to enemies."),
				row("Utility unused at death", String.format(Locale.ROOT, "$%.0f", num(s, "utility_on_death_avg")), -1,
						"The value of grenades still in your pockets when you die. Lower is better.")};
		Object[][] duels = {
				row("Opening duels won (CT)", pctT(num(s, "ct_opening_duel_success_percentage")), num(s, "ct_opening_duel_success_percentage") / 100,
						"When the first fight of a round involves you on CT, how often you win it."),
				row("Opening duels won (T)", pctT(num(s, "t_opening_duel_success_percentage")), num(s, "t_opening_duel_success_percentage") / 100,
						"When the first fight of a round involves you on T, how often you win it."),
				row("Aggressive openings (CT)", pctT(num(s, "ct_opening_aggression_success_rate")), num(s, "ct_opening_aggression_success_rate") / 100,
						"When you go looking for the first fight on CT, how often it works."),
				row("Aggressive openings (T)", pctT(num(s, "t_opening_aggression_success_rate")), num(s, "t_opening_aggression_success_rate") / 100,
						"When you go looking for the first fight on T, how often it works.")};
		Object[][] trades = {
				row("Trade kills", pctT(num(s, "trade_kills_success_percentage")), num(s, "trade_kills_success_percentage") / 100,
						"When a teammate dies near you, how often you kill their killer right after."),
				row("Deaths traded", pctT(num(s, "traded_deaths_success_percentage")), num(s, "traded_deaths_success_percentage") / 100,
						"When you die, how often a teammate kills your killer right after."),
				row("Trade chances a round", String.format(Locale.ROOT, "%.2f", num(s, "trade_kill_opportunities_per_round")), num(s, "trade_kill_opportunities_per_round"),
						"How often you're in position to trade a teammate, per round.")};
		int half = (w - 16) / 2;
		int ly = group(g, "AIM", aim, x, y, half, mouseX, mouseY);
		ly = group(g, "OPENING DUELS", duels, x, ly + 8, half, mouseX, mouseY);
		int ry = group(g, "UTILITY", util, x + half + 16, y, half, mouseX, mouseY);
		ry = group(g, "TRADES", trades, x + half + 16, ry + 8, half, mouseX, mouseY);
		return Math.max(ly, ry);
	}

	private static Object[] row(String label, String value, double meter, String help) {
		return new Object[] {label, value, meter, help};
	}

	private int group(GuiGraphicsExtractor g, String title, Object[][] rows, int x, int y, int w, int mouseX, int mouseY) {
		section(g, title, x, y, w);
		y += 14;
		for (Object[] r : rows) {
			boolean on = over(mouseX, mouseY, x - 2, y - 2, w + 4, 16);
			if (on) {
				g.fill(x - 2, y - 2, x + w + 2, y + 14, 0x18FFFFFF);
				hint((String) r[3]);
			}
			g.text(font, (String) r[0], x, y + 2, on ? 0xFFFFFFFF : SOFT, false);
			String v = (String) r[1];
			g.text(font, v, x + w - font.width(v), y + 1, TEXT, true);
			double m = (double) r[2];
			if (m != -1) {
				int mx = x + Math.min(130, w / 2);
				meter(g, mx, y + 4, Math.max(20, x + w - font.width(v) - 8 - mx), 5, m, 0xFF5E98D9);
			}
			y += 16;
		}
		return y;
	}

	private int matches(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		// mode filter chips
		int cx = x;
		for (int i = 0; i < MODES.length; i++) {
			int cw = font.width(MODES[i]) + 16;
			boolean on = over(mouseX, mouseY, cx, y, cw, 16);
			boolean sel = mode == i;
			g.fill(cx, y, cx + cw, y + 16, sel ? 0xE0DCB45A : on ? 0x40FFFFFF : 0x24FFFFFF);
			g.text(font, MODES[i], cx + 8, y + 4, sel ? 0xFF101010 : TEXT, false);
			clickable(cx, y, cw, 16, "mode" + i);
			if (on) {
				hint("Show " + (i == 0 ? "every mode" : MODES[i] + " matches only") + ".");
			}
			cx += cw + 4;
		}
		y += 22;
		List<JsonObject> shown = new ArrayList<>();
		for (JsonElement e : recent()) {
			JsonObject m = e.getAsJsonObject();
			if (mode == 0 || modeOf(m) == mode) {
				shown.add(m);
			}
		}
		if (shown.isEmpty()) {
			g.text(font, "No " + MODES[mode] + " matches in your recent games.", x, y + 4, SOFT, false);
			return y + 20;
		}
		int wins = 0;
		int losses = 0;
		double sum = 0;
		for (JsonObject m : shown) {
			wins += "win".equals(str(m, "outcome")) ? 1 : 0;
			losses += "loss".equals(str(m, "outcome")) ? 1 : 0;
			sum += num(m, "leetify_rating");
		}
		g.text(font, shown.size() + " matches   " + wins + " wins   " + losses + " losses", x, y, SOFT, false);
		String avg = String.format(Locale.ROOT, "average rating %+.2f", sum / shown.size() * 100);
		g.text(font, avg, x + w - font.width(avg), y, sum >= 0 ? GOOD : BAD, false);
		y += 14;
		// columns
		int cMap = x + 26;
		int cMode = x + 128;
		int cScore = x + 206;
		int cRating = x + 256;
		int cHs = x + 310;
		int cReact = x + 352;
		boolean wide = w > 460;
		g.text(font, "MAP", cMap, y, DIM, false);
		g.text(font, "MODE", cMode, y, DIM, false);
		g.text(font, "SCORE", cScore, y, DIM, false);
		g.text(font, "RATING", cRating, y, DIM, false);
		if (wide) {
			g.text(font, "HS", cHs, y, DIM, false);
			g.text(font, "REACTION", cReact, y, DIM, false);
		}
		g.text(font, "WHEN", x + w - font.width("WHEN"), y, DIM, false);
		y += 12;
		for (JsonObject m : shown) {
			int h = 20;
			boolean on = over(mouseX, mouseY, x, y, w, h);
			String o = str(m, "outcome");
			int oc = "win".equals(o) ? 0xFF4CAF50 : "loss".equals(o) ? 0xFFC62828 : 0xFF757575;
			g.fillGradient(x, y, x + w, y + h, (oc & 0x00FFFFFF) | (on ? 0x48000000 : 0x26000000), 0x10000000);
			g.fill(x, y, x + 3, y + h, oc);
			String map = str(m, "map_name");
			if (has(icon(map))) {
				g.blit(RenderPipelines.GUI_TEXTURED, icon(map), x + 7, y + 2, 0f, 0f, 16, 16, 64, 64, 64, 64);
			}
			g.text(font, font.plainSubstrByWidth(mapName(map), cMode - cMap - 4), cMap, y + 6, 0xFFFFFFFF, false);
			int md = modeOf(m);
			chip(g, md > 0 ? MODES[md] : "Other", cMode, y + 4, md == 1 ? 0xFF66C0F4 : md == 2 ? 0xFF4CAF50 : md == 3 ? 0xFFEABE54 : md == 4 ? 0xFFFF5500 : 0xFF757575);
			JsonArray sc = m.has("score") && m.get("score").isJsonArray() ? m.getAsJsonArray("score") : new JsonArray();
			if (sc.size() == 2) {
				g.text(font, sc.get(0).getAsInt() + " : " + sc.get(1).getAsInt(), cScore, y + 6, TEXT, true);
			}
			double r = num(m, "leetify_rating") * 100;
			g.text(font, String.format(Locale.ROOT, "%+.2f", r), cRating, y + 6, r >= 0 ? GOOD : BAD, true);
			if (wide) {
				g.text(font, String.format(Locale.ROOT, "%.0f%%", num(m, "accuracy_head")), cHs, y + 6, SOFT, false);
				g.text(font, String.format(Locale.ROOT, "%.0f ms", num(m, "reaction_time_ms")), cReact, y + 6, SOFT, false);
			}
			String when = ago(str(m, "finished_at"));
			g.text(font, when, x + w - font.width(when), y + 6, DIM, false);
			if (on) {
				hint(matchLine(m));
			}
			y += h + 2;
		}
		return y;
	}

	private int runs(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		List<RunHistory.Run> runs = RunHistory.runs();
		if (runs.isEmpty()) {
			g.text(font, "No Counter-Craft runs yet. Pick a team in a Counter-Craft (hardcore) world to start one.", x, y + 4, SOFT, false);
			return y + 20;
		}
		int best = 0;
		int total = 0;
		int alive = 0;
		for (RunHistory.Run r : runs) {
			best = Math.max(best, r.days);
			total += r.days;
			alive += r.finished ? 0 : 1;
		}
		int cw = (w - 3 * 6) / 4;
		stat(g, x, y, cw, 44, "RUNS", Integer.toString(runs.size()), TEXT, alive + " still going", "Hardcore Counter-Craft worlds you've played.", mouseX, mouseY);
		stat(g, x + cw + 6, y, cw, 44, "BEST RUN", best + (best == 1 ? " day" : " days"), ACCENT, "", "Your longest survival.", mouseX, mouseY);
		stat(g, x + 2 * (cw + 6), y, cw, 44, "DAYS SURVIVED", Integer.toString(total), TEXT, "across all runs", "Every day survived, in every run.", mouseX, mouseY);
		stat(g, x + 3 * (cw + 6), y, cw, 44, "DEATHS", Integer.toString(runs.size() - alive), BAD, "runs ended", "Runs that ended in death.", mouseX, mouseY);
		y += 54;
		java.time.format.DateTimeFormatter date = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
		java.time.ZoneId zone = java.time.ZoneId.systemDefault();
		for (RunHistory.Run r : runs) {
			int h = 26;
			boolean on = over(mouseX, mouseY, x, y, w, h);
			boolean onDelete = on && mouseX >= x + w - 20;
			boolean ct = dev.countercraft.team.Teams.CT.equals(r.team);
			int team = 0xFF000000 | (ct ? dev.countercraft.team.Teams.CT_COLOR : dev.countercraft.team.Teams.T_COLOR);
			CsUi.card(g, x, y, w, h, CsUi.hover(r, on), team & 0xFFFFFF, false);
			g.fill(x, y, x + 3, y + h, team);
			g.text(font, font.plainSubstrByWidth(r.world == null ? "?" : r.world, 170), x + 10, y + 4, 0xFFFFFFFF, true);
			String sub = (ct ? "Counter-Terrorist" : "Terrorist") + "   started " + date.format(java.time.Instant.ofEpochMilli(r.started).atZone(zone));
			g.text(font, sub, x + 10, y + 15, DIM, false);
			chip(g, r.finished ? "ENDED" : "ONGOING", Math.max(x + 190, x + 18 + font.width(sub)), y + 7, r.finished ? BAD : GOOD);
			String days = r.days + (r.days == 1 ? " day" : " days");
			big(g, days, x + w - 30 - Math.round(font.width(days) * 1.5f), y + 7, ACCENT, 1.5f);
			g.text(font, onDelete ? "✕" : "▶", x + w - 14, y + 9, onDelete ? BAD : DIM, false);
			clickable(x, y, w, h, r);
			if (on) {
				hint(onDelete ? "Delete this run's record (asks first). The world itself isn't touched." : "Open this run's deep dive.");
			}
			y += h + 4;
		}
		return y;
	}

	@Override
	boolean clicked(Object what, boolean right) {
		if (what instanceof String s && s.startsWith("mode")) {
			mode = Integer.parseInt(s.substring(4));
			return true;
		}
		if (what instanceof RunHistory.Run r) {
			if (right) {
				RunScreen.confirmDelete(minecraft, this, this, r);
			} else {
				minecraft.gui.setScreen(new RunScreen(this, r));
			}
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- pieces

	/** A map's picture (darkened, so text reads on it), or a plain card without one. */
	private void mapCard(GuiGraphicsExtractor g, String map, int x, int y, int w, int h, boolean on) {
		Identifier pic = CounterCraft.id("textures/gui/maps/" + map + ".png");
		if (has(pic)) {
			// cover: the picture's middle band at the card's shape (the pictures are 16:9, 480 x 270)
			float band = Math.min(1f, (h / (float) w) * 16f / 9f);
			int vh = Math.round(270 * band);
			g.blit(RenderPipelines.GUI_TEXTURED, pic, x, y, 0f, (270 - vh) / 2f, w, h, 480, vh, 480, 270);
			g.fillGradient(x, y, x + w, y + h, on ? 0x50000000 : 0x80000000, on ? 0xB0000000 : 0xD8000000);
			g.outline(x, y, w, h, on ? 0x80FFFFFF : 0x30FFFFFF);
		} else {
			CsUi.card(g, x, y, w, h, on ? 1f : 0f, 0xDCB45A, false);
		}
	}

	private void badge(GuiGraphicsExtractor g, String set, int rank, int x, int y, float scale) {
		String b = rank > 0 ? set + Math.min(18, rank) : set.equals("wingman") ? "wingman_none" : "skillgroup_none";
		Identifier id = CounterCraft.id("textures/gui/skillgroups/" + b + ".png");
		g.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, Math.round(39 * scale), Math.round(16 * scale), 118, 48, 118, 48);
	}

	private void recordText(GuiGraphicsExtractor g, int[] r, int right, int y) {
		int games = games(r);
		if (games == 0) {
			g.text(font, "no recent games", right - font.width("no recent games"), y + 6, DIM, false);
			return;
		}
		String t = r[0] + " W  " + r[1] + " L";
		String p = String.format(Locale.ROOT, "%.0f%% won", 100.0 * r[0] / games);
		g.text(font, t, right - font.width(t), y, TEXT, true);
		g.text(font, p, right - font.width(p), y + 12, r[0] * 2 >= games ? GOOD : BAD, false);
	}

	private static Identifier icon(String map) {
		return CounterCraft.id("textures/gui/maps/icon_" + map + ".png");
	}

	private boolean has(Identifier id) {
		return HAVE.computeIfAbsent(id, k -> minecraft.getResourceManager().getResource(k).isPresent());
	}

	private JsonArray recent() {
		return leetify != null && leetify.has("recent_matches") && leetify.get("recent_matches").isJsonArray() ? leetify.getAsJsonArray("recent_matches") : new JsonArray();
	}

	/** 1 Premier, 2 Competitive, 3 Wingman, 4 FACEIT, 0 anything else. */
	private static int modeOf(JsonObject m) {
		if ("faceit".equals(str(m, "data_source"))) {
			return 4;
		}
		int t = m.has("rank_type") && !m.get("rank_type").isJsonNull() ? m.get("rank_type").getAsInt() : -1;
		return t == 11 ? 1 : t == 12 ? 2 : t == 7 ? 3 : 0;
	}

	/** Wins, losses, ties per map over the recent matches (rankType null: every mode). */
	private static Map<String, int[]> perMap(JsonArray recent, Integer rankType) {
		Map<String, int[]> out = new LinkedHashMap<>();
		for (JsonElement e : recent) {
			JsonObject m = e.getAsJsonObject();
			int t = m.has("rank_type") && !m.get("rank_type").isJsonNull() ? m.get("rank_type").getAsInt() : -1;
			if (rankType != null && t != rankType) {
				continue;
			}
			String o = str(m, "outcome");
			out.computeIfAbsent(str(m, "map_name"), z -> new int[3])["win".equals(o) ? 0 : "loss".equals(o) ? 1 : 2]++;
		}
		return out;
	}

	private static int[] record(JsonArray recent, int rankType) {
		int[] r = new int[3];
		for (int[] m : perMap(recent, rankType).values()) {
			r[0] += m[0];
			r[1] += m[1];
			r[2] += m[2];
		}
		return r;
	}

	private static int games(int[] r) {
		return r == null ? 0 : r[0] + r[1] + r[2];
	}

	private static String matchLine(JsonObject m) {
		JsonArray sc = m.has("score") && m.get("score").isJsonArray() ? m.getAsJsonArray("score") : new JsonArray();
		String score = sc.size() == 2 ? " " + sc.get(0).getAsInt() + ":" + sc.get(1).getAsInt() : "";
		int md = modeOf(m);
		return mapName(str(m, "map_name")) + (md > 0 ? " (" + MODES[md] + ")" : "") + ", " + str(m, "outcome").toUpperCase(Locale.ROOT) + score
				+ String.format(Locale.ROOT, ", rating %+.2f, ", num(m, "leetify_rating") * 100) + ago(str(m, "finished_at"));
	}

	private static String ago(String iso) {
		try {
			long s = Duration.between(java.time.Instant.parse(iso), java.time.Instant.now()).getSeconds();
			long d = s / 86400;
			return s < 3600 ? s / 60 + " min ago" : s < 86400 ? s / 3600 + " h ago" : d == 1 ? "yesterday" : d < 31 ? d + " days ago" : monthYear(iso);
		} catch (Exception e) {
			return iso.length() >= 10 ? iso.substring(0, 10) : iso;
		}
	}

	private static String monthYear(String iso) {
		try {
			return java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.ROOT).format(java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()));
		} catch (Exception e) {
			return iso;
		}
	}

	private static int skillColor(double v) {
		return v >= 70 ? 0xFF4CAF50 : v >= 50 ? 0xFF8BC34A : v >= 35 ? 0xFFEABE54 : 0xFFE05050;
	}

	private static String pctT(double v) {
		return String.format(Locale.ROOT, "%.0f%%", v);
	}

	/** A plain-text card to paste into a video description, a chat or a post. */
	private String card() {
		StringBuilder b = new StringBuilder();
		String name = profile != null ? profile.name() : leetify != null ? str(leetify, "name") : "";
		b.append(name).append(" - CS2 stats\n");
		if (leetify != null) {
			JsonObject ranks = obj(leetify, "ranks");
			JsonObject r = obj(leetify, "rating");
			b.append(String.format(Locale.ROOT, "Premier %,d | FACEIT level %d (%d elo) | Leetify %+.2f%n", (int) num(ranks, "premier"), (int) num(ranks, "faceit"),
					(int) num(ranks, "faceit_elo"), num(ranks, "leetify")));
			b.append(String.format(Locale.ROOT, "Win rate %.0f%% over %,d matches | Aim %.0f | Positioning %.0f | Utility %.0f%n", num(leetify, "winrate") * 100,
					(int) num(leetify, "total_matches"), num(r, "aim"), num(r, "positioning"), num(r, "utility")));
			b.append("Stats: Leetify (leetify.com/app/profile/").append(id()).append(")\n");
		}
		List<RunHistory.Run> runs = RunHistory.runs();
		int best = 0;
		for (RunHistory.Run r : runs) {
			best = Math.max(best, r.days);
		}
		b.append("Counter-Craft 2: ").append(runs.size()).append(runs.size() == 1 ? " run" : " runs").append(", best ").append(best)
				.append(best == 1 ? " day survived" : " days survived");
		return b.toString();
	}

	private static final String[] SKILL_GROUPS = {"Unranked", "Silver I", "Silver II", "Silver III", "Silver IV", "Silver Elite", "Silver Elite Master",
			"Gold Nova I", "Gold Nova II", "Gold Nova III", "Gold Nova Master", "Master Guardian I", "Master Guardian II", "Master Guardian Elite",
			"Distinguished Master Guardian", "Legendary Eagle", "Legendary Eagle Master", "Supreme Master First Class", "The Global Elite"};

	/** "de_dust2" -> "Dust II", "cs_office" -> "Office". */
	private static String mapName(String map) {
		String m = map.replaceFirst("^(de|cs|ar|gg)_", "");
		if (m.equals("dust2")) {
			return "Dust II";
		}
		return m.isEmpty() ? map : Character.toUpperCase(m.charAt(0)) + m.substring(1).replace('_', ' ');
	}

	private static int premierColor(int r) {
		return r >= 30000 ? 0xFFFFD700 : r >= 25000 ? 0xFFEB4B4B : r >= 20000 ? 0xFFD32CE6 : r >= 15000 ? 0xFF8847FF : r >= 10000 ? 0xFF4B69FF : r >= 5000
				? 0xFF5E98D9 : 0xFFB0C3D9;
	}

	private static JsonObject obj(JsonObject o, String k) {
		return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
	}

	private static double num(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : 0;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
