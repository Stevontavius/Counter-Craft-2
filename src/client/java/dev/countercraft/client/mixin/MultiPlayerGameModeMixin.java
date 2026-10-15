package dev.countercraft.client.mixin;

import dev.countercraft.net.CreativeCsSlotPayload;
import dev.countercraft.team.CsInventory;
import dev.countercraft.team.CsSlots;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Creative sets slots with a packet the server limits to slots 1-45: the CS slots (46+) go through our own payload. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
	@Inject(method = "handleCreativeModeItemAdd", at = @At("HEAD"), cancellable = true)
	private void countercraft$csSlot(final ItemStack stack, final int slot, final CallbackInfo ci) {
		int i = slot - CsSlots.MENU_START;
		if (i >= 0 && i < CsInventory.MENU_SLOTS) {
			ClientPlayNetworking.send(new CreativeCsSlotPayload(i, stack.copy()));
			ci.cancel();
		}
	}
}
