package dev.countercraft.client.hud;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.loadout.LoadoutConfig;
import dev.countercraft.client.loadout.MusicKit;
import dev.countercraft.net.MvpPayload;
import dev.countercraft.team.Teams;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

/**
 * Achievements as CS2 MVP banners instead of Minecraft's toasts.
 * <ul>
 * <li>Advancements and goals (yours only): a compact MVP-style card under the compass with the advancement's icon,
 * title and description, and CS2's own UI chimes (UI.XP.LevelUp for an advancement, UI.CoinLevelUp for a goal). No
 * music.</li>
 * <li>Challenges (the whole server, {@link dev.countercraft.Mvp}): CS2's round-end banner with the challenge's name,
 * "MVP: player" in their faction's colour and what they did, and the achiever's music kit, whose MVP anthem plays for
 * everyone (Counter-Craft options: "Challenges play MVP Track").</li>
 * </ul>
 * One at a time: they queue.
 */
public final class MvpBanners {
	private record Banner(boolean challenge, String label, String title, String description, ItemStack icon, int accent, String name, String kit,
			double seconds) {
	}

	private static final Deque<Banner> QUEUE = new ArrayDeque<>();
	private static Banner current;
	private static long shownAt;

	private MvpBanners() {
	}

	/** One of your own advancements (or goals). */
	public static void personal(DisplayInfo d) {
		boolean goal = d.type() == AdvancementType.GOAL;
		ItemStack icon;
		try {
			icon = d.icon().create();
		} catch (RuntimeException e) {
			icon = ItemStack.EMPTY;
		}
		QUEUE.add(new Banner(false, goal ? "GOAL REACHED" : "ADVANCEMENT MADE", d.title().getString(), d.description().getString(), icon,
				goal ? 0xFFEABE54 : 0xFF7ED37E, "", "", 4.5));
	}

	/** Someone completed a challenge (from the server). */
	public static void challenge(MvpPayload p) {
		int accent = 0xFF000000 | (Teams.CT.equals(p.team()) ? Teams.CT_COLOR : Teams.T.equals(p.team()) ? Teams.T_COLOR : 0xB06BFF);
		QUEUE.add(new Banner(true, "CHALLENGE COMPLETE", p.title(), p.description(), ItemStack.EMPTY, accent, p.name(),
				p.kit().isEmpty() ? LoadoutConfig.DEFAULT_MUSIC_KIT : p.kit(), 8.0));
	}

	private static void start(Banner b) {
		current = b;
		shownAt = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		if (b.challenge()) {
			if (dev.countercraft.client.options.CcOptions.challengeMvp()) {
				MusicKit.playKit(b.kit(), "mvp", "snd_mvp_volume");
			}
		} else {
			String snd = b.label().startsWith("GOAL") ? "ui.coinlevelup" : "ui.xp.levelup";
			var id = CounterCraft.id(snd);
			if (mc.getSoundManager().getSoundEvent(id) != null) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), 1f, 0.6f));
			}
		}
	}

	public static void draw(GuiGraphicsExtractor g) {
		if (current == null) {
			Banner next = QUEUE.poll();
			if (next == null) {
				return;
			}
			start(next);
		}
		double t = (System.nanoTime() - shownAt) / 1e9;
		if (t > current.seconds()) {
			current = null;
			return;
		}
		float alpha = (float) Math.min(1.0, Math.min(t / 0.2, (current.seconds() - t) / 0.4));
		if (current.challenge()) {
			drawChallenge(g, t, alpha);
		} else {
			drawPersonal(g, t, alpha);
		}
	}

	/** CS2's round-end banner: the challenge's name, then "MVP: name" and what they did; the kit underneath. */
	private static void drawChallenge(GuiGraphicsExtractor g, double t, float alpha) {
		Banner b = current;
		int w = g.guiWidth();
		int h = g.guiHeight();
		float k = CsUi.k(h);
		WinPanel.draw(g, w, h, t, b.title().toUpperCase(Locale.ROOT), b.label(), b.accent(), 0xE0101214, alpha);
		if (t < 0.3) {
			return;
		}
		int a = Math.round(alpha * 255) << 24;
		float y = 190 * k + 80 * k + 10 * k;
		String mvp = "MVP: " + b.name();
		float mw = CsUi.textWidth(mvp, 24, h);
		float dw = CsUi.textWidth(b.description(), 16, h);
		String kit = "♪ " + MusicKit.name(b.kit());
		float kw = CsUi.textWidth(kit, 16, h);
		float boxW = Math.max(mw, Math.max(Math.min(dw, 560 * k), kw)) + 32 * k;
		float x0 = w / 2f - boxW / 2;
		g.fill((int) x0, (int) y, (int) (x0 + boxW), (int) (y + 72 * k), (Math.round(alpha * 0xC0) << 24) | 0x101214);
		CsUi.text(g, mvp, w / 2f - mw / 2, y + 8 * k, 24, h, a | (b.accent() & 0xFFFFFF), -1);
		String desc = CsUi.fit(b.description(), 560 * k, 16, h);
		CsUi.text(g, desc, w / 2f - CsUi.textWidth(desc, 16, h) / 2, y + 34 * k, 16, h, a | 0xBFBFBF, -1);
		CsUi.text(g, kit, w / 2f - kw / 2, y + 52 * k, 16, h, a | 0xEABE54, -1);
	}

	/** A compact MVP card for your own advancement: icon, label, title, description. */
	private static void drawPersonal(GuiGraphicsExtractor g, double t, float alpha) {
		Banner b = current;
		int w = g.guiWidth();
		int h = g.guiHeight();
		float k = CsUi.k(h);
		float bw = 420 * k;
		float bh = 64 * k;
		float slide = (float) Math.min(1.0, t / 0.25);
		float x0 = w / 2f - bw / 2;
		float y = 112 * k - (1 - slide) * 16 * k;
		int a = Math.round(alpha * 255) << 24;
		g.fill((int) x0, (int) y, (int) (x0 + bw), (int) (y + bh), (Math.round(alpha * 0xD8) << 24) | 0x101214);
		g.fill((int) x0, (int) y, (int) (x0 + 4 * k), (int) (y + bh), a | (b.accent() & 0xFFFFFF));
		g.fill((int) (x0 + bw - 4 * k), (int) y, (int) (x0 + bw), (int) (y + bh), a | (b.accent() & 0xFFFFFF));
		float tx = x0 + 16 * k;
		if (!b.icon().isEmpty() && alpha > 0.5f) {
			float s = 40 * k;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x0 + 14 * k, y + (bh - s) / 2);
			pose.scale(s / 16f, s / 16f);
			g.item(b.icon(), 0, 0);
			pose.popMatrix();
			tx += s + 8 * k;
		}
		float room = x0 + bw - 16 * k - tx;
		CsUi.text(g, b.label(), tx, y + 8 * k, 13, h, a | (b.accent() & 0xFFFFFF), -1);
		CsUi.text(g, CsUi.fit(b.title(), room, 24, h), tx, y + 22 * k, 24, h, a | 0xFFFFFF, -1);
		CsUi.text(g, CsUi.fit(b.description(), room, 14, h), tx, y + 46 * k, 14, h, a | 0xA0A0A0, -1);
	}
}
