package dev.countercraft.client.radio;

import com.mojang.blaze3d.platform.InputConstants;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.RadioPayload;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * CS2's radial radio (chat wheel). Hold C ({@code +radialradio}) or V ({@code +radialradio2}) to open it, move the mouse
 * toward a command, let go to say it; the mouse wheel switches between the three tabs. A quick tap with nothing chosen
 * pings instead (cl_radial_radio_tap_to_ping). Tabs hold CS2's default cl_radial_radio_tab_N_text_M values, read from
 * CS2's console: segment 1 is at the top, then clockwise.
 */
public final class RadioWheel {
	private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(CounterCraft.id("radio"));
	public static final KeyMapping RADIO = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.radio", InputConstants.Type.KEYBOARD, InputConstants.KEY_C, CATEGORY));
	public static final KeyMapping RADIO2 = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.radio2", InputConstants.Type.KEYBOARD, InputConstants.KEY_V, CATEGORY));
	public static final KeyMapping PING = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.countercraft.ping", InputConstants.Type.MOUSE, InputConstants.MOUSE_BUTTON_MIDDLE, CATEGORY));

	/** CS2's defaults (cl_radial_radio_tab_0..2_text_1..8). */
	static final String[][] TABS = {
			{"Chatwheel_requestspend", "Chatwheel_requestweapon", "Chatwheel_bplan", "Chatwheel_followingyou", "Chatwheel_midplan",
					"Chatwheel_followme", "Chatwheel_aplan", "Chatwheel_requestecoround"},
			{"Chatwheel_enemyspotted", "Chatwheel_needbackup", "Chatwheel_bplan", "Chatwheel_bombcarrierspotted",
					"Chatwheel_multipleenemieshere", "Chatwheel_sniperspotted", "Chatwheel_aplan", "Chatwheel_inposition"},
			{"Chatwheel_affirmative", "Chatwheel_negative", "Chatwheel_compliment", "Chatwheel_thanks", "Chatwheel_cheer",
					"Chatwheel_peptalk", "Chatwheel_sorry", "Chatwheel_sectorclear"}};
	/** Seconds: releasing sooner than this with nothing selected pings instead. */
	private static final double TAP = 0.25;
	/** Mouse travel (look degrees) needed to pick a segment, and its cap. */
	private static final double DEAD = 4.0;
	private static final double REACH = 12.0;

	private static boolean open;
	private static KeyMapping heldKey;
	private static int tab;
	private static double mx;
	private static double my;
	private static long openedAt;

	private RadioWheel() {
	}

	public static void init() {
	}

	/** {@code /cs_radio <option>} (a chat wheel option, e.g. Chatwheel_enemyspotted) and {@code /cs_ping}. */
	public static void register(com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> d) {
		var options = java.util.Arrays.stream(TABS).flatMap(java.util.Arrays::stream).distinct().toList();
		d.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("cs_radio")
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("option", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(options, b))
						.executes(c -> {
							String key = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "option");
							RadioClient.Option o = RadioClient.option(key);
							if (o == null) {
								c.getSource().sendError(net.minecraft.network.chat.Component.literal("Unknown chat wheel option " + key));
								return 0;
							}
							ClientPlayNetworking.send(new RadioPayload(o.concept(), key));
							return 1;
						})));
		d.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("cs_ping").executes(c -> {
			Pings.pingHere(Minecraft.getInstance(), false);
			return 1;
		}));
	}

	public static boolean isOpen() {
		return open;
	}

	/** Client tick: open / close with the keys. */
	public static void tick(Minecraft mc) {
		while (RADIO.consumeClick() || RADIO2.consumeClick()) {
			// drained; held state drives the wheel
		}
		if (mc.player == null || mc.gui.screen() != null) {
			open = false;
			return;
		}
		if (!open) {
			KeyMapping k = RADIO.isDown() ? RADIO : RADIO2.isDown() ? RADIO2 : null;
			if (k != null) {
				open = true;
				heldKey = k;
				tab = k == RADIO ? 0 : 1;
				mx = my = 0;
				openedAt = System.nanoTime();
			}
			return;
		}
		if (!heldKey.isDown()) {
			open = false;
			int seg = selected();
			if (seg >= 0) {
				String key = TABS[tab][seg];
				RadioClient.Option o = RadioClient.option(key);
				if (o != null && ClientPlayNetworking.canSend(RadioPayload.TYPE)) {
					ClientPlayNetworking.send(new RadioPayload(o.concept(), key));
				}
			} else if ((System.nanoTime() - openedAt) / 1e9 < TAP) {
				Pings.pingHere(mc, false);
			}
		}
	}

	/** Mouse look while open moves the selector instead of the view. */
	public static void mouse(double dYaw, double dPitch) {
		mx += dYaw * 0.15;
		my += dPitch * 0.15;
		double len = Math.hypot(mx, my);
		if (len > REACH) {
			mx *= REACH / len;
			my *= REACH / len;
		}
	}

	public static void scroll(double dy) {
		tab = Math.floorMod(tab + (dy < 0 ? 1 : -1), TABS.length);
	}

	/** Segment under the selector (0 = top, clockwise), or -1 in the middle. */
	private static int selected() {
		if (Math.hypot(mx, my) < DEAD) {
			return -1;
		}
		double ang = Math.toDegrees(Math.atan2(mx, -my)); // 0 = up, clockwise
		return Math.floorMod((int) Math.round(ang / 45.0), 8);
	}

	public static void render(GuiGraphicsExtractor g) {
		if (!open) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int cx = g.guiWidth() / 2;
		int cy = g.guiHeight() / 2;
		int r = (int) (g.guiHeight() * 0.3);
		// backdrop: a dark disc
		for (int y = -r - 12; y <= r + 12; y++) {
			int half = (int) Math.sqrt(Math.max(0, (r + 12) * (r + 12) - y * y));
			g.fill(cx - half, cy + y, cx + half, cy + y + 1, 0x8C000000);
		}
		int sel = selected();
		for (int i = 0; i < 8; i++) {
			double a = Math.toRadians(i * 45.0);
			int x = cx + (int) Math.round(Math.sin(a) * r * 0.72);
			int y = cy - (int) Math.round(Math.cos(a) * r * 0.72);
			RadioClient.Option o = RadioClient.option(TABS[tab][i]);
			boolean hot = i == sel;
			if (hot) {
				g.fill(x - 34, y - 26, x + 34, y + 22, 0x66FFFFFF);
			}
			if (o != null && o.icon() != null) {
				Identifier tex = CounterCraft.id("textures/hud/chatwheel/" + o.icon() + ".png");
				g.blit(RenderPipelines.GUI_TEXTURED, tex, x - 10, y - 20, 0f, 0f, 20, 20, 64, 64, 64, 64, hot ? 0xFFFFFFFF : 0xFFC8C8C8);
			}
			String label = o != null ? o.text() : TABS[tab][i];
			g.text(font, label, x - font.width(label) / 2, y + 4, hot ? 0xFFFFFFFF : 0xFFBFBFBF, true);
		}
		// tabs
		for (int t = 0; t < TABS.length; t++) {
			int x = cx - 12 + t * 12;
			g.fill(x - 3, cy - r - 8, x + 3, cy - r - 2, t == tab ? 0xFFFFFFFF : 0x80FFFFFF);
		}
		if (sel >= 0) {
			RadioClient.Option o = RadioClient.option(TABS[tab][sel]);
			String label = o != null ? o.text() : "";
			g.text(font, label, cx - font.width(label) / 2, cy - 4, 0xFFFFFFFF, true);
		}
		g.fill(cx + (int) (mx * r / REACH / 2) - 2, cy + (int) (my * r / REACH / 2) - 2, cx + (int) (mx * r / REACH / 2) + 2,
				cy + (int) (my * r / REACH / 2) + 2, 0xFFFFFFFF);
	}
}
