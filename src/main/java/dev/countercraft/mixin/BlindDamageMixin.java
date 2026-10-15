package dev.countercraft.mixin;

import dev.countercraft.MobVision;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** A flashed mob takes {@link MobVision#BLIND_DAMAGE} times the damage while it can't see (Counter-Craft's rule). */
@Mixin(LivingEntity.class)
abstract class BlindDamageMixin {
	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float countercraft$blind(float amount) {
		return (Object) this instanceof Mob m && MobVision.blinded(m) ? amount * MobVision.BLIND_DAMAGE : amount;
	}
}
