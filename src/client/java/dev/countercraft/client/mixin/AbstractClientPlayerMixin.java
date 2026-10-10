package dev.countercraft.client.mixin;

import dev.countercraft.client.movement.MovementSettings;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS2's FOV never breathes with speed: no sprint / speed-attribute FOV change (bows and spyglasses keep theirs). */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("HEAD"), cancellable = true)
	private void countercraft$steadyFov(final boolean firstPerson, final float effectScale, final CallbackInfoReturnable<Float> cir) {
		if (MovementSettings.enabled && (Object) this instanceof LocalPlayer p && !p.isUsingItem() && !p.isScoping()) {
			cir.setReturnValue(1.0f);
		}
	}
}
