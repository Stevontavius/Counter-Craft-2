package dev.countercraft.client.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Like CS2, the HUD (vitals, weapons, money, paperdoll, crosshair) is gone while you're dead. */
@Mixin(Hud.class)
abstract class HudMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void countercraft$hideWhenDead(final GuiGraphicsExtractor g, final DeltaTracker delta, final CallbackInfo ci) {
		var p = net.minecraft.client.Minecraft.getInstance().player;
		if (dev.countercraft.client.death.DeathCam.active() || p != null && p.isDeadOrDying()) {
			ci.cancel();
		}
	}
}
