package dev.countercraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Name tags for players drawn as agents (their vanilla body submit is skipped). */
@Mixin(EntityRenderer.class)
public interface EntityRendererInvoker {
	@Invoker("submitNameDisplay")
	void countercraft$submitNameDisplay(EntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera);
}
