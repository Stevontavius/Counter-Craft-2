package dev.countercraft.client.mixin;

import dev.countercraft.client.movement.CsPlayerMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The local player moves with CS2 physics on land; vanilla handles water, ladders, flying, elytra and riding. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void countercraft$csTravel(final Vec3 input, final CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer p) {
			if (CsPlayerMovement.handles(p)) {
				CsPlayerMovement.travel(p);
				ci.cancel();
			} else if (CsPlayerMovement.active()) {
				CsPlayerMovement.release();
			}
		}
	}

	/** Jumps are CS2's (impulse, bhop penalty) while CS movement runs. */
	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
	private void countercraft$noVanillaJump(final CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer p && CsPlayerMovement.handles(p)) {
			ci.cancel();
		}
	}
}
