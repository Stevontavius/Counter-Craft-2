package dev.countercraft.client.hud;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * CS2's round end banner (panorama hudwinpanel): 400 x 80 px at y 190 (1080p), 4 px sides in the accent colour on a
 * dark tinted fill, the title in the accent colour with 8 px letter spacing between double arrows, the "fun fact" line
 * under it. Its intro as CS2's: opens sideways over 0.25 s behind a white flash, the title settles from 1.5x, the
 * arrows slide in after 0.5 s. Used for Counter-Craft's GAME OVER (round lost) and DAY SURVIVED (round won).
 */
public final class WinPanel {
	private static final Identifier ARROWS = CounterCraft.id("textures/gui/hud/double_arrows.png");

	private WinPanel() {
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
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
		float open = (float) smooth(t / 0.25);
		float bw = w * open;
		float x0 = cx - bw / 2;
		int acc = fade(accent, alpha);
		g.fill((int) x0, (int) y, (int) (x0 + bw), (int) (y + h), fade(fill, alpha));
		g.fill((int) x0, (int) y, (int) (x0 + 4 * k), (int) (y + h), acc);
		g.fill((int) (x0 + bw - 4 * k), (int) y, (int) (x0 + bw), (int) (y + h), acc);
		if (open >= 0.999f) {
			// title: 64 px, 8 px letter spacing, shrunk to its 340 px box, settling from 1.5x
			float size = 64;
			float spacing = 8 * k;
			float natural = CsUi.textWidth(title, size, height) + spacing * (title.length() - 1);
			float fitScale = Math.min(1f, 340 * k / natural);
			float settle = (float) (1.5 - 0.5 * smooth(t / 0.25));
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
			// the double arrows, sliding in after half a second
			float slide = (float) smooth((t - 0.5) / 0.25) * 25 * k;
			float ah = 16 * k;
			float aw = ah * 60 / 64f;
			float ay = y + 33 * k - ah / 2;
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(x0 + 12 * k + aw + 25 * k - slide, ay);
			pose.scale(-1f, 1f);
			CsUi.image(g, ARROWS, 0, 0, aw, ah, 60, 64, acc);
			pose.popMatrix();
			CsUi.image(g, ARROWS, x0 + bw - 12 * k - aw - 25 * k + slide, ay, aw, ah, 60, 64, acc);
			CsUi.text(g, fact, cx, y + h - 19 * k, 14, height, fade(0xFFFFFFFF, alpha), 0);
		}
		// the white flash fading out (0.25 s, then 0.2 s)
		float white = (float) (t < 0.25 ? 1 : Math.max(0, 1 - (t - 0.25) / 0.2));
		if (white > 0) {
			g.fill((int) x0, (int) y, (int) (x0 + bw), (int) (y + h), (int) (white * alpha * 255) << 24 | 0xFFFFFF);
		}
	}

	private static int fade(int argb, float alpha) {
		return (int) ((argb >>> 24) * alpha) << 24 | argb & 0xFFFFFF;
	}
}
