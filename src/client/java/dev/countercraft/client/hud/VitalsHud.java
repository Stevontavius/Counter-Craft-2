package dev.countercraft.client.hud;

import dev.countercraft.Combat;
import dev.countercraft.client.loadout.CsUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * CS2-style vitals in place of Minecraft's health, armor and food bars: bottom left, an icon and a percentage each.
 *
 * <ul>
 * <li>Health: Minecraft's heart sprite in its current state, like the vanilla bar (poisoned, withered, frozen,
 * hardcore, blinking after damage); health as % of max (CS2 HP: 100 HP is 20 health); absorption adds a golden
 * "+N%".</li>
 * <li>Armor: CS2 kevlar shows an iron chestplate, kevlar + helmet adds an iron helmet over it, with the CS2 armor value.
 * Without kevlar, the Minecraft armor being worn shows instead (its chestplate / helmet, armor points as % of 20);
 * with neither, a faded chestplate and 0%.</li>
 * <li>Hunger: the drumstick (the hunger effect turns it green, like vanilla) and food as % of 20.</li>
 * <li>Oxygen (when vanilla would show the bubbles: underwater or still catching breath): a bubble and air as %. At 0%
 * the bubble pops (vanilla's bursting bubble), then becomes Minecraft's warning sign, and the 0% flashes red.</li>
 * </ul>
 * Hidden where vanilla hides these bars (creative, spectator); hunger gives way to a ridden mount's health.
 */
public final class VitalsHud {
	private static final ItemStack CHEST = new ItemStack(Items.IRON_CHESTPLATE);
	private static final ItemStack HELMET = new ItemStack(Items.IRON_HELMET);
	/** CS2 HUD sizes at 1080p (panorama px): icon box, text size, gap between the three groups. */
	private static final float ICON = 40f;
	private static final float TEXT = 28f;
	private static final float MARGIN = 24f;

	private VitalsHud() {
	}

	public static void draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) {
			return;
		}
		int h = g.guiHeight();
		float k = CsUi.k(h);
		float icon = ICON * k;
		float x = MARGIN * k;
		float y = h - MARGIN * k - icon;
		float textY = y + (icon - TEXT * k * 0.8f) / 2;

		// health
		boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
		boolean blink = p.hurtTime > 0 && (p.hurtTime / 3) % 2 == 1;
		// vanilla layers the heart over its container (the outline)
		sprite(g, Identifier.withDefaultNamespace("hud/heart/container" + (hardcore ? "_hardcore" : "") + (blink ? "_blinking" : "")), x, y, icon);
		sprite(g, heart(p, hardcore, blink), x, y, icon);
		x += icon + 10 * k;
		String hp = pct(p.getHealth() / p.getMaxHealth());
		CsUi.text(g, hp, x, textY, TEXT, h, 0xFFFFFFFF, -1);
		x += CsUi.textWidth(hp, TEXT, h);
		if (p.getAbsorptionAmount() > 0) {
			String abs = " +" + pct(p.getAbsorptionAmount() / p.getMaxHealth());
			CsUi.text(g, abs, x, textY, TEXT, h, 0xFFFFD83A, -1);
			x += CsUi.textWidth(abs, TEXT, h);
		}
		x += 40 * k;

		// armor
		Combat.Kevlar kev = p.getAttached(Combat.KEVLAR);
		String armor;
		if (kev != null && kev.armor() > 0) {
			item(g, CHEST, x, y, icon, 1f);
			if (kev.helmet()) {
				item(g, HELMET, x + icon * 0.15f, y - icon * 0.38f, icon * 0.7f, 1f);
			}
			armor = kev.armor() + "%";
		} else {
			ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
			ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
			int points = p.getArmorValue();
			boolean worn = points > 0;
			item(g, worn && !chest.isEmpty() ? chest : CHEST, x, y, icon, worn ? 1f : 0.35f);
			if (worn && !head.isEmpty()) {
				item(g, head, x + icon * 0.15f, y - icon * 0.38f, icon * 0.7f, 1f);
			}
			armor = pct(points / 20f);
		}
		x += icon + 10 * k;
		CsUi.text(g, armor, x, textY, TEXT, h, 0xFFFFFFFF, -1);
		x += CsUi.textWidth(armor, TEXT, h) + 40 * k;

		// hunger (a ridden mount's health takes its place, like vanilla)
		if (!(p.getVehicle() instanceof LivingEntity mount && mount.showVehicleHealth())) {
			boolean hungry = p.hasEffect(MobEffects.HUNGER);
			sprite(g, Identifier.withDefaultNamespace(hungry ? "hud/food_empty_hunger" : "hud/food_empty"), x, y, icon);
			sprite(g, Identifier.withDefaultNamespace(hungry ? "hud/food_full_hunger" : "hud/food_full"), x, y, icon);
			x += icon + 10 * k;
			String food = pct(p.getFoodData().getFoodLevel() / 20f);
			CsUi.text(g, food, x, textY, TEXT, h, 0xFFFFFFFF, -1);
			x += CsUi.textWidth(food, TEXT, h) + 40 * k;
		}

		// oxygen
		int air = p.getAirSupply();
		int maxAir = p.getMaxAirSupply();
		if (p.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) || air < maxAir) {
			long now = System.nanoTime();
			if (air > 0) {
				emptySince = -1;
				sprite(g, Identifier.withDefaultNamespace("hud/air"), x, y, icon);
				x += icon + 10 * k;
				CsUi.text(g, pct(Math.max(0, air) / (float) maxAir), x, textY, TEXT, h, 0xFFFFFFFF, -1);
			} else {
				if (emptySince < 0) {
					emptySince = now;
				}
				double t = (now - emptySince) / 1e9;
				if (t < POP_SECONDS) {
					// the bubble pops: vanilla's bursting bubble, growing as it bursts
					float s = icon * (1f + (float) (t / POP_SECONDS) * 0.3f);
					sprite(g, Identifier.withDefaultNamespace("hud/air_bursting"), x - (s - icon) / 2, y - (s - icon) / 2, s);
				} else {
					var pose = g.pose();
					pose.pushMatrix();
					pose.translate(x, y);
					pose.scale(icon / 32f, icon / 32f);
					g.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("world_list/warning"), 0, 0, 32, 32);
					pose.popMatrix();
				}
				x += icon + 10 * k;
				// 0%, flashing red (fades in and out twice a second)
				float pulse = 0.25f + 0.75f * (float) (0.5 + 0.5 * Math.cos(t * Math.PI * 4));
				CsUi.text(g, "0%", x, textY, TEXT, h, Math.round(pulse * 255) << 24 | 0xFF3030, -1);
			}
		} else {
			emptySince = -1;
		}
	}

	/** When the air ran out (-1: it hasn't), for the bubble's pop and the flashing 0%. */
	private static long emptySince = -1;
	private static final double POP_SECONDS = 0.3;

	private static String pct(float f) {
		return Math.max(0, Math.round(f * 100f)) + "%";
	}

	/** The vanilla heart sprite for the player's state (Hud.HeartType's order: poison, wither, frozen; hardcore). */
	private static Identifier heart(LocalPlayer p, boolean hardcore, boolean blink) {
		String type = p.hasEffect(MobEffects.POISON) ? "poisoned_" : p.hasEffect(MobEffects.WITHER) ? "withered_" : p.isFullyFrozen() ? "frozen_" : "";
		return Identifier.withDefaultNamespace("hud/heart/" + type + (hardcore ? "hardcore_" : "") + "full" + (blink ? "_blinking" : ""));
	}

	/** A 9 x 9 HUD sprite scaled into a square. */
	public static void sprite(GuiGraphicsExtractor g, Identifier sprite, float x, float y, float size) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size / 9f, size / 9f);
		g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 0, 0, 9, 9);
		pose.popMatrix();
	}

	/** An item icon scaled into a square (alpha < 1: faded, drawn as a grey ghost). */
	public static void item(GuiGraphicsExtractor g, ItemStack stack, float x, float y, float size, float alpha) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(size / 16f, size / 16f);
		g.item(stack, 0, 0);
		pose.popMatrix();
		if (alpha < 1f) {
			// item rendering ignores tint: fade it with the background colour over it
			g.fill((int) x, (int) y, (int) (x + size), (int) (y + size), Math.round((1f - alpha) * 160) << 24);
		}
	}
}
