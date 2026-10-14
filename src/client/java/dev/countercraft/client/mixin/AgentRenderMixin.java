package dev.countercraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.client.agent.AgentClient;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players are drawn as their CS2 agent (with the held weapon) instead of the Minecraft model, when the pack has agents. */
@Mixin(LivingEntityRenderer.class)
abstract class AgentRenderMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"), cancellable = true)
	private void countercraft$agent(final LivingEntityRenderState state, final PoseStack poseStack, final SubmitNodeCollector collector,
			final CameraRenderState camera, final CallbackInfo ci) {
		// the agent draws Minecraft's glow itself, in its own shape (AgentRenderer.outline)
		if (state instanceof AvatarRenderState avatar && AgentClient.submit(avatar, poseStack, collector, (LivingEntityRenderer<?, ?, ?>) (Object) this)) {
			if (state.nameTag != null) {
				((EntityRendererInvoker) this).countercraft$submitNameDisplay(state, poseStack, collector, camera);
			}
			ci.cancel();
		}
	}
}
