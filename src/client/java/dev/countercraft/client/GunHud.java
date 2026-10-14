package dev.countercraft.client;

import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GunSpec;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import dev.countercraft.ModComponents;
import dev.countercraft.gun.CsWeapon;

/** Crosshair rules (CS2 snipers have none unscoped), the scope overlay, and CS2's cl_drawhud. */
public final class GunHud {
	/**
	 * CS2's cl_drawhud: 0 hides the whole HUD (crosshair, chat, hotbar, every Counter-Craft panel and the pings), for
	 * clean shots. Unlike Minecraft's F1, the hands and the viewmodel stay, and so do the sniper scope and a flashbang's
	 * white-out (they're what you see, not HUD). Not saved: back on at the next start, as in CS2.
	 */
	public static boolean drawHud = true;

	private GunHud() {
	}

	/** With cl_drawhud 0: only the scope (zoomed in) and the flashbang's white-out. */
	public static void viewOverlays(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		GunSpec spec = GunClient.held(mc.player);
		if (spec != null && !GunClient.ironsight(spec) && GunClient.zoomLevel() > 0) {
			scope(g);
		}
		float a = flashAlpha();
		if (a > 0.004f) {
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), ((int) (a * 255) << 24) | 0xFFFFFF);
		}
	}

	static void registerCommand(com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> d) {
		d.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("cl_drawhud")
				.executes(c -> {
					c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("cl_drawhud " + (drawHud ? 1 : 0)));
					return drawHud ? 1 : 0;
				})
				.then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("value",
						com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 1)).executes(c -> {
					drawHud = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "value") == 1;
					c.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("cl_drawhud " + (drawHud ? 1 : 0)));
					return 1;
				})));
	}

	static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			GunSpec spec = GunClient.held(mc.player);
			if (GunClient.ironsight(spec)) {
				// AUG / SG 553: aiming down the scope hides the crosshair; the scope's red dot marks the aim point
				float w = GunClient.adsWeight();
				if (w >= 0.9f) {
					int cx = g.guiWidth() / 2;
					int cy = g.guiHeight() / 2;
					g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFF2A2A);
					return;
				}
				if (w > 0.4f) {
					return;
				}
			} else if (spec != null && GunClient.zoomLevel() > 0) {
				scope(g);
				return;
			}
			if (spec != null && !spec.zoom().crosshair()) {
				return;
			}
			if (dev.countercraft.client.crosshair.CsCrosshair.draw(g)) {
				return;
			}
			vanilla.extractRenderState(g, delta);
		});
		HudElementRegistry.addLast(CounterCraft.id("ammo"), (g, delta) -> ammo(g));
		HudElementRegistry.addLast(CounterCraft.id("flash"), (g, delta) -> {
			float a = flashAlpha();
			if (a > 0.004f) {
				g.fill(0, 0, g.guiWidth(), g.guiHeight(), ((int) (a * 255) << 24) | 0xFFFFFF);
			}
		});
		CounterCraft.LOG.debug("[countercraft] HUD hooks registered");
	}

	/** CS2-style weapon panel, bottom right: magazine / reserve (big), mode, weapon name. */
	private static void ammo(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		if (!(stack.getItem() instanceof CsWeapon)) {
			return;
		}
		Font font = mc.font;
		int right = g.guiWidth() - 8;
		int bottom = g.guiHeight() - 8;
		String name = stack.getHoverName().getString();
		g.text(font, name, right - font.width(name), bottom - font.lineHeight, 0xFFBFBFBF, true);
		GunSpec spec = GunClient.held(mc.player);
		if (spec == null) {
			return;
		}
		int ammo = stack.getOrDefault(ModComponents.AMMO, 0);
		int reserve = stack.getOrDefault(ModComponents.RESERVE, 0);
		if (stack.getItem() instanceof dev.countercraft.gun.GunItem gun) {
			reserve += dev.countercraft.team.ExtraAmmo.rounds(mc.player, gun); // bought spare magazines
		}
		String mode = stack.has(ModComponents.RELOAD_END) && !spec.exhaustible() ? "RELOADING"
				: stack.getOrDefault(ModComponents.BURST, false) ? "BURST"
				: "detachable".equals(spec.silencer()) && stack.getOrDefault(ModComponents.SILENCER_OFF, false) ? "NO SILENCER" : "";
		String big = spec.exhaustible() ? (ammo > 0 ? "READY" : "CHARGING") : String.valueOf(ammo);
		String small = spec.exhaustible() ? "" : " / " + reserve;
		var pose = g.pose();
		pose.pushMatrix();
		float k = 2f;
		int wBig = font.width(big);
		int wSmall = font.width(small);
		int y = bottom - font.lineHeight - 6 - (int) (font.lineHeight * k);
		pose.translate(right - wSmall - wBig * k, y);
		pose.scale(k, k);
		g.text(font, big, 0, 0, ammo == 0 && !spec.exhaustible() ? 0xFFFF5555 : 0xFFFFFFFF, true);
		pose.popMatrix();
		g.text(font, small, right - wSmall, y + (int) (font.lineHeight * k) - font.lineHeight, 0xFFBFBFBF, true);
		if (!mode.isEmpty()) {
			g.text(font, mode, right - font.width(mode), y - font.lineHeight - 2, 0xFFFFD25A, true);
		}
	}

	private static long flashStart;
	private static float flashSeconds;

	/** Flashed: full white for the first 45%, then a linear fade (stronger flashes hold longer). */
	static void flash(float seconds) {
		if (seconds > remainingFlash()) {
			flashStart = System.nanoTime();
			flashSeconds = seconds;
		}
	}

	private static float remainingFlash() {
		return flashSeconds - (System.nanoTime() - flashStart) / 1e9f;
	}

	private static float flashAlpha() {
		if (flashSeconds <= 0) {
			return 0f;
		}
		float t = (System.nanoTime() - flashStart) / 1e9f;
		float hold = flashSeconds * 0.45f;
		if (t >= flashSeconds) {
			return 0f;
		}
		return t <= hold ? 1f : 1f - (t - hold) / (flashSeconds - hold);
	}

	private static final net.minecraft.resources.Identifier SCOPE_CIRCLE = CounterCraft.id("textures/hud/scope_circle.png");
	private static final net.minecraft.resources.Identifier SCOPE_LINE = CounterCraft.id("textures/hud/scope_line_blur.png");
	/** CS2's scope circle: 0.85 of the screen height (measured on a 2560x1440 capture of the AWP scope). */
	private static final float SCOPE_DIAMETER = 0.85f;

	/**
	 * CS2's scope overlay with its own HUD textures (panorama/images/hud/scope, converted into the pack): the soft circle
	 * mask, black outside; cross hairs across the lens only. CS2's lens texture (a brown film with dirt and scratches,
	 * scope_lens.png, still in the pack) isn't drawn: a clean view, the user's choice. Standing still the
	 * lines are 1 px, 82% dark; moving they spread into blurred bands whose width follows the movement inaccuracy (measured:
	 * ~60 px, 31% dark at 1440p walking with the AWP), darkness 0.82 x (2 / width)^0.3 between those two points.
	 */
	private static void scope(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		double gs = mc.getWindow().getGuiScale();
		// physical pixels, so a 1 px line stays 1 px
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale((float) (1 / gs), (float) (1 / gs));
		int w = mc.getWindow().getWidth();
		int h = mc.getWindow().getHeight();
		int d = Math.round(h * SCOPE_DIAMETER);
		int x0 = (w - d) / 2;
		int y0 = (h - d) / 2;
		int black = 0xFF000000;
		var gui = net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED;
		g.blit(gui, SCOPE_CIRCLE, x0, y0, 0f, 0f, d, d, 512, 512, 512, 512);
		g.fill(0, 0, x0, h, black);
		g.fill(x0 + d, 0, w, h, black);
		g.fill(x0, 0, x0 + d, y0, black);
		g.fill(x0, y0 + d, x0 + d, h, black);
		int cx = w / 2;
		int cy = h / 2;
		// band width: CS2's AWP walking scoped shows ~60 px at 1440p (4.2% of the height) with its movement inaccuracy
		// at full; other amounts scale linearly (approximated: one measured point)
		dev.countercraft.gun.GunSpec awp = dev.countercraft.gun.Guns.get("awp");
		double ref = awp != null ? dev.countercraft.gun.AccuracySpec.pick(awp.accuracy().moving(), true) : 0.25;
		int band = Math.max(1, (int) Math.round(1 + 0.042 * h * GunClient.scopeBlurInaccuracy() / ref));
		float dark = band <= 2 ? 0.82f : (float) (0.82 * Math.pow(2.0 / band, 0.3));
		int a = Math.round(dark * 255);
		if (band <= 2) {
			int c = a << 24;
			g.fill(x0, cy, x0 + d, cy + 1, c);
			g.fill(cx, y0, cx + 1, y0 + d, c);
		} else {
			// the blur texture is a soft vertical line: stretched across for the vertical hair, rotated for the horizontal
			int col = (a << 24) | 0xFFFFFF;
			g.blit(gui, SCOPE_LINE, cx - band / 2, y0, 0f, 0f, band, d, 64, 64, 64, 64, col);
			pose.pushMatrix();
			pose.translate(x0, cy + band / 2f);
			pose.rotate((float) (-Math.PI / 2));
			g.blit(gui, SCOPE_LINE, 0, 0, 0f, 0f, band, d, 64, 64, 64, 64, col);
			pose.popMatrix();
		}
		pose.popMatrix();
	}
}
