package dev.countercraft.client.mixin;

import dev.countercraft.client.fx.MuzzleLight;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities (and so the viewmodel, which uses the player's light) light up from a nearby muzzle flash. */
@Mixin(EntityRenderer.class)
abstract class EntityLightMixin<T extends Entity> {
	@Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true)
	private void countercraft$muzzleLight(final T entity, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
		int add = MuzzleLight.at(pos);
		if (add > cir.getReturnValueI()) {
			cir.setReturnValue(add);
		}
	}
}
