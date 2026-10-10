package dev.countercraft.client.mixin;

import dev.countercraft.team.CsSlots;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The survival inventory: a click on the CS column is a slot click, not a click outside the window (which drops the item). */
@Mixin(AbstractRecipeBookScreen.class)
abstract class RecipeBookScreenMixin {
	@Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
	private void countercraft$csColumnInside(final double mx, final double my, final int left, final int top, final CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof InventoryScreen && CsSlots.inside(mx, my, left, top)) {
			cir.setReturnValue(false);
		}
	}
}
