package dev.countercraft.client.mixin;

import dev.countercraft.client.gpu.GpuAgents;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every submit of the frame is in once vanilla prepares its feature frame: write the GPU agents' poses then. */
@Mixin(FeatureRenderDispatcher.class)
abstract class FeatureFrameMixin {
	@Inject(method = "prepareFrame", at = @At("RETURN"))
	private void countercraft$prepareAgents(final SubmitNodeStorage storage, final CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
		GpuAgents.prepare();
	}
}
