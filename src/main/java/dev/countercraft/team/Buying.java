package dev.countercraft.team;

import dev.countercraft.Combat;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of the buy menu: the item must be one the player's team can buy and the player must afford it
 * ({@link Economy}: survival pays, creative buys for free).
 * Weapons go to the player's CS slots ({@link CsInventory}): a primary / pistol / Zeus replaces (drops) the one there,
 * grenades follow CS2's carry limits (one of each, two flashbangs). Kevlar / Kevlar + Helmet set the player's armor.
 */
public final class Buying {
	private Buying() {
	}

	public static void buy(ServerPlayer player, String item) {
		String team = Teams.of(player);
		if (!Loadouts.allowed(team, item) || player.isSpectator() || DayCycle.refuseBuy(player)) {
			return;
		}
		int price = Loadouts.price(item);
		if (ExtraAmmo.slotOf(item) != null) {
			price = ExtraAmmo.price(player, ExtraAmmo.slotOf(item)); // one magazine for that slot's gun (AmmoMatrix)
		}
		if (item.equals("item_assaultsuit") && Combat.fullArmorNoHelmet(player)) {
			price = Loadouts.HELMET_PRICE; // CS2: just the helmet
		}
		if (!Economy.canAfford(player, price)) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.3f, 1.4f);
			return;
		}
		boolean ok;
		// what's paid (creative buys free), remembered for a refund (CS2's sell back: Refunds)
		int paid = Economy.freeBuy(player) ? 0 : price;
		float armorBefore = Combat.armorOf(player);
		boolean helmetBefore = Combat.hasHelmet(player);
		switch (item) {
			case "item_kevlar" -> ok = Combat.giveArmor(player, false);
			case "item_assaultsuit" -> ok = Combat.giveArmor(player, true);
			case "item_defuser" -> ok = Combat.giveDefuser(player);
			case ExtraAmmo.PRIMARY_ITEM, ExtraAmmo.SECONDARY_ITEM -> ok = ExtraAmmo.buy(player, ExtraAmmo.slotOf(item));
			default -> ok = give(player, Loadouts.modId(item), paid, item);
		}
		if (ok && (item.equals("item_kevlar") || item.equals("item_assaultsuit") || item.equals("item_defuser"))) {
			Refunds.boughtGear(player, item, paid, armorBefore, helmetBefore);
		}
		if (ok && item.startsWith("weapon_") && !CsInventory.active(player)) {
			CsInventory.toggle(player); // the bought weapon is in the CS slots: switch to them
		}
		if (ok) {
			Economy.spend(player, price);
			dev.countercraft.Achievements.bought(player);
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.2f);
		}
	}

	/** A gun as the player gets it: with their loadout's finish for it, like CS2. */
	public static ItemStack gunStack(ServerPlayer player, GunItem gun) {
		ItemStack stack = new ItemStack(gun);
		int skin = Cosmetics.skinFor(player, gun.spec().id());
		if (skin > 0) {
			stack.set(dev.countercraft.ModComponents.SKIN, skin);
		}
		return stack;
	}

	/**
	 * Into the player's CS slots ({@link CsInventory#give}: weapons replace and drop the old one, grenade limits), marked
	 * as bought for {@code paid} (a refund gives that back).
	 */
	private static boolean give(ServerPlayer player, String id, int paid, String item) {
		GunItem gun = CounterCraft.GUNS.get(id);
		if (gun != null) {
			return CsInventory.give(player, Refunds.stamp(player, gunStack(player, gun), paid, item));
		}
		GrenadeItem grenade = CounterCraft.GRENADES.get(id);
		return grenade != null && CsInventory.give(player, Refunds.stamp(player, new ItemStack(grenade), paid, item));
	}
}
