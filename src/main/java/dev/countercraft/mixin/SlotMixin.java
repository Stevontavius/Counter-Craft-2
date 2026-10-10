package dev.countercraft.mixin;

import dev.countercraft.team.HotbarRule;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS2 weapons don't go in the hotbar or off-hand slots of any menu ({@link HotbarRule}). */
@Mixin(Slot.class)
abstract class SlotMixin {
	@Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
	private void countercraft$noCsInHand(final ItemStack stack, final CallbackInfoReturnable<Boolean> cir) {
		Slot self = (Slot) (Object) this;
		if (cir.getReturnValueZ() && HotbarRule.blocked(self.container, self.getContainerSlot(), stack)) {
			cir.setReturnValue(false);
		}
	}
}
