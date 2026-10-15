package dev.countercraft.team;

import dev.countercraft.Combat;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GunItem;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Server side of the buy menu: the item must be one the player's team can buy and the player must afford it
 * ({@link Economy}: survival pays, creative buys for free).
 * Weapons go to the player's CS slots ({@link CsInventory}): a primary / pistol / Zeus replaces (drops) the one there,
 * grenades follow CS2's carry limits (one of each, two flashbangs). Kevlar / Kevlar + Helmet set the player's armor.
 * Counter-Craft's own (not CS2's): the nade kit ({@link #NADE_KIT}: the team's HE, flashbang, smoke and fire grenade you
 * don't carry, {@link #KIT_DISCOUNT}% off) and wartime pricing (grenades {@code sv_wartime_discount}% off for a player
 * in an active raid).
 */
public final class Buying {
	public static final String NADE_KIT = "item_nadekit";
	/** The nade kit's discount, %. */
	public static final int KIT_DISCOUNT = 10;
	private static final List<String> KIT_T = List.of("weapon_hegrenade", "weapon_flashbang", "weapon_smokegrenade", "weapon_molotov");
	private static final List<String> KIT_CT = List.of("weapon_hegrenade", "weapon_flashbang", "weapon_smokegrenade", "weapon_incgrenade");

	/** A player in an active raid: the grenade discount they get there, % (synced to them for the buy menu). */
	public static final AttachmentType<Integer> WARTIME = AttachmentRegistry.<Integer>builder()
			.syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("wartime"));

	private Buying() {
	}

	/** Forces class init (and so the WARTIME attachment's registration, which both sides need) during mod init. */
	public static void init() {
	}

	/** Every server tick: who's in an active raid (checked each second). */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		int off = dev.countercraft.ServerCvars.get("sv_wartime_discount");
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			var raid = off > 0 ? p.level().getRaidAt(p.blockPosition()) : null;
			Integer want = raid != null && raid.isActive() ? off : null;
			if (!java.util.Objects.equals(want, p.getAttached(WARTIME))) {
				if (want == null) {
					p.removeAttached(WARTIME);
				} else {
					p.setAttached(WARTIME, want);
				}
			}
		}
	}

	/** The nade kit's grenades for this player now: the team's four they don't carry, as far as their grenade slots go. */
	public static List<String> kit(Player p) {
		List<String> out = new ArrayList<>();
		CsInventory.Data d = CsInventory.of(p);
		int free = 0;
		for (int i = CsInventory.GRENADE0; i < CsInventory.GRENADE0 + CsInventory.GRENADES; i++) {
			free += d.get(i).isEmpty() ? 1 : 0;
		}
		for (String item : Teams.CT.equals(Teams.of(p)) ? KIT_CT : KIT_T) {
			boolean carried = false;
			for (int i = CsInventory.GRENADE0; i < CsInventory.GRENADE0 + CsInventory.GRENADES; i++) {
				carried |= d.get(i).getItem() instanceof GrenadeItem g && g.weaponId().equals(Loadouts.modId(item));
			}
			if (!carried && out.size() < free) {
				out.add(item);
			}
		}
		return out;
	}

	/** What a grenade (or the nade kit) costs this player now, after the kit's and wartime's discounts. */
	public static int price(Player p, String item) {
		if (item.equals(NADE_KIT)) {
			int sum = 0;
			for (String g : kit(p)) {
				sum += discounted(p, g, KIT_DISCOUNT);
			}
			return sum;
		}
		return discounted(p, item, 0);
	}

	/** One item's price with {@code kitOff}% off, then the wartime discount for grenades. */
	private static int discounted(Player p, String item, int kitOff) {
		int base = Loadouts.price(item);
		Integer war = CounterCraft.GRENADES.containsKey(Loadouts.modId(item)) ? p.getAttached(WARTIME) : null;
		return Math.round(base * (100 - kitOff) / 100f * (100 - (war == null ? 0 : war)) / 100f);
	}

	public static void buy(ServerPlayer player, String item) {
		String team = Teams.of(player);
		if (!allowed(player, team, item) || player.isSpectator() || DayCycle.refuseBuy(player)) {
			return;
		}
		int price = price(player, item);
		if (ExtraAmmo.slotOf(item) != null) {
			price = ExtraAmmo.price(player, ExtraAmmo.slotOf(item)); // one magazine for that slot's gun (AmmoMatrix)
		}
		if (item.equals("item_assaultsuit") && Combat.fullArmorNoHelmet(player)) {
			price = Loadouts.HELMET_PRICE; // CS2: just the helmet
		}
		if (item.equals(NADE_KIT)) {
			buyKit(player);
			return;
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

	/** Whether this player may buy {@code item}: their team's loadout; the defuse kit only with the C4 test on (CTs). */
	public static boolean allowed(Player player, String team, String item) {
		if (item.equals("item_defuser") && !CsInventory.c4Test(player)) {
			return false;
		}
		return item.equals(NADE_KIT) || Loadouts.allowed(team, item);
	}

	/** The nade kit: each grenade it holds is bought (and refundable) on its own, at its discounted price. */
	private static void buyKit(ServerPlayer player) {
		List<String> items = kit(player);
		if (items.isEmpty() || !Economy.canAfford(player, price(player, NADE_KIT))) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.3f, 1.4f);
			return;
		}
		int spent = 0;
		for (String item : items) {
			int each = discounted(player, item, KIT_DISCOUNT);
			if (give(player, Loadouts.modId(item), Economy.freeBuy(player) ? 0 : each, item)) {
				spent += each;
			}
		}
		if (spent > 0 || Economy.freeBuy(player)) {
			Economy.spend(player, spent);
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
