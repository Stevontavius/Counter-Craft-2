package dev.countercraft.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Agents hold Minecraft items by their handle: the item's layers, for their display transform. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
	@Accessor("layers")
	ItemStackRenderState.LayerRenderState[] countercraft$layers();

	@Accessor("activeLayerCount")
	int countercraft$activeLayerCount();
}
