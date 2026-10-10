package dev.countercraft.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets menus add their own picture-in-picture element (the CS2 agent preview) to the GUI render state. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsAccessor {
	@Accessor("guiRenderState")
	GuiRenderState countercraft$guiRenderState();
}
