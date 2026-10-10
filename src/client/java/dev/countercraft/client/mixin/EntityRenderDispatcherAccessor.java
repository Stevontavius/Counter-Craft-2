package dev.countercraft.client.mixin;

import java.util.Map;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every entity type's renderer (MobHeadsClient reads their models). */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccessor {
	@Accessor("renderers")
	Map<EntityType<?>, EntityRenderer<?, ?>> countercraft$renderers();
}
