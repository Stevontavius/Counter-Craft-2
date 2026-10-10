package dev.countercraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Mob head hitboxes: the size a renderer draws its model at (a ghast 4.5x, a horse 1.1x...). Agents: the renderer's
 * layers (an agent wears the vanilla elytra from the player renderer's wings layer).
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
	@Accessor("layers")
	java.util.List<net.minecraft.client.renderer.entity.layers.RenderLayer<?, ?>> countercraft$layers();

	@Invoker("scale")
	void countercraft$scale(LivingEntityRenderState state, PoseStack poseStack);
}
