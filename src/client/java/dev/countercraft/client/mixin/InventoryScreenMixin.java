package dev.countercraft.client.mixin;

import dev.countercraft.client.loadout.CsSlotPanel;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The CS slot column beside the survival inventory. */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenMixin {
	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void countercraft$csPanel(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta, final CallbackInfo ci) {
		ContainerScreenAccessor s = (ContainerScreenAccessor) this;
		CsSlotPanel.draw(g, s.countercraft$leftPos(), s.countercraft$topPos());
	}
}
