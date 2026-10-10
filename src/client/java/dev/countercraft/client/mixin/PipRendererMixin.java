package dev.countercraft.client.mixin;

import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Picture-in-picture colour textures can also be copied from (USAGE_COPY_SRC), so the deep dive's killer picture can be
 * read back and saved ({@link dev.countercraft.client.steam.KillerPip}).
 */
@Mixin(PictureInPictureRenderer.class)
abstract class PipRendererMixin {
	@ModifyConstant(method = "prepareTexturesAndProjection", constant = @Constant(intValue = 13))
	private int countercraft$copySource(int usage) {
		return usage | com.mojang.renderpearl.api.textures.GpuTexture.USAGE_COPY_SRC;
	}
}
