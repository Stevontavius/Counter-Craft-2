package dev.countercraft.client.hud;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.gun.PlantedC4;
import dev.countercraft.team.Teams;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The C4's notices, in the style of CS2's HUD (panorama hud/hudalerts and hud/hudteamcounter), in Minecraft's font:
 * <ul>
 * <li>The alert in the middle ("The bomb has been planted", "The bomb has been defused"), CS2's way:
 * <ul>
 * <li>it opens from its middle out, white, and the white fades to its half-black background;</li>
 * <li>a 2 px bar each side, bomb red when planted and CT blue when defused;</li>
 * <li>it fades out at the end.</li>
 * </ul></li>
 * <li>News at the top left for everyone: "&lt;planter&gt; planted the bomb", with a red strip down its left side and the
 * C4. It sits under the money panel, and the debug panels go under it, so it never covers another panel.</li>
 * <li>While a bomb is planted: CS2's team counter bomb at the top of the screen (under the compass when that's on;
 * the boss bars go below it). Its rays flash red with each beep, and blink hard on and off in the last seconds.</li>
 * </ul>
 */
public final class BombNotices {
	private static final int BOMB_RED = 0xE83A3A;
	private static final int CT = 0xB5D4EE;
	private static final int T = 0xEAD18A;
	/** CS2's team counter bomb (the extras step: textures/gui/hud). */
	private static final Identifier C4_ICON = CounterCraft.id("textures/gui/hud/bomb_c4.png");
	private static final Identifier RAYS = CounterCraft.id("textures/gui/hud/bomb_rays.png");
	private static final float ICON = 46;
	private static final double ALERT_SECONDS = 5.0;
	private static final double NEWS_SECONDS = 6.0;

	private static String alert1;
	private static String alert2;
	private static int alertColor;
	private static long alertAt;
	private static String newsName;
	private static String newsTeam;
	private static long newsAt;
	/** A bomb is planted (as the news said), since when (System.nanoTime). */
	private static long plantedAt = -1;

	private BombNotices() {
	}

	static void alert(String line1, String line2, int sideColor) {
		alert1 = line1;
		alert2 = line2;
		alertColor = sideColor;
		alertAt = System.nanoTime();
	}

	static void planted(String planter, String team) {
		alert("The bomb has been planted", null, BOMB_RED); // the second line: see BombHud
		newsName = planter;
		newsTeam = team;
		newsAt = System.nanoTime();
		plantedAt = System.nanoTime();
	}

	static void gone() {
		plantedAt = -1;
	}

	/** Room the planted bomb takes at the top (GUI units; 0 when none is planted), for CompassHud.height. */
	public static int iconSpace() {
		if (plantedAt < 0) {
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		return (int) Math.ceil((ICON + 10) * mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale());
	}

	static void draw(GuiGraphicsExtractor g, Minecraft mc) {
		if (mc.level == null) {
			plantedAt = -1;
		}
		int h = g.guiHeight();
		float k = CsUi.k(h);
		drawAlert(g, k, h);
		drawNews(g, k, h);
		drawPlanted(g, mc, k, h);
	}

	private static void drawAlert(GuiGraphicsExtractor g, float k, int h) {
		double t = (System.nanoTime() - alertAt) / 1e9;
		if (alert1 == null || t > ALERT_SECONDS) {
			return;
		}
		int w = g.guiWidth();
		// open from the middle out (0.25 s), white; the white fades (0.15 s) to the half-black; out over the last 0.6 s
		float open = (float) Math.min(1, t / 0.25);
		open = open * open;
		float white = (float) (t < 0.25 ? 1 : Math.max(0, 1 - (t - 0.25) / 0.15));
		float fade = (float) Math.min(1, (ALERT_SECONDS - t) / 0.6);
		float text = (float) Math.min(1, Math.max(0, (t - 0.25) / 0.25)) * fade;
		float tw = Math.max(CsUi.textWidth(alert1, 20, h), alert2 == null ? 0 : CsUi.textWidth(alert2, 15, h)) + 56 * k;
		float bh = (alert2 == null ? 42 : 64) * k;
		float y = 400 * k; // under a challenge's MVP strip (WinPanel: 286 to 376)
		float half = tw / 2 * open;
		int x0 = Math.round(w / 2f - half);
		int x1 = Math.round(w / 2f + half);
		int y0 = Math.round(y);
		int y1 = Math.round(y + bh);
		int r = Math.max(1, Math.round(3 * k));
		int bg = Math.round(0x80 * fade) << 24 | 0x000000;
		g.fill(x0, y0 + r, x1, y1 - r, bg);
		g.fill(x0 + r, y0, x1 - r, y0 + r, bg);
		g.fill(x0 + r, y1 - r, x1 - r, y1, bg);
		if (white > 0) {
			g.fill(x0, y0, x1, y1, Math.round(0xB0 * white) << 24 | 0xFFFFFF);
		}
		int side = Math.max(1, Math.round(2 * k));
		int bar = Math.round(255 * fade) << 24 | alertColor;
		g.fill(x0, y0, x0 + side, y1, bar);
		g.fill(x1 - side, y0, x1, y1, bar);
		if (text > 0.02f) {
			int a = Math.round(text * 255) << 24;
			CsUi.text(g, alert1, w / 2f, y + (alert2 == null ? 11 : 9) * k, 20, h, a | 0xFFFFFF, 0);
			if (alert2 != null) {
				CsUi.text(g, alert2, w / 2f, y + 39 * k, 15, h, a | 0xC8C8C8, 0);
			}
		}
	}

	/** The time line under the alert's title. */
	static void alertLine(String line2) {
		alert2 = line2;
	}

	private static void drawNews(GuiGraphicsExtractor g, float k, int h) {
		double t = (System.nanoTime() - newsAt) / 1e9;
		if (newsName == null || t > NEWS_SECONDS) {
			return;
		}
		float in = (float) Math.min(1, t / 0.2);
		float fade = (float) Math.min(1, (NEWS_SECONDS - t) / 0.5);
		int a = Math.round(255 * fade) << 24;
		String what = " planted the bomb";
		float size = 16;
		float pad = 10 * k;
		float icon = 22 * k;
		float strip = 4 * k;
		float pw = strip + pad + icon + 8 * k + CsUi.textWidth(newsName + what, size, h) + pad;
		float ph = 34 * k;
		float x = 18 * k - (1 - in) * (pw + 18 * k);
		float y = HudSpace.bottom(HudSpace.Panel.MONEY, 18 * k) + 10 * k;
		HudSpace.mark(HudSpace.Panel.NOTICE, y, y + ph);
		g.fill(Math.round(x), Math.round(y), Math.round(x + pw), Math.round(y + ph), Math.round(0xB0 * fade) << 24 | 0x101214);
		g.fill(Math.round(x), Math.round(y), Math.round(x + strip), Math.round(y + ph), a | BOMB_RED);
		c4(g, x + strip + pad, y + (ph - icon) / 2, icon, a | BOMB_RED);
		float tx = x + strip + pad + icon + 8 * k;
		float ty = y + (ph - size * k * 0.8f) / 2;
		int team = Teams.CT.equals(newsTeam) ? CT : Teams.T.equals(newsTeam) ? T : 0xFFFFFF;
		CsUi.text(g, newsName, tx, ty, size, h, a | team, -1);
		CsUi.text(g, what, tx + CsUi.textWidth(newsName, size, h), ty, size, h, a | 0xFFFFFF, -1);
	}

	private static void drawPlanted(GuiGraphicsExtractor g, Minecraft mc, float k, int h) {
		if (plantedAt < 0) {
			return;
		}
		float flash = flash(mc);
		float s = ICON * k;
		float x = g.guiWidth() / 2f - s / 2;
		float y = CompassHud.baseHeight() + 4 * k;
		if (has(C4_ICON) && has(RAYS)) {
			CsUi.image(g, C4_ICON, x, y, s, s, 128, 128, Math.round((0.6f + 0.4f * flash) * 255) << 24 | BOMB_RED);
			CsUi.image(g, RAYS, x, y, s, s, 128, 128, Math.round(flash * 255) << 24 | 0xFF2020);
		} else {
			c4(g, x, y, s, Math.round((0.35f + 0.65f * flash) * 255) << 24 | BOMB_RED);
		}
	}

	/** The C4, tinted (CS2's team counter bomb from the pack; else the buy menu's C4). */
	public static void c4(GuiGraphicsExtractor g, float x, float y, float size, int argb) {
		if (has(C4_ICON)) {
			CsUi.image(g, C4_ICON, x, y, size, size, 128, 128, argb);
		} else {
			CsUi.iconIn(g, "weapon_c4", x, y, size, size, 1f, argb);
		}
	}

	/**
	 * The beat's brightness, 0..1: the loaded bomb's own (in step with its beeps, PlantedC4.warningFlash), else the
	 * same beeps replayed from when it was planted.
	 */
	private static float flash(Minecraft mc) {
		PlantedC4 soonest = null;
		for (PlantedC4 bomb : mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(512))) {
			soonest = soonest == null || bomb.fuseAtBeep() < soonest.fuseAtBeep() ? bomb : soonest;
		}
		if (soonest != null) {
			return soonest.warningFlash();
		}
		double elapsed = (System.nanoTime() - plantedAt) / 1e9;
		int ticks = (int) (elapsed * 20);
		int fuse = PlantedC4.FUSE_TICKS;
		int next = 0;
		int beepTick = 0;
		int beepFuse = fuse;
		for (int i = 1; i <= ticks && fuse > 0; i++) {
			fuse--;
			if (--next <= 0 && fuse > 20) {
				next = PlantedC4.beepInterval(fuse);
				beepTick = i;
				beepFuse = fuse;
			}
		}
		double beat = PlantedC4.beepInterval(beepFuse) / 20.0;
		double since = elapsed - beepTick / 20.0;
		if (beepFuse <= PlantedC4.HARD_FLASH_TICKS) {
			return since % beat < beat / 2 ? 1f : 0f;
		}
		return (float) Math.max(0.15, 1 - since / beat);
	}

	private static final Map<Identifier, Boolean> HAS = new HashMap<>();
	private static long hasAt;

	/** The pack has this texture (looked up again every 10 s: packs change). */
	private static boolean has(Identifier tex) {
		if (System.nanoTime() - hasAt > 10_000_000_000L) {
			HAS.clear();
			hasAt = System.nanoTime();
		}
		return HAS.computeIfAbsent(tex, t -> Minecraft.getInstance().getResourceManager().getResource(t).isPresent());
	}
}
