package dev.countercraft.mixin;

import dev.countercraft.team.CsInventory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CS mode: the hand holds the selected CS slot ({@link CsInventory}) instead of the hotbar slot. Everything that asks
 * for the held item (guns, rendering, other players' view, item use) goes through these, on both sides. The knife
 * can't be dropped.
 */
@Mixin(Inventory.class)
abstract class InventoryMixin {
	@Shadow
	@Final
	public Player player;

	@Shadow
	public abstract int getSelectedSlot();

	/** Pick-ups and /give: a CS2 weapon goes to the main rows, never the hotbar ({@link dev.countercraft.team.HotbarRule}). */
	@Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
	private void countercraft$csToMainRows(final int slot, final ItemStack stack, final CallbackInfoReturnable<Boolean> cir) {
		if (slot == -1 && stack.getItem() instanceof dev.countercraft.gun.CsWeapon) {
			cir.setReturnValue(dev.countercraft.team.HotbarRule.addToMainRows((Inventory) (Object) this, stack));
		}
	}

	@Inject(method = "getSelectedItem", at = @At("HEAD"), cancellable = true)
	private void countercraft$csSelected(final CallbackInfoReturnable<ItemStack> cir) {
		if (CsInventory.active(player)) {
			cir.setReturnValue(CsInventory.of(player).get(getSelectedSlot()));
		}
	}

	@Inject(method = "setSelectedItem", at = @At("HEAD"), cancellable = true)
	private void countercraft$csSetSelected(final ItemStack stack, final CallbackInfoReturnable<ItemStack> cir) {
		if (CsInventory.active(player)) {
			int slot = getSelectedSlot();
			CsInventory.Data d = CsInventory.of(player);
			if (slot < 0 || slot >= CsInventory.SIZE || slot == CsInventory.KNIFE) {
				cir.setReturnValue(ItemStack.EMPTY);
				return;
			}
			ItemStack old = d.items.set(slot, stack);
			CsInventory.markDirty(player);
			cir.setReturnValue(old);
		}
	}

	@Inject(method = "removeFromSelected", at = @At("HEAD"), cancellable = true)
	private void countercraft$csDrop(final boolean all, final CallbackInfoReturnable<ItemStack> cir) {
		if (CsInventory.active(player)) {
			int slot = getSelectedSlot();
			CsInventory.Data d = CsInventory.of(player);
			ItemStack s = d.get(slot);
			if (s.isEmpty() || slot == CsInventory.KNIFE) {
				cir.setReturnValue(ItemStack.EMPTY);
				return;
			}
			ItemStack out = all ? s.copy() : s.split(1);
			if (all) {
				d.items.set(slot, ItemStack.EMPTY);
			}
			if (d.get(slot).isEmpty()) {
				d.items.set(slot, ItemStack.EMPTY);
			}
			CsInventory.markDirty(player);
			cir.setReturnValue(out);
		}
	}
}
