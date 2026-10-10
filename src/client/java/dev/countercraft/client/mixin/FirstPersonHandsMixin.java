package dev.countercraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.countercraft.client.GunClient;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holding a gun with a converted viewmodel: draw the CS2 arms + gun instead of the vanilla hands. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class FirstPersonHandsMixin {
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void countercraft$viewmodel(final float partialTick, final PoseStack poseStack, final SubmitNodeCollector collector,
			final PlayerRenderState player, final FirstPersonHandsAndItemsRenderState state, final CallbackInfo ci) {
		var me = net.minecraft.client.Minecraft.getInstance().player;
		if (dev.countercraft.client.death.DeathCam.active() || me != null && me.isDeadOrDying()) {
			ci.cancel(); // dead: no viewmodel or hands (CS2 shows the death cam instead)
			return;
		}
		var watched = dev.countercraft.client.death.SpectateViewmodel.target();
		if (watched != null) {
			// Another Chance, through someone's eyes: their viewmodel, or their Minecraft hands and items
			if (dev.countercraft.client.death.SpectateViewmodel.render(watched, poseStack, collector, player.avatarRenderState.lightCoords)) {
				ci.cancel();
			} else {
				dev.countercraft.client.death.SpectateViewmodel.vanillaHands(watched, player, state, partialTick);
			}
			return;
		}
		if (GunClient.renderViewmodel(poseStack, collector, player.avatarRenderState.lightCoords)) {
			ci.cancel();
		}
	}
}
