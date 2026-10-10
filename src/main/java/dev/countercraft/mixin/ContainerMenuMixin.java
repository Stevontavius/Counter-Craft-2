package dev.countercraft.mixin;

import dev.countercraft.team.HotbarRule;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A number key / F swap doesn't put a CS2 weapon in the hotbar or off-hand ({@link HotbarRule}). */
@Mixin(AbstractContainerMenu.class)
abstract class ContainerMenuMixin {
	@Inject(method = "doClick", at = @At("HEAD"), cancellable = true)
	private void countercraft$noCsSwapToHand(final int slotId, final int button, final ContainerInput input, final Player player, final CallbackInfo ci) {
		if (input != ContainerInput.SWAP || slotId < 0) {
			return;
		}
		AbstractContainerMenu self = (AbstractContainerMenu) (Object) this;
		if (slotId >= self.slots.size()) {
			return;
		}
		Slot slot = self.slots.get(slotId);
		if (HotbarRule.blocked(player.getInventory(), button, slot.getItem())) {
			ci.cancel();
		}
	}
}
