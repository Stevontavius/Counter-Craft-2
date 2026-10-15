package dev.countercraft.client.loadout;

import dev.countercraft.client.agent.AgentClient;
import dev.countercraft.client.agent.AgentPip;
import dev.countercraft.team.Loadouts;
import dev.countercraft.team.Teams;
import dev.countercraft.client.skins.Agents;
import dev.countercraft.client.skins.Skins;
import dev.countercraft.client.skins.SkinsConfig;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * CS2's loadout screen (panorama loadout_grid.xml / .css). Top 56 %: the CT and T sections side by side, each shifted
 * 308 px from the centre so they overlap, the selected team in front: an agent preview (332 px, CS2's loadout cameras),
 * the cosmetic row (64 px slots) and the grid: Equipment (60 px, fixed), Pistols (starting pistol + 4), Mid-Tier (5),
 * Rifles (5), Grenades (60 px, fixed), 160 x 98 px slots. Bottom: the item list (192 x 190 px tiles) of what fits the
 * selected slot; clicking one equips it there (a weapon already in the loadout swaps places, like CS2). The buy menu
 * shows these slots. Cosmetic slots (agent, gloves, knife, music kit) show CS2's defaults (the only ones for now).
 */
public final class LoadoutScreen extends Screen {
	private static final String[][] GRID = {
			{"secondary0", "secondary1", "secondary2", "secondary3", "secondary4"},
			{"smg0", "smg1", "smg2", "smg3", "smg4"},
			{"rifle0", "rifle1", "rifle2", "rifle3", "rifle4"}};
	private static final String[] GRID_TITLES = {"PISTOLS", "MID-TIER", "RIFLES"};
	private static final String[] ROW = {"customplayer", "clothing_hands", "melee", "musickit"};
	private static final String[] ROW_NAMES = {"Agent", "Gloves", "Knife", "Music Kit"};

	private final Screen parent;
	private String team = Teams.CT;
	private String slot = "secondary0";
	private String hoveredItem;
	private String hoveredSlot;
	/** The cosmetic row slot under the mouse (index into ROW), or -1. */
	private int hoveredRow = -1;
	/** CS2's item list filters (panorama id-loadout-filter-group / -itemdef / -sort). */
	private String filterGroup = "all";
	private String filterItem = "all";
	private static String sort = "inv_sort_slot";
	/** Open dropdown: 0 none, 1 group, 2 weapon, 3 sort. */
	private int dropdown;
	private String hoveredOption;
	private float scroll;
	/** The item list's search text (CS2 inventory search, inv_search_placeholder "Search Items"). */
	private String query = "";
	private net.minecraft.client.gui.components.EditBox search;
	private long poseSince = System.nanoTime();
	private String posed = "";

	public LoadoutScreen(Screen parent) {
		super(Component.literal("Loadout"));
		this.parent = parent;
		if (net.minecraft.client.Minecraft.getInstance().player != null) {
			team = Teams.of(net.minecraft.client.Minecraft.getInstance().player);
		}
	}

	@Override
	protected void init() {
		float k = k();
		int h = Math.max(16, Math.round(28 * k));
		int w = Math.round(260 * k);
		int x = Math.round(width - 32 * k - 200 * k - 40 * k) - w;
		search = new net.minecraft.client.gui.components.EditBox(font, x, Math.round(listTop()), w, h, Component.literal("Search Items"));
		search.setHint(Component.literal("Search Items").withColor(0xFF8A8A8A));
		search.setValue(query);
		search.setResponder(text -> {
			query = text;
			scroll = 0;
		});
		addRenderableWidget(search);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xF0101214);
	}

	/**
	 * UI scale: CS2's 1920 x 1080 layout scaled by height, or by width on narrower screens (4:3, 5:4), so both teams'
	 * sections always fit across.
	 */
	private float k() {
		return Math.min(CsUi.k(height), width / 1920f);
	}

	/** The screen height the text helpers scale from (the same scale as {@link #k}). */
	private int uiH() {
		return Math.round(k() * 1080f);
	}

	private float slotsTop() {
		return 64 * k();
	}

	private float slotsHeight() {
		return (height - slotsTop()) * 0.56f;
	}

	/** Section width: toggle (agent 332 + row 64 + margins) 620 + grid (60 + 3 x 160 + 60 + 5 x 16). */
	private static final float SECTION = 620 + 680;

	/** Left edge of a team's section (CS2: centred, CT shifted 308 px left, T 308 px right). */
	private float sectionX(String t) {
		float k = k();
		return width / 2f - SECTION * k / 2 + (Teams.CT.equals(t) ? -308 : 308) * k;
	}

	/** The grid's left edge within a section (CT: grid first, T: toggle first). */
	private float gridX(String t) {
		return sectionX(t) + (Teams.CT.equals(t) ? 0 : 620 * k());
	}

	private float agentX(String t) {
		float k = k();
		return Teams.CT.equals(t) ? sectionX(t) + (680 + 620 - 332) * k : sectionX(t);
	}

	/** Column c (0 equipment, 1-3 grid, 4 grenades): x and width. */
	private float[] column(String t, int c) {
		float k = k();
		float x = gridX(t) + 8 * k;
		for (int i = 0; i < c; i++) {
			x += ((i == 0 ? 60 : 160) + 16) * k;
		}
		return new float[] {x, (c == 0 || c == 4 ? 60 : 160) * k};
	}

	private float columnTop() {
		return slotsTop() + (slotsHeight() - 528 * k()) / 2;
	}

	/** Grid slot (column 1-3, row 0-4): x, y, w, h. */
	private float[] gridSlot(String t, int c, int r) {
		float k = k();
		float[] col = column(t, c);
		return new float[] {col[0], columnTop() + 28 * k + r * 104 * k, col[1], 98 * k};
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		float k = k();
		hoveredItem = null;
		hoveredSlot = null;
		hoveredRow = -1;
		CsUi.text(g, "LOADOUT", 40 * k, 20 * k, 32, uiH(), 0xFFFFFFFF, -1);
		boolean backHover = mouseY < slotsTop() && mouseX > width - 200 * k;
		CsUi.text(g, "BACK", width - 40 * k, 24 * k, 24, uiH(), backHover ? 0xFFFFFFFF : 0xFFBFBFBF, 1);
		g.fill(0, (int) slotsTop(), width, (int) (slotsTop() + slotsHeight()), 0x40000000);
		g.fill(0, (int) (slotsTop() + slotsHeight()), width, (int) (slotsTop() + slotsHeight()) + 1, 0x1AFFFFFF);
		// the other team behind, the selected one in front
		String other = Teams.CT.equals(team) ? Teams.T : Teams.CT;
		section(g, other, mouseX, mouseY, false);
		section(g, team, mouseX, mouseY, true);
		itemList(g, mouseX, mouseY);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	private void section(GuiGraphicsExtractor g, String t, int mouseX, int mouseY, boolean front) {
		float k = k();
		boolean ct = Teams.CT.equals(t);
		int color = ct ? CsUi.CT_COLOR : CsUi.T_COLOR;
		float x = sectionX(t);
		float y0 = slotsTop();
		float h = slotsHeight();
		// CS2: blue (CT) / gold (T) gradient fading towards the other side, with a 4 px edge line
		int base = ct ? 0x6C1D5687 : 0x80645106;
		float w = SECTION * k;
		// solid for the first three quarters, then a fade drawn one GUI pixel column at a time: edges never overlap (an
		// overlap doubles the alpha into a visible stripe)
		int rgbBase = base & 0xFFFFFF;
		int full = base >>> 24;
		int xs = Math.round(x), xe = Math.round(x + w);
		int solidEnd = Math.round(x + w * 0.75f);
		int solidStart = Math.round(x + w * 0.25f);
		if (ct) {
			g.fill(solidStart, (int) y0, xe, (int) (y0 + h), full << 24 | rgbBase);
			for (int px = xs; px < solidStart; px++) {
				float a = (px + 0.5f - x) / (w * 0.25f);
				g.fill(px, (int) y0, px + 1, (int) (y0 + h), Math.round(full * a) << 24 | rgbBase);
			}
		} else {
			g.fill(xs, (int) y0, solidEnd, (int) (y0 + h), full << 24 | rgbBase);
			for (int px = solidEnd; px < xe; px++) {
				float a = 1f - (px + 0.5f - (x + w * 0.75f)) / (w * 0.25f);
				g.fill(px, (int) y0, px + 1, (int) (y0 + h), Math.round(full * Math.max(0f, a)) << 24 | rgbBase);
			}
		}
		g.fill((int) (ct ? x + w - 4 * k : x), (int) y0, (int) (ct ? x + w : x + 4 * k), (int) (y0 + h), (ct ? 0xAD3093EA : 0x8FD6B42D));
		if (!front) {
			g.fill((int) x, (int) y0, (int) (x + w), (int) (y0 + h), 0x80000000);
		}
		// agent preview: holding the selected slot's weapon, the equipped knife on the melee slot, or the first rifle
		float ax = agentX(t);
		String gun;
		Skins.Skin finish;
		if (front && slot.equals("row:melee")) {
			dev.countercraft.team.Cosmetics.Side side = LoadoutConfig.side(t);
			gun = side.knife().isEmpty() ? (ct ? "knife_ct" : "knife_t") : side.knife();
			finish = side.knifeSkin() > 0 ? Skins.get(gun, side.knifeSkin()) : null;
		} else {
			String gunItem = front ? LoadoutConfig.get(t, slot) : null;
			if (gunItem == null) {
				gunItem = LoadoutConfig.get(t, "rifle0");
			}
			gun = gunItem == null ? "ak47" : Loadouts.modId(gunItem);
			finish = Skins.get(gun, LoadoutConfig.skin(t, gun));
		}
		String key = t + gun;
		if (front && !key.equals(posed)) {
			posed = key;
			poseSince = System.nanoTime();
		}
		float time = front ? (System.nanoTime() - poseSince) / 1e9f : 10f;
		AgentPip.draw(g, AgentClient.agentFor(t), gun, finish, AgentPip.clipFor(t, gun), time, ct ? AgentPip.LOADOUT_CT : AgentPip.LOADOUT_T, (int) ax, (int) y0,
				(int) (ax + 332 * k), (int) (y0 + h), null, net.minecraft.world.item.ItemStack.EMPTY, null, AgentClient.gloveFor(t));
		// team title on the agent
		String title = ct ? "CT EQUIPMENT" : "T EQUIPMENT";
		float tw = CsUi.textWidth(title, 20, uiH()) + 16 * k;
		float tx = ax + 166 * k - tw / 2;
		g.fill((int) tx, (int) (y0 + 16 * k), (int) (tx + tw), (int) (y0 + 16 * k + 30 * k), color);
		CsUi.text(g, title, ax + 166 * k, y0 + 22 * k, 20, uiH(), 0xFF000000, 0);
		// cosmetic row
		float rx = ct ? ax - 72 * k : ax + 332 * k + 8 * k;
		float ry = y0 + (h - ROW.length * 69 * k) / 2;
		for (int i = 0; i < ROW.length; i++) {
			float sy = ry + i * 69 * k;
			boolean hover = front && CsUi.inside(mouseX, mouseY, rx, sy, 64 * k, 64 * k);
			boolean selected = front && slot.equals("row:" + ROW[i]);
			CsUi.card(g, rx, sy, 64 * k, 64 * k, CsUi.hover("row:" + t + i, hover), color, selected);
			cosmeticThumb(g, t, ROW[i], rx, sy + 8 * k, 64 * k, 48 * k, true);
			if (hover) {
				hoveredSlot = ROW_NAMES[i] + ": " + cosmeticName(t, i, true);
				hoveredRow = i;
			}
		}
		// equipment column (fixed)
		float ct0 = columnTop();
		equipColumn(g, t, 0, "EQUIPMENT", Loadouts.EQUIPMENT, ct0);
		equipColumn(g, t, 4, "GRENADES", Loadouts.GRENADES, ct0);
		// grid
		for (int c = 0; c < 3; c++) {
			float[] col = column(t, c + 1);
			String colTitle = c == 0 ? "STARTING PISTOL" : GRID_TITLES[c];
			CsUi.text(g, colTitle, col[0], ct0 + 4 * k, CsUi.fitSize(colTitle, col[1], 18, uiH()), uiH(), color, -1);
			for (int r = 0; r < 5; r++) {
				String s = GRID[c][r];
				float[] b = gridSlot(t, c + 1, r);
				if (c == 0 && r == 1) {
					// the other pistols start here: a thin rule in the gap (no room for a label without covering a slot)
					g.fill((int) b[0], (int) (b[1] - 4 * k), (int) (b[0] + b[2]), (int) (b[1] - 4 * k) + 1, 0x60000000 | (color & 0xFFFFFF));
				}
				boolean selected = front && t.equals(team) && s.equals(slot);
				boolean hover = front && CsUi.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
				CsUi.card(g, b[0], b[1], b[2], b[3], CsUi.hover("grid:" + t + s, hover), color, selected);
				String item = LoadoutConfig.get(t, s);
				if (item != null) {
					float ih = b[3] - 22 * k;
					float iw = ih * 4 / 3;
					Skins.Skin fin = Skins.get(Loadouts.modId(item), LoadoutConfig.skin(t, Loadouts.modId(item)));
					if (fin != null && fin.thumb()) {
						CsUi.image(g, fin.thumbnail(), b[0] + (b[2] - iw) / 2, b[1] + (hover ? 0 : 6 * k), iw, ih, 192, 144, 0xFFFFFFFF);
						if (Skins.rarity(fin.rarity()) != null) {
							g.fill((int) b[0], (int) (b[1] + b[3] - 3 * k), (int) (b[0] + b[2]), (int) (b[1] + b[3]), Skins.rarity(fin.rarity()).color());
						}
					} else {
						CsUi.image(g, CsUi.tile(item), b[0] + (b[2] - iw) / 2, b[1] + (hover ? 0 : 6 * k), iw, ih, 256, 192, 0xFFFFFFFF);
					}
					CsUi.text(g, CsUi.name(item), b[0] + 6 * k, b[1] + b[3] - 16 * k, 12, uiH(), color, -1);
					CsUi.text(g, "$" + Loadouts.price(item), b[0] + b[2] - 6 * k, b[1] + b[3] - 16 * k, 12, uiH(), 0xFFBFBFBF, 1);
				}
			}
		}
	}

	private void equipColumn(GuiGraphicsExtractor g, String t, int c, String title, List<Loadouts.Fixed> items, float top) {
		float k = k();
		float[] col = column(t, c);
		CsUi.text(g, title, col[0] + col[1] / 2, top + 4 * k, CsUi.fitSize(title, col[1], 14, uiH()), uiH(), 0x80000000 | 0xFFFFFF, 0);
		List<String> mine = new ArrayList<>();
		for (Loadouts.Fixed f : items) {
			if (f.teams().contains(t) && !f.item().equals("item_defuser")) { // a C4 test item, not a loadout
				mine.add(f.item());
			}
		}
		int n = c == 0 ? 3 : 5;
		float avail = 528 * k - 28 * k;
		float sh = (avail - (n - 1) * 6 * k) / n;
		for (int i = 0; i < n; i++) {
			float sy = top + 28 * k + i * (sh + 6 * k);
			CsUi.card(g, col[0], sy, col[1], sh, 0f, 0xFFFFFF, false);
			if (i < mine.size()) {
				CsUi.iconIn(g, mine.get(i), col[0], sy, col[1], sh, 0.5f, 0xCCFFFFFF);
			}
		}
	}

	/** CS2's filter groups (loadout_grid.xml id-loadout-filter-group; C4, flair and spray: not in the mod). */
	private static final String[][] GROUPS = {{"all", "All Items"}, {"secondary0", "Starting Pistol"}, {"secondary", "Other Pistols"},
			{"smg", "Mid-Tier"}, {"rifle", "Rifles"}, {"customplayer", "Agents"}, {"clothing_hands", "Gloves"}, {"melee", "Melee"},
			{"equipment2", "Zeus"}, {"musickit", "Music Kit"}};
	/** CS2's sort methods that mean something without skins (csgo_english inv_sort_*). */
	private static final String[][] SORTS = {{"inv_sort_slot", "Equip Slot"}, {"inv_sort_alpha", "Alphabetical"}, {"inv_sort_equipped", "Equipped"}};
	private static final String[] WEAPON_GROUPS = {"secondary0", "secondary", "smg", "rifle"};
	private static final net.minecraft.resources.Identifier FILTER_ICON = dev.countercraft.CounterCraft.id("textures/gui/icons/filter.png");
	private static final net.minecraft.resources.Identifier SORT_ICON = dev.countercraft.CounterCraft.id("textures/gui/icons/sort.png");
	private static final net.minecraft.resources.Identifier CANCEL_ICON = dev.countercraft.CounterCraft.id("textures/gui/icons/cancel.png");

	private float listTop() {
		return slotsTop() + slotsHeight() + 8 * k();
	}

	/** Navbar controls: 0 group dropdown, 1 weapon dropdown, 2 clear filters, 3 sort dropdown -> x, y, w, h. */
	private float[] control(int i) {
		float k = k();
		float y = listTop();
		float h = 28 * k;
		return switch (i) {
			case 0 -> new float[] {56 * k, y, 220 * k, h};
			case 1 -> new float[] {286 * k, y, 220 * k, h};
			case 2 -> new float[] {(weaponFilterVisible() ? 516 : 286) * k, y, 28 * k, h};
			default -> new float[] {width - 32 * k - 200 * k, y, 200 * k, h};
		};
	}

	private boolean weaponFilterVisible() {
		return filterGroup.equals("secondary") || filterGroup.equals("smg") || filterGroup.equals("rifle");
	}

	private boolean clearVisible() {
		return !filterGroup.equals("all");
	}

	/** The options of dropdown d: {id, label}. */
	private List<String[]> options(int d) {
		List<String[]> out = new ArrayList<>();
		if (d == 1) {
			for (String[] gr : GROUPS) {
				out.add(gr);
			}
		} else if (d == 2) {
			out.add(new String[] {"all", "All " + groupName(filterGroup).charAt(0) + groupName(filterGroup).substring(1).toLowerCase()});
			List<String> names = new ArrayList<>(Loadouts.options(team, filterGroup));
			names.sort(java.util.Comparator.comparing(CsUi::name));
			for (String item : names) {
				out.add(new String[] {item, CsUi.name(item)});
			}
		} else if (d == 3) {
			for (String[] so : SORTS) {
				out.add(so);
			}
		}
		return out;
	}

	private static String label(List<String[]> opts, String id) {
		for (String[] o : opts) {
			if (o[0].equals(id)) {
				return o[1];
			}
		}
		return opts.isEmpty() ? "" : opts.get(0)[1];
	}

	/** The items the list shows: what the filters let through, sorted. Cosmetics are their row slot's id. */
	private List<String> listItems() {
		List<String> out = new ArrayList<>();
		for (String gr : WEAPON_GROUPS) {
			if (filterGroup.equals("all") || filterGroup.equals(gr)) {
				for (String item : Loadouts.options(team, gr)) {
					if (!out.contains(item) && (filterItem.equals("all") || filterItem.equals(item))) {
						out.add(item);
						// its finishes you have (all of them in skinchanger mode)
						String id = Loadouts.modId(item);
						for (Skins.Skin sk : Skins.forItem(id)) {
							if (SkinsConfig.ownsFinish(id, sk.id())) {
								out.add("skin:" + id + ":" + sk.id());
							}
						}
					}
				}
			}
		}
		for (String c : new String[] {"customplayer", "clothing_hands", "melee", "equipment2"}) {
			if (filterGroup.equals("all") || filterGroup.equals(c)) {
				out.add(c.equals("equipment2") ? "weapon_taser" : c);
				if (c.equals("customplayer")) {
					for (Agents.Agent a : Agents.forTeam(team)) {
						if (SkinsConfig.owns("a:" + a.id()) || a.rarity() == null || "common".equals(a.rarity())) {
							out.add("agent:" + a.id());
						}
					}
				} else if (c.equals("clothing_hands")) {
					for (String type : Skins.gloveTypes().keySet()) {
						for (Skins.Glove gl : Skins.gloves(type)) {
							if (SkinsConfig.owns("g:" + type + ":" + gl.id())) {
								out.add("glove:" + type + ":" + gl.id());
							}
						}
					}
				} else if (c.equals("melee")) {
					for (String knife : KNIVES) {
						boolean any = SkinsConfig.owns("k:" + knife);
						List<String> finishes = new ArrayList<>();
						for (Skins.Skin sk : Skins.forItem(knife)) {
							if (SkinsConfig.ownsFinish(knife, sk.id())) {
								finishes.add("kskin:" + knife + ":" + sk.id());
							}
						}
						if (any) {
							out.add("knife:" + knife);
						}
						out.addAll(finishes);
					}
				}
			}
		}
		if (filterGroup.equals("all") || filterGroup.equals("musickit")) {
			for (String kit : MusicKit.kits().keySet()) {
				if (kit.equals(LoadoutConfig.DEFAULT_MUSIC_KIT) || SkinsConfig.owns("m:" + kit)) {
					out.add("musickit:" + kit);
				}
			}
		}
		if (!query.isBlank()) {
			String q = query.trim().toLowerCase(java.util.Locale.ROOT);
			out.removeIf(item -> !itemName(item).toLowerCase(java.util.Locale.ROOT).contains(q));
		}
		if (sort.equals("inv_sort_alpha")) {
			out.sort(java.util.Comparator.comparing(this::itemName));
		} else if (sort.equals("inv_sort_equipped")) {
			List<String> eq = new ArrayList<>(out.stream().filter(this::isEquipped).toList());
			out.removeAll(eq);
			eq.addAll(out);
			out = eq;
		}
		return out;
	}

	/** The knives CS2 sells as items (not the default T / CT knives). */
	private static final List<String> KNIVES = dev.countercraft.CounterCraft.KNIVES.keySet().stream()
			.filter(k -> !k.equals("knife_t") && !k.equals("knife_ct")).toList();

	private String itemName(String item) {
		if (item.startsWith("musickit:")) {
			return MusicKit.name(item.substring(9));
		}
		if (item.startsWith("skin:") || item.startsWith("kskin:")) {
			Skins.Skin sk = skinOf(item);
			if (sk == null) {
				return item;
			}
			String w = CsUi.name("weapon_" + sk.item());
			return sk.fullName(item.startsWith("kskin:") ? "\u2605 " + w : w);
		}
		if (item.startsWith("knife:")) {
			return "\u2605 " + CsUi.name("weapon_" + item.substring(6));
		}
		if (item.startsWith("glove:")) {
			Skins.Glove gl = gloveOf(item);
			return gl == null ? item : "\u2605 " + Skins.gloveTypes().getOrDefault(gl.type(), gl.type()) + " | " + gl.name();
		}
		if (item.startsWith("agent:")) {
			Agents.Agent a = Agents.get(item.substring(6));
			return a == null ? item : a.name();
		}
		int row = java.util.Arrays.asList(ROW).indexOf(item);
		return row >= 0 ? cosmeticName(team, row, false) : CsUi.name(item);
	}

	/** Tile i of the list: x, y, w, h (192 x 190 like CS2's InventoryItemList), scrolled. */
	private float[] tile(int i) {
		float k = k();
		float tw = 192 * k;
		float th = 190 * k;
		int perRow = Math.max(1, (int) ((width - 48 * k) / tw));
		return new float[] {32 * k + (i % perRow) * tw, listTop() + 40 * k + (i / perRow) * th - scroll, tw - 6 * k, th - 6 * k};
	}

	/** Bottom: CS2's item list with its filter / sort bar; clicking an item equips it. */
	private void itemList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		float k = k();
		float top = listTop();
		hoveredOption = null;
		boolean menuOpen = dropdown != 0;
		// items (clipped below the navbar)
		List<String> items = listItems();
		g.enableScissor(0, (int) (top + 36 * k), width, height);
		for (int i = 0; i < items.size(); i++) {
			String item = items.get(i);
			float[] b = tile(i);
			if (b[1] + b[3] < top || b[1] > height) {
				continue;
			}
			boolean hover = !menuOpen && mouseY > top + 36 * k && CsUi.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			boolean equipped = isEquipped(item);
			CsUi.card(g, b[0], b[1], b[2], b[3], CsUi.hover("item:" + item, hover), Teams.CT.equals(team) ? CsUi.CT_COLOR : CsUi.T_COLOR, false);
			if (equipped) {
				// equipped: a green line along the top of the tile
				g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + Math.max(1, 2 * k)), 0xFF6FB86F);
			}
			float iw = b[2] - 24 * k;
			String rarity = rarityOf(item);
			if (rarity != null && Skins.rarity(rarity) != null) {
				// CS2: the item's rarity as a bar along the bottom of its tile
				g.fill((int) b[0], (int) (b[1] + b[3] - 4 * k), (int) (b[0] + b[2]), (int) (b[1] + b[3]), Skins.rarity(rarity).color());
			}
			if (java.util.Arrays.asList(ROW).contains(item) || item.startsWith("musickit:")) {
				cosmeticThumb(g, team, item, b[0] + 12 * k, b[1] + 14 * k, iw, iw * 0.75f, false);
			} else if (item.startsWith("skin:") || item.startsWith("kskin:")) {
				Skins.Skin sk = skinOf(item);
				CsUi.image(g, sk != null && sk.thumb() ? sk.thumbnail() : CsUi.tile("weapon_" + CsUi.knifeAsset(item.split(":")[1])), b[0] + 12 * k, b[1] + 14 * k, iw,
						iw * 0.75f, 192, 144, 0xFFFFFFFF);
			} else if (item.startsWith("glove:")) {
				Skins.Glove gl = gloveOf(item);
				if (gl != null && gl.thumb()) {
					CsUi.image(g, gl.thumbnail(), b[0] + 12 * k, b[1] + 14 * k, iw, iw * 0.75f, 192, 144, 0xFFFFFFFF);
				}
			} else if (item.startsWith("knife:")) {
				CsUi.image(g, CsUi.tile("weapon_" + CsUi.knifeAsset(item.substring(6))), b[0] + 12 * k, b[1] + 14 * k, iw, iw * 0.75f, 256, 192, 0xFFFFFFFF);
			} else if (item.startsWith("agent:")) {
				Agents.Agent a = Agents.get(item.substring(6));
				if (a != null && a.thumb()) {
					CsUi.image(g, a.thumbnail(), b[0] + 12 * k, b[1] + 14 * k, iw, iw * 0.75f, 192, 144, 0xFFFFFFFF);
				}
			} else {
				CsUi.image(g, CsUi.tile(item), b[0] + 12 * k, b[1] + 14 * k, iw, iw * 0.75f, 256, 192, 0xFFFFFFFF);
			}
			// CS2's tile: the item on one line, its finish below (both cut to the tile), equipped / price in the corner
			String name = itemName(item);
			int bar = name.indexOf(" | ");
			float maxW = b[2] - 20 * k;
			if (bar >= 0) {
				CsUi.text(g, CsUi.fit(name.substring(0, bar), maxW, 12, uiH()), b[0] + 10 * k, b[1] + b[3] - 40 * k, 12, uiH(), 0xFFBFBFBF, -1);
				CsUi.text(g, CsUi.fit(name.substring(bar + 3), maxW, 14, uiH()), b[0] + 10 * k, b[1] + b[3] - 24 * k, 14, uiH(), 0xFFFFFFFF, -1);
			} else {
				CsUi.text(g, CsUi.fit(name, maxW, 14, uiH()), b[0] + 10 * k, b[1] + b[3] - 24 * k, 14, uiH(), 0xFFFFFFFF, -1);
			}
			String sub = equipped ? "EQUIPPED" : Loadouts.price(item) > 0 ? "$" + Loadouts.price(item) : "";
			CsUi.text(g, sub, b[0] + b[2] - 8 * k, b[1] + 6 * k, 12, uiH(), equipped ? 0xFF6FB86F : 0xFFBFBFBF, 1);
			if (hover) {
				hoveredItem = item;
				if (bar >= 0 || !name.equals(CsUi.fit(name, maxW, 14, uiH()))) {
					hoveredSlot = name; // the full name by the cursor
				}
			}
		}
		g.disableScissor();
		if (items.isEmpty() && !query.isBlank()) {
			// CS2 inv_search_no_results
			CsUi.text(g, "Your search " + query.trim() + " did not match any results.", 32 * k, top + 56 * k, 14, uiH(), 0xFFBFBFBF, -1);
		}
		// navbar: filter icon, group, weapon, clear; sort on the right
		CsUi.image(g, FILTER_ICON, 32 * k, top + 6 * k, 16 * k, 16 * k, 56, 56, 0xFFBFBFBF);
		dropButton(g, 0, label(options(1), filterGroup), mouseX, mouseY);
		if (weaponFilterVisible()) {
			dropButton(g, 1, label(options(2), filterItem), mouseX, mouseY);
		}
		if (clearVisible()) {
			float[] c = control(2);
			boolean hover = !menuOpen && CsUi.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
			CsUi.image(g, CANCEL_ICON, c[0] + 4 * k, c[1] + 4 * k, 20 * k, 20 * k, 56, 56, hover ? 0xFFFFFFFF : 0xFFBFBFBF);
			if (hover) {
				hoveredSlot = "Clear filters";
			}
		}
		float[] sc = control(3);
		CsUi.image(g, SORT_ICON, sc[0] - 24 * k, top + 6 * k, 16 * k, 16 * k, 56, 56, 0xFFBFBFBF);
		dropButton(g, 3, label(options(3), sort), mouseX, mouseY);
		// the open menu on top
		if (menuOpen) {
			float[] c = control(dropdown == 2 ? 1 : dropdown == 1 ? 0 : 3);
			List<String[]> opts = options(dropdown);
			float oy = c[1] + c[3];
			g.fill((int) c[0], (int) oy, (int) (c[0] + c[2]), (int) (oy + opts.size() * 26 * k), 0xF5202326);
			for (int i = 0; i < opts.size(); i++) {
				float y = oy + i * 26 * k;
				boolean hover = CsUi.inside(mouseX, mouseY, c[0], y, c[2], 26 * k);
				if (hover) {
					g.fill((int) c[0], (int) y, (int) (c[0] + c[2]), (int) (y + 26 * k), 0x40FFFFFF);
					hoveredOption = opts.get(i)[0];
				}
				CsUi.text(g, opts.get(i)[1], c[0] + 10 * k, y + 6 * k, 14, uiH(), 0xFFFFFFFF, -1);
			}
		}
		if (hoveredSlot != null) {
			CsUi.text(g, hoveredSlot, mouseX + 12, mouseY - 4, 14, uiH(), 0xFFFFFFFF, -1);
		}
	}

	private void dropButton(GuiGraphicsExtractor g, int control, String text, int mouseX, int mouseY) {
		float k = k();
		float[] c = control(control);
		boolean hover = dropdown == 0 && CsUi.inside(mouseX, mouseY, c[0], c[1], c[2], c[3]);
		g.fill((int) c[0], (int) c[1], (int) (c[0] + c[2]), (int) (c[1] + c[3]), hover ? 0x40FFFFFF : 0x26FFFFFF);
		CsUi.text(g, text, c[0] + 10 * k, c[1] + 7 * k, 14, uiH(), 0xFFFFFFFF, -1);
		CsUi.text(g, "\u25BC", c[0] + c[2] - 10 * k, c[1] + 8 * k, 10, uiH(), 0xFFBFBFBF, 1);
	}

	/** A cosmetic's CS2 thumbnail (the team's equipped agent / knife, or CS2's default; the music kit). */
	private void cosmeticThumb(GuiGraphicsExtractor g, String t, String row, float x, float y, float w, float h, boolean equipped) {
		boolean ct = Teams.CT.equals(t);
		dev.countercraft.team.Cosmetics.Side side = equipped ? LoadoutConfig.side(t) : dev.countercraft.team.Cosmetics.Side.DEFAULT;
		if (row.equals("customplayer") && !side.agent().isEmpty()) {
			Agents.Agent a = Agents.byModel(side.agent(), t);
			if (a != null && a.thumb()) {
				fit(g, a.thumbnail(), x, y, w, h, 192, 144);
				return;
			}
		}
		if (row.equals("clothing_hands") && !side.gloves().isEmpty()) {
			Skins.Glove gl = Skins.glove(side.gloves(), side.gloveSkin());
			if (gl != null && gl.thumb()) {
				fit(g, gl.thumbnail(), x, y, w, h, 192, 144);
				return;
			}
		}
		if (row.equals("melee") && !side.knife().isEmpty()) {
			Skins.Skin sk = side.knifeSkin() > 0 ? Skins.get(side.knife(), side.knifeSkin()) : null;
			if (sk != null && sk.thumb()) {
				fit(g, sk.thumbnail(), x, y, w, h, 192, 144);
			} else {
				fit(g, CsUi.tile("weapon_" + CsUi.knifeAsset(side.knife())), x, y, w, h, 256, 192);
			}
			return;
		}
		String tex = switch (row) {
			case "customplayer" -> ct ? "cosmetics/customplayer_ctm_sas" : "cosmetics/customplayer_tm_phoenix";
			case "clothing_hands" -> ct ? "weapons/ct_gloves" : "weapons/t_gloves";
			case "melee" -> ct ? "weapons/weapon_knife" : "weapons/weapon_knife_t";
			default -> "cosmetics/" + (row.startsWith("musickit:") ? row.substring(9) : LoadoutConfig.musicKit());
		};
		if (row.equals("musickit") && !equipped) {
			tex = "cosmetics/" + LoadoutConfig.DEFAULT_MUSIC_KIT;
		}
		int th = row.startsWith("musickit") ? 198 : 192;
		float ih = Math.min(h, w * th / 256f);
		float iw = ih * 256f / th;
		CsUi.image(g, dev.countercraft.CounterCraft.id("textures/gui/" + tex + ".png"), x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, 256, th, 0xFFFFFFFF);
	}

	private static void fit(GuiGraphicsExtractor g, net.minecraft.resources.Identifier tex, float x, float y, float w, float h, int tw, int th) {
		float ih = Math.min(h, w * th / tw);
		float iw = ih * tw / th;
		CsUi.image(g, tex, x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, tw, th, 0xFFFFFFFF);
	}

	private static Skins.Skin skinOf(String item) {
		String[] p = item.split(":");
		return p.length < 3 ? null : Skins.get(p[1], Integer.parseInt(p[2]));
	}

	private static Skins.Glove gloveOf(String item) {
		String[] p = item.split(":");
		return p.length < 3 ? null : Skins.glove(p[1], Integer.parseInt(p[2]));
	}

	private static String rarityOf(String item) {
		if (item.startsWith("glove:")) {
			Skins.Glove gl = gloveOf(item);
			return gl == null ? null : gl.rarity();
		}
		if (item.startsWith("skin:") || item.startsWith("kskin:")) {
			Skins.Skin sk = skinOf(item);
			return sk == null ? null : sk.rarity();
		}
		if (item.startsWith("agent:")) {
			Agents.Agent a = Agents.get(item.substring(6));
			return a == null ? null : a.rarity();
		}
		return item.startsWith("knife:") ? "ancient" : null;
	}

	/** The equipped cosmetic of a row slot. */
	private static String cosmeticName(String t, int i, boolean equipped) {
		boolean ct = Teams.CT.equals(t);
		dev.countercraft.team.Cosmetics.Side side = equipped ? LoadoutConfig.side(t) : dev.countercraft.team.Cosmetics.Side.DEFAULT;
		if (i == 0 && !side.agent().isEmpty() && Agents.byModel(side.agent(), t) != null) {
			return Agents.byModel(side.agent(), t).name();
		}
		if (i == 1 && !side.gloves().isEmpty() && Skins.glove(side.gloves(), side.gloveSkin()) != null) {
			Skins.Glove gl = Skins.glove(side.gloves(), side.gloveSkin());
			return "\u2605 " + Skins.gloveTypes().getOrDefault(gl.type(), gl.type()) + " | " + gl.name();
		}
		if (i == 2 && !side.knife().isEmpty()) {
			Skins.Skin sk = side.knifeSkin() > 0 ? Skins.get(side.knife(), side.knifeSkin()) : null;
			String n = "\u2605 " + CsUi.name("weapon_" + side.knife());
			return sk == null ? n : sk.fullName(n);
		}
		return switch (i) {
			case 0 -> ct ? "Default CT Agent (SAS)" : "Default T Agent (Phoenix)";
			case 1 -> ct ? "Default CT Gloves" : "Default T Gloves";
			case 2 -> ct ? "Default CT Knife" : "Default T Knife";
			default -> MusicKit.name(equipped ? LoadoutConfig.musicKit() : LoadoutConfig.DEFAULT_MUSIC_KIT);
		};
	}

	private boolean isEquipped(String item) {
		dev.countercraft.team.Cosmetics.Side side = LoadoutConfig.side(team);
		if (item.startsWith("musickit:")) {
			return item.substring(9).equals(LoadoutConfig.musicKit());
		}
		if (item.startsWith("skin:")) {
			String[] p = item.split(":");
			return LoadoutConfig.skin(team, p[1]) == Integer.parseInt(p[2]) && isEquipped("weapon_" + p[1]);
		}
		if (item.startsWith("agent:")) {
			Agents.Agent a = Agents.get(item.substring(6));
			return a != null && a.model().equals(side.agent());
		}
		if (item.startsWith("knife:")) {
			return side.knife().equals(item.substring(6)) && side.knifeSkin() == 0;
		}
		if (item.startsWith("kskin:")) {
			String[] p = item.split(":");
			return side.knife().equals(p[1]) && side.knifeSkin() == Integer.parseInt(p[2]);
		}
		if (item.equals("customplayer")) {
			return side.agent().isEmpty();
		}
		if (item.startsWith("glove:")) {
			String[] p = item.split(":");
			return side.gloves().equals(p[1]) && side.gloveSkin() == Integer.parseInt(p[2]);
		}
		if (item.equals("clothing_hands")) {
			return side.gloves().isEmpty();
		}
		if (item.equals("melee")) {
			return side.knife().isEmpty();
		}
		if (java.util.Arrays.asList(ROW).contains(item) || item.equals("weapon_taser")) {
			return true; // default gloves (glove finishes come with the gloves conversion); the Zeus is fixed
		}
		for (String[] row : GRID) {
			for (String s : row) {
				if (item.equals(LoadoutConfig.get(team, s))) {
					return true;
				}
			}
		}
		return false;
	}

	private static String groupName(String group) {
		return switch (group) {
			case "secondary0" -> "STARTING PISTOL";
			case "secondary" -> "PISTOLS";
			case "smg" -> "MID-TIER";
			default -> "RIFLES";
		};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (e.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(e, doubleClick);
		}
		float k = k();
		if (e.y() < slotsTop() && e.x() > width - 200 * k) {
			onClose();
			return true;
		}
		if (dropdown != 0) {
			if (hoveredOption != null) {
				if (dropdown == 1) {
					setGroup(hoveredOption);
				} else if (dropdown == 2) {
					filterItem = hoveredOption;
				} else {
					sort = hoveredOption;
				}
				scroll = 0;
			}
			dropdown = 0;
			return true;
		}
		for (int c = 0; c < 4; c++) {
			float[] b = control(c);
			boolean visible = c == 1 ? weaponFilterVisible() : c != 2 || clearVisible();
			if (visible && CsUi.inside(e.x(), e.y(), b[0], b[1], b[2], b[3])) {
				if (c == 2) {
					setGroup("all");
				} else {
					dropdown = c == 0 ? 1 : c == 1 ? 2 : 3;
				}
				return true;
			}
		}
		if (hoveredItem != null) {
			equip(hoveredItem);
			return true;
		}
		if (hoveredRow >= 0) {
			slot = "row:" + ROW[hoveredRow];
			setGroup(ROW[hoveredRow]);
			return true;
		}
		// column titles toggle their filter (CS2 LoadoutGrid.ToggleGroupDropdown)
		for (int c = 0; c < 3; c++) {
			float[] col = column(team, c + 1);
			float ty = columnTop();
			if (CsUi.inside(e.x(), e.y(), col[0], ty, col[1], 24 * k)) {
				String gr = c == 0 ? "secondary0" : c == 1 ? "smg" : "rifle";
				setGroup(filterGroup.equals(gr) ? "all" : gr);
				return true;
			}
		}
		// a slot of the front team
		for (int c = 0; c < 3; c++) {
			for (int r = 0; r < 5; r++) {
				float[] b = gridSlot(team, c + 1, r);
				if (CsUi.inside(e.x(), e.y(), b[0], b[1], b[2], b[3])) {
					slot = GRID[c][r];
					setGroup(LoadoutConfig.group(slot));
					return true;
				}
			}
		}
		// the other team's section brings it to the front
		String other = Teams.CT.equals(team) ? Teams.T : Teams.CT;
		float ox = sectionX(other);
		if (CsUi.inside(e.x(), e.y(), ox, slotsTop(), SECTION * k, slotsHeight()) && !CsUi.inside(e.x(), e.y(), sectionX(team), slotsTop(), SECTION * k, slotsHeight())
				|| CsUi.inside(e.x(), e.y(), agentX(other), slotsTop(), 332 * k, slotsHeight())) {
			team = other;
			return true;
		}
		return super.mouseClicked(e, doubleClick);
	}

	private void setGroup(String group) {
		filterGroup = group;
		filterItem = "all";
		scroll = 0;
	}

	/** Equip a list item: into the selected slot if it fits there, else into its group's first slot (selecting it). */
	private void equip(String item) {
		if (item.startsWith("musickit:")) {
			LoadoutConfig.setMusicKit(item.substring(9));
			slot = "row:musickit";
			return;
		}
		if (item.startsWith("skin:")) {
			String[] p = item.split(":");
			equip("weapon_" + p[1]);
			LoadoutConfig.setSkin(team, p[1], Integer.parseInt(p[2]));
			return;
		}
		if (item.startsWith("agent:")) {
			Agents.Agent a = Agents.get(item.substring(6));
			if (a != null) {
				LoadoutConfig.setSide(team, sd -> new dev.countercraft.team.Cosmetics.Side(sd.skins(), sd.knife(), sd.knifeSkin(), sd.gloves(), sd.gloveSkin(),
						a.model(), a.voice()));
			}
			slot = "row:customplayer";
			return;
		}
		if (item.startsWith("knife:") || item.startsWith("kskin:") || item.equals("melee")) {
			String[] p = item.split(":");
			String knife = item.equals("melee") ? "" : p[1];
			int kit = item.startsWith("kskin:") ? Integer.parseInt(p[2]) : 0;
			LoadoutConfig.setSide(team, sd -> new dev.countercraft.team.Cosmetics.Side(sd.skins(), knife, kit, sd.gloves(), sd.gloveSkin(), sd.agent(), sd.voice()));
			slot = "row:melee";
			return;
		}
		if (item.startsWith("glove:") || item.equals("clothing_hands")) {
			String[] p = item.split(":");
			String type = item.equals("clothing_hands") ? "" : p[1];
			int kit = item.equals("clothing_hands") ? 0 : Integer.parseInt(p[2]);
			LoadoutConfig.setSide(team, sd -> new dev.countercraft.team.Cosmetics.Side(sd.skins(), sd.knife(), sd.knifeSkin(), type, kit, sd.agent(), sd.voice()));
			slot = "row:clothing_hands";
			return;
		}
		if (item.equals("customplayer")) {
			LoadoutConfig.setSide(team, sd -> new dev.countercraft.team.Cosmetics.Side(sd.skins(), sd.knife(), sd.knifeSkin(), sd.gloves(), sd.gloveSkin(), "", ""));
			slot = "row:customplayer";
			return;
		}
		if (item.startsWith("weapon_") && !item.equals("weapon_taser")) {
			LoadoutConfig.setSkin(team, Loadouts.modId(item), 0); // the plain weapon: no finish
		}
		int row = java.util.Arrays.asList(ROW).indexOf(item);
		if (row >= 0 || item.equals("weapon_taser")) {
			if (row >= 0) {
				slot = "row:" + item; // CS2's defaults are the only cosmetics: already equipped
			}
			return;
		}
		String group = null;
		for (String gr : WEAPON_GROUPS) {
			if (Loadouts.options(team, gr).contains(item)) {
				group = gr;
			}
		}
		if (group == null) {
			return;
		}
		if (!slot.startsWith("row:") && LoadoutConfig.group(slot).equals(group)) {
			LoadoutConfig.set(team, slot, item);
			return;
		}
		for (String[] col : GRID) {
			for (String s : col) {
				if (item.equals(LoadoutConfig.get(team, s))) {
					slot = s; // already in the loadout: select it
					return;
				}
			}
		}
		slot = group.equals("secondary") ? "secondary1" : group + (group.equals("secondary0") ? "" : "0");
		LoadoutConfig.set(team, slot, item);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (my > listTop()) {
			int n = listItems().size();
			float last = n == 0 ? 0 : tile(n - 1)[1] + scroll + 190 * k() - (height - listTop() - 40 * k());
			scroll = Math.max(0, Math.min(Math.max(0, last - listTop()), scroll - (float) dy * 60 * k()));
			return true;
		}
		return super.mouseScrolled(mx, my, dx, dy);
	}

	@Override
	public void onClose() {
		LoadoutConfig.save();
		minecraft.gui.setScreen(parent);
	}
}
