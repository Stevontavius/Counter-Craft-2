package dev.countercraft.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The vanilla death screen doesn't draw for the one frame before CsDeathScreen replaces it. */
@Mixin(DeathScreen.class)
abstract class DeathScreenMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void countercraft$hide(final GuiGraphicsExtractor g, final int mx, final int my, final float delta, final CallbackInfo ci) {
		ci.cancel();
	}

	@Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
	private void countercraft$hideBackground(final GuiGraphicsExtractor g, final int mx, final int my, final float delta, final CallbackInfo ci) {
		ci.cancel();
	}
}
