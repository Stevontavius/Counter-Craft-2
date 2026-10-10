package dev.countercraft.team;

import dev.countercraft.gun.CsWeapon;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * CS2 weapons (guns, knives, grenades, the Zeus) never go in the Minecraft hotbar or the off-hand, where they could be
 * held: only the CS slots (where they're used), the main inventory rows and containers (where they're stored). Slots
 * refuse them ({@code SlotMixin}), number-key / off-hand swaps don't move them there ({@code ContainerMenuMixin}),
 * pick-ups and /give put them in the main rows ({@code InventoryMixin}), and anything that still gets there is moved to
 * the main rows (or dropped) every tick ({@link #sweep}).
 */
public final class HotbarRule {
	private HotbarRule() {
	}

	/** A hotbar or off-hand slot of a player's inventory. */
	public static boolean handSlot(Container c, int index) {
		return c instanceof Inventory && (index >= 0 && index < Inventory.getSelectionSize() || index == Inventory.SLOT_OFFHAND);
	}

	/** {@code stack} may not go in this slot. */
	public static boolean blocked(Container c, int index, ItemStack stack) {
		return stack.getItem() instanceof CsWeapon && handSlot(c, index);
	}

	/**
	 * Inventory.add for a CS weapon: into the main rows only (stacks of the same first, then empty slots). Returns
	 * whether all of it went in; {@code stack} keeps what didn't.
	 */
	public static boolean addToMainRows(Inventory inv, ItemStack stack) {
		int first = Inventory.getSelectionSize();
		int end = Inventory.INVENTORY_SIZE;
		for (int i = first; i < end && !stack.isEmpty(); i++) {
			ItemStack have = inv.getItem(i);
			if (!have.isEmpty() && ItemStack.isSameItemSameComponents(have, stack) && have.getCount() < have.getMaxStackSize()) {
				int n = Math.min(stack.getCount(), have.getMaxStackSize() - have.getCount());
				have.grow(n);
				stack.shrink(n);
			}
		}
		for (int i = first; i < end && !stack.isEmpty(); i++) {
			if (inv.getItem(i).isEmpty()) {
				inv.setItem(i, stack.split(stack.getCount()));
			}
		}
		inv.setChanged();
		return stack.isEmpty();
	}

	/** Every server tick: a CS weapon in the hotbar or off-hand moves to the main rows, or is dropped if they're full. */
	public static void sweep(ServerPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			move(p, inv, i);
		}
		move(p, inv, Inventory.SLOT_OFFHAND);
	}

	private static void move(ServerPlayer p, Inventory inv, int slot) {
		ItemStack s = inv.getItem(slot);
		if (!(s.getItem() instanceof CsWeapon)) {
			return;
		}
		inv.setItem(slot, ItemStack.EMPTY);
		if (!addToMainRows(inv, s)) {
			CsDrop.toss(p, s);
		}
	}
}
