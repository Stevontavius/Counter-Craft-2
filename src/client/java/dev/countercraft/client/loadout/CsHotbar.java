package dev.countercraft.client.loadout;

import dev.countercraft.client.GunKeys;
import dev.countercraft.net.CsModePayload;
import dev.countercraft.team.CsInventory;
import dev.countercraft.team.Teams;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Client side of CS mode ({@link CsInventory}): the toggle key, CS2's slot keys (1 primary, 2 pistol, 3 knife,
 * 4 cycles the grenades, 5 Zeus) and scrolling through the filled slots, and CS2's weapon list (panorama
 * hudweaponselection) drawn instead of the hotbar: one row per slot at the right edge, the selected weapon's icon bigger
 * and brighter with its name, in the team colour.
 */
public final class CsHotbar {
	/** CS2 rows: key 1-5 and the CS slots they hold. */
	private static final int[][] ROWS = {{CsInventory.PRIMARY}, {CsInventory.SECONDARY}, {CsInventory.KNIFE},
			{CsInventory.GRENADE0, CsInventory.GRENADE0 + 1, CsInventory.GRENADE0 + 2, CsInventory.GRENADE0 + 3}, {CsInventory.ZEUS}};
	/** Scroll order through the CS slots. */
	private static final int[] ORDER = {0, 1, 2, 3, 4, 5, 6, 7};

	private CsHotbar() {
	}

	public static boolean active() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && CsInventory.active(mc.player);
	}

	/** Start of client tick, before vanilla reads the hotbar keys. */
	public static void startTick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		while (GunKeys.CS_MODE.consumeClick()) {
			if (mc.gui.screen() == null) {
				ClientPlayNetworking.send(CsModePayload.INSTANCE);
			}
		}
		if (!active()) {
			return;
		}
		CsInventory.Data d = CsInventory.of(mc.player);
		var keys = mc.options.keyHotbarSlots;
		for (int i = 0; i < keys.length; i++) {
			while (keys[i].consumeClick()) {
				if (mc.gui.screen() != null || i >= ROWS.length) {
					continue;
				}
				int target = pick(d, i, mc.player.getInventory().getSelectedSlot());
				if (target >= 0) {
					mc.player.getInventory().setSelectedSlot(target);
				}
			}
		}
	}

	/** The slot key {@code row} selects: its (next) filled slot, or -1 if it has none. */
	private static int pick(CsInventory.Data d, int row, int current) {
		int[] slots = ROWS[row];
		int start = 0;
		for (int k = 0; k < slots.length; k++) {
			if (slots[k] == current) {
				start = k + 1; // pressing grenades again cycles to the next one
			}
		}
		for (int n = 0; n < slots.length; n++) {
			int s = slots[(start + n) % slots.length];
			if (!d.get(s).isEmpty()) {
				return s;
			}
		}
		return -1;
	}

	/** Mouse wheel in CS mode: the next / previous filled slot. Returns true if handled. */
	public static boolean scroll(double dy) {
		Minecraft mc = Minecraft.getInstance();
		if (!active() || mc.gui.screen() != null || dy == 0) {
			return false;
		}
		CsInventory.Data d = CsInventory.of(mc.player);
		int cur = mc.player.getInventory().getSelectedSlot();
		int step = dy > 0 ? -1 : 1;
		for (int n = 1; n <= ORDER.length; n++) {
			int s = Math.floorMod(cur + step * n, ORDER.length);
			if (!d.get(s).isEmpty()) {
				mc.player.getInventory().setSelectedSlot(s);
				break;
			}
		}
		return true;
	}

	/** CS2's weapon list in place of the hotbar. */
	public static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		float k = CsUi.k(h);
		int tc = Teams.CT.equals(Teams.of(mc.player)) ? CsUi.CT_COLOR : CsUi.T_COLOR;
		CsInventory.Data d = CsInventory.of(mc.player);
		int sel = mc.player.getInventory().getSelectedSlot();
		float rowH = 64 * k;
		float right = w - 24 * k;
		float y = h * 0.62f - ROWS.length * rowH / 2;
		for (int r = 0; r < ROWS.length; r++) {
			float x = right - 24 * k;
			boolean rowSelected = false;
			boolean any = false;
			String selectedName = null;
			for (int s : ROWS[r]) {
				ItemStack st = d.get(s);
				if (st.isEmpty()) {
					continue;
				}
				any = true;
				boolean selected = s == sel;
				rowSelected |= selected;
				String item = itemName(st);
				int[] sz = CsUi.iconSize(item);
				if (sz == null) {
					continue;
				}
				float ih = 61.5f * k * (selected ? 0.9f : 0.7f);
				float iw = ih * sz[0] / sz[1];
				int col = selected ? tc : 0xCC000000 | (tc & 0xFFFFFF);
				CsUi.image(g, CsUi.icon(item), x - iw, y + (rowH - ih) / 2, iw, ih, sz[0], sz[1], col);
				if (selected) {
					selectedName = displayName(st);
				}
				x -= iw + 8 * k;
			}
			if (selectedName != null) {
				// left of the whole row: in the grenade row the other grenades' icons sit left of the selected one
				CsUi.text(g, selectedName, x - 4 * k, y + rowH / 2 - 7 * k, 16, h, tc, 1);
			}
			if (any) {
				CsUi.text(g, Integer.toString(r + 1), right, y + 6 * k, 16, h, rowSelected ? tc : 0xA0A0A0A0, 1);
				y += rowH;
			}
		}
	}

	/** The weapon's name with its finish, CS2 style ("AK-47 | Redline"). */
	public static String displayName(ItemStack st) {
		String n = st.getHoverName().getString();
		dev.countercraft.client.skins.Skins.Skin sk = dev.countercraft.client.skins.Skins.of(st);
		return sk == null ? n : sk.fullName(n);
	}

	/** CS item name for an icon: "weapon_ak47" style from the stack's item. */
	private static String itemName(ItemStack st) {
		if (st.getItem() instanceof dev.countercraft.gun.CsWeapon w) {
			String id = w.weaponId();
			if (id.startsWith("knife") || id.equals("bayonet")) {
				return CsUi.knifeAsset(id); // CS2's icon names (default CT knife, Talon, Navaja, Bowie)
			}
			return "weapon_" + id;
		}
		return "";
	}
}
