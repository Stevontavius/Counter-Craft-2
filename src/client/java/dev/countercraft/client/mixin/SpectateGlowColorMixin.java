package dev.countercraft.client.mixin;

import dev.countercraft.client.death.Spectate;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Another Chance's X-Ray glow is red, whatever the player's team colour. */
@Mixin(EntityRenderer.class)
abstract class SpectateGlowColorMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("TAIL"))
	private void countercraft$red(final Entity entity, final EntityRenderState state, final float partial, final CallbackInfo ci) {
		if (Spectate.xray(entity)) {
			state.outlineColor = 0xFFFF0000;
		}
	}
}
