package dev.countercraft.client.loadout;

import dev.countercraft.client.agent.AgentClient;
import dev.countercraft.client.agent.AgentPip;
import dev.countercraft.gun.CsWeapon;
import dev.countercraft.net.BuyPayload;
import dev.countercraft.team.Loadouts;
import dev.countercraft.team.Teams;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * CS2's buy menu (panorama buymenu.xml / buymenu.css): the player's agent posed full screen through CS2's buy menu camera
 * (holding the hovered weapon, CS2's buy menu clips), the 950 px panel on the left with the money bar and five columns
 * (Equipment, Pistols, Mid-Tier, Rifles, Grenades; 18/18/22/24/18 %) of 90 px item buttons (key, name, icon,
 * price), the hovered item's info box, and the nav bar. Keys like CS2: a number picks a column, then a number buys.
 * Columns come from the current team's loadout ({@link LoadoutConfig}), weapons named with your equipped finish. The money
 * bar shows your cash ({@link dev.countercraft.team.Economy}; creative mode: free buy); what you can't afford is dimmed
 * with its price in red. CS2's sell back (Refunds): what you bought this round and haven't used shows "Refund" and its
 * price back; right-click it to refund it, or "Refund All" (sellbackall, Delete) in the nav bar. Rebuy / Autobuy and the
 * ground weapons list aren't there yet.
 */
public final class BuyMenuScreen extends Screen {
	private static final String[] TITLES = {"EQUIPMENT", "PISTOLS", "MID-TIER", "RIFLES", "GRENADES"};
	private static final float[] WIDTHS = {0.18f, 0.18f, 0.22f, 0.24f, 0.18f};

	private final List<List<String>> columns = new ArrayList<>();
	private final String team;
	private int category = -1;
	private String hovered;
	private String shown;
	private long shownSince;
	private String purchased;
	private long purchasedAt;

	public BuyMenuScreen() {
		super(Component.literal("Buy Menu"));
		var mc = net.minecraft.client.Minecraft.getInstance();
		team = mc.player == null ? Teams.T : Teams.of(mc.player);
		List<String> equipment = new ArrayList<>();
		for (Loadouts.Fixed f : Loadouts.EQUIPMENT) {
			if (f.teams().contains(team)) {
				equipment.add(f.item());
			}
		}
		for (Loadouts.Fixed f : Loadouts.AMMO) {
			equipment.add(f.item());
		}
		columns.add(equipment);
		columns.add(slots("secondary0", "secondary1", "secondary2", "secondary3", "secondary4"));
		columns.add(slots("smg0", "smg1", "smg2", "smg3", "smg4"));
		columns.add(slots("rifle0", "rifle1", "rifle2", "rifle3", "rifle4"));
		List<String> grenades = new ArrayList<>();
		for (Loadouts.Fixed f : Loadouts.GRENADES) {
			if (f.teams().contains(team)) {
				grenades.add(f.item());
			}
		}
		columns.add(grenades);
		// what the agent may be shown holding (and with which finish): load it in the background now, not on hover
		for (List<String> c : columns) {
			for (String item : c) {
				if (item.startsWith("weapon_")) {
					String id = Loadouts.modId(item);
					dev.countercraft.client.GunClient.prefetch(id);
					dev.countercraft.client.GunClient.prefetch(finish(id));
				}
			}
		}
	}

	private List<String> slots(String... names) {
		List<String> out = new ArrayList<>();
		for (String s : names) {
			String item = LoadoutConfig.get(team, s);
			if (item != null) {
				out.add(item);
			}
		}
		return out;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void tick() {
		super.tick();
		// Counter-Craft: the menu closes when the buy phase ends, like CS2's buy time
		if (minecraft.player != null && !minecraft.player.isCreative() && !dev.countercraft.client.hud.RoundCycle.buyPhase()) {
			onClose();
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		// CS2 draws its own background (below)
	}

	// layout, CS2 1080 px units scaled by k
	private float k() {
		return CsUi.k(height);
	}

	private float left() {
		// buymenu__contents: the 950 px left panel (+16 margin) and the right column, centred, shifted 50 px left
		return (width - (966 + 382) * k()) / 2 - 50 * k() + 16 * k();
	}

	private float bodyTop() {
		return (180 + 38 + 4) * k();
	}

	/** Column i's box: x, width. */
	private float[] column(int i) {
		float x = left();
		for (int c = 0; c < i; c++) {
			x += WIDTHS[c] * 950 * k();
		}
		return new float[] {x, WIDTHS[i] * 950 * k()};
	}

	/** Item j of column i: x, y, w, h. */
	private float[] button(int i, int j) {
		float k = k();
		float[] col = column(i);
		float x = col[0] + 15 * k;
		float w = col[1] - 30 * k;
		float y = bodyTop() + 15 * k + (28 + 15) * k + j * 100 * k;
		return new float[] {x, y, w, 90 * k};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float k = k();
		// CS2's hud-colorize-wash: the menu's text and icons take the team colour
		int tc = Teams.CT.equals(team) ? CsUi.CT_COLOR : CsUi.T_COLOR;
		int rgb = tc & 0xFFFFFF;
		int dimCol = 0x66000000 | rgb;
		hovered = null;
		for (int i = 0; i < columns.size(); i++) {
			for (int j = 0; j < columns.get(i).size(); j++) {
				float[] b = button(i, j);
				if (CsUi.inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
					hovered = columns.get(i).get(j);
				}
			}
		}
		// CS2: black at 95 %, then the agent through cam_buymenu over the whole screen
		g.fill(0, 0, width, height, 0xF2000000);
		String gun = agentGun();
		if (!gun.equals(shown)) {
			shown = gun;
			shownSince = System.nanoTime();
		}
		String agent = AgentClient.agentFor(team);
		AgentPip.draw(g, agent, gun, finish(gun), AgentPip.clipFor(team, gun), (System.nanoTime() - shownSince) / 1e9f, AgentPip.BUY_MENU, 0, 0, width, height,
				null, null, dev.countercraft.client.agent.AgentClient.gloveFor(team));

		float x0 = left();
		// money / info bar
		CsUi.panel(g, x0, 180 * k, 950 * k, 38 * k);
		g.fill((int) x0, (int) ((180 + 38) * k) - 2, (int) (x0 + 950 * k), (int) ((180 + 38) * k), 0x80000000 | rgb);
		boolean free = minecraft.player == null || dev.countercraft.team.Economy.freeBuy(minecraft.player);
		CsUi.text(g, free ? "FREE BUY" : "$" + dev.countercraft.team.Economy.money(minecraft.player), x0 + 10 * k, (180 + 8) * k, 22, height,
				free ? tc : 0xFF7ED37E, -1);
		if (free) {
			CsUi.text(g, "CREATIVE MODE", x0 + 10 * k + CsUi.textWidth("FREE BUY", 22, height) + 12 * k, (180 + 13) * k, 12, height, 0x99000000 | rgb, -1);
		}
		CsUi.text(g, Teams.CT.equals(team) ? "COUNTER-TERRORIST" : "TERRORIST", x0 + 940 * k, (180 + 11) * k, 14, height,
				Teams.CT.equals(team) ? CsUi.CT_COLOR : CsUi.T_COLOR, 1);
		// Counter-Craft: the buy phase's seconds left (the menu closes when it runs out)
		if (minecraft.level != null && minecraft.level.getLevelData().isHardcore() && !free) {
			long t = Math.floorMod(minecraft.level.getOverworldClockTime(), dev.countercraft.team.DayCycle.DAY);
			int left = (int) Math.max(0, Math.ceil((dev.countercraft.team.DayCycle.BUY_TICKS - t) / 20.0));
			CsUi.text(g, String.format("Buy Time Left: %02d", left), x0 + 475 * k, (180 + 10) * k, 16, height, left <= 10 ? 0xFFE05050 : 0xFFFFFFFF, 0);
		}
		// body
		float top = bodyTop();
		int rows = 0;
		for (List<String> c : columns) {
			rows = Math.max(rows, c.size());
		}
		float bodyH = (15 + 43 + rows * 100 + 10) * k; // as tall as the longest column
		CsUi.panel(g, x0, top, 950 * k, bodyH);
		for (int i = 0; i < columns.size(); i++) {
			float[] col = column(i);
			boolean dim = category >= 0 && category != i;
			int white = dim ? dimCol : tc;
			if (category < 0) {
				CsUi.text(g, Integer.toString(i + 1), col[0] + 15 * k, top + 20 * k, 18, height, dimCol, -1);
			}
			// the title centred in what the number leaves, shrunk to fit the column
			float titleW = col[1] - 2 * 34 * k;
			CsUi.text(g, TITLES[i], col[0] + col[1] / 2, top + 15 * k, CsUi.fitSize(TITLES[i], titleW, 28, height), height, white, 0);
			// a short team-coloured rule under the column title
			float ruleW = 36 * k;
			g.fill((int) (col[0] + col[1] / 2 - ruleW / 2), (int) (top + 47 * k), (int) (col[0] + col[1] / 2 + ruleW / 2), (int) (top + 47 * k) + Math.max(1,
					Math.round(2 * k)), (dim ? 0x40000000 : 0xC0000000) | rgb);
			List<String> items = columns.get(i);
			for (int j = 0; j < items.size(); j++) {
				String item = items.get(j);
				float[] b = button(i, j);
				boolean hover = item.equals(hovered);
				boolean afford = affordable(item);
				float hv = CsUi.hover("buy:" + item, hover && !dim);
				CsUi.card(g, b[0], b[1], b[2], b[3], hv, afford ? rgb : 0xE05050, false);
				// a purchase pulses green (bought) or red (can't afford), fading over half a second
				double since = (System.nanoTime() - purchasedAt) / 1e9;
				if (item.equals(purchased) && since < 0.5) {
					int a = (int) (0x90 * (1 - since / 0.5));
					g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), a << 24 | (purchaseFailed ? 0xB84040 : refunded ? 0xDCB45A : 0x6FB86F));
				}
				boolean owned = owned(item);
				// already carried: greyed out (it can't be bought again)
				int textCol = owned ? 0xFF6E6E6E : dim || !afford ? dimCol : hover ? tc : 0xD8000000 | rgb;
				CsUi.text(g, Integer.toString(j + 1), b[0] + 10 * k, b[1] + 6 * k, 14, height, dim || !(category == i) ? dimCol : tc, -1);
				float nameW = b[2] - 34 * k;
				// long names shrink to fit (down to 10 px) before anything is cut
				String name = CsUi.name(item);
				float nameSize = Math.max(10f, CsUi.fitSize(name, nameW, 14, height));
				CsUi.text(g, CsUi.fit(name, nameW, nameSize, height), b[0] + b[2] - 10 * k, b[1] + 6 * k, nameSize, height, textCol, 1);
				dev.countercraft.client.skins.Skins.Skin fin = item.startsWith("weapon_") ? finish(Loadouts.modId(item)) : null;
				if (fin != null) {
					// your equipped finish under the weapon's name
					CsUi.text(g, CsUi.fit(fin.name(), nameW, 11, height), b[0] + b[2] - 10 * k, b[1] + 22 * k, 11, height, 0xA0000000 | (textCol & 0xFFFFFF), 1);
				}
				CsUi.iconIn(g, item, b[0], b[1] + (fin != null ? 6 * k : 0), b[2], b[3], 0.45f, textCol);
				CsUi.text(g, "$" + price(item), b[0] + b[2] - 10 * k, b[1] + b[3] - 20 * k, 14, height, afford || owned ? textCol : 0xFFE05050, 1);
				int back = refund(item);
				if (back >= 0) {
					// CS2's BuyMenu_Sellback: bought this round and unused, right-click to get the money back
					g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), hover ? 0x30DCB45A : 0x50000000);
					CsUi.text(g, "REFUND", b[0] + 10 * k, b[1] + b[3] - 20 * k, 11, height, 0xFFDCB45A, -1);
				} else if (owned) {
					g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), 0x70000000);
					CsUi.text(g, "OWNED", b[0] + 10 * k, b[1] + b[3] - 20 * k, 11, height, 0xFF6FB86F, -1);
				}
				if (fin != null && dev.countercraft.client.skins.Skins.rarity(fin.rarity()) != null) {
					// the equipped finish's rarity along the bottom, as on CS2's inventory tiles
					g.fill((int) b[0], (int) (b[1] + b[3] - 2 * k), (int) (b[0] + b[2]), (int) (b[1] + b[3]),
							0xC0000000 | dev.countercraft.client.skins.Skins.rarity(fin.rarity()).color() & 0xFFFFFF);
				}
			}
		}
		// item info box (right column)
		if (hovered != null) {
			float ix = x0 + 950 * k + 32 * k;
			float iy = 670 * k;
			CsUi.panel(g, ix, iy, 350 * k, 110 * k);
			g.fill((int) ix, (int) iy, (int) (ix + 350 * k), (int) (iy + Math.max(1, 2 * k)), 0xFF000000 | rgb);
			dev.countercraft.client.skins.Skins.Skin fin = hovered.startsWith("weapon_") ? finish(Loadouts.modId(hovered)) : null;
			String full = fin != null ? fin.fullName(CsUi.name(hovered)) : CsUi.name(hovered);
			CsUi.text(g, CsUi.fit(full.toUpperCase(), 330 * k, 16, height), ix + 10 * k, iy + 10 * k, 16, height, tc, -1);
			if (fin != null && dev.countercraft.client.skins.Skins.rarity(fin.rarity()) != null) {
				g.fill((int) ix, (int) (iy + 110 * k - 3 * k), (int) (ix + 350 * k), (int) (iy + 110 * k), dev.countercraft.client.skins.Skins.rarity(fin.rarity()).color());
			}
			boolean afford = affordable(hovered);
			CsUi.text(g, "Price: $" + price(hovered), ix + 10 * k, iy + 40 * k, 14, height, afford ? 0xBF000000 | rgb : 0xFFE05050, -1);
			int back = refund(hovered);
			if (back >= 0) {
				CsUi.text(g, back > 0 ? "Right-click to refund: +$" + back : "Right-click to refund", ix + 10 * k, iy + 64 * k, 14, height, 0xFFDCB45A, -1);
			} else if (!afford) {
				CsUi.text(g, "Insufficient funds", ix + 10 * k, iy + 64 * k, 14, height, 0xFFE05050, -1);
			}
		}
		// nav bar
		float navY = height - 56 * k;
		g.fill(0, (int) navY, width, (int) navY + 1, 0x0DFFFFFF);
		boolean backHover = mouseY >= navY && mouseX >= width - 200 * k;
		CsUi.text(g, "BACK", width - 40 * k, navY + 16 * k, 24, height, backHover ? tc : 0xBF000000 | rgb, 1);
		// CS2's BuyMenu_Sellbackall: "[key] Refund All"
		String all = refundAllLabel();
		boolean allHover = mouseY >= navY && mouseX >= 40 * k && mouseX < 40 * k + CsUi.textWidth(all, 18, height);
		boolean anyBack = anyRefund();
		CsUi.text(g, all, 40 * k, navY + 20 * k, 18, height, !anyBack ? 0x40000000 | rgb : allHover ? tc : 0xBF000000 | rgb, -1);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private static String refundAllLabel() {
		return "[" + dev.countercraft.client.GunKeys.SELL_BACK_ALL.getTranslatedKeyMessage().getString().toUpperCase() + "] REFUND ALL";
	}

	/** The weapon the agent holds: the hovered gun, else the gun in hand, else the first rifle. */
	private String agentGun() {
		if (hovered != null && hovered.startsWith("weapon_") && AgentPip.clipFor(team, Loadouts.modId(hovered)) != null) {
			return Loadouts.modId(hovered);
		}
		if (shown != null && hovered == null && !shown.isEmpty()) {
			return shown;
		}
		var p = minecraft.player;
		if (p != null && p.getMainHandItem().getItem() instanceof CsWeapon w && AgentPip.clipFor(team, w.weaponId()) != null) {
			return w.weaponId();
		}
		String rifle = LoadoutConfig.get(team, "rifle0");
		return rifle == null ? "ak47" : Loadouts.modId(rifle);
	}

	/** Your equipped finish of a gun on this team, or null. */
	private dev.countercraft.client.skins.Skins.Skin finish(String gun) {
		return dev.countercraft.client.skins.Skins.get(gun, LoadoutConfig.skin(team, gun));
	}

	/** What this item costs you now (Kevlar + Helmet with full kevlar: just the helmet, like CS2). */
	private int price(String item) {
		String ammoSlot = dev.countercraft.team.ExtraAmmo.slotOf(item);
		if (ammoSlot != null && minecraft.player != null) {
			int p = dev.countercraft.team.ExtraAmmo.price(minecraft.player, ammoSlot);
			return p > 0 ? p : Loadouts.price(item); // one magazine for the gun in that slot
		}
		if (item.equals("item_assaultsuit") && minecraft.player != null) {
			dev.countercraft.Combat.Kevlar kev = minecraft.player.getAttached(dev.countercraft.Combat.KEVLAR);
			if (kev != null && kev.armor() >= 100 && !kev.helmet()) {
				return Loadouts.HELMET_PRICE;
			}
		}
		return Loadouts.price(item);
	}

	/** What a refund of this item gives back now, or -1 if it can't be refunded (Refunds, synced from the server). */
	private int refund(String item) {
		var list = minecraft.player == null ? null : minecraft.player.getAttached(dev.countercraft.team.Refunds.REFUNDABLE);
		if (list != null) {
			for (int i = list.size() - 1; i >= 0; i--) {
				if (list.get(i).item().equals(item)) {
					return list.get(i).price();
				}
			}
		}
		return -1;
	}

	private boolean anyRefund() {
		var list = minecraft.player == null ? null : minecraft.player.getAttached(dev.countercraft.team.Refunds.REFUNDABLE);
		return list != null && !list.isEmpty();
	}

	private void sellBack(String item) {
		if (item == null) {
			return;
		}
		purchased = item;
		purchasedAt = System.nanoTime();
		purchaseFailed = false;
		refunded = true;
		ClientPlayNetworking.send(new dev.countercraft.net.RefundPayload(item));
	}

	/** Already carried: a weapon or grenade in your CS slots (grenades: as many as CS2 lets you carry). */
	private boolean owned(String item) {
		var p = minecraft.player;
		if (p == null || !item.startsWith("weapon_")) {
			return false;
		}
		String id = Loadouts.modId(item);
		for (net.minecraft.world.item.ItemStack st : dev.countercraft.team.CsInventory.of(p).items) {
			if (st.getItem() instanceof CsWeapon w && w.weaponId().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private boolean affordable(String item) {
		return minecraft.player == null || dev.countercraft.team.Economy.canAfford(minecraft.player, price(item));
	}

	private boolean purchaseFailed;
	/** The last pulse was a refund (gold, not green). */
	private boolean refunded;

	private void buy(String item) {
		refunded = false;
		purchaseFailed = !affordable(item);
		purchased = item;
		purchasedAt = System.nanoTime();
		if (purchaseFailed) {
			return; // CS2 refuses the purchase (the server would too)
		}
		ClientPlayNetworking.send(new BuyPayload(item));
		if (minecraft.player != null) {
			minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.4f);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) {
			for (int i = 0; i < columns.size(); i++) {
				for (int j = 0; j < columns.get(i).size(); j++) {
					float[] b = button(i, j);
					if (CsUi.inside(e.x(), e.y(), b[0], b[1], b[2], b[3]) && refund(columns.get(i).get(j)) >= 0) {
						sellBack(columns.get(i).get(j));
						return true;
					}
				}
			}
		}
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && e.y() >= height - 56 * k() && e.x() >= 40 * k()
				&& e.x() < 40 * k() + CsUi.textWidth(refundAllLabel(), 18, height) && anyRefund()) {
			sellBack(dev.countercraft.team.Refunds.ALL);
			return true;
		}
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = 0; i < columns.size(); i++) {
				for (int j = 0; j < columns.get(i).size(); j++) {
					float[] b = button(i, j);
					if (CsUi.inside(e.x(), e.y(), b[0], b[1], b[2], b[3])) {
						buy(columns.get(i).get(j));
						return true;
					}
				}
			}
			if (e.y() >= height - 56 * k() && e.x() >= width - 200 * k()) {
				onClose();
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		int key = e.key();
		if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
			int n = key - InputConstants.KEY_1;
			if (category < 0) {
				if (n < columns.size()) {
					category = n;
				}
			} else if (n < columns.get(category).size()) {
				buy(columns.get(category).get(n));
				category = -1;
			}
			return true;
		}
		if (key == InputConstants.KEY_BACKSPACE && category >= 0) {
			category = -1;
			return true;
		}
		if (dev.countercraft.client.GunKeys.SELL_BACK_ALL.matches(e)) {
			if (anyRefund()) {
				sellBack(dev.countercraft.team.Refunds.ALL);
			}
			return true;
		}
		if (dev.countercraft.client.GunKeys.BUY.matches(e)) {
			onClose();
			return true;
		}
		return super.keyPressed(e);
	}
}
