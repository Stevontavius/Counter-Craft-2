package dev.countercraft.client.mixin;

import dev.countercraft.client.agent.AgentClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advance each visible player's agent animation once per frame, as their render state is built. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void countercraft$animate(final Avatar entity, final AvatarRenderState state, final float partialTick, final CallbackInfo ci) {
		if (entity instanceof AbstractClientPlayer p) {
			AgentClient.extract(p, state, partialTick);
		}
	}
}
