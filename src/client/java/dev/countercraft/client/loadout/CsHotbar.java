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
 * 4 cycles the grenades, 5 Zeus), lastinv (Q: the weapon held before) and scrolling through the filled slots, and CS2's
 * weapon list (panorama hudweaponselection) drawn instead of the hotbar: one row per slot at the right edge, the
 * selected weapon's icon bigger and brighter with its name, in the team colour, easing between selections.
 */
public final class CsHotbar {
	/** CS2 rows: key 1-5 and the CS slots they hold. */
	private static final int[][] ROWS = {{CsInventory.PRIMARY}, {CsInventory.SECONDARY}, {CsInventory.KNIFE},
			{CsInventory.GRENADE0, CsInventory.GRENADE0 + 1, CsInventory.GRENADE0 + 2, CsInventory.GRENADE0 + 3}, {CsInventory.ZEUS}};
	/** Scroll order through the CS slots. */
	private static final int[] ORDER = {0, 1, 2, 3, 4, 5, 6, 7};
	/** The CS slot selected now and the one before it (lastinv), or -1. */
	private static int current = -1;
	private static int previous = -1;

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
		for (var e : GunKeys.GRENADE_KEYS.entrySet()) {
			boolean pressed = false;
			while (e.getKey().consumeClick()) {
				pressed = true;
			}
			if (pressed && mc.gui.screen() == null) {
				selectGrenade(mc, e.getValue());
			}
		}
		if (!active()) {
			while (GunKeys.LAST_WEAPON.consumeClick()) {
				// CS hotbar only (here Q is Minecraft's Drop)
			}
			current = previous = -1;
			return;
		}
		while (mc.options.keyDrop.consumeClick()) {
			// the CS hotbar drops with G (CS2's drop); Minecraft's Drop key is lastinv's Q here
		}
		CsInventory.Data d = CsInventory.of(mc.player);
		int sel = mc.player.getInventory().getSelectedSlot();
		if (sel != current) {
			previous = current;
			current = sel;
		}
		while (GunKeys.LAST_WEAPON.consumeClick()) {
			if (mc.gui.screen() == null && previous >= 0 && previous < ORDER.length && previous != sel && !d.get(previous).isEmpty()) {
				mc.player.getInventory().setSelectedSlot(previous);
			}
		}
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

	/** A grenade key: the first slot holding one of {@code ids}, in the CS slots (CS mode) or the Minecraft hotbar. */
	private static void selectGrenade(Minecraft mc, java.util.List<String> ids) {
		boolean cs = active();
		int slots = cs ? ORDER.length : 9;
		for (String id : ids) {
			for (int s = 0; s < slots; s++) {
				ItemStack st = cs ? CsInventory.of(mc.player).get(s) : mc.player.getInventory().getItem(s);
				if (st.getItem() instanceof dev.countercraft.gun.GrenadeItem g && g.weaponId().equals(id)) {
					mc.player.getInventory().setSelectedSlot(s);
					return;
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

	/**
	 * CS2's weapon list in place of the hotbar. The selected row sits on a dark band fading in from the left with a team
	 * coloured edge; icons grow and brighten into the selection and fade back out of it. With CC2 Options > HUD > CS Hotbar
	 * Rarity Colours, each finish's rarity colour underlines its icon.
	 */
	public static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		float k = CsUi.k(h);
		int tc = Teams.CT.equals(Teams.of(mc.player)) ? CsUi.CT_COLOR : CsUi.T_COLOR;
		boolean rarity = dev.countercraft.client.options.CcOptions.hotbarRarity();
		CsInventory.Data d = CsInventory.of(mc.player);
		int sel = mc.player.getInventory().getSelectedSlot();
		float rowH = 64 * k;
		float right = w - 24 * k;
		float y = h * 0.62f - ROWS.length * rowH / 2;
		dev.countercraft.client.hud.HudSpace.mark(dev.countercraft.client.hud.HudSpace.Panel.CS_HOTBAR, y, y + ROWS.length * rowH);
		for (int r = 0; r < ROWS.length; r++) {
			boolean any = false;
			float rowSel = 0f;
			for (int s : ROWS[r]) {
				if (!d.get(s).isEmpty()) {
					any = true;
					rowSel = Math.max(rowSel, CsUi.hover("cs_hotbar_row" + s, s == sel));
				}
			}
			if (!any) {
				for (int s : ROWS[r]) {
					CsUi.hover("cs_hotbar_row" + s, false);
				}
				continue;
			}
			if (rowSel > 0f) {
				band(g, right - 420 * k, y + 4 * k, right + 16 * k, y + rowH - 4 * k, rowSel, tc);
			}
			float x = right - 24 * k;
			String selectedName = null;
			for (int s : ROWS[r]) {
				ItemStack st = d.get(s);
				if (st.isEmpty()) {
					continue;
				}
				float e = CsUi.hover("cs_hotbar_icon" + s, s == sel);
				String item = itemName(st);
				int[] sz = CsUi.iconSize(item);
				if (sz == null) {
					continue;
				}
				float ih = 61.5f * k * (0.7f + 0.2f * e);
				float iw = ih * sz[0] / sz[1];
				int alpha = Math.round(0xA0 + 0x5F * e);
				int col = alpha << 24 | (tc & 0xFFFFFF);
				dev.countercraft.client.skins.Skins.Rarity rr = rarity ? rarityOf(st) : null;
				for (int n = 0; n < Math.max(1, st.getCount()); n++) {
					// two flashbangs show as two icons, as in CS2
					float iy = y + (rowH - ih) / 2;
					CsUi.image(g, CsUi.icon(item), x - iw, iy, iw, ih, sz[0], sz[1], col);
					if (rr != null) {
						int under = Math.round(0x70 + 0x8F * e) << 24 | (rr.color() & 0xFFFFFF);
						g.fill(Math.round(x - iw), Math.round(iy + ih + 1 * k), Math.round(x), Math.round(iy + ih + 1 * k + Math.max(1, 3 * k)), under);
					}
					x -= iw + 8 * k;
				}
				if (s == sel) {
					selectedName = displayName(st);
				}
			}
			if (selectedName != null) {
				// left of the whole row: in the grenade row the other grenades' icons sit left of the selected one
				CsUi.text(g, selectedName, x - 4 * k, y + rowH / 2 - 7 * k, 16, h, tc, 1);
			}
			int numAlpha = Math.round(0xA0 + 0x5F * rowSel);
			CsUi.text(g, Integer.toString(r + 1), right, y + 6 * k, 16, h, numAlpha << 24 | (rowSel > 0.5f ? tc : 0xA0A0A0) & 0xFFFFFF, 1);
			y += rowH;
		}
	}

	/** The selected row's backdrop: dark, fading in from the left, with a team-coloured edge on the right; {@code a} 0..1. */
	private static void band(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float a, int tc) {
		int steps = 24;
		float sw = (x1 - x0) / steps;
		for (int i = 0; i < steps; i++) {
			float t = (i + 1) / (float) steps;
			int alpha = Math.round(0x90 * a * t * t);
			g.fill(Math.round(x0 + i * sw), Math.round(y0), Math.round(x0 + (i + 1) * sw), Math.round(y1), alpha << 24);
		}
		int edge = Math.max(1, Math.round((y1 - y0) / 20f));
		g.fill(Math.round(x1 - edge * 2), Math.round(y0), Math.round(x1), Math.round(y1), Math.round(0xFF * a) << 24 | (tc & 0xFFFFFF));
	}

	/** The rarity of the finish on a weapon, or null (no finish, or none known). */
	private static dev.countercraft.client.skins.Skins.Rarity rarityOf(ItemStack st) {
		dev.countercraft.client.skins.Skins.Skin sk = dev.countercraft.client.skins.Skins.of(st);
		return sk == null ? null : dev.countercraft.client.skins.Skins.rarity(sk.rarity());
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
