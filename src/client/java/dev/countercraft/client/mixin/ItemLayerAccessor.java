package dev.countercraft.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** An item layer's display transform (where the item model sits in the hand). */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerAccessor {
	@Accessor("itemTransform")
	ItemTransform countercraft$itemTransform();
}
