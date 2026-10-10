package dev.countercraft.client.steam;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.skins.Agents;
import dev.countercraft.team.Teams;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * One Counter-Craft run's deep dive, in the same frame as My CS2 Stats ({@link StatsScreen}): the team's emblem, the
 * world and how far it got in the header; Summary (how it ended, the agent worn, the headline numbers), Combat and World
 * pages; the footer explains what the mouse is over. The record can be deleted here (the world isn't touched). Built
 * from the copy kept in {@link RunHistory}, so it works after the world is gone.
 */
public final class RunScreen extends StatsScreen {
	private static final Identifier T_LOGO = CounterCraft.id("textures/gui/team/t_logo.png");
	private static final Identifier CT_LOGO = CounterCraft.id("textures/gui/team/ct_logo.png");
	private static final String[][] BOSSES = {
			{"minecraft:ender_dragon", "Ender Dragon"}, {"minecraft:wither", "Wither"}, {"minecraft:elder_guardian", "Elder Guardian"},
			{"minecraft:warden", "Warden"}};
	private static final String[] TABS = {"Summary", "Combat", "World"};
	private static final String[] TAB_HELP = {
			"How the run ended, the agent worn in it and the headline numbers.",
			"Fights: kills, damage taken and given, and CS2-style feats.",
			"Money, bosses, building, crafting and travel."};
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT);
	private static final String NO_STATS = "No stats yet: they arrive a few seconds after you join the world (this run began before the stats were recorded).";

	private final RunHistory.Run run;

	public RunScreen(Screen parent, RunHistory.Run run) {
		super(Component.literal("Counter-Craft run"), parent);
		this.run = run;
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
		return "A Counter-Craft hardcore run, recorded on this computer.";
	}

	@Override
	void pageWidgets(int page) {
		addRenderableWidget(Button.builder(Component.literal("Delete record"), b -> confirmDelete(minecraft, this, parent, run))
				.bounds(width - PAD - 80 - 6 - 96, height - 24, 96, 18).build());
	}

	/** "Delete this run's record?" then back to {@code after} (the world isn't touched). */
	static void confirmDelete(net.minecraft.client.Minecraft mc, Screen back, Screen after, RunHistory.Run run) {
		mc.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
			if (yes) {
				RunHistory.delete(run);
				mc.gui.setScreen(after);
			} else {
				mc.gui.setScreen(back);
			}
		}, Component.literal("Delete the record of \"" + (run.world == null ? "?" : run.world) + "\"?"),
				Component.literal("Its stats and killer picture are removed from My CS2 Stats for good. The world itself isn't deleted."),
				Component.literal("Delete record"), Component.literal("Cancel")));
	}

	/** Killer pictures loaded as textures, by file. */
	private static final Map<java.nio.file.Path, Identifier> PICTURES = new java.util.HashMap<>();

	/** Forget a loaded picture (it was replaced or deleted). */
	static void forget(java.nio.file.Path file) {
		Identifier id = PICTURES.remove(file);
		if (id != null) {
			net.minecraft.client.Minecraft.getInstance().getTextureManager().release(id);
		}
	}

	/** The run's killer picture as a texture (null if there isn't one), with its pixel size in {@code size}. */
	private Identifier picture(int[] size) {
		java.nio.file.Path file = KillerPip.file(run);
		Identifier id = PICTURES.get(file);
		if (id == null && java.nio.file.Files.exists(file)) {
			try (var in = java.nio.file.Files.newInputStream(file)) {
				com.mojang.blaze3d.platform.NativeImage img = com.mojang.blaze3d.platform.NativeImage.read(in);
				id = CounterCraft.id("dynamic/killer_" + run.started);
				minecraft.getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "killer picture", img));
				PICTURES.put(file, id);
			} catch (Exception e) {
				return null;
			}
		}
		if (id != null && minecraft.getTextureManager().getTexture(id) instanceof net.minecraft.client.renderer.texture.DynamicTexture dt && dt.getPixels() != null) {
			size[0] = dt.getPixels().getWidth();
			size[1] = dt.getPixels().getHeight();
		}
		return id;
	}

	// ---------------------------------------------------------------- header

	private boolean ct() {
		return Teams.CT.equals(run.team);
	}

	private int teamColor() {
		return 0xFF000000 | (ct() ? Teams.CT_COLOR : Teams.T_COLOR);
	}

	@Override
	void header(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		CsUi.image(g, ct() ? CT_LOGO : T_LOGO, PAD, 10, 40, 40, 256, 256, 0xFFFFFFFF);
		int nx = PAD + 50;
		String days = run.days + (run.days == 1 ? " DAY" : " DAYS");
		int daysW = Math.round(font.width(days) * 2f);
		String world = font.plainSubstrByWidth(run.world == null ? "?" : run.world, (width - 2 * PAD - 50 - daysW - 20) / 2);
		big(g, world, nx, 12, 0xFFFFFFFF, 2f);
		ZoneId zone = ZoneId.systemDefault();
		g.text(font, (ct() ? "Counter-Terrorist" : "Terrorist") + "   ·   started " + DATE.format(Instant.ofEpochMilli(run.started).atZone(zone))
				+ "   ·   last played " + DATE.format(Instant.ofEpochMilli(run.lastPlayed).atZone(zone)), nx, 36, SOFT, false);
		int cx = nx + chip(g, run.finished ? "ENDED" : "ONGOING", nx, 47, run.finished ? BAD : GOOD) + 4;
		chip(g, ct() ? "CT" : "T", cx, 47, teamColor());
		big(g, days, width - PAD - daysW, 18, ACCENT, 2f);
		if (mouseY < HEADER && mouseX >= width - PAD - daysW) {
			hint("Days survived: the Minecraft days this run lasted.");
		}
	}

	// ---------------------------------------------------------------- pages

	@Override
	int page(GuiGraphicsExtractor g, int page, int x, int y, int w, int mouseX, int mouseY) {
		if (page == 0) {
			return summary(g, x, y, w, mouseX, mouseY);
		}
		List<Section> all = sections();
		if (all.isEmpty()) {
			g.text(font, font.plainSubstrByWidth(NO_STATS, w), x, y, SOFT, false);
			return y + 12;
		}
		// two columns: Combat = combat and Counter-Strike; World = economy, bosses, Minecraft, travel
		List<Section> shown = page == 1 ? all.subList(0, 2) : all.subList(2, all.size());
		int cols = w >= 460 ? 2 : 1;
		int cw = (w - (cols - 1) * 16) / cols;
		int[] colY = new int[cols];
		java.util.Arrays.fill(colY, y);
		for (Section sct : shown) {
			int c = cols == 1 || colY[0] <= colY[1] ? 0 : 1;
			colY[c] = rows(g, sct, x + c * (cw + 16), colY[c], cw, mouseX, mouseY) + 12;
		}
		return cols > 1 ? Math.max(colY[0], colY[1]) : colY[0];
	}

	/** How it ended (or that it's still going), the agent worn, then the headline numbers as cards. */
	private int summary(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		int agentW = Math.min(150, w / 4);
		int left = w - agentW - 8;
		int h = 112;
		if (run.finished) {
			ending(g, x, y, left, h, mouseX, mouseY);
		} else {
			CsUi.card(g, x, y, left, h, 0f, GOOD & 0xFFFFFF, false);
			g.fill(x, y, x + 3, y + h, GOOD);
			section(g, "STILL GOING", x + 10, y + 8, left - 20);
			g.text(font, font.plainSubstrByWidth("This run hasn't ended: its stats keep counting while you play the world.", left - 20), x + 10, y + 26, SOFT,
					false);
		}
		agent(g, x + left + 8, y, agentW, h);
		y += h + 10;
		if (run.counts == null || run.counts.isEmpty()) {
			g.text(font, font.plainSubstrByWidth(NO_STATS, w), x, y, SOFT, false);
			return y + 12;
		}
		long kills = run.count("kills");
		long shots = run.count("shots");
		int n = w >= 600 ? 6 : 3;
		int cw = (w - (n - 1) * 6) / n;
		String[][] cards = {
				{"MOBS KILLED", num(run.count("mob_kills")), "", "Every mob this run killed, by any means."},
				{"PLAYERS KILLED", num(run.count("player_kills")), "", "Other players (and bots) this run killed."},
				{"HEADSHOTS", kills == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * run.count("headshots") / kills),
						num(run.count("headshots")) + " kills", "Kills finished with a headshot, out of all kills."},
				{"ACCURACY", shots == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", Math.min(100.0, 100.0 * run.count("hits") / shots)),
						num(shots) + " shots", "Shots that hit something, out of every shot fired."},
				{"MONEY EARNED", money(run.count("money_earned")), money(run.count("money_spent")) + " spent", "CS2 cash earned (and spent) in this run."},
				{"TIME PLAYED", time(run.count("play_ticks")), "", "Time spent in the world while this run lasted."}};
		int[] colors = {TEXT, TEXT, ACCENT, TEXT, GOOD, TEXT};
		for (int i = 0; i < cards.length; i++) {
			int cx = x + (i % n) * (cw + 6);
			int cy = y + (i / n) * 50;
			stat(g, cx, cy, cw, 44, cards[i][0], cards[i][1], colors[i], cards[i][2], cards[i][3], mouseX, mouseY);
		}
		return y + ((cards.length + n - 1) / n) * 50;
	}

	/** The agent worn in this world: its picture and name on a card in the team's colour. */
	private void agent(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		CsUi.card(g, x, y, w, h, 0f, teamColor() & 0xFFFFFF, false);
		g.fill(x, y, x + w, y + 2, teamColor());
		Agents.Agent a = run.agent == null || run.agent.isEmpty() ? null : Agents.byModel(run.agent, run.team);
		int iw = w - 12;
		int ih = Math.round(iw * 0.75f);
		if (a != null && a.thumb()) {
			CsUi.image(g, a.thumbnail(), x + 6, y + 8, iw, ih, 192, 144, 0xFFFFFFFF);
		} else {
			CsUi.image(g, CounterCraft.id("textures/gui/cosmetics/" + (ct() ? "customplayer_ctm_sas" : "customplayer_tm_phoenix") + ".png"), x + 6, y + 8,
					iw, ih, 256, 192, 0xFFFFFFFF);
		}
		g.fillGradient(x, y + h - 34, x + w, y + h, 0x00101214, 0xF0101214); // the name reads over the agent's body
		g.text(font, "AGENT", x + 6, y + h - 24, DIM, false);
		g.text(font, font.plainSubstrByWidth(a != null ? a.name() : ct() ? "SAS" : "Phoenix", w - 12), x + 6, y + h - 13, TEXT, true);
	}

	/** One section of label / value rows, highlighted under the mouse. */
	private int rows(GuiGraphicsExtractor g, Section sct, int x, int y, int w, int mouseX, int mouseY) {
		section(g, sct.title, x, y, w);
		y += 14;
		for (String[] row : sct.rows) {
			boolean on = over(mouseX, mouseY, x - 2, y - 2, w + 4, 16);
			if (on) {
				g.fill(x - 2, y - 2, x + w + 2, y + 14, 0x18FFFFFF);
			}
			g.text(font, row[0], x, y + 2, on ? 0xFFFFFFFF : SOFT, false);
			int color = row.length > 2 ? (int) Long.parseLong(row[2], 16) : TEXT;
			String v = font.plainSubstrByWidth(row[1], w - font.width(row[0]) - 8);
			g.text(font, v, x + w - font.width(v), y + 1, color, true);
			y += 16;
		}
		return y;
	}

	/** How it ended: the killer's picture (or its spawn egg), the death message, the cause, the killer and the weapon. */
	private void ending(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
		CsUi.card(g, x, y, w, h, 0f, BAD & 0xFFFFFF, false);
		g.fill(x, y, x + 3, y + h, BAD);
		section(g, "HOW IT ENDED", x + 10, y + 8, w - 20);
		int px = x + 10;
		int py = y + 24;
		int[] size = {72, 96};
		Identifier pic = picture(size);
		boolean framed = pic != null || !run.killerType.isEmpty() && !run.killerPlayer;
		if (framed) {
			g.fill(px, py, px + 64, py + 80, 0x40000000);
			g.outline(px, py, 64, 80, 0x30FFFFFF);
		}
		if (pic != null) {
			float k = Math.min(62f / size[0], 78f / size[1]);
			int dw = Math.round(size[0] * k);
			int dh = Math.round(size[1] * k);
			g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, pic, px + (64 - dw) / 2, py + (80 - dh) / 2, 0f, 0f, dw, dh, size[0], size[1],
					size[0], size[1]);
		} else if (framed) {
			var egg = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(run.killerType + "_spawn_egg"));
			if (egg.isPresent()) {
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(px + 8, py + 16);
				pose.scale(3f, 3f);
				g.item(new net.minecraft.world.item.ItemStack(egg.get()), 0, 0);
				pose.popMatrix();
			}
		}
		int tx = framed ? px + 74 : px;
		int ty = py;
		int tw = x + w - tx - 10;
		String msg = run.deathMessage.isEmpty() ? "You died." : run.deathMessage;
		for (var line : font.split(Component.literal(msg), tw)) {
			g.text(font, line, tx, ty, 0xFFFFFFFF, true);
			ty += 11;
		}
		ty += 6;
		String cause = switch (run.deathCause) {
			case "killed" -> "Killed";
			case "self" -> "Your own doing";
			case "environment" -> "The world";
			default -> "-";
		};
		g.text(font, "Cause", tx, ty, DIM, false);
		g.text(font, cause, tx + 54, ty, TEXT, false);
		ty += 12;
		if (!run.killerName.isEmpty()) {
			g.text(font, "Killer", tx, ty, DIM, false);
			g.text(font, font.plainSubstrByWidth(run.killerName, tw - 54), tx + 54, ty, TEXT, false);
			ty += 12;
		}
		String weapon = !run.csWeapon.isEmpty() ? weaponName(run.csWeapon) : !run.weaponItem.isEmpty() ? weaponName("item:" + run.weaponItem) : "";
		if (!weapon.isEmpty()) {
			g.text(font, "Weapon", tx, ty, DIM, false);
			int wx = tx + 54;
			if (!run.weaponItem.isEmpty()) {
				var item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(run.weaponItem));
				if (item.isPresent()) {
					g.item(new net.minecraft.world.item.ItemStack(item.get()), wx, ty - 4);
					wx += 18;
				}
			}
			g.text(font, font.plainSubstrByWidth(weapon, tw - (wx - tx)), wx, ty, ACCENT, false);
		}
		if (over(mouseX, mouseY, x, y, w, h)) {
			hint(pic != null ? "What killed you, as it looked at that moment." : "How the run ended.");
		}
	}

	private record Section(String title, List<String[]> rows) {
	}

	private List<Section> sections() {
		List<Section> out = new ArrayList<>();
		if (run.counts == null || run.counts.isEmpty()) {
			return out;
		}
		long kills = run.count("kills");
		List<String[]> combat = new ArrayList<>();
		combat.add(row("Mobs killed", num(run.count("mob_kills"))));
		combat.add(row("Players killed", num(run.count("player_kills"))));
		String[] fav = top(run.weapons);
		combat.add(row("Favorite weapon", fav == null ? "-" : weaponName(fav[0]) + " (" + fav[1] + ")"));
		String[] mob = top(run.mobs);
		combat.add(row("Favorite mob to kill", mob == null ? "-" : mobName(mob[0]) + " (" + mob[1] + ")"));
		combat.add(row("Damage given", hp(run.count("damage_given"))));
		combat.add(row("Damage taken", hp(run.count("damage_taken"))));
		combat.add(row("Longest kill", String.format(Locale.ROOT, "%.1f blocks", run.count("longest_kill_cm") / 100.0)));
		combat.add(row("Shield blocked", hp(run.count("shield_blocked"))));
		out.add(new Section("COMBAT", combat));

		List<String[]> cs = new ArrayList<>();
		long shots = run.count("shots");
		cs.add(row("Shots fired", num(shots)));
		cs.add(row("Accuracy", shots == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", Math.min(100.0, 100.0 * run.count("hits") / shots))));
		cs.add(row("Headshot kills", num(run.count("headshots")) + (kills == 0 ? "" : String.format(Locale.ROOT, " (%.0f%%)", 100.0 * run.count("headshots") / kills))));
		cs.add(row("Wallbangs", num(run.count("wallbangs"))));
		cs.add(row("No-scopes", num(run.count("noscopes"))));
		cs.add(row("Through smoke", num(run.count("through_smoke"))));
		cs.add(row("While blind", num(run.count("blind_kills"))));
		cs.add(row("Airborne kills", num(run.count("airborne_kills"))));
		cs.add(row("Grenades thrown", num(run.count("grenades"))));
		out.add(new Section("COUNTER-STRIKE", cs));

		List<String[]> eco = new ArrayList<>();
		eco.add(row("Money earned", money(run.count("money_earned")), "FF7ED37E"));
		eco.add(row("Money spent", money(run.count("money_spent")), "FFE05050"));
		eco.add(row("Biggest day of spending", money(run.count("best_day_spend"))));
		out.add(new Section("ECONOMY", eco));

		List<String[]> boss = new ArrayList<>();
		for (String[] b : BOSSES) {
			int n = run.mobs == null ? 0 : run.mobs.getOrDefault(b[0], 0);
			boss.add(n > 0 ? row(b[1], "✔ " + (n > 1 ? "x" + n : "Defeated"), "FF7ED37E") : row(b[1], "-", "FF6E6E6E"));
		}
		long raids = run.count("raids_won");
		boss.add(raids > 0 ? row("Raid", "✔ " + (raids > 1 ? "x" + raids : "Won"), "FF7ED37E") : row("Raid", "-", "FF6E6E6E"));
		out.add(new Section("BOSS CHECKLIST", boss));

		List<String[]> world = new ArrayList<>();
		world.add(row("Blocks placed", num(run.count("blocks_placed"))));
		world.add(row("Blocks mined", num(run.count("blocks_mined"))));
		world.add(row("Items crafted", num(run.count("items_crafted"))));
		world.add(row("Items enchanted", num(run.count("items_enchanted"))));
		world.add(row("Chests opened", num(run.count("chests_opened"))));
		world.add(row("Villager trades", num(run.count("villager_trades"))));
		world.add(row("Animals bred", num(run.count("animals_bred"))));
		world.add(row("Fish caught", num(run.count("fish_caught"))));
		world.add(row("Cake slices eaten", num(run.count("cake_slices"))));
		world.add(row("Nights slept", num(run.count("nights_slept"))));
		out.add(new Section("MINECRAFT", world));

		List<String[]> travel = new ArrayList<>();
		travel.add(row("Time played", time(run.count("play_ticks"))));
		travel.add(row("Distance on foot", km(run.count("walked_cm"))));
		travel.add(row("Sprinted", km(run.count("sprinted_cm"))));
		travel.add(row("Flown (elytra)", km(run.count("elytra_cm"))));
		travel.add(row("Fallen", km(run.count("fallen_cm"))));
		travel.add(row("Jumps", num(run.count("jumps"))));
		out.add(new Section("TRAVEL", travel));
		return out;
	}

	private static String[] row(String label, String value) {
		return new String[] {label, value};
	}

	private static String[] row(String label, String value, String argb) {
		return new String[] {label, value, argb};
	}

	private static String num(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}

	private static String money(long n) {
		return String.format(Locale.ROOT, "$%,d", n);
	}

	/** Tenths of Minecraft health points, shown in CS2 HP (100 HP = 20 health). */
	private static String hp(long tenths) {
		return String.format(Locale.ROOT, "%,d HP", Math.round(tenths / 10.0 * 5));
	}

	private static String km(long cm) {
		return cm >= 100_000 ? String.format(Locale.ROOT, "%.2f km", cm / 100_000.0) : String.format(Locale.ROOT, "%,d m", cm / 100);
	}

	private static String time(long ticks) {
		long min = ticks / 20 / 60;
		return min >= 60 ? String.format(Locale.ROOT, "%dh %02dm", min / 60, min % 60) : min + "m";
	}

	private static String[] top(Map<String, Integer> m) {
		if (m == null) {
			return null;
		}
		String best = null;
		int n = 0;
		for (var e : m.entrySet()) {
			if (e.getValue() > n && !"minecraft:player".equals(e.getKey())) {
				best = e.getKey();
				n = e.getValue();
			}
		}
		return best == null ? null : new String[] {best, Integer.toString(n)};
	}

	private static String mobName(String id) {
		try {
			return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id)).getDescription().getString();
		} catch (Exception e) {
			return id;
		}
	}

	/** The kill feed's weapon id as a name: CS2 guns, knives and grenades, Minecraft items, or fists. */
	static String weaponName(String id) {
		if (id.equals("fists")) {
			return "Fists";
		}
		if (id.startsWith("item:")) {
			try {
				return BuiltInRegistries.ITEM.getValue(Identifier.parse(id.substring(5))).getDefaultInstance().getHoverName().getString();
			} catch (Exception e) {
				return id.substring(5);
			}
		}
		if (id.equals("inferno")) {
			return "Molotov fire";
		}
		for (String s = id; ; s = s.substring(0, s.lastIndexOf('_'))) {
			var gun = CounterCraft.GUNS.get(s);
			if (gun != null) {
				return gun.spec().name();
			}
			var nade = CounterCraft.GRENADES.get(s);
			if (nade != null) {
				return nade.def().name();
			}
			var knife = dev.countercraft.gun.Guns.MELEE.get(s);
			if (knife != null) {
				return knife.name();
			}
			if (!s.contains("_")) {
				break;
			}
		}
		return id;
	}
}
