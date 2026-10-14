package dev.countercraft.client.hud;

import dev.countercraft.client.options.CcOptions;
import dev.countercraft.net.TeamListPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Hardcore: the players on the server, each with their skin's face, coordinates, dimension and distance, or DEAD
 * (TeamList on the server). It fits into the free space on its side of the screen (HudSpace): on the left under the money
 * panel and above the paperdoll, vitals and chat; on the right under the status effects and kill feed and above the CS
 * hotbar, gliding as they come and go. Rows that don't fit become "+N more". CC2 Options > HUD > Player List sets what it
 * shows and where.
 */
public final class TeamListHud {
	private static final int ROW = 12;
	private static final int FACE = 9;
	private static List<TeamListPayload.Entry> players = List.of();
	private static long at;
	/** Where the list is drawn (GUI px), easing toward where it fits; -1: not drawn last frame. */
	private static float shownTop = -1;
	private static long frameAt;

	private record Row(TeamListPayload.Entry e, String info, double distance) {
	}

	private TeamListHud() {
	}

	public static void onList(TeamListPayload p) {
		players = p.players();
		at = System.nanoTime();
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !mc.level.getLevelData().isHardcore() || !CcOptions.teamList() || players.isEmpty()
				|| System.nanoTime() - at > 3_000_000_000L || mc.getDebugOverlay().showDebugScreen()) {
			shownTop = -1;
			return; // nothing heard for 3 s: left the server, or it isn't hardcore any more; F3 fills both top corners
		}
		CcOptions.PlayerList o = CcOptions.playerList();
		List<Row> rows = rows(mc, o);
		if (rows.isEmpty()) {
			shownTop = -1;
			return;
		}
		Font font = mc.font;
		int nameW = 0;
		int infoW = 0;
		for (Row r : rows) {
			nameW = Math.max(nameW, font.width(r.e().name()));
			infoW = Math.max(infoW, font.width(r.info()));
		}
		int face = o.faces ? FACE + 4 : 0;
		int w = 4 + face + nameW + (infoW > 0 ? 10 + infoW : 0) + 4;
		float s = o.scale;
		int gh = g.guiHeight();
		boolean left = o.side == CcOptions.PlayerList.Side.LEFT;
		float top;
		float limit;
		if (left) {
			top = HudSpace.bottom(HudSpace.Panel.MONEY, 0);
			limit = Math.min(Math.min(HudSpace.top(HudSpace.Panel.PAPERDOLL, gh), HudSpace.top(HudSpace.Panel.VITALS, gh)), chatTop(mc, gh));
		} else {
			top = Math.max(effectsBottom(mc), HudSpace.bottom(HudSpace.Panel.KILL_FEED, 0));
			limit = HudSpace.top(HudSpace.Panel.CS_HOTBAR, gh - 64); // else the weapon panel and subtitles, bottom right
		}
		top += 4;
		limit -= 4;
		int fit = (int) ((limit - top - 4 * s) / (ROW * s));
		if (fit < 1) {
			shownTop = -1;
			return;
		}
		int shown = rows.size() <= fit ? rows.size() : fit - 1;
		String more = rows.size() > shown ? "+" + (rows.size() - shown) + " more" : null;
		if (more != null) {
			w = Math.max(w, 8 + font.width(more));
		}
		// glide to the new place rather than jump
		long now = System.nanoTime();
		shownTop = shownTop < 0 ? top : shownTop + (top - shownTop) * Math.min(1f, (now - frameAt) / 1e9f * 12f);
		frameAt = now;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(left ? 4 : g.guiWidth() - 4 - w * s, shownTop);
		pose.scale(s, s);
		g.fill(0, 0, w, 3 + (shown + (more != null ? 1 : 0)) * ROW + 1, Math.round(o.opacity * 255) << 24);
		int ry = 3;
		for (Row r : rows.subList(0, shown)) {
			TeamListPayload.Entry e = r.e();
			int tx = 4;
			if (o.faces) {
				PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(e.id());
				PlayerFaceExtractor.extractRenderState(g, info != null ? info.getSkin() : DefaultPlayerSkin.get(e.id()), tx, ry, FACE);
				tx += FACE + 4;
			}
			g.text(font, e.name(), tx, ry + 1, e.dead() ? 0xFF9A9A9A : 0xFFFFFFFF, true);
			if (!r.info().isEmpty()) {
				g.text(font, r.info(), tx + nameW + 10, ry + 1, e.dead() ? 0xFFE05050 : 0xFFDCB45A, true);
			}
			ry += ROW;
		}
		if (more != null) {
			g.text(font, more, 4, ry + 1, 0xFF9A9A9A, true);
		}
		pose.popMatrix();
	}

	/** The rows to show, by name (the server's order) or nearest first (the dead and other dimensions last). */
	private static List<Row> rows(Minecraft mc, CcOptions.PlayerList o) {
		String here = mc.level.dimension().identifier().toString();
		List<Row> out = new ArrayList<>();
		for (TeamListPayload.Entry e : players) {
			boolean me = e.id().equals(mc.player.getUUID());
			if (me && !o.self) {
				continue;
			}
			if (me) { // your own position as it is now, not as of the last list
				e = new TeamListPayload.Entry(e.id(), e.name(), mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), e.dimension(),
						e.dead());
			}
			boolean near = !e.dead() && e.dimension().equals(here);
			double d = me ? 0 : near ? Math.sqrt(mc.player.distanceToSqr(e.x() + 0.5, e.y(), e.z() + 0.5)) : Double.MAX_VALUE;
			List<String> info = new ArrayList<>();
			if (e.dead()) {
				info.add("DEAD");
			} else {
				if (o.coordinates) {
					info.add(e.x() + ", " + e.y() + ", " + e.z());
				}
				if (o.dimension) {
					info.add(dimension(e.dimension()));
				}
				if (o.distance && near && !me) {
					info.add(Math.round(d) + " m");
				}
			}
			out.add(new Row(e, String.join("  ", info), d));
		}
		if (o.sort == CcOptions.PlayerList.Sort.DISTANCE) {
			out.sort(Comparator.comparingDouble(Row::distance)); // stable: ties stay in name order
		}
		return out;
	}

	/** The bottom of Minecraft's status effect icons (top right): the beneficial ones in a row at the top, the others under them. */
	private static float effectsBottom(Minecraft mc) {
		float b = 0;
		for (MobEffectInstance e : mc.player.getActiveEffects()) {
			if (e.showIcon()) {
				b = Math.max(b, e.getEffect().value().isBeneficial() ? 25 : 51);
			}
		}
		return b;
	}

	/** The top of the chat (bottom left, 40 px up), at its open height while you type. */
	private static float chatTop(Minecraft mc, int h) {
		double height = mc.gui.hud.getChat().isChatFocused() ? mc.options.chatHeightFocused().get() : mc.options.chatHeightUnfocused().get();
		return (float) (h - 40 - ChatComponent.getHeight(height) * mc.options.chatScale().get());
	}

	static String dimension(String id) {
		return switch (id) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> {
				String path = id.substring(id.indexOf(':') + 1).replace('_', ' ');
				yield path.isEmpty() ? id : path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
			}
		};
	}
}
