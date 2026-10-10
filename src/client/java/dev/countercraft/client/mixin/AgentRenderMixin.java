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
		if (AgentClient.outlineOnly) {
			return; // the Minecraft model, for the glow alone (below)
		}
		if (state instanceof AvatarRenderState avatar && AgentClient.submit(avatar, poseStack, collector, (LivingEntityRenderer<?, ?, ?>) (Object) this)) {
			if (state.nameTag != null) {
				((EntityRendererInvoker) this).countercraft$submitNameDisplay(state, poseStack, collector, camera);
			}
			if (state.outlineColor != 0) {
				// Minecraft's glow (the Glowing effect, a spectral arrow, Another Chance's X-Ray): the agent draws outside its
				// pass, so the Minecraft model goes into the outline alone (vanilla's "invisible but glowing"), no layers
				boolean invisible = state.isInvisible;
				boolean invisibleToPlayer = state.isInvisibleToPlayer;
				net.minecraft.network.chat.Component tag = state.nameTag;
				state.isInvisible = true;
				state.isInvisibleToPlayer = true;
				state.nameTag = null;
				AgentClient.outlineOnly = true;
				try {
					@SuppressWarnings({"unchecked", "rawtypes"})
					LivingEntityRenderer self = (LivingEntityRenderer) (Object) this;
					self.submit(state, poseStack, collector, camera);
				} finally {
					AgentClient.outlineOnly = false;
					state.isInvisible = invisible;
					state.isInvisibleToPlayer = invisibleToPlayer;
					state.nameTag = tag;
				}
			}
			ci.cancel();
		}
	}
}
