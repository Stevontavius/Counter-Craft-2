package dev.countercraft.team;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** The CS slots as a vanilla container + slots, for the inventory screen (see InventoryMenuMixin). */
public final class CsSlots {
	/** Where the CS slots start in the player's inventory menu (after vanilla's 46). */
	public static final int MENU_START = 46;
	/** Position of the CS slot column, relative to the inventory screen (a column left of it). */
	public static final int X = -22;
	public static final int Y = 8;
	/** Rows shown: the knife slot is hidden from the inventory screen (it's set by the loadout only). */
	public static final int ROWS = CsInventory.MENU_SLOTS - 1;

	/** Screen row of CS slot {@code i} with the knife hidden (the knife itself: row 0, inactive). */
	public static int row(int i) {
		return i < CsInventory.KNIFE ? i : i == CsInventory.KNIFE ? 0 : i - 1;
	}

	/** Whether (mx, my) is over the CS column of a screen at (left, top): clicks there aren't "outside". */
	public static boolean inside(double mx, double my, int left, int top) {
		return mx >= left + X - 4 && mx < left + X + 20 && my >= top + Y - 4 && my < top + Y + ROWS * 18 + 2;
	}

	private CsSlots() {
	}

	/** The player's CS slots as a Container (reads and writes the synced attachment). */
	public static final class Inv implements Container {
		private final Player player;

		public Inv(Player player) {
			this.player = player;
		}

		@Override
		public int getContainerSize() {
			return CsInventory.SIZE;
		}

		@Override
		public boolean isEmpty() {
			for (ItemStack s : CsInventory.of(player).items) {
				if (!s.isEmpty()) {
					return false;
				}
			}
			return true;
		}

		@Override
		public ItemStack getItem(int slot) {
			return CsInventory.of(player).get(slot);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			ItemStack s = getItem(slot);
			if (s.isEmpty() || slot == CsInventory.KNIFE) {
				return ItemStack.EMPTY;
			}
			ItemStack out = s.split(count);
			if (s.isEmpty()) {
				CsInventory.of(player).items.set(slot, ItemStack.EMPTY);
			}
			setChanged();
			return out;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			ItemStack s = getItem(slot);
			CsInventory.of(player).items.set(slot, ItemStack.EMPTY);
			return s;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			if (slot >= 0 && slot < CsInventory.SIZE) {
				CsInventory.of(player).items.set(slot, stack);
				setChanged();
			}
		}

		@Override
		public void setChanged() {
			CsInventory.markDirty(player);
		}

		@Override
		public boolean stillValid(Player p) {
			return true;
		}

		@Override
		public void clearContent() {
			for (int i = 0; i < CsInventory.SIZE; i++) {
				if (i != CsInventory.KNIFE) {
					CsInventory.of(player).items.set(i, ItemStack.EMPTY);
				}
			}
			setChanged();
		}
	}

	/** One CS slot: only its kind of item; the knife slot can't be taken from or put into. */
	public static final class CsSlot extends Slot {
		public CsSlot(Container c, int index, int x, int y) {
			super(c, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return CsInventory.mayPlace(getContainerSlot(), stack);
		}

		@Override
		public boolean isActive() {
			return getContainerSlot() != CsInventory.KNIFE; // hidden: the knife only changes in the loadout
		}

		@Override
		public boolean mayPickup(Player player) {
			return getContainerSlot() != CsInventory.KNIFE;
		}

		@Override
		public int getMaxStackSize() {
			return 1;
		}
	}
}
