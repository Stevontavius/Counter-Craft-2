package dev.countercraft.client.hud;

import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.client.GunKeys;
import dev.countercraft.team.CsInventory;
import dev.countercraft.team.Economy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * CS2's money panel (top left): your cash, with the last change ("+$300" / "-$2700") fading out beside it. Shown in
 * survival / adventure on either hotbar (creative buys for free, so it has no money to show), with a reminder of the key
 * that switches between the CS weapons and the Minecraft hotbar.
 */
public final class MoneyHud {
	private static int last = -1;
	private static int delta;
	private static long deltaAt;

	private MoneyHud() {
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		var p = mc.player;
		if (p == null || p.isSpectator() || Economy.freeBuy(p)) {
			last = -1;
			return;
		}
		int money = Economy.money(p);
		if (last >= 0 && money != last) {
			delta = money - last;
			deltaAt = System.nanoTime();
		}
		last = money;
		int h = g.guiHeight();
		float k = CsUi.k(h);
		float x = 24 * k;
		float y = 24 * k;
		// Counter-Craft: BUY PHASE (green) or the countdown to the next one, above the money
		String phase = RoundCycle.phaseText();
		if (phase != null) {
			boolean buy = RoundCycle.buyPhase();
			float pw = CsUi.textWidth(phase, 18, h);
			g.fill((int) (x - 8 * k), (int) (y - 6 * k), (int) (x + pw + 10 * k), (int) (y + 22 * k), 0x66000000);
			CsUi.text(g, phase, x, y, 18, h, buy ? 0xFF7ED37E : 0xFFFFFFFF, -1);
			y += 34 * k;
		}
		String text = "$" + money;
		float w = CsUi.textWidth(text, 26, h);
		g.fill((int) (x - 8 * k), (int) (y - 6 * k), (int) (x + w + 10 * k), (int) (y + 30 * k), 0x66000000);
		CsUi.text(g, text, x, y, 26, h, 0xFF7ED37E, -1);
		double age = (System.nanoTime() - deltaAt) / 1e9;
		if (delta != 0 && age < 3) {
			int a = (int) (255 * Math.min(1, 3 - age));
			String d = (delta > 0 ? "+$" : "-$") + Math.abs(delta);
			CsUi.text(g, d, x + w + 20 * k, y + 4 * k, 20, h, a << 24 | (delta > 0 ? 0x7ED37E : 0xE05050), -1);
		}
		hotbarHint(g, x, y + 40 * k, k, h, CsInventory.active(p));
	}

	/** "Press [Left Alt] to switch to [Minecraft] Hotbar", under the money (hidden while the key is unbound). */
	private static void hotbarHint(GuiGraphicsExtractor g, float x, float y, float k, int h, boolean cs) {
		if (GunKeys.CS_MODE.isUnbound()) {
			return;
		}
		String key = GunKeys.CS_MODE.getTranslatedKeyMessage().getString();
		String next = cs ? "Minecraft" : "CS Weapons";
		String[] parts = {"Press ", key, " to switch to ", next, " Hotbar"};
		float size = 14;
		float total = 0;
		for (String s : parts) {
			total += CsUi.textWidth(s, size, h);
		}
		float pad = 6 * k;
		total += 4 * pad; // the key's and the hotbar's boxes
		g.fill((int) (x - 8 * k), (int) (y - 5 * k), (int) (x + total + 10 * k), (int) (y + 18 * k), 0x66000000);
		float cx = x;
		for (int i = 0; i < parts.length; i++) {
			float pw = CsUi.textWidth(parts[i], size, h);
			if (i == 1 || i == 3) {
				// the key and the hotbar it leads to, boxed like CS2's key hints
				g.fill((int) cx, (int) (y - 3 * k), (int) (cx + pw + 2 * pad), (int) (y + 16 * k), i == 1 ? 0x40FFFFFF : 0x30000000);
				g.outline((int) cx, (int) (y - 3 * k), (int) (pw + 2 * pad), (int) (19 * k), i == 1 ? 0x80FFFFFF : 0x60FFFFFF);
				CsUi.text(g, parts[i], cx + pad, y, size, h, i == 1 ? 0xFFFFFFFF : cs ? 0xFF8FC8FF : 0xFFEABE54, -1);
				cx += pw + 2 * pad;
			} else {
				CsUi.text(g, parts[i], cx, y, size, h, 0xFFBFBFBF, -1);
				cx += pw;
			}
		}
	}
}
