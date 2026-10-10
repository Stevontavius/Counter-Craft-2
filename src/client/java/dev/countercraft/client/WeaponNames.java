package dev.countercraft.client;

import dev.countercraft.ModComponents;
import dev.countercraft.client.skins.Skins;
import dev.countercraft.gun.CsWeapon;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.KnifeItem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * How a CS2 weapon is named outside the CS slots (inventory, chests, the pick-up prompt), so everyone can tell whose
 * finish is whose: "Weapon | Finish" in the finish's CS2 rarity colour (knives "★ Knife | Finish"; a weapon without a
 * finish in white), plus the rounds it carries.
 */
public final class WeaponNames {
	private WeaponNames() {
	}

	/** The weapon's CS2 name in its rarity colour (null for anything that isn't a CS weapon). */
	public static MutableComponent name(ItemStack stack, String base) {
		if (!(stack.getItem() instanceof CsWeapon)) {
			return null;
		}
		Skins.Skin skin = Skins.of(stack);
		boolean knife = stack.getItem() instanceof KnifeItem && !(skin == null && isDefaultKnife(stack));
		String text = (knife ? "★ " : "") + base + (skin != null ? " | " + skin.name() : "");
		int color = 0xFFFFFF;
		if (skin != null) {
			Skins.Rarity r = Skins.rarity(skin.rarity());
			if (r != null) {
				color = r.color() & 0xFFFFFF;
			}
		} else if (knife) {
			color = 0x8650AC; // CS2's ★ (extraordinary) colour for a knife without a finish
		}
		return Component.literal(text).withColor(color);
	}

	private static boolean isDefaultKnife(ItemStack stack) {
		String id = ((CsWeapon) stack.getItem()).weaponId();
		return id.equals("knife_t") || id.equals("knife_ct");
	}

	/** Tooltip: the coloured name in place of the plain one; guns add their rounds ("30 / 90"). */
	public static void tooltip(ItemStack stack, List<Component> lines) {
		if (lines.isEmpty()) {
			return;
		}
		MutableComponent n = name(stack, stack.getHoverName().getString());
		if (n == null) {
			return;
		}
		lines.set(0, n);
		if (stack.getItem() instanceof GunItem g && !g.spec().exhaustible()) {
			lines.add(1, Component.literal(stack.getOrDefault(ModComponents.AMMO, 0) + " / " + stack.getOrDefault(ModComponents.RESERVE, 0)).withColor(0xA0A0A0));
		}
	}

	/** Looking at a dropped weapon: "[E] Pick up AK-47 | Neon Rider" (CS mode) or "[E] Stash ..." (Minecraft hotbar). */
	public static void pickupPrompt(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gui.screen() != null || mc.player.isSpectator()) {
			return;
		}
		var target = DropClient.target(mc);
		if (target == null) {
			return;
		}
		ItemStack s = target.getItem();
		MutableComponent n = name(s, s.getHoverName().getString());
		if (n == null) {
			return;
		}
		boolean cs = dev.countercraft.team.CsInventory.active(mc.player);
		String key = mc.options.keyInventory.getTranslatedKeyMessage().getString();
		Component line = Component.literal("[" + key + "] ").withColor(0xFFFFFF)
				.append(Component.literal(cs ? "Pick up " : "Stash in inventory: ").withColor(0xBFBFBF)).append(n);
		var font = mc.font;
		int w = font.width(line);
		int x = (g.guiWidth() - w) / 2;
		int y = g.guiHeight() / 2 + 18;
		g.fill(x - 4, y - 3, x + w + 4, y + 11, 0x90000000);
		g.text(font, line, x, y, 0xFFFFFFFF, true);
	}
}
