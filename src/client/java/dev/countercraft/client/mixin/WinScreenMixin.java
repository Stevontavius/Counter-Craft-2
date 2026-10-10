package dev.countercraft.client.mixin;

import dev.countercraft.CounterCraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end credits: the Counter-Strike 2 wordmark under the Minecraft logo (in the gap above the first credits line,
 * scrolling with it).
 */
@Mixin(WinScreen.class)
abstract class WinScreenMixin extends Screen {
	private static final Identifier CS2_LOGO = CounterCraft.id("textures/gui/logo/cs2_logo_horizontal.png");
	/** The texture's size (the converter rasterises CS2's SVG 96 px high). */
	private static final int TEX_W = 1794;
	private static final int TEX_H = 96;

	protected WinScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IFI)V", shift = At.Shift.AFTER))
	private void countercraft$cs2Logo(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		// the Minecraft logo is 256 wide at height + 50, its edition strip ends about 52 px lower; lines start at + 100
		int w = 200;
		int h = Math.round(w * TEX_H / (float) TEX_W);
		int x = (width - w) / 2;
		int y = height + 50 + 62;
		g.blit(RenderPipelines.GUI_TEXTURED, CS2_LOGO, x, y, 0f, 0f, w, h, TEX_W, TEX_H, TEX_W, TEX_H);
	}
}
