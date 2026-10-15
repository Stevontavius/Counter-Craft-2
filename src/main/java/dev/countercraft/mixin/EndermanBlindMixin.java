package dev.countercraft.mixin;

import dev.countercraft.MobVision;
import net.minecraft.world.entity.monster.Enderman;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A flashed enderman can't teleport until its sight comes back. */
@Mixin(Enderman.class)
abstract class EndermanBlindMixin {
	@Inject(method = {"teleport()Z", "teleport(DDD)Z"}, at = @At("HEAD"), cancellable = true)
	private void countercraft$blind(CallbackInfoReturnable<Boolean> cir) {
		if (MobVision.blinded((Enderman) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
