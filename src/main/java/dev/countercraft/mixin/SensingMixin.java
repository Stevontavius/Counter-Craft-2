package dev.countercraft.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs can't see through smoke or while flashed ({@link dev.countercraft.MobVision}). */
@Mixin(Sensing.class)
abstract class SensingMixin {
	@Shadow
	@Final
	private Mob mob;

	@Inject(method = "hasLineOfSight", at = @At("RETURN"), cancellable = true)
	private void countercraft$smokeAndFlash(final Entity target, final CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && !dev.countercraft.MobVision.canSee(mob, target, true)) {
			cir.setReturnValue(false);
		}
	}
}
