package dev.countercraft.client.hud;

import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.team.Teams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * The player's compass, a heading strip at the top centre of the screen (the mod's own: CS2 has no heading compass, its
 * only one is Retakes' bombsite pips). Ticks every 5 degrees, the eight directions as letters, your bearing under the
 * centre mark, and a pip in their team colour for every teammate within {@link #PIP_RANGE} blocks. Laid out in 1080p
 * reference pixels like the rest of the HUD; Minecraft's font. It hangs below Minecraft's player locator bar, which
 * moves to the top of the screen ({@code LocatorBarMixin}); vanilla's boss bars move down below both ({@link #height}).
 */
public final class CompassHud {
	/** Strip size (1080p px) and how much of the circle it shows. */
	private static final int W = 520;
	private static final int H = 24;
	/** The locator bar's top (GUI px; room above it for its dots' up arrows) and the space it takes with them. */
	public static final int LOCATOR_TOP = 8;
	private static final int LOCATOR_SPACE = LOCATOR_TOP + 5 + 7;
	private static final float SPAN = 180f;
	private static final double PIP_RANGE = 256;
	private static final String[] LETTERS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

	private CompassHud() {
	}

	/** Space the locator bar, the compass and a planted bomb's icon take at the top (GUI units), for what sits below. */
	public static int height() {
		return baseHeight() + BombNotices.iconSpace();
	}

	/** Space the locator bar and the compass take at the top (GUI units): where the planted bomb's icon goes. */
	public static int baseHeight() {
		if (!dev.countercraft.client.options.CcOptions.compass()) {
			return LOCATOR_SPACE;
		}
		Minecraft mc = Minecraft.getInstance();
		return LOCATOR_SPACE + (int) Math.ceil((H + 26) * scale(mc));
	}

	private static float scale(Minecraft mc) {
		return (float) (mc.getWindow().getHeight() / 1080.0 / mc.getWindow().getGuiScale());
	}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		Player me = mc.player;
		if (me == null || !dev.countercraft.client.options.CcOptions.compass()) {
			return;
		}
		float k = scale(mc);
		int screenW = Math.round(g.guiWidth() / k);
		int top = Math.round(LOCATOR_SPACE / k) + 2; // 1080p px, below the locator bar
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale(k, k);
		Font font = mc.font;
		int x0 = (screenW - W) / 2;
		int cx = screenW / 2;
		float pxPerDeg = W / SPAN;
		// bearing: 0 north, 90 east (Minecraft yaw 0 faces south, 90 west)
		float bearing = wrap360(me.getYRot() + 180f);
		// the strip: dark, fading out at both ends
		// (13 px steps: every fill is a GUI element vanilla layers per frame; 4 px steps were 130 of them)
		for (int i = 0; i < W; i += 13) {
			float edge = Math.min(i + 6.5f, W - i - 6.5f) / (W * 0.18f);
			int a = (int) (0x70 * Math.min(1f, edge));
			g.fill(x0 + i, top, x0 + Math.min(W, i + 13), top + H, a << 24);
		}
		// ticks and letters
		int first = (int) Math.floor((bearing - SPAN / 2) / 5f) * 5;
		for (int d = first; d <= bearing + SPAN / 2; d += 5) {
			float x = cx + (d - bearing) * pxPerDeg;
			float edge = Math.min(x - x0, x0 + W - x) / (W * 0.18f);
			if (edge <= 0) {
				continue;
			}
			int alpha = (int) (255 * Math.min(1f, edge));
			int dd = Math.floorMod(d, 360);
			if (dd % 45 == 0) {
				String s = LETTERS[dd / 45];
				boolean cardinal = dd % 90 == 0;
				int col = (dd == 0 ? 0xE05050 : cardinal ? 0xFFFFFF : 0xBFBFBF) | alpha << 24;
				float ts = cardinal ? 1.6f : 1.25f;
				pose.pushMatrix();
				pose.translate(x, top + H / 2f - font.lineHeight * ts / 2f + 1);
				pose.scale(ts, ts);
				g.text(font, s, -font.width(s) / 2, 0, col, true);
				pose.popMatrix();
			} else {
				int len = dd % 15 == 0 ? 8 : 4;
				g.fill(Math.round(x), top + H - len - 2, Math.round(x) + 1, top + H - 2, (alpha * 3 / 4) << 24 | 0xFFFFFF);
			}
		}
		// teammates
		String team = Teams.of(me);
		if (mc.level != null) {
			for (Player p : mc.level.players()) {
				if (p == me || p.isSpectator() || p.isInvisible() || !team.equals(Teams.of(p))) {
					continue;
				}
				double dx = p.getX() - me.getX();
				double dz = p.getZ() - me.getZ();
				double dist = Math.hypot(dx, dz);
				if (dist > PIP_RANGE || dist < 0.5) {
					continue;
				}
				float to = (float) Math.toDegrees(Math.atan2(dx, -dz)); // bearing to them: 0 north (-Z), 90 east (+X)
				float off = wrap180(to - bearing);
				if (Math.abs(off) > SPAN / 2 - 6) {
					continue;
				}
				int px = Math.round(cx + off * pxPerDeg);
				int col = (Teams.CT.equals(team) ? CsUi.CT_COLOR : CsUi.T_COLOR) & 0xFFFFFF;
				int a = (int) (255 * Math.max(0.35, 1 - dist / PIP_RANGE));
				g.fill(px - 3, top + 2, px + 3, top + 8, 0xFF000000);
				g.fill(px - 2, top + 3, px + 2, top + 7, a << 24 | col);
			}
		}
		// centre mark and bearing
		g.fill(cx, top - 3, cx + 1, top + H + 3, 0xFFFFFFFF);
		String deg = Integer.toString(Math.round(bearing) % 360);
		int bw = font.width(deg) * 2 + 12;
		g.fill(cx - bw / 2, top + H + 4, cx + bw / 2, top + H + 22, 0x90000000);
		pose.pushMatrix();
		pose.translate(cx, top + H + 5);
		pose.scale(2f, 2f);
		g.text(font, deg, -font.width(deg) / 2, 0, 0xFFFFFFFF, true);
		pose.popMatrix();
		pose.popMatrix();
	}

	private static float wrap360(float a) {
		a %= 360f;
		return a < 0 ? a + 360f : a;
	}

	private static float wrap180(float a) {
		a = wrap360(a);
		return a > 180f ? a - 360f : a;
	}
}
