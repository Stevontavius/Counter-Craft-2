package dev.countercraft.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.net.ScoreboardPayload;
import dev.countercraft.team.Teams;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;

/**
 * CS2's scoreboard in place of Minecraft's player list: hold the player list key (Tab) in any mode, singleplayer
 * included. Counter-Terrorists, then Terrorists, then players without a team; in Counter-Craft (hardcore, where everyone
 * is on the same side) one list, each row edged in its faction's colour. Each row has the face, name (the ✓ for a linked
 * Steam account without Skinchanger Mode), money (teammates only, like CS2), kills, deaths, days survived in a row and
 * ping. Right-click while it's open frees the cursor ({@link ScoreboardScreen}).
 */
public final class ScoreboardHud {
	private static List<ScoreboardPayload.Row> rows = List.of();
	/** Each drawn row's rectangle and player, for the free cursor. */
	static final List<Object[]> HITS = new ArrayList<>();
	private static final int ROW = 12;

	private ScoreboardHud() {
	}

	public static void onPayload(ScoreboardPayload p) {
		rows = p.rows();
	}

	public static void clear() {
		rows = List.of();
	}

	/** The player list key is held (works inside screens too, where key mappings don't update). */
	public static boolean held() {
		Minecraft mc = Minecraft.getInstance();
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mc.options.keyPlayerList);
		if (key.getType() == InputConstants.Type.KEYBOARD) {
			return InputConstants.isKeyDown(key.getValue());
		}
		return mc.options.keyPlayerList.isDown();
	}

	/** HUD layer: shown while the key is held and no screen is open. */
	public static void hud(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() == null && mc.player != null && mc.options.keyPlayerList.isDown()) {
			draw(g, -1, -1, null);
		}
	}

	/** Draw the board; returns the hovered player's id (or null). */
	public static UUID draw(GuiGraphicsExtractor g, int mouseX, int mouseY, UUID selected) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		var conn = mc.getConnection();
		if (conn == null) {
			return null;
		}
		HITS.clear();
		List<ScoreboardPayload.Row> all = new ArrayList<>(rows);
		// anyone listed but not yet in a payload (just joined)
		for (PlayerInfo info : conn.getListedOnlinePlayers()) {
			UUID id = info.getProfile().id();
			if (all.stream().noneMatch(r -> r.id().equals(id))) {
				all.add(new ScoreboardPayload.Row(id, info.getProfile().name(), "", 0, 0, 0, -1, info.getLatency(), true, false));
			}
		}
		int w = Math.min(420, g.guiWidth() - 20);
		int x = (g.guiWidth() - w) / 2;
		int y = Math.max(10, g.guiHeight() / 8);
		UUID hovered = null;
		// header: where and which day
		String where = mc.getSingleplayerServer() != null ? mc.getSingleplayerServer().getWorldData().getLevelName()
				: mc.getCurrentServer() != null ? mc.getCurrentServer().name : "Multiplayer";
		long day = mc.level == null ? 0 : mc.level.getOverworldClockTime() / 24000L + 1;
		g.fill(x, y, x + w, y + 16, 0xE0101214);
		g.text(font, where, x + 6, y + 4, 0xFFFFFFFF, true);
		String right = "Day " + day + "  •  " + all.size() + (all.size() == 1 ? " player" : " players");
		g.text(font, right, x + w - 6 - font.width(right), y + 4, 0xFFBFBFBF, false);
		y += 18;
		boolean unified = mc.level != null && mc.level.getLevelData().isHardcore();
		String[][] groups = unified ? new String[][] {{"*", "SURVIVORS"}}
				: new String[][] {{Teams.CT, "COUNTER-TERRORISTS"}, {Teams.T, "TERRORISTS"}, {"", "UNASSIGNED"}};
		for (String[] grp : groups) {
			List<ScoreboardPayload.Row> list = new ArrayList<>();
			for (ScoreboardPayload.Row r : all) {
				if (unified || r.team().equals(grp[0])) {
					list.add(r);
				}
			}
			if (list.isEmpty() && grp[0].isEmpty()) {
				continue;
			}
			list.sort(Comparator.comparingInt(ScoreboardPayload.Row::kills).reversed().thenComparing(ScoreboardPayload.Row::name));
			int color = grp[0].equals(Teams.CT) ? Teams.CT_COLOR : grp[0].equals(Teams.T) ? Teams.T_COLOR : unified ? 0x8E2020 : 0x9A9A9A;
			long alive = list.stream().filter(ScoreboardPayload.Row::alive).count();
			// team bar: name, alive count, column headings
			g.fill(x, y, x + w, y + 14, 0xC0000000 | color);
			g.fill(x, y + 14, x + w, y + 15, 0xFF000000 | color);
			g.text(font, grp[1], x + 6, y + 3, 0xFFFFFFFF, true);
			g.text(font, alive + "/" + list.size() + " alive", x + 12 + font.width(grp[1]), y + 3, 0xD0FFFFFF, false);
			columns(g, font, x, w, y + 3, new String[] {"MONEY", "K", "D", "DAYS", "PING"}, 0xD0FFFFFF);
			y += 15;
			for (ScoreboardPayload.Row r : list) {
				boolean me = mc.player != null && r.id().equals(mc.player.getUUID());
				boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW;
				int bg = r.id().equals(selected) ? 0xE0303A48 : hover ? 0xE0283038 : me ? 0xD0242A30 : 0xC0141618;
				g.fill(x, y, x + w, y + ROW, bg);
				if (unified) {
					// one list: each row edged and tinted in its own faction's colour
					int faction = Teams.CT.equals(r.team()) ? Teams.CT_COLOR : Teams.T.equals(r.team()) ? Teams.T_COLOR : 0x9A9A9A;
					g.fill(x, y, x + w, y + ROW, 0x22000000 | faction);
					g.fill(x, y, x + 3, y + ROW, 0xFF000000 | faction);
					if (me) {
						g.fill(x + w - 2, y, x + w, y + ROW, 0xFF000000 | faction);
					}
				} else if (me) {
					g.fill(x, y, x + 2, y + ROW, 0xFF000000 | color);
				}
				PlayerInfo info = conn.getPlayerInfo(r.id());
				if (info != null) {
					PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x + 4, y + 2, 8);
				}
				int nameColor = !r.alive() ? 0xFF6E6E6E : 0xFFFFFFFF;
				boolean muted = mc.getPlayerSocialManager().isHidden(r.id());
				String name = r.name() + (muted ? " (muted)" : "");
				g.text(font, font.plainSubstrByWidth(name, w - 230), x + 16, y + 2, nameColor, false);
				if (r.genuine()) {
					g.text(font, " ✓", x + 16 + font.width(font.plainSubstrByWidth(name, w - 230)), y + 2, 0xFF4FC3F7, false);
				}
				if (!r.alive()) {
					g.text(font, "DEAD", x + w - 220, y + 2, 0xFFE05050, false);
				}
				int ping = info != null && info.getLatency() > 0 ? info.getLatency() : r.ping();
				columns(g, font, x, w, y + 2, new String[] {r.money() >= 0 ? "$" + r.money() : "", Integer.toString(r.kills()), Integer.toString(r.deaths()),
						Integer.toString(r.days()), Integer.toString(ping)}, r.alive() ? 0xFFFFFFFF : 0xFF8A8A8A);
				if (hover) {
					hovered = r.id();
				}
				HITS.add(new Object[] {y, r, x, w});
				y += ROW;
			}
			y += 6;
		}
		return hovered;
	}

	/** The right-hand columns (money, K, D, days, ping), right-aligned. */
	private static void columns(GuiGraphicsExtractor g, Font font, int x, int w, int y, String[] values, int color) {
		int[] right = {x + w - 160, x + w - 118, x + w - 88, x + w - 48, x + w - 8};
		int[] colors = {0xFF7ED37E, color, color, 0xFFEABE54, color};
		for (int i = 0; i < values.length; i++) {
			int c = values[i].equals("MONEY") || values[i].equals("DAYS") || values[i].equals("K") || values[i].equals("D") || values[i].equals("PING") ? color : colors[i];
			g.text(font, values[i], right[i] - font.width(values[i]), y, c, false);
		}
	}
}
