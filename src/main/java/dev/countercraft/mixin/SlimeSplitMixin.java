package dev.countercraft.mixin;

import dev.countercraft.gun.GrenadeEntity;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A slime or magma cube an HE killed doesn't split. */
@Mixin(AbstractCubeMob.class)
abstract class SlimeSplitMixin {
	@Inject(method = "getSplitCount", at = @At("HEAD"), cancellable = true)
	private void countercraft$heKill(CallbackInfoReturnable<Integer> cir) {
		if (GrenadeEntity.NO_SPLIT.contains(this)) {
			cir.setReturnValue(0);
		}
	}
}
