package dev.countercraft.mixin;

import dev.countercraft.team.CsInventory;
import dev.countercraft.team.CsSlots;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player's inventory menu gets the 8 CS slots (primary, pistol, knife, 4 grenades, Zeus) as a column left of the
 * inventory, after vanilla's 46 slots. Shift-click moves between them and the inventory.
 */
@Mixin(InventoryMenu.class)
abstract class InventoryMenuMixin extends AbstractCraftingMenu {
	private InventoryMenuMixin() {
		super(null, 0, 0, 0);
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void countercraft$csSlots(final Inventory inventory, final boolean active, final Player owner, final CallbackInfo ci) {
		CsSlots.Inv inv = new CsSlots.Inv(owner);
		for (int i = 0; i < CsInventory.MENU_SLOTS; i++) {
			addSlot(new CsSlots.CsSlot(inv, i, CsSlots.X, CsSlots.Y + CsSlots.row(i) * 18));
		}
	}

	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void countercraft$csQuickMove(final Player player, final int index, final CallbackInfoReturnable<ItemStack> cir) {
		int csEnd = CsSlots.MENU_START + CsInventory.MENU_SLOTS;
		Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return;
		}
		ItemStack stack = slot.getItem();
		if (index >= CsSlots.MENU_START && index < csEnd) {
			// CS slot -> inventory
			ItemStack copy = stack.copy();
			if (!moveItemStackTo(stack, 9, 45, false)) {
				cir.setReturnValue(ItemStack.EMPTY);
				return;
			}
			slot.setChanged();
			cir.setReturnValue(copy);
			return;
		}
		if (index >= 9 && index < 45) {
			// inventory -> its CS slot, if free
			int target = CsInventory.slotFor(stack);
			if (target < 0 || target == CsInventory.KNIFE) {
				return;
			}
			int from = CsSlots.MENU_START + target;
			int to = target == CsInventory.GRENADE0 ? from + CsInventory.GRENADES : from + 1;
			ItemStack copy = stack.copy();
			if (moveItemStackTo(stack, from, to, false)) {
				slot.setChanged();
				if (stack.isEmpty()) {
					slot.set(ItemStack.EMPTY);
				}
				cir.setReturnValue(copy);
			}
		}
	}
}
