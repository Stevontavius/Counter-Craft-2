package dev.countercraft.client.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS2's death cam colour grading as a post effect while the death cam plays. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerPostMixin {
	@Inject(method = "getActivePostEffects", at = @At("RETURN"), cancellable = true)
	private void countercraft$deathcam(final CallbackInfoReturnable<List<Identifier>> cir) {
		if (dev.countercraft.client.death.DeathCam.active()) {
			List<Identifier> out = new ArrayList<>(cir.getReturnValue());
			dev.countercraft.client.death.DeathCam.addEffects(out);
			cir.setReturnValue(out);
		}
	}
}
