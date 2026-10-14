package dev.countercraft.client.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.KillFeedPayload;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;

/**
 * CS2's kill feed (panorama huddeathnotice): top right, 72 px down and 10 px in (1080p reference px), one row per
 * death notice with CS2's icons in CS2's order, 5 s on screen (x1.5 when you are the killer or the victim) then a 1 s
 * fade. Rows #000000a0; your kills #000000e7 with a 2 px #e10000 border; your deaths #630606d2. Names in the Minecraft
 * font (players in CS2's T colour, as they are T agents; mobs in the CT colour). Minecraft-item kills show the item.
 */
public final class KillFeed {
	private static final float LIFETIME = 5.0f;
	private static final float LOCAL_MOD = 1.5f;
	private static final float FADE = 1.0f;
	private static final int MAX = 8;
	private static final int T_COLOR = 0xEABE54;
	private static final int CT_COLOR = 0x6F9CE6;
	/** Mobs and other non-players (and a player without a faction): white. */
	private static final int NEUTRAL_COLOR = 0xFFFFFF;

	/** A name's colour: a player's faction (T gold, CT blue), else white. */
	private static int nameColor(String team) {
		return dev.countercraft.team.Teams.CT.equals(team) ? CT_COLOR : dev.countercraft.team.Teams.T.equals(team) ? T_COLOR : NEUTRAL_COLOR;
	}
	private static final int ICON_H = 24;
	private static final float TEXT_PX = 16f;

	private record Notice(KillFeedPayload p, long born, boolean killer, boolean victim) {
	}

	private static final List<Notice> NOTICES = new ArrayList<>();
	private static final Map<String, int[]> ICONS = new HashMap<>();

	private KillFeed() {
	}

	public static void reload(ResourceManager rm) {
		ICONS.clear();
		try (var in = rm.open(CounterCraft.id("hud/killfeed.json"))) {
			JsonObject j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("icons").entrySet()) {
				var a = e.getValue().getAsJsonArray();
				ICONS.put(e.getKey(), new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
			}
		} catch (Exception e) {
			CounterCraft.LOG.info("[countercraft] no hud/killfeed.json ({}): run converter/convert.py", e.toString());
		}
	}

	public static void onKill(KillFeedPayload p) {
		Minecraft mc = Minecraft.getInstance();
		int me = mc.player == null ? -1 : mc.player.getId();
		NOTICES.add(new Notice(p, System.nanoTime(), p.attackerId() == me && p.victimId() != me, p.victimId() == me));
		while (NOTICES.size() > MAX) {
			NOTICES.remove(0);
		}
	}

	public static void clear() {
		NOTICES.clear();
	}

	public static void render(GuiGraphicsExtractor g) {
		if (NOTICES.isEmpty() || !dev.countercraft.client.options.CcOptions.killFeed()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		long now = System.nanoTime();
		NOTICES.removeIf(n -> (now - n.born()) / 1e9 > life(n) + FADE);
		double gs = mc.getWindow().getGuiScale();
		float k = (float) (mc.getWindow().getHeight() / 1080.0 / gs); // 1080p reference px -> GUI units
		int screenW = Math.round(g.guiWidth() / k);
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(k, k);
		int y = 72;
		for (Notice n : NOTICES) {
			double age = (now - n.born()) / 1e9;
			float alpha = (float) Math.max(0, Math.min(1, (life(n) + FADE - age) / FADE));
			y += 3 + row(g, mc.font, n, screenW - 10, y, alpha);
		}
		pose.popMatrix();
		HudSpace.mark(HudSpace.Panel.KILL_FEED, 72 * k, y * k);
	}

	private static float life(Notice n) {
		return n.killer() || n.victim() ? LIFETIME * LOCAL_MOD : LIFETIME;
	}

	/** One notice, right-aligned at {@code right}; returns its height. */
	private static int row(GuiGraphicsExtractor g, Font font, Notice n, int right, int top, float alpha) {
		KillFeedPayload p = n.p();
		float ts = TEXT_PX / font.lineHeight;
		List<Consumer<GuiGraphicsExtractor>> draws = new ArrayList<>();
		// CS2's order: [blind] attacker [in air] weapon [no scope] [smoke] [penetrate] [headshot] | [suicide] victim
		int[] x = {0};
		int h = 6 + ICON_H + 3;
		int cy = top + 6;
		boolean suicide = (p.flags() & KillFeedPayload.F_SUICIDE) != 0;
		if (!suicide) {
			if ((p.flags() & KillFeedPayload.F_BLIND) != 0) {
				icon(draws, x, "blind_kill", cy, alpha);
			}
			if (!p.attacker().isEmpty()) {
				label(draws, font, x, p.attacker(), nameColor(p.attackerTeam()), cy, ts, alpha);
			}
			if ((p.flags() & KillFeedPayload.F_INAIR) != 0) {
				icon(draws, x, "inairkill", cy, alpha);
			}
			weapon(draws, x, p.weapon(), cy, alpha);
			if ((p.flags() & KillFeedPayload.F_NOSCOPE) != 0) {
				icon(draws, x, "noscope", cy, alpha);
			}
			if ((p.flags() & KillFeedPayload.F_SMOKE) != 0) {
				icon(draws, x, "smoke_kill", cy, alpha);
			}
			if ((p.flags() & KillFeedPayload.F_PENETRATE) != 0) {
				icon(draws, x, "penetrate", cy, alpha);
			}
			if ((p.flags() & KillFeedPayload.F_HEADSHOT) != 0) {
				icon(draws, x, "icon_headshot", cy, alpha);
			}
		} else {
			icon(draws, x, "icon_suicide", cy, alpha);
		}
		label(draws, font, x, p.victim(), nameColor(p.victimTeam()), cy, ts, alpha);
		int w = x[0] + 20;
		int left = right - w;
		int bg = n.killer() ? 0xE7000000 : n.victim() ? 0xD2630606 : 0xA0000000;
		g.fill(left, top, right, top + h, scaleAlpha(bg, alpha));
		if (n.killer()) {
			int red = scaleAlpha(0xFFE10000, alpha);
			g.fill(left, top, right, top + 2, red);
			g.fill(left, top + h - 2, right, top + h, red);
			g.fill(left, top, left + 2, top + h, red);
			g.fill(right - 2, top, right, top + h, red);
		}
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(left + 10, 0);
		for (Consumer<GuiGraphicsExtractor> d : draws) {
			d.accept(g);
		}
		pose.popMatrix();
		return h;
	}

	private static int scaleAlpha(int argb, float a) {
		return (Math.round((argb >>> 24) * a) << 24) | (argb & 0xFFFFFF);
	}

	private static void label(List<Consumer<GuiGraphicsExtractor>> draws, Font font, int[] x, String text, int rgb, int cy, float ts, float alpha) {
		int x0 = x[0] + 2;
		x[0] += Math.round(font.width(text) * ts) + 4;
		draws.add(g -> {
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x0, cy + (ICON_H - font.lineHeight * ts) / 2f + 1);
			pose.scale(ts, ts);
			g.text(font, text, 0, 0, scaleAlpha(0xFF000000 | rgb, alpha), true);
			pose.popMatrix();
		});
	}

	private static void icon(List<Consumer<GuiGraphicsExtractor>> draws, int[] x, String name, int cy, float alpha) {
		int[] size = ICONS.get(name);
		if (size == null) {
			return;
		}
		int w = Math.round(size[0] * (float) ICON_H / size[1]);
		int x0 = x[0] + 2;
		x[0] += w + 4;
		Identifier tex = CounterCraft.id("textures/hud/killfeed/" + name + ".png");
		draws.add(g -> g.blit(RenderPipelines.GUI_TEXTURED, tex, x0, cy - 2, 0f, 0f, w, ICON_H, size[0], size[1], size[0], size[1],
				scaleAlpha(0xFFFFFFFF, alpha)));
	}

	private static void weapon(List<Consumer<GuiGraphicsExtractor>> draws, int[] x, String weapon, int cy, float alpha) {
		if (weapon.startsWith("item:")) {
			Identifier id = Identifier.tryParse(weapon.substring(5));
			ItemStack stack = id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(id));
			int x0 = x[0] + 2;
			x[0] += ICON_H + 4;
			draws.add(g -> {
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(x0, cy - 2);
				pose.scale(ICON_H / 16f, ICON_H / 16f);
				g.item(stack, 0, 0);
				pose.popMatrix();
			});
			return;
		}
		if (!weapon.isEmpty()) {
			icon(draws, x, ICONS.containsKey(weapon) ? weapon : "melee", cy, alpha);
		}
	}
}
