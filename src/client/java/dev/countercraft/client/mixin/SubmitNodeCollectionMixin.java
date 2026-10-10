package dev.countercraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.client.gpu.GpuMobs;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every model submit passes here (the storage forwards to its collections): mobs go to the GPU when they can. */
@Mixin(SubmitNodeCollection.class)
abstract class SubmitNodeCollectionMixin {
	@Inject(method = "submitModel", at = @At("HEAD"), cancellable = true)
	private void countercraft$gpuMob(final Model<?> model, final Object state, final PoseStack poseStack, final RenderType type, final int light,
			final int overlay, final int color, final UvMapping uv, final int outline, final CallbackInfo ci) {
		if (GpuMobs.submit(model, state, poseStack, type, light, overlay, color, uv, outline)) {
			ci.cancel();
		}
	}
}
