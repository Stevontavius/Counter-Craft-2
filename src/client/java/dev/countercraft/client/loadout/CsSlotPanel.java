package dev.countercraft.client.loadout;

import dev.countercraft.team.CsInventory;
import dev.countercraft.team.CsSlots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The CS slot column beside the inventory (survival and creative): a vanilla-style panel with 8 slot frames and a faint
 * CS2 icon in each empty slot (primary, pistol, knife, grenades, Zeus). Drawn after the container background, before
 * the items. The knife slot is darker: it's set by the loadout and can't be moved.
 */
public final class CsSlotPanel {
	private static final String[] HINTS = {"weapon_ak47", "weapon_deagle", "knife", "weapon_hegrenade", "weapon_flashbang", "weapon_smokegrenade",
			"weapon_molotov", "weapon_taser"};

	private CsSlotPanel() {
	}

	public static void draw(GuiGraphicsExtractor g, int left, int top) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		int x0 = left + CsSlots.X - 4;
		int y0 = top + CsSlots.Y - 4;
		int w = 18 + 6;
		int h = CsSlots.ROWS * 18 + 6;
		// vanilla panel: white / grey bevel on light grey
		g.fill(x0, y0, x0 + w, y0 + h, 0xFF000000);
		g.fill(x0 + 1, y0 + 1, x0 + w - 1, y0 + h - 1, 0xFFC6C6C6);
		g.fill(x0 + 1, y0 + 1, x0 + w - 2, y0 + 3, 0xFFFFFFFF);
		g.fill(x0 + 1, y0 + 1, x0 + 3, y0 + h - 2, 0xFFFFFFFF);
		g.fill(x0 + 3, y0 + h - 3, x0 + w - 1, y0 + h - 1, 0xFF555555);
		g.fill(x0 + w - 3, y0 + 3, x0 + w - 1, y0 + h - 1, 0xFF555555);
		CsInventory.Data d = CsInventory.of(mc.player);
		for (int i = 0; i < CsInventory.MENU_SLOTS; i++) {
			if (i == CsInventory.KNIFE) {
				continue; // hidden: the knife only changes in the loadout
			}
			int sx = left + CsSlots.X - 1;
			int sy = top + CsSlots.Y + CsSlots.row(i) * 18 - 1;
			g.fill(sx, sy, sx + 18, sy + 18, 0xFF373737);
			g.fill(sx + 1, sy + 1, sx + 18, sy + 18, 0xFFFFFFFF);
			g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF8B8B8B);
			if (d.get(i).isEmpty()) {
				CsUi.iconIn(g, HINTS[i], sx + 1, sy + 1, 16, 16, 0.6f, 0x50FFFFFF);
			}
		}
	}
}
