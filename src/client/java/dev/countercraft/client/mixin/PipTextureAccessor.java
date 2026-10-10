package dev.countercraft.client.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The picture-in-picture renderer's colour texture (for reading the killer picture back). */
@Mixin(PictureInPictureRenderer.class)
public interface PipTextureAccessor {
	@Accessor("texture")
	GpuTexture countercraft$texture();
}
