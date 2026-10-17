package dev.countercraft.client.hud;

import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.gun.PlantedC4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CS2's defuse progress bar (panorama hud/hudprogressbar, as CS2 draws it, in Minecraft's font), square cornered:
 * <ul>
 * <li>the panel, 500 x 120 px, washed in the defuse's colour as it goes, red to yellow to green, darker at the left;</li>
 * <li>on its left a ring filling anticlockwise from the top in a light shade of that colour, round CS2's C4, which
 * flashes white on each of the bomb's beeps and eases back to gold, swelling with them;</li>
 * <li>CS2's line (with or without a kit) at the top right, the time left (00:09.546) at the bottom right.</li>
 * </ul>
 * It fades in over 0.4 s. Let go and it shakes and fades; finish and it zooms out brightening.
 * <p>Not drawn: CS2's blur of the world behind the panel (no cheap way in Minecraft's HUD).</p>
 */
final class DefuseBar {
	private static final float W = 500;
	private static final float H = 120;
	private static final int BOMB = 0xFFB136;
	/** CS2's SFUIHUD_InfoPanel_DefuseText / _NoKit. */
	private static final String WITH_KIT = "You are defusing the bomb.";
	private static final String NO_KIT = "You are defusing the bomb without a kit.";

	/** The defuse shown: when it started, its last progress and kit; how it ended (0 still going, 1 let go, 2 done) and when. */
	private static long since = -1;
	private static float progress;
	private static boolean kit;
	private static double total = 10;
	private static long progressAt;
	private static int ended;
	private static long endedAt;
	private static long defusedAt = -1;
	/** The bomb's beat, 0..1 (PlantedC4.warningFlash: full on each beep, fading; hard on / off in the last seconds). */
	private static float beep;

	private DefuseBar() {
	}

	/** The bomb was defused (C4Payload.DEFUSED). */
	static void defused() {
		defusedAt = System.nanoTime();
	}

	static void draw(GuiGraphicsExtractor g, Minecraft mc) {
		long now = System.nanoTime();
		PlantedC4 mine = null;
		for (PlantedC4 bomb : mc.level.getEntitiesOfClass(PlantedC4.class, mc.player.getBoundingBox().inflate(6))) {
			mine = bomb.defuser() == mc.player.getId() ? bomb : mine;
		}
		if (mine != null) {
			if (since < 0 || ended != 0) {
				since = now;
				ended = 0;
			}
			if (mine.defuseProgress() != progress) {
				progress = mine.defuseProgress();
				progressAt = now;
			}
			kit = mine.defuseKit();
			total = mine.defuseSeconds();
			beep = mine.warningFlash();
		} else if (since >= 0 && ended == 0) {
			ended = defusedAt >= 0 && (now - defusedAt) / 1e9 < 0.5 ? 2 : 1;
			endedAt = now;
			progress = ended == 2 ? 1f : progress;
		}
		if (since < 0) {
			return;
		}
		double in = (now - since) / 1e9;
		double out = ended == 0 ? 0 : (now - endedAt) / 1e9;
		float alpha = (float) Math.min(1, in / 0.4);
		float zoom = 1f;
		float shake = 0f;
		float bright = 0f;
		if (ended == 1) {
			// let go: three shakes of 4 px (0.3 s), then gone
			shake = out < 0.3 ? (float) (4 * Math.sin(out / 0.1 * Math.PI * 2)) : 0f;
			alpha *= (float) Math.max(0, 1 - Math.max(0, out - 0.3) / 0.25);
			if (out > 0.55) {
				since = -1;
				return;
			}
		} else if (ended == 2) {
			// done: zooms out to 1.3x, brightening, over 0.4 s
			float t = (float) Math.min(1, out / 0.4);
			zoom = 1 + 0.3f * t;
			bright = t;
			alpha *= 1 - t;
			if (out > 0.4) {
				since = -1;
				defusedAt = -1;
				return;
			}
		}
		// the progress, smoothed between server ticks
		float shown = ended == 0 && progress > 0 ? (float) Math.min(1, progress + Math.min((now - progressAt) / 1e9, 0.05) / total) : progress;
		int w = g.guiWidth();
		int h = g.guiHeight();
		float k = CsUi.k(h);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(w / 2f + shake * k, h * 0.66f + H * k / 2);
		pose.scale(k * zoom, k * zoom);
		pose.translate(-W / 2, -H / 2);
		int a = Math.round(alpha * 255);
		// the panel, square, washed in the defuse's colour (red, yellow, green as it goes): darker at the left, brighter to
		// the right, as CS2's
		int tint = mix(mix(fill(shown), 0x707070, 0.3f), 0xFFFFFF, bright);
		int slices = 48;
		for (int i = 0; i < slices; i++) {
			float t = i / (slices - 1f);
			int col = mix(mix(tint, 0x000000, 0.62f), mix(tint, 0x000000, 0.22f), t);
			int x0 = Math.round(W * i / slices);
			int x1 = Math.round(W * (i + 1) / slices);
			g.fill(x0, 0, x1, (int) H, Math.round(alpha * (0.62f + 0.18f * t) * 255) << 24 | col);
		}
		// the ring: dark all round, the defuse filling it anticlockwise from the top in a light shade of the colour
		float cx = 14 + 47.5f;
		float cy = H / 2;
		ring(g, cx, cy, 41.5f, 8, 360, Math.round(alpha * 0x55) << 24, 1);
		ring(g, cx, cy, 41.5f, 8, 360 * shown, Math.round(alpha * 0xF0) << 24 | mix(tint, 0xFFFFFF, 0.55f), -1);
		pose.popMatrix();

		// the C4 in the ring (CS2's equipment icon), in time with the bomb's beeps: white on each beep, easing back to the
		// bomb's gold, swelling a little with it
		float scale = 1f + 0.08f * beep;
		int icon = mix(mix(BOMB, 0xFFFFFF, beep), 0xFFFFFF, bright);
		float left = w / 2f + shake * k - W / 2 * k * zoom;
		float top = h * 0.66f + H * k / 2 - H / 2 * k * zoom;
		float s = k * zoom;
		float is = 52 * s * scale;
		if (CsUi.iconSize("weapon_c4") != null) {
			CsUi.iconIn(g, "weapon_c4", left + cx * s - is / 2, top + cy * s - is / 2, is, is, 1f, a << 24 | icon);
		} else {
			BombNotices.c4(g, left + cx * s - is / 2, top + cy * s - is / 2, is, a << 24 | icon); // no buy menu icons
		}
		// CS2's line at the top right, and the time left (minutes, seconds, thousandths) at the bottom right
		int textA = a << 24;
		float infoX = left + (14 + 95 + 20) * s;
		float infoRight = left + (W - 20) * s;
		String title = kit ? WITH_KIT : NO_KIT;
		float size = CsUi.fitSize(title, infoRight - infoX, 18 * zoom, h);
		CsUi.text(g, title, infoRight, top + 16 * s, size, h, textA | 0xFFFFFF, 1);
		double rest = Math.max(0, (1 - shown) * total);
		int ms = (int) Math.round(rest * 1000);
		String time = String.format("%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000);
		CsUi.text(g, time, infoRight, top + (H - 16 - 17) * s, 18 * zoom, h, textA | 0xFFFFFF, 1);
	}

	/** The ring's colour at this much progress: CS2's red, yellow, green. */
	private static int fill(float t) {
		return t < 0.5f ? mix(0xFF0000, 0xFFFF00, t * 2) : mix(0xFFFF00, 0x00FF00, (t - 0.5f) * 2);
	}

	private static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int gg = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bb = Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return r << 16 | gg << 8 | bb;
	}

	/**
	 * A ring band (radius to its middle, thickness), {@code sweep} degrees from the top, clockwise ({@code dir} 1) or
	 * anticlockwise (-1), in small steps.
	 */
	private static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, float thick, float sweep, int argb, int dir) {
		if (sweep <= 0 || (argb >>> 24) == 0) {
			return;
		}
		int steps = Math.max(1, Math.round(sweep / 3f));
		float step = sweep / steps;
		float len = (float) (2 * Math.PI * r * step / 360) + 0.6f;
		var pose = g.pose();
		for (int i = 0; i < steps; i++) {
			pose.pushMatrix();
			pose.translate(cx, cy);
			pose.rotate((float) Math.toRadians(dir * (i * step + step / 2)));
			pose.translate(-len / 2, -r - thick / 2);
			pose.scale(len, thick);
			g.fill(0, 0, 1, 1, argb);
			pose.popMatrix();
		}
	}
}
