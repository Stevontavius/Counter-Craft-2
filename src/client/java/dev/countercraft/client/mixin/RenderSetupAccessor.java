package dev.countercraft.client.mixin;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A render type's texture bindings (sampler name to RenderSetup.TextureBinding, a package-private record). */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
	@Accessor("textures")
	Map<String, Object> countercraft$textures();
}
