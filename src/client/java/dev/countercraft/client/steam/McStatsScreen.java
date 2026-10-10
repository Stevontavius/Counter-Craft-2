package dev.countercraft.client.steam;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.client.loadout.CsUi;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * MC Stats, in pages: Overview (your skin, the headline numbers, favourites), Lifetime (everything Minecraft counts,
 * grouped, with your top mobs and blocks), Worlds (each world's share), Hypixel and Bedrock. The Java numbers are added
 * up from Minecraft's own stats and advancements files in every world saved in this game folder
 * (saves/&lt;world&gt;/players/stats|advancements/&lt;your uuid&gt;.json), read only, on this computer.
 * <p>Hypixel: its API needs a key and every key-free mirror is gone or behind bot protection, so the page opens the
 * public stats sites, and can show the numbers here if you paste your own free key ({@link HypixelStats}). Bedrock:
 * its stats live behind an Xbox sign-in, so the page opens your public Xbox profile.</p>
 */
public final class McStatsScreen extends StatsScreen {
	private static final String[] TABS = {"Overview", "Lifetime", "Worlds", "Hypixel", "Bedrock"};
	private static final String[] TAB_HELP = {
			"Your Minecraft career at a glance, added up from every world on this computer.",
			"Everything Minecraft counts: combat, travel, building and the rest, plus your top mobs and blocks.",
			"Each of your worlds, by how long you've played it.",
			"Your Hypixel stats: open them on Plancke, 25Karma or NameMC, or show them here.",
			"Your Bedrock Edition profile on Xbox."};

	/** One world's share. */
	private record World(String name, long playTicks, long deaths, long mobKills, long mined) {
	}

	/** Everything added up. */
	private record Totals(Map<String, Long> custom, Map<String, Long> mined, Map<String, Long> killed, Map<String, Long> killedBy,
			Map<String, Long> placed, Map<String, Long> crafted, long broken, int advancements, List<World> worlds) {
		long c(String key) {
			return custom.getOrDefault("minecraft:" + key, 0L);
		}

		long distanceCm() {
			return custom.entrySet().stream().filter(e -> e.getKey().endsWith("_one_cm")).mapToLong(Map.Entry::getValue).sum();
		}
	}

	private static CompletableFuture<Totals> loading;
	private Totals totals;
	private EditBox gamertag;
	private static String gamertagText = "";
	private HypixelStats.Result hypixel;
	private String hypixelNote = "";

	public McStatsScreen(Screen parent) {
		super(Component.literal("MC Stats"), parent);
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
		return switch (page) {
			case 3 -> "Hypixel stats from Hypixel's own API, with your key. The sites open in your browser.";
			case 4 -> "Opens xbox.com in your browser. Counter-Craft 2 doesn't sign in to Xbox or save your gamertag.";
			default -> "Read from Minecraft's own stats files in your saves folder, on this computer. Servers keep their own stats.";
		};
	}

	// ---------------------------------------------------------------- data

	private static Totals read(Path saves, String uuid) {
		Map<String, Long> custom = new HashMap<>();
		Map<String, Long> mined = new HashMap<>();
		Map<String, Long> killed = new HashMap<>();
		Map<String, Long> killedBy = new HashMap<>();
		Map<String, Long> placed = new HashMap<>();
		Map<String, Long> crafted = new HashMap<>();
		Map<String, Long> broken = new HashMap<>();
		Set<String> adv = new HashSet<>();
		List<World> worlds = new ArrayList<>();
		if (!Files.isDirectory(saves)) {
			return new Totals(custom, mined, killed, killedBy, placed, crafted, 0, 0, worlds);
		}
		try (Stream<Path> dirs = Files.list(saves)) {
			for (Path w : dirs.filter(Files::isDirectory).toList()) {
				try {
					Path stats = first(w.resolve("players/stats/" + uuid + ".json"), w.resolve("stats/" + uuid + ".json"));
					if (stats != null) {
						JsonObject s = JsonParser.parseString(Files.readString(stats)).getAsJsonObject().getAsJsonObject("stats");
						if (s != null) {
							JsonObject c = s.getAsJsonObject("minecraft:custom");
							add(custom, c);
							add(mined, s.getAsJsonObject("minecraft:mined"));
							add(killed, s.getAsJsonObject("minecraft:killed"));
							add(killedBy, s.getAsJsonObject("minecraft:killed_by"));
							add(crafted, s.getAsJsonObject("minecraft:crafted"));
							add(broken, s.getAsJsonObject("minecraft:broken"));
							JsonObject used = s.getAsJsonObject("minecraft:used");
							if (used != null) {
								for (Map.Entry<String, JsonElement> e : used.entrySet()) {
									Identifier id = Identifier.tryParse(e.getKey());
									if (id != null && BuiltInRegistries.ITEM.getValue(id) instanceof BlockItem) {
										placed.merge(e.getKey(), e.getValue().getAsLong(), Long::sum);
									}
								}
							}
							worlds.add(new World(w.getFileName().toString(), get(c, "play_time"), get(c, "deaths"), get(c, "mob_kills"),
									sum(s.getAsJsonObject("minecraft:mined"))));
						}
					}
					Path a = first(w.resolve("players/advancements/" + uuid + ".json"), w.resolve("advancements/" + uuid + ".json"));
					if (a != null) {
						for (Map.Entry<String, JsonElement> e : JsonParser.parseString(Files.readString(a)).getAsJsonObject().entrySet()) {
							if (e.getValue().isJsonObject() && !e.getKey().contains(":recipes/") && e.getValue().getAsJsonObject().has("done")
									&& e.getValue().getAsJsonObject().get("done").getAsBoolean()) {
								adv.add(e.getKey());
							}
						}
					}
				} catch (Exception ex) {
					dev.countercraft.CounterCraft.LOG.debug("[countercraft] MC Stats skipped {}: {}", w, ex.toString());
				}
			}
		} catch (Exception ex) {
			dev.countercraft.CounterCraft.LOG.warn("[countercraft] MC Stats couldn't list {}: {}", saves, ex.toString());
		}
		worlds.sort(Comparator.comparingLong(World::playTicks).reversed());
		return new Totals(custom, mined, killed, killedBy, placed, crafted, broken.values().stream().mapToLong(Long::longValue).sum(), adv.size(), worlds);
	}

	private static Path first(Path a, Path b) {
		return Files.exists(a) ? a : Files.exists(b) ? b : null;
	}

	private static long get(JsonObject o, String key) {
		return o != null && o.has("minecraft:" + key) ? o.get("minecraft:" + key).getAsLong() : 0;
	}

	private static long sum(JsonObject o) {
		long n = 0;
		if (o != null) {
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				n += e.getValue().getAsLong();
			}
		}
		return n;
	}

	private static long sum(Map<String, Long> m) {
		return m.values().stream().mapToLong(Long::longValue).sum();
	}

	private static void add(Map<String, Long> into, JsonObject o) {
		if (o != null) {
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				into.merge(e.getKey(), e.getValue().getAsLong(), Long::sum);
			}
		}
	}

	// ---------------------------------------------------------------- widgets

	private String name() {
		return minecraft.getUser().getName();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	@Override
	protected void init() {
		if (loading == null) {
			Path saves = minecraft.gameDirectory.toPath().resolve("saves");
			String uuid = minecraft.getUser().getProfileId().toString();
			loading = CompletableFuture.supplyAsync(() -> read(saves, uuid));
		}
		loading.thenAccept(t -> minecraft.execute(() -> totals = t));
		super.init();
	}

	@Override
	void pageWidgets(int page) {
		int y = contentTop();
		int x = PAD;
		if (page == 3) {
			// the sites: one click each
			String n = enc(name());
			String[][] sites = {{"Open on Plancke", "https://plancke.io/hypixel/player/stats/" + n}, {"Open on 25Karma", "https://25karma.xyz/player/" + n},
					{"Open on NameMC", "https://namemc.com/profile/" + n}};
			int bw = (width - 2 * PAD - 12) / 3;
			for (int i = 0; i < sites.length; i++) {
				String url = sites[i][1];
				addRenderableWidget(Button.builder(Component.literal(sites[i][0]), b -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(url)))
						.bounds(x + i * (bw + 6), y + 22, bw, 20).build());
			}
			if (HypixelStats.key() == null) {
				// show them here: two steps, no typing
				int sy = y + 98;
				addRenderableWidget(Button.builder(Component.literal("1.  Get a free key"), b -> ConfirmLinkScreen.confirmLinkNow(this, URI.create("https://developer.hypixel.net/")))
						.bounds(x, sy, 150, 20).build());
				addRenderableWidget(Button.builder(Component.literal("2.  Paste it from clipboard"), b -> {
					String k = minecraft.keyboardHandler.getClipboard().trim();
					if (!HypixelStats.looksLikeKey(k)) {
						hypixelNote = "Your clipboard doesn't hold a Hypixel key. On developer.hypixel.net, press Copy next to your key, then try again.";
						return;
					}
					HypixelStats.setKey(k);
					hypixelNote = "";
					hypixel = null;
					rebuildWidgets();
				}).bounds(x + 156, sy, 180, 20).build());
			} else {
				addRenderableWidget(Button.builder(Component.literal("Forget key"), b -> {
					HypixelStats.setKey(null);
					hypixel = null;
					rebuildWidgets();
				}).bounds(width - PAD - 80, y + 64, 80, 16).build());
				var f = HypixelStats.get(minecraft.getUser().getProfileId());
				if (f != null) {
					f.thenAccept(r -> minecraft.execute(() -> hypixel = r));
				}
			}
		} else if (page == 4) {
			gamertag = new EditBox(font, x, y + 40, 200, 20, Component.literal("Xbox gamertag"));
			gamertag.setHint(Component.literal("Your Xbox gamertag"));
			gamertag.setMaxLength(16);
			gamertag.setValue(gamertagText);
			gamertag.setResponder(v -> gamertagText = v);
			addRenderableWidget(gamertag);
			addRenderableWidget(Button.builder(Component.literal("Open my Xbox profile"), b -> {
				String tag = gamertag.getValue().trim();
				if (!tag.isEmpty()) {
					ConfirmLinkScreen.confirmLinkNow(this, URI.create("https://account.xbox.com/en-us/profile?gamertag=" + enc(tag)));
				}
			}).bounds(x + 206, y + 40, 140, 20).build());
		}
	}

	@Override
	public void removed() {
		loading = null; // next time: read the worlds again
	}

	// ---------------------------------------------------------------- header

	@Override
	void header(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		// the face, big
		PlayerSkin skin = skin();
		g.fill(PAD - 1, 9, PAD + 41, 51, 0x40FFFFFF);
		net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, skin, PAD, 10, 40);
		int nx = PAD + 50;
		big(g, font.plainSubstrByWidth(name(), (width - nx - PAD) / 2), nx, 12, 0xFFFFFFFF, 2f);
		Totals t = totals;
		String sub = "Java Edition" + (t == null ? "" : "   ·   " + t.worlds().size() + (t.worlds().size() == 1 ? " world" : " worlds") + " on this computer"
				+ "   ·   " + time(t.c("play_time")) + " played");
		g.text(font, sub, nx, 36, SOFT, false);
	}

	private PlayerSkin skin() {
		return minecraft.getSkinManager().createLookup(minecraft.getGameProfile(), false).get();
	}

	// ---------------------------------------------------------------- pages

	@Override
	int page(GuiGraphicsExtractor g, int page, int x, int y, int w, int mouseX, int mouseY) {
		if (page == 3) {
			return hypixelPage(g, x, y, w, mouseX, mouseY);
		}
		if (page == 4) {
			return bedrockPage(g, x, y, w);
		}
		Totals t = totals;
		if (t == null) {
			g.centeredText(font, Component.literal("Reading your worlds..."), width / 2, y + 30, SOFT);
			return y + 50;
		}
		if (t.worlds().isEmpty()) {
			g.centeredText(font, Component.literal("No stats yet: play a singleplayer world and they'll show up here."), width / 2, y + 30, SOFT);
			return y + 50;
		}
		return switch (page) {
			case 0 -> overview(g, t, x, y, w, mouseX, mouseY);
			case 1 -> lifetime(g, t, x, y, w, mouseX, mouseY);
			default -> worlds(g, t, x, y, w, mouseX, mouseY);
		};
	}

	private int overview(GuiGraphicsExtractor g, Totals t, int x, int y, int w, int mouseX, int mouseY) {
		// the skin, full length, beside the cards
		int pw = 92;
		int ph = 150;
		CsUi.card(g, x, y, pw, ph, 0f, 0xFFFFFF, false);
		flatSkin(g, x + pw / 2, y + 10, 4);
		int cx = x + pw + 10;
		int cw = (x + w - cx - 2 * 6) / 3;
		long deaths = t.c("deaths");
		long kills = t.c("mob_kills");
		Object[][] hero = {
				{"PLAY TIME", time(t.c("play_time")), ACCENT, "in " + t.worlds().size() + " worlds", "Time spent in your worlds (not counting the pause menu)."},
				{"MOB KILLS", num(kills), TEXT, String.format(Locale.ROOT, "%.1f per death", deaths == 0 ? (double) kills : kills / (double) deaths),
						"Every mob you've killed, in every world."},
				{"DEATHS", num(deaths), BAD, deaths == 0 ? "never died!" : "in all worlds", "How many times you've died, in every world."},
				{"ADVANCEMENTS", num(t.advancements()), 0xFF7ED37E, "different ones unlocked", "Advancements you've unlocked, each counted once across all worlds."},
				{"DISTANCE", km(t.distanceCm()), TEXT, "walked, swum, flown...", "Every way you've moved, added up. The Lifetime page splits it up."},
				{"BLOCKS MINED", num(sum(t.mined())), TEXT, num(sum(t.placed())) + " placed", "Blocks you've broken and placed."}};
		for (int i = 0; i < hero.length; i++) {
			int hx = cx + (i % 3) * (cw + 6);
			int hy = y + (i / 3) * 50;
			stat(g, hx, hy, cw, 44, (String) hero[i][0], (String) hero[i][1], (int) hero[i][2], (String) hero[i][3], (String) hero[i][4], mouseX, mouseY);
		}
		y += Math.max(ph, 94) + 12;
		section(g, "FAVOURITES", x, y, w);
		y += 14;
		Map.Entry<String, Long> mob = top(t.killed());
		Map.Entry<String, Long> block = top(t.mined());
		Map.Entry<String, Long> built = top(t.placed());
		Map.Entry<String, Long> craft = top(t.crafted());
		Map.Entry<String, Long> nemesis = top(t.killedBy());
		World fav = t.worlds().get(0);
		Object[][] favs = {
				{"MOST HUNTED", mob, egg(mob), "killed", "The mob you've killed the most."},
				{"MOST MINED", block, item(block), "mined", "The block you've broken the most."},
				{"MOST PLACED", built, item(built), "placed", "The block you've placed the most."},
				{"MOST CRAFTED", craft, item(craft), "crafted", "The item you've crafted the most."},
				{"NEMESIS", nemesis, egg(nemesis), "deaths", "What has killed you the most."}};
		int fw = (w - 4 * 6) / 5;
		for (int i = 0; i < favs.length; i++) {
			int fx = x + i * (fw + 6);
			@SuppressWarnings("unchecked")
			Map.Entry<String, Long> e = (Map.Entry<String, Long>) favs[i][1];
			boolean on = over(mouseX, mouseY, fx, y, fw, 48);
			CsUi.card(g, fx, y, fw, 48, CsUi.hover("fav" + i, on), 0xDCB45A, false);
			g.text(font, (String) favs[i][0], fx + 7, y + 6, DIM, false);
			if (e == null) {
				g.text(font, "-", fx + 7, y + 20, TEXT, true);
				continue;
			}
			icon(g, (Identifier) favs[i][2], fx + fw - 22, y + 4);
			g.text(font, font.plainSubstrByWidth(pretty(e.getKey()), fw - 14), fx + 7, y + 20, 0xFFFFFFFF, true);
			g.text(font, num(e.getValue()) + " " + favs[i][3], fx + 7, y + 33, SOFT, false);
			if (on) {
				hint((String) favs[i][4]);
			}
		}
		y += 56;
		boolean on = over(mouseX, mouseY, x, y, w, 26);
		CsUi.card(g, x, y, w, 26, CsUi.hover("favworld", on), 0xDCB45A, false);
		g.text(font, "FAVOURITE WORLD", x + 7, y + 9, DIM, false);
		g.text(font, fav.name(), x + 110, y + 9, 0xFFFFFFFF, true);
		String ft = time(fav.playTicks()) + " played   " + fav.deaths() + (fav.deaths() == 1 ? " death" : " deaths");
		g.text(font, ft, x + w - 8 - font.width(ft), y + 9, SOFT, false);
		if (on) {
			hint("The world you've spent the most time in. The Worlds page lists them all.");
		}
		return y + 34;
	}

	private int lifetime(GuiGraphicsExtractor g, Totals t, int x, int y, int w, int mouseX, int mouseY) {
		int half = (w - 16) / 2;
		// left: combat and building; right: travel
		Object[][] combat = {
				{"Mob kills", num(t.c("mob_kills"))}, {"Player kills", num(t.c("player_kills"))}, {"Deaths", num(t.c("deaths"))},
				{"Damage dealt", hearts(t.c("damage_dealt"))}, {"Damage taken", hearts(t.c("damage_taken"))},
				{"Damage blocked by shield", hearts(t.c("damage_blocked_by_shield"))}, {"Raids won", num(t.c("raid_win"))}};
		Object[][] building = {
				{"Blocks mined", num(sum(t.mined()))}, {"Blocks placed", num(sum(t.placed()))}, {"Items crafted", num(sum(t.crafted()))},
				{"Items enchanted", num(t.c("enchant_item"))}, {"Tools worn out", num(t.broken())}};
		Object[][] life = {
				{"Jumps", num(t.c("jump"))}, {"Nights slept", num(t.c("sleep_in_bed"))}, {"Fish caught", num(t.c("fish_caught"))},
				{"Animals bred", num(t.c("animals_bred"))}, {"Villager trades", num(t.c("traded_with_villager"))},
				{"Chests opened", num(t.c("open_chest"))}, {"Cake slices eaten", num(t.c("eat_cake_slice"))}, {"Items dropped", num(t.c("drop"))}};
		int ly = list(g, "COMBAT", combat, x, y, half, mouseX, mouseY);
		ly = list(g, "BUILDING", building, x, ly + 8, half, mouseX, mouseY);
		ly = list(g, "LIFE", life, x, ly + 8, half, mouseX, mouseY);
		int rx = x + half + 16;
		int ry = travel(g, t, rx, y, half, mouseX, mouseY);
		ry = topList(g, "TOP MOBS KILLED", t.killed(), true, rx, ry + 8, half, mouseX, mouseY);
		ry = topList(g, "TOP BLOCKS MINED", t.mined(), false, rx, ry + 8, half, mouseX, mouseY);
		ry = topList(g, "KILLED MOST BY", t.killedBy(), true, rx, ry + 8, half, mouseX, mouseY);
		return Math.max(ly, ry);
	}

	private int list(GuiGraphicsExtractor g, String title, Object[][] rows, int x, int y, int w, int mouseX, int mouseY) {
		section(g, title, x, y, w);
		y += 14;
		for (int i = 0; i < rows.length; i++) {
			if (i % 2 == 0) {
				g.fill(x - 2, y - 2, x + w + 2, y + 9, 0x14FFFFFF);
			}
			g.text(font, (String) rows[i][0], x, y, SOFT, false);
			String v = (String) rows[i][1];
			g.text(font, v, x + w - font.width(v), y, TEXT, false);
			y += 11;
		}
		return y;
	}

	private static final String[][] TRAVEL = {
			{"walk_one_cm", "Walking"}, {"sprint_one_cm", "Sprinting"}, {"crouch_one_cm", "Sneaking"}, {"swim_one_cm", "Swimming"},
			{"walk_under_water_one_cm", "Walking under water"}, {"walk_on_water_one_cm", "Walking on water"}, {"climb_one_cm", "Climbing"},
			{"fall_one_cm", "Falling"}, {"fly_one_cm", "Flying"}, {"aviate_one_cm", "Elytra"}, {"boat_one_cm", "Boat"}, {"horse_one_cm", "Horse"},
			{"minecart_one_cm", "Minecart"}, {"pig_one_cm", "Pig"}, {"strider_one_cm", "Strider"}, {"happy_ghast_one_cm", "Happy Ghast"}};

	private int travel(GuiGraphicsExtractor g, Totals t, int x, int y, int w, int mouseX, int mouseY) {
		section(g, "TRAVEL  ·  " + km(t.distanceCm()), x, y, w);
		y += 14;
		long max = 1;
		for (String[] m : TRAVEL) {
			max = Math.max(max, t.c(m[0]));
		}
		for (String[] m : TRAVEL) {
			long cm = t.c(m[0]);
			if (cm <= 0) {
				continue;
			}
			g.text(font, m[1], x, y, SOFT, false);
			String v = km(cm);
			g.text(font, v, x + w - font.width(v), y, TEXT, false);
			int mx = x + 110;
			meter(g, mx, y + 2, Math.max(10, x + w - font.width(v) - 6 - mx), 5, cm / (double) max, 0xFF5E98D9);
			y += 11;
		}
		return y;
	}

	private int topList(GuiGraphicsExtractor g, String title, Map<String, Long> m, boolean mobs, int x, int y, int w, int mouseX, int mouseY) {
		section(g, title, x, y, w);
		y += 14;
		List<Map.Entry<String, Long>> top = new ArrayList<>(m.entrySet());
		top.sort(Map.Entry.<String, Long>comparingByValue().reversed());
		if (top.isEmpty()) {
			g.text(font, "Nothing yet.", x, y, DIM, false);
			return y + 11;
		}
		long max = top.get(0).getValue();
		for (int i = 0; i < Math.min(5, top.size()); i++) {
			var e = top.get(i);
			icon(g, mobs ? egg(e) : item(e), x, y - 1);
			g.text(font, pretty(e.getKey()), x + 20, y + 3, TEXT, false);
			String v = num(e.getValue());
			g.text(font, v, x + w - font.width(v), y + 3, SOFT, false);
			int mx = x + 120;
			meter(g, mx, y + 5, Math.max(10, x + w - font.width(v) - 6 - mx), 4, e.getValue() / (double) max, 0xFFDCB45A);
			y += 18;
		}
		return y;
	}

	private int worlds(GuiGraphicsExtractor g, Totals t, int x, int y, int w, int mouseX, int mouseY) {
		long most = Math.max(1, t.worlds().get(0).playTicks());
		g.text(font, "WORLD", x + 8, y, DIM, false);
		int cTime = x + w - 200;
		g.text(font, "PLAYED", cTime, y, DIM, false);
		g.text(font, "DEATHS", x + w - 140, y, DIM, false);
		g.text(font, "MOB KILLS", x + w - 90, y, DIM, false);
		y += 12;
		for (int i = 0; i < t.worlds().size(); i++) {
			World wd = t.worlds().get(i);
			int h = 20;
			boolean on = over(mouseX, mouseY, x, y, w, h);
			CsUi.card(g, x, y, w, h, CsUi.hover("world" + wd.name(), on), 0xDCB45A, false);
			int barW = (int) ((cTime - x - 170) * (wd.playTicks() / (double) most));
			g.text(font, (i + 1) + ".", x + 6, y + 6, DIM, false);
			g.text(font, font.plainSubstrByWidth(wd.name(), 140), x + 24, y + 6, 0xFFFFFFFF, false);
			g.fillGradient(x + 168, y + 7, x + 168 + Math.max(1, barW), y + 13, 0xE0DCB45A, 0x80DCB45A);
			g.text(font, time(wd.playTicks()), cTime, y + 6, ACCENT, false);
			g.text(font, num(wd.deaths()), x + w - 140, y + 6, wd.deaths() > 0 ? BAD : SOFT, false);
			g.text(font, num(wd.mobKills()), x + w - 90, y + 6, SOFT, false);
			if (on) {
				hint(wd.name() + ": " + time(wd.playTicks()) + " played, " + num(wd.mined()) + " blocks mined, " + num(wd.mobKills()) + " mobs killed.");
			}
			y += h + 3;
		}
		return y;
	}

	private int hypixelPage(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		section(g, "SEE YOUR HYPIXEL STATS", x, y, w);
		g.text(font, "Opens your page in your web browser.", x, y + 46, DIM, false);
		y += 62;
		if (HypixelStats.key() == null) {
			section(g, "OR SHOW THEM RIGHT HERE (optional)", x, y, w);
			g.text(font, "Hypixel only shares stats with a key. It's free and takes a minute: sign in at developer.hypixel.net, press Copy,", x, y + 14, SOFT, false);
			g.text(font, "then press Paste. The key stays on this computer and is only ever sent to Hypixel.", x, y + 24, SOFT, false);
			if (!hypixelNote.isEmpty()) {
				g.text(font, hypixelNote, x, y + 64, BAD, false);
			}
			return y + 80;
		}
		HypixelStats.Result r = hypixel;
		com.google.gson.JsonObject p = r == null ? null : r.player();
		String[] rank = p == null ? null : HypixelStats.rank(p);
		section(g, "HYPIXEL", x, y, w - 90);
		if (rank != null && !rank[0].isEmpty()) {
			String tag = "[" + rank[0] + "]";
			g.text(font, tag, x + font.width("HYPIXEL") + 10, y, 0xFF000000 | Integer.parseInt(rank[1], 16), true);
		}
		y += 22;
		if (r == null) {
			g.text(font, "Asking Hypixel...", x, y, DIM, false);
			return y + 14;
		}
		if (p == null) {
			g.text(font, r.error(), x, y, BAD, false);
			return y + 14;
		}
		JsonObject bw = HypixelStats.obj(p, "stats", "Bedwars");
		JsonObject sw = HypixelStats.obj(p, "stats", "SkyWars");
		JsonObject du = HypixelStats.obj(p, "stats", "Duels");
		int cw = (w - 2 * 6) / 3;
		stat(g, x, y, cw, 48, "NETWORK LEVEL", String.format(Locale.ROOT, "%.1f", HypixelStats.networkLevel(p)), ACCENT,
				num((long) HypixelStats.num(p, "karma")) + " karma", "Your Hypixel Network level, from all the experience you've earned on the server.", mouseX, mouseY);
		stat(g, x + cw + 6, y, cw, 48, "ACHIEVEMENT POINTS", num((long) HypixelStats.num(p, "achievementPoints")), TEXT, "",
				"Points from Hypixel's achievements, across every game.", mouseX, mouseY);
		stat(g, x + 2 * (cw + 6), y, cw, 48, "BED WARS", HypixelStats.bedWarsLevel((long) HypixelStats.num(bw, "Experience")) + "✫", 0xFFFFAA00,
				num((long) HypixelStats.num(bw, "wins_bedwars")) + " wins   FKDR " + HypixelStats.ratio(HypixelStats.num(bw, "final_kills_bedwars"),
						HypixelStats.num(bw, "final_deaths_bedwars")), "Bed Wars star level, wins, and final kills per final death (FKDR).", mouseX, mouseY);
		y += 54;
		stat(g, x, y, cw, 48, "SKYWARS", HypixelStats.skyWarsLevel(sw), 0xFF55FFFF,
				num((long) HypixelStats.num(sw, "wins")) + " wins   KDR " + HypixelStats.ratio(HypixelStats.num(sw, "kills"), HypixelStats.num(sw, "deaths")),
				"SkyWars level, wins, and kills per death (KDR).", mouseX, mouseY);
		stat(g, x + cw + 6, y, cw, 48, "DUELS", num((long) HypixelStats.num(du, "wins")) + " wins", TEXT,
				"WLR " + HypixelStats.ratio(HypixelStats.num(du, "wins"), HypixelStats.num(du, "losses")), "Duels wins, and wins per loss (WLR).", mouseX, mouseY);
		stat(g, x + 2 * (cw + 6), y, cw, 48, "BED WARS GAMES", num((long) HypixelStats.num(bw, "games_played_bedwars")), TEXT,
				num((long) HypixelStats.num(bw, "beds_broken_bedwars")) + " beds broken", "Bed Wars games played, and beds you've broken.", mouseX, mouseY);
		return y + 56;
	}

	private int bedrockPage(GuiGraphicsExtractor g, int x, int y, int w) {
		section(g, "BEDROCK EDITION", x, y, w);
		g.text(font, "Bedrock keeps your stats and achievements on your Xbox profile. Type your gamertag to open it.", x, y + 14, SOFT, false);
		g.text(font, "Bedrock stats can't be shown here: reading them would mean signing in to Xbox, and Counter-Craft 2 never does that.", x, y + 72, DIM, false);
		return y + 90;
	}

	// ---------------------------------------------------------------- pieces

	/** Your skin, flat from the front (base layer, then the outer layer), centred on cx; s px per skin pixel. */
	private void flatSkin(GuiGraphicsExtractor g, int cx, int y, int s) {
		PlayerSkin skin = skin();
		Identifier tex = skin.body().texturePath();
		int arm = skin.model() == PlayerModelType.SLIM ? 3 : 4;
		int x = cx - 4 * s;
		// {u, v, w, h, dx, dy} for each part: base layer then overlay
		int[][] parts = {
				{8, 8, 8, 8, 0, 0}, {20, 20, 8, 12, 0, 8}, {44, 20, arm, 12, -arm, 8}, {36, 52, arm, 12, 8, 8}, {4, 20, 4, 12, 0, 20}, {20, 52, 4, 12, 4, 20},
				{40, 8, 8, 8, 0, 0}, {20, 36, 8, 12, 0, 8}, {44, 36, arm, 12, -arm, 8}, {52, 52, arm, 12, 8, 8}, {4, 36, 4, 12, 0, 20}, {4, 52, 4, 12, 4, 20}};
		for (int[] p : parts) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + p[4] * s, y + p[5] * s, p[0], p[1], p[2] * s, p[3] * s, p[2], p[3], 64, 64);
		}
	}

	private static Map.Entry<String, Long> top(Map<String, Long> m) {
		return m.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
	}

	/** A mob's spawn egg item id (a skull for anything without one). */
	private static Identifier egg(Map.Entry<String, Long> e) {
		Identifier id = e == null ? null : Identifier.tryParse(e.getKey() + "_spawn_egg");
		return id != null && BuiltInRegistries.ITEM.containsKey(id) ? id : Identifier.withDefaultNamespace("skeleton_skull");
	}

	private static Identifier item(Map.Entry<String, Long> e) {
		return e == null ? null : Identifier.tryParse(e.getKey());
	}

	/**
	 * An item's icon. Item stacks only exist once a world has bound the item components (in the pause menu); on the
	 * title screen the item's (or block's) own texture is drawn instead, when there is one.
	 */
	private void icon(GuiGraphicsExtractor g, Identifier id, int x, int y) {
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
			return;
		}
		Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item.builtInRegistryHolder().areComponentsBound()) {
			g.item(new ItemStack(item), x, y);
			return;
		}
		var rm = minecraft.getResourceManager();
		for (String dir : new String[] {"textures/item/", "textures/block/"}) {
			Identifier tex = id.withPath(dir + id.getPath() + ".png");
			if (rm.getResource(tex).isPresent()) {
				g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, 16, 16, 16, 16, 16, 16);
				return;
			}
		}
	}

	/** "minecraft:deepslate_iron_ore" -> "Deepslate Iron Ore". */
	private static String pretty(String id) {
		String path = id.substring(id.indexOf(':') + 1);
		StringBuilder b = new StringBuilder();
		for (String w : path.split("_")) {
			if (!w.isEmpty()) {
				b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
			}
		}
		return b.toString();
	}

	private static String num(long v) {
		return String.format(Locale.ROOT, "%,d", v);
	}

	/** Ticks -> "3d 4h", "5h 12m" or "12m". */
	private static String time(long ticks) {
		long m = ticks / 20 / 60;
		long h = m / 60;
		long d = h / 24;
		return d > 0 ? d + "d " + h % 24 + "h" : h > 0 ? h + "h " + m % 60 + "m" : m + "m";
	}

	private static String km(long cm) {
		return cm >= 100_000 ? String.format(Locale.ROOT, "%.1f km", cm / 100_000.0) : String.format(Locale.ROOT, "%,d m", cm / 100);
	}

	/** Minecraft keeps damage in tenths of a health point: shown in hearts. */
	private static String hearts(long tenths) {
		return String.format(Locale.ROOT, "%,.0f ❤", tenths / 20.0);
	}
}
