package dev.countercraft.client.hud;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * CS2's round end banner (panorama hudwinpanel), sizes in 1080p px. The panel: 400 x 80 at y 190, 4 px sides in the accent
 * colour on the team's tinted fill (CS2's winPanelBgColorT / CT; the lost round's dark red), the title (64 px, 8 px letter
 * spacing, shrunk to fit) between double arrows, the fun fact (12 px) along its bottom. Its intro as CS2's: it opens
 * sideways and fades in over 0.25 s (ease-in) behind a white flash, with a bright glitch over it until 0.7 s; the title
 * settles from 1.5x; the arrows, white-hot and shifted 25 px right (the right one clipped by the panel), slide into place
 * after 0.5 s. Under it, a challenge's MVP strip ({@link #mvp}). Used for DAY SURVIVED (round won), GAME OVER (round
 * lost) and challenges (MvpBanners).
 */
public final class WinPanel {
	private static final Identifier ARROWS = CounterCraft.id("textures/gui/hud/double_arrows.png");
	/** CS2's winPanelBgColorT / winPanelBgColorCT: rgba(66, 46, 8, 0.85), rgba(9, 40, 61, 0.85). */
	public static final int FILL_T = 0xD9422E08;
	public static final int FILL_CT = 0xD909283D;
	/** The lost round: rgba(32, 2, 2, 0.877), negativeColor sides and title. */
	public static final int FILL_LOST = 0xE0200202;
	public static final int NEGATIVE = 0xFFDB4437;

	private WinPanel() {
	}

	/** CSS ease-in, cubic-bezier(0.42, 0, 1, 1), for t in 0..1. */
	static double easeIn(double t) {
		return bezier(t, 0.42, 1.0);
	}

	/** CSS ease-in-out, cubic-bezier(0.42, 0, 0.58, 1). */
	static double easeInOut(double t) {
		return bezier(t, 0.42, 0.58);
	}

	/** A CSS timing curve with control points (x1, 0) and (x2, 1): y at x. */
	private static double bezier(double x, double x1, double x2) {
		x = Math.max(0, Math.min(1, x));
		double lo = 0;
		double hi = 1;
		double t = x;
		for (int i = 0; i < 24; i++) {
			t = (lo + hi) / 2;
			double bx = 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t;
			if (bx < x) {
				lo = t;
			} else {
				hi = t;
			}
		}
		return 3 * t * t - 2 * t * t * t;
	}

	/** {@code t}: seconds since it appeared (nothing before 0); {@code alpha} fades the whole panel (1 = solid). */
	public static void draw(GuiGraphicsExtractor g, int width, int height, double t, String title, String fact, int accent, int fill) {
		draw(g, width, height, t, title, fact, accent, fill, 1f);
	}

	public static void draw(GuiGraphicsExtractor g, int width, int height, double t, String title, String fact, int accent, int fill, float alpha) {
		if (t < 0 || alpha <= 0f) {
			return;
		}
		float k = CsUi.k(height);
		float w = 400 * k;
		float h = 80 * k;
		float cx = width / 2f;
		float y = 190 * k;
		float open = (float) easeIn(t / 0.25);
		float bw = w * open;
		float x0 = cx - bw / 2;
		float a = alpha * open; // (opacity eases in with the opening)
		int acc = fade(accent, a);
		g.fill((int) x0, (int) y, (int) (x0 + bw), (int) (y + h), fade(fill, a));
		glitch(g, t, x0, y, bw, h, accent, a);
		g.fill((int) x0, (int) y, (int) (x0 + 4 * k), (int) (y + h), acc);
		g.fill((int) (x0 + bw - 4 * k), (int) y, (int) (x0 + bw), (int) (y + h), acc);
		if (open >= 0.999f) {
			// title: 64 px, 8 px letter spacing, shrunk to its 340 px box (320 here: Minecraft's font has no side bearings,
			// so a shrunk title met the arrows), settling from 1.5x
			float size = 64;
			float spacing = 8 * k;
			float natural = CsUi.textWidth(title, size, height) + spacing * (title.length() - 1);
			float fitScale = Math.min(1f, 320 * k / natural);
			float settle = (float) (1.5 - 0.5 * easeIn(t / 0.25));
			float sz = size * fitScale * settle;
			float sp = spacing * fitScale * settle;
			float total = CsUi.textWidth(title, sz, height) + sp * (title.length() - 1);
			float tx = cx - total / 2;
			float ty = y + 33 * k - sz * k * 0.4f;
			for (char ch : title.toCharArray()) {
				String c = String.valueOf(ch);
				CsUi.text(g, c, tx, ty, sz, height, acc, -1);
				tx += CsUi.textWidth(c, sz, height) + sp;
			}
			// the double arrows: 25 px right of their places and white-hot (CS2: brightness 10), sliding in after 0.5 s
			float s = (float) easeIn((t - 0.5) / 0.25);
			float shift = (1 - s) * 25 * k;
			float ah = 16 * k;
			float aw = ah * 60 / 64f;
			float ay = y + 33 * k - ah / 2;
			int arrow = fade(mix(0xFFFFFFFF, accent, s), a);
			g.enableScissor((int) x0, (int) y, (int) (x0 + bw), (int) (y + h));
			arrow(g, x0 + 12 * k + shift, ay, aw, ah, arrow, true);
			arrow(g, x0 + bw - 12 * k - aw + shift, ay, aw, ah, arrow, false);
			g.disableScissor();
			// the fun fact: 12 px, shrunk to fit, along the bottom (3 px margin)
			float fs = CsUi.fitSize(fact, bw - 16 * k, 12, height);
			CsUi.text(g, fact, cx, y + h - 3 * k - 8 * k - fs * k * 0.45f, fs, height, fade(0xFFFFFFFF, a), 0);
		}
		// the white flash fading out (0.25 s, then 0.2 s)
		float white = (float) (t < 0.25 ? 1 : 1 - easeIn((t - 0.25) / 0.2));
		if (white > 0) {
			g.fill((int) x0, (int) y, (int) (x0 + bw), (int) (y + h), (int) (white * alpha * 255) << 24 | 0xFFFFFF);
		}
	}

	/** The lost round: GAME OVER. */
	public static void gameOver(GuiGraphicsExtractor g, int width, int height, double t, String fact, float alpha) {
		draw(g, width, height, t, "GAME OVER", fact, NEGATIVE, FILL_LOST, alpha);
	}

	/** The team's fill (CS2's win panel colours) for a faction ("t" / "ct"). */
	public static int fill(String team) {
		return dev.countercraft.team.Teams.CT.equals(team) ? FILL_CT : FILL_T;
	}

	/**
	 * CS2's MVP strip under the banner (hudwinpanel MVP_section): 640 x 90 px, 16 px below it, the team's fill fading out
	 * toward both ends; the MVP's avatar (72 px), the reason on a tag in the accent colour (18 px, black), their name
	 * (28 px) and music kit (12 px, "name ♪") in the accent colour. It opens sideways with the banner (0.25 s, ease-in-
	 * out) and its white flash fades after 0.25 s.
	 */
	public static void mvp(GuiGraphicsExtractor g, int width, int height, double t, net.minecraft.world.entity.player.PlayerSkin skin, String reason,
			String name, String kit, int accent, int fill, float alpha) {
		if (t < 0 || alpha <= 0f) {
			return;
		}
		float k = CsUi.k(height);
		float w = 640 * k;
		float h = 90 * k;
		float cx = width / 2f;
		float y = (190 + 80 + 16) * k;
		float open = (float) easeInOut(t / 0.25);
		float bw = w * open;
		float x0 = cx - bw / 2;
		float a = alpha * open;
		// CS2's mvp_banner_fade mask: the fill fades out toward each end
		int strips = 48;
		for (int i = 0; i < strips; i++) {
			float u = (i + 0.5f) / strips;
			float m = Math.min(1f, Math.min(u, 1 - u) / 0.18f);
			g.fill((int) (x0 + bw * i / strips), (int) y, (int) (x0 + bw * (i + 1) / strips), (int) (y + h), fade(fill, a * m * m * (3 - 2 * m)));
		}
		if (open >= 0.999f) {
			float av = 72 * k;
			// the reason shrinks a little (to 14 px) before it's cut
			float rs = Math.max(14f, CsUi.fitSize(reason, 480 * k, 18, height));
			String r = CsUi.fit(reason, 480 * k, rs, height);
			float rw = CsUi.textWidth(r, rs, height) + 12 * k;
			String kitLine = kit.isEmpty() ? "" : kit + " ♪";
			float detailsW = Math.max(rw, Math.max(CsUi.textWidth(name, 28, height), CsUi.textWidth(kitLine, 12, height)));
			float left = cx - (av + 8 * k + detailsW) / 2;
			int col = fade(accent, a);
			// avatar: the player's face on CS2's grey gradient
			float ay = y + (h - av) / 2;
			g.fillGradient((int) left, (int) ay, (int) (left + av), (int) (ay + av), fade(0xFF6D6D6D, a), fade(0xFF464646, a));
			if (skin != null && a > 0.5f) {
				net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, skin, Math.round(left), Math.round(ay), Math.round(av));
			}
			float dx = left + av + 8 * k;
			float dy = y + (h - (21 + 38 + (kitLine.isEmpty() ? 0 : 16)) * k) / 2;
			g.fill((int) dx, (int) dy, (int) (dx + rw), (int) (dy + 21 * k), col);
			CsUi.text(g, r, dx + 6 * k, dy + (21 - rs) / 2 * k + 1.5f * k, rs, height, fade(0xFF000000, a), -1, false);
			CsUi.text(g, name, dx, dy + 21 * k + 6 * k, 28, height, col, -1);
			if (!kitLine.isEmpty()) {
				CsUi.text(g, CsUi.fit(kitLine, 300 * k, 12, height), dx, dy + (21 + 38) * k + 2 * k, 12, height, col, -1);
			}
		}
		float white = (float) (t < 0.25 ? 1 : 1 - easeInOut((t - 0.25) / 0.2));
		if (white > 0) {
			g.fill((int) x0, (int) y, (int) (x0 + bw), (int) (y + h), (int) (white * alpha * 255) << 24 | 0xFFFFFF);
		}
	}

	/**
	 * CS2 lays a digital glitch video over the panel as it opens (additive, a quarter strength, very bright, in the accent
	 * colour), fading out at 0.5 s over 0.2 s: here, flickering bright bars.
	 */
	private static void glitch(GuiGraphicsExtractor g, double t, float x0, float y, float bw, float h, int accent, float alpha) {
		double fadeOut = t < 0.5 ? 1 : 1 - easeIn((t - 0.5) / 0.2);
		if (fadeOut <= 0) {
			return;
		}
		java.util.Random r = new java.util.Random((long) (t * 30)); // a new pattern every 1/30 s
		int c = mix(0xFFFFFFFF, accent, 0.4f);
		for (int i = 0; i < 6; i++) {
			float by = y + r.nextFloat() * h;
			float bh = h * (0.03f + r.nextFloat() * 0.1f);
			float bx = x0 + r.nextFloat() * bw * 0.7f;
			float bl = bw * (0.15f + r.nextFloat() * 0.5f);
			g.fill((int) bx, (int) by, (int) Math.min(x0 + bw, bx + bl), (int) Math.min(y + h, by + bh), fade(c, (float) (0.25 * fadeOut * alpha)));
		}
	}

	/** One double arrow, pointing left ({@code mirror}: right, its texture flipped rather than the pose, which culls it). */
	private static void arrow(GuiGraphicsExtractor g, float x, float y, float w, float h, int color, boolean mirror) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(w / 60f, h / 64f);
		g.blit(RenderPipelines.GUI_TEXTURED, ARROWS, 0, 0, mirror ? 60f : 0f, 0f, 60, 64, mirror ? -60 : 60, 64, 60, 64, color);
		pose.popMatrix();
	}

	private static int mix(int from, int to, float s) {
		s = Math.max(0, Math.min(1, s));
		int r = Math.round(((from >> 16) & 255) * (1 - s) + ((to >> 16) & 255) * s);
		int gr = Math.round(((from >> 8) & 255) * (1 - s) + ((to >> 8) & 255) * s);
		int b = Math.round((from & 255) * (1 - s) + (to & 255) * s);
		return 0xFF000000 | r << 16 | gr << 8 | b;
	}

	private static int fade(int argb, float alpha) {
		return (int) ((argb >>> 24) * Math.max(0f, Math.min(1f, alpha))) << 24 | argb & 0xFFFFFF;
	}
}
