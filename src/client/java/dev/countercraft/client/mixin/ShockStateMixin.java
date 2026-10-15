package dev.countercraft.client.mixin;

import dev.countercraft.client.fx.MiniLightning;
import dev.countercraft.client.fx.ShockLayer;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A creature's render state says whether it's zapped (ShockLayer draws the shock on its model). Dev: with
 * -Dcountercraft.zapDemo=true every creature but players stays zapped, to look the effect over.
 */
@Mixin(LivingEntityRenderer.class)
abstract class ShockStateMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void countercraft$zap(final LivingEntity entity, final LivingEntityRenderState state, final float partial, final CallbackInfo ci) {
		long at = MiniLightning.zappedAt(entity.getId());
		if (at < 0 && ShockLayer.DEMO &&!(entity instanceof net.minecraft.world.entity.player.Player)) {
			long now = System.nanoTime();
			at = now - now % 1_200_000_000L; // zapped again every 1.2 s: the net forms, stays, goes out
		}
		((FabricRenderState) state).setData(ShockLayer.ZAP, at < 0 ? ShockLayer.Zap.NONE : new ShockLayer.Zap(entity.getId(), at));
	}
}
