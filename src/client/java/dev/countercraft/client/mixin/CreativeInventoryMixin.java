package dev.countercraft.client.mixin;

import dev.countercraft.client.loadout.CsSlotPanel;
import dev.countercraft.team.CsInventory;
import dev.countercraft.team.CsSlots;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Creative's inventory tab: the CS slots in their column (creative re-lays out every slot) and the panel behind them. */
@Mixin(CreativeModeInventoryScreen.class)
abstract class CreativeInventoryMixin {
	@Shadow
	private static CreativeModeTab selectedTab;

	@Inject(method = "selectTab", at = @At("TAIL"))
	private void countercraft$csSlots(final CreativeModeTab tab, final CallbackInfo ci) {
		if (tab.getType() != CreativeModeTab.Type.INVENTORY) {
			return;
		}
		var slots = ((AbstractContainerScreen<?>) (Object) this).getMenu().slots;
		for (int i = 0; i < CsInventory.SIZE; i++) {
			int idx = CsSlots.MENU_START + i;
			if (idx < slots.size()) {
				Slot s = slots.get(idx);
				((SlotAccessor) s).countercraft$setX(CsSlots.X);
				((SlotAccessor) s).countercraft$setY(CsSlots.Y + CsSlots.row(i) * 18);
			}
		}
	}

	@Inject(method = "hasClickedOutside", at = @At("HEAD"), cancellable = true)
	private void countercraft$csColumnInside(final double mx, final double my, final int left, final int top,
			final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (selectedTab != null && selectedTab.getType() == CreativeModeTab.Type.INVENTORY && CsSlots.inside(mx, my, left, top)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void countercraft$csPanel(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float delta, final CallbackInfo ci) {
		if (selectedTab != null && selectedTab.getType() == CreativeModeTab.Type.INVENTORY) {
			ContainerScreenAccessor s = (ContainerScreenAccessor) this;
			CsSlotPanel.draw(g, s.countercraft$leftPos(), s.countercraft$topPos());
		}
	}
}
