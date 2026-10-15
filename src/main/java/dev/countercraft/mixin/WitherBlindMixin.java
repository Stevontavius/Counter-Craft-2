package dev.countercraft.mixin;

import dev.countercraft.MobVision;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Wither's heads pick targets on their own (not through Sensing), so smoke and flashes are checked at the shot: a
 * flashed Wither, or a head whose view of its target is smoked, holds its skull.
 */
@Mixin(WitherBoss.class)
abstract class WitherBlindMixin {
	@Inject(method = "performRangedAttack(ILnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), cancellable = true)
	private void countercraft$blind(int head, LivingEntity target, CallbackInfo ci) {
		WitherBoss self = (WitherBoss) (Object) this;
		if (MobVision.blinded(self) || (target != null && MobVision.smokeBlocks(self, target))) {
			ci.cancel();
		}
	}
}
