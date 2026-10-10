package dev.countercraft.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.countercraft.client.gpu.GpuAgents;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The GPU agents draw in vanilla's solid feature pass, right after its own solid / cutout entities. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
abstract class SolidFeaturesMixin {
	@Inject(method = "executeSolid", at = @At("TAIL"))
	private void countercraft$drawAgents(final RenderPass pass, final CallbackInfo ci) {
		GpuAgents.execute(pass);
	}
}
