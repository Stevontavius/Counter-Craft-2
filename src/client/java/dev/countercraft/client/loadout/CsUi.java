package dev.countercraft.client.loadout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import dev.countercraft.team.Loadouts;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Shared bits of the CS2 menus. Layout numbers are CS2's panorama CSS (1080 px tall reference), scaled to the GUI;
 * text is Minecraft's font (never CS2's). Item names, buy menu icons (white CS2 equipment SVGs, tinted) and loadout
 * tiles (CS2 inventory renders) come from the mod's data and the CS2 asset pack.
 */
public final class CsUi {
	/** CS2's team colours (csgostyles t-color / ct-color). */
	public static final int T_COLOR = 0xFFEABE54;
	public static final int CT_COLOR = 0xFF6F9CE6;
	private static final Map<String, int[]> ICON_SIZES = new HashMap<>();
	private static boolean iconsLoaded;

	private CsUi() {
	}

	/** GUI pixels per CS2 panorama pixel (panorama lays out at 1080 px tall). */
	public static float k(int screenHeight) {
		return screenHeight / 1080f;
	}

	/** Text at a CS2 font size (px), scaled from Minecraft's 9 px line; align -1 left, 0 centre, 1 right. */
	public static void text(GuiGraphicsExtractor g, String s, float x, float y, float sizePx, int screenH, int color, int align) {
		text(g, s, x, y, sizePx, screenH, color, align, true);
	}

	/** As above; {@code shadow} false: no drop shadow (dark text on a light tag). */
	public static void text(GuiGraphicsExtractor g, String s, float x, float y, float sizePx, int screenH, int color, int align, boolean shadow) {
		Font font = Minecraft.getInstance().font;
		float sc = sizePx * k(screenH) / 9f;
		float w = font.width(s) * sc;
		float x0 = align < 0 ? x : align == 0 ? x - w / 2 : x - w;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x0, y);
		pose.scale(sc, sc);
		g.text(font, s, 0, 0, color, shadow);
		pose.popMatrix();
	}

	/** The text cut to fit {@code maxW} at this size, with "..." when cut. */
	public static String fit(String s, float maxW, float sizePx, int screenH) {
		if (textWidth(s, sizePx, screenH) <= maxW) {
			return s;
		}
		String t = s;
		while (t.length() > 1 && textWidth(t + "...", sizePx, screenH) > maxW) {
			t = t.substring(0, t.length() - 1);
		}
		return t.trim() + "...";
	}

	/**
	 * CS2's icon / tile name of a knife item id: the mod uses CS2's display names where CS2's files use internal ones
	 * (Talon = widowmaker, Navaja = gypsy_jackknife, Bowie = survival_bowie, CT default = knife).
	 */
	public static String knifeAsset(String id) {
		return switch (id) {
			case "knife_ct" -> "knife";
			case "knife_navaja" -> "knife_gypsy_jackknife";
			case "knife_talon" -> "knife_widowmaker";
			case "knife_bowie" -> "knife_survival_bowie";
			default -> id;
		};
	}

	public static float textWidth(String s, float sizePx, int screenH) {
		return Minecraft.getInstance().font.width(s) * sizePx * k(screenH) / 9f;
	}

	/** The font size (at most {@code sizePx}) at which {@code s} fits {@code maxW}: titles shrink instead of overflowing. */
	public static float fitSize(String s, float maxW, float sizePx, int screenH) {
		float w = textWidth(s, sizePx, screenH);
		return w <= maxW || w <= 0 ? sizePx : sizePx * maxW / w;
	}

	/** A whole texture stretched into the rectangle, tinted. */
	public static void image(GuiGraphicsExtractor g, Identifier tex, float x, float y, float w, float h, int texW, int texH, int color) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(w / texW, h / texH);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, texW, texH, texW, texH, texW, texH, color);
		pose.popMatrix();
	}

	/** Display name of a CS2 item ("weapon_ak47" -> "AK-47"). */
	public static String name(String item) {
		switch (item) {
			case "item_kevlar":
				return "Kevlar Vest";
			case "item_assaultsuit":
				return "Kevlar + Helmet";
			case "item_defuser":
				return "Defuse Kit";
			case dev.countercraft.team.Buying.NADE_KIT:
				return "Nade Kit";
			case dev.countercraft.team.ExtraAmmo.PRIMARY_ITEM:
				return "Primary Magazine";
			case dev.countercraft.team.ExtraAmmo.SECONDARY_ITEM:
				return "Secondary Magazine";
			default:
				break;
		}
		String id = Loadouts.modId(item);
		GunItem gun = CounterCraft.GUNS.get(id);
		if (gun != null) {
			return gun.spec().name();
		}
		GrenadeItem g = CounterCraft.GRENADES.get(id);
		if (g != null) {
			return g.def().name();
		}
		dev.countercraft.gun.Guns.MeleeDef knife = dev.countercraft.gun.Guns.MELEE.get(id);
		return knife != null ? knife.name() : id;
	}

	/** CS2 equipment icon name of an item. */
	public static String iconName(String item) {
		return switch (item) {
			case "item_kevlar" -> "armor";
			case "item_assaultsuit" -> "armor_helmet";
			case "item_defuser" -> "defuser";
			case dev.countercraft.team.ExtraAmmo.PRIMARY_ITEM -> "ammobox_threepack";
			case dev.countercraft.team.ExtraAmmo.SECONDARY_ITEM -> "ammobox";
			default -> Loadouts.modId(item);
		};
	}

	/** Buy menu icon texture and its size, or null. */
	public static Identifier icon(String item) {
		return CounterCraft.id("textures/gui/buy/" + iconName(item) + ".png");
	}

	public static int[] iconSize(String item) {
		if (!iconsLoaded) {
			iconsLoaded = true;
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("gui/buy_icons.json"))) {
				JsonObject icons = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("icons");
				for (String k : icons.keySet()) {
					var a = icons.getAsJsonArray(k);
					ICON_SIZES.put(k, new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()});
				}
			} catch (Exception e) {
				CounterCraft.LOG.warn("[countercraft] no buy menu icons (run converter --step ui): {}", e.toString());
			}
		}
		return ICON_SIZES.get(iconName(item));
	}

	/** Loadout tile (CS2 inventory render, 256 x 192) of an item. */
	public static Identifier tile(String item) {
		return CounterCraft.id("textures/gui/weapons/" + item + ".png");
	}

	/** Draw an item's white CS2 icon centred in the box, keeping its aspect, at most {@code hFrac} of the box height. */
	public static void iconIn(GuiGraphicsExtractor g, String item, float x, float y, float w, float h, float hFrac, int color) {
		int[] sz = iconSize(item);
		if (sz == null) {
			return;
		}
		float ih = h * hFrac;
		float iw = ih * sz[0] / sz[1];
		if (iw > w * 0.92f) {
			iw = w * 0.92f;
			ih = iw * sz[1] / sz[0];
		}
		image(g, icon(item), x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, sz[0], sz[1], color);
	}

	/** An eased hover: its value (0..1) and when it was last advanced. */
	private static final class Hover {
		float value;
		long at;
	}

	private static final java.util.Map<Object, Hover> HOVER = new java.util.HashMap<>();

	/**
	 * A hover that eases in and out (about 0.08 s in, 0.15 s out) instead of snapping: 0..1 for {@code key} (anything
	 * stable: an item id, a slot name), advanced by real time.
	 */
	public static float hover(Object key, boolean on) {
		long now = System.nanoTime();
		Hover h = HOVER.computeIfAbsent(key, k -> new Hover());
		float dt = h.at == 0 ? 0f : Math.min(0.1f, (now - h.at) / 1e9f);
		h.at = now;
		h.value = on ? Math.min(1f, h.value + dt * 12f) : Math.max(0f, h.value - dt * 7f);
		if (HOVER.size() > 512) {
			HOVER.values().removeIf(v -> v.value <= 0f);
		}
		return h.value * h.value * (3f - 2f * h.value);
	}

	/**
	 * A CS2-style card: a soft top-lit fill that brightens with {@code hover} (0..1), a 1 px light top edge and dark bottom
	 * edge, a {@code accent}-coloured bar down the left that grows in on hover, and a white frame when selected.
	 */
	public static void card(GuiGraphicsExtractor g, float x, float y, float w, float h, float hover, int accent, boolean selected) {
		int x0 = (int) x, y0 = (int) y, x1 = (int) (x + w), y1 = (int) (y + h);
		int top = Math.round(0x22 + 0x22 * hover);
		int bottom = Math.round(0x0A + 0x10 * hover);
		g.fillGradient(x0, y0, x1, y1, top << 24 | 0xFFFFFF, bottom << 24 | 0xFFFFFF);
		if (hover > 0f) {
			g.fill(x0, y0, x1, y1, Math.round(0x26 * hover) << 24 | accent & 0xFFFFFF);
		}
		g.fill(x0, y0, x1, y0 + 1, 0x24FFFFFF);
		g.fill(x0, y1 - 1, x1, y1, 0x50000000);
		float bar = selected ? 1f : hover;
		if (bar > 0f) {
			float k = k(Minecraft.getInstance().getWindow().getGuiScaledHeight());
			int bw = Math.max(1, Math.round(3 * k));
			int bh = Math.round((y1 - y0) * (0.35f + 0.65f * bar));
			int by = y0 + (y1 - y0 - bh) / 2;
			g.fill(x0, by, x0 + bw, by + bh, Math.round(255 * bar) << 24 | accent & 0xFFFFFF);
		}
		if (selected) {
			g.outline(x0, y0, x1 - x0, y1 - y0, 0xFFFFFFFF);
		}
	}

	/** A dark panel: a top-to-bottom gradient with a faint frame. */
	public static void panel(GuiGraphicsExtractor g, float x, float y, float w, float h) {
		g.fillGradient((int) x, (int) y, (int) (x + w), (int) (y + h), 0xD0141619, 0xD0080909);
		g.outline((int) x, (int) y, (int) w, (int) h, 0x1EFFFFFF);
	}

	public static boolean inside(double mx, double my, float x, float y, float w, float h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
