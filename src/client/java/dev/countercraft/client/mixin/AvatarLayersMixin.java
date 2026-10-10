package dev.countercraft.client.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The glow-only Minecraft model under an agent (AgentRenderMixin) has no armor, cape or held item layers. */
@Mixin(AvatarRenderer.class)
abstract class AvatarLayersMixin {
	@Inject(method = "shouldRenderLayers(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Z", at = @At("HEAD"), cancellable = true)
	private void countercraft$noLayers(final AvatarRenderState state, final CallbackInfoReturnable<Boolean> cir) {
		if (dev.countercraft.client.agent.AgentClient.outlineOnly) {
			cir.setReturnValue(false);
		}
	}
}
