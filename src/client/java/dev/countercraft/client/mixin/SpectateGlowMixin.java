package dev.countercraft.client.mixin;

import dev.countercraft.client.death.Spectate;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Another Chance's X-Ray: the players alive glow (in red: SpectateGlowColorMixin). */
@Mixin(Minecraft.class)
abstract class SpectateGlowMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void countercraft$xray(final Entity entity, final CallbackInfoReturnable<Boolean> cir) {
		if (Spectate.xray(entity)) {
			cir.setReturnValue(true);
		}
	}
}
