package dev.countercraft.mixin;

import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every hit a living thing takes, the killing one included (before its health drops), for CS2's death panel tallies. */
@Mixin(CombatTracker.class)
abstract class CombatTrackerMixin {
	@Shadow
	@Final
	private LivingEntity mob;

	@Inject(method = "recordDamage", at = @At("HEAD"))
	private void countercraft$tally(final DamageSource source, final float damage, final CallbackInfo ci) {
		if (!mob.level().isClientSide()) {
			dev.countercraft.DeathTracker.damaged(mob, source, damage);
		}
	}
}
