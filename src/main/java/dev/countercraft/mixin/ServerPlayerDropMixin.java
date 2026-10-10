package dev.countercraft.mixin;

import dev.countercraft.team.CsDrop;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every item a player drops (Q, throwing it out of the inventory screen, death loot) goes through here: CS2 items
 * become CS2 dropped weapons ({@link CsDrop}); Minecraft items drop as usual.
 */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerDropMixin {
	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)Lnet/minecraft/world/entity/item/ItemEntity;",
			at = @At("HEAD"), cancellable = true)
	private void countercraft$csDrop(final ItemStack stack, final boolean randomly, final Prediction prediction, final CallbackInfoReturnable<ItemEntity> cir) {
		if (stack.isEmpty() || !CsDrop.isCsItem(stack)) {
			return;
		}
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (randomly) {
			CsDrop.fall(self, stack); // death loot
		} else {
			CsDrop.toss(self, stack);
			if (dev.countercraft.team.CsInventory.active(self)
					&& dev.countercraft.team.CsInventory.of(self).get(self.getInventory().getSelectedSlot()).isEmpty()) {
				dev.countercraft.team.CsInventory.selectBest(self);
			}
		}
		cir.setReturnValue(null);
	}
}
