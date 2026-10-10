package dev.countercraft.mixin;

import dev.countercraft.CounterCraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CS2 has no hit knockback: bullets don't push players (mobs still get Minecraft's push, it keeps them off you, except
 * the Ender Dragon, which bullets don't move), and molotov / incendiary fire pushes nothing (a creature standing in the
 * flames would otherwise be shoved out of them).
 */
@Mixin(LivingEntity.class)
abstract class NoKnockbackMixin {
	@Inject(method = "dealDefaultKnockback", at = @At("HEAD"), cancellable = true)
	private void countercraft$noKnockback(final DamageSource source, final float amount, final boolean blocked, final CallbackInfo ci) {
		if (source.is(CounterCraft.FIRE) || (source.is(CounterCraft.BULLET) || source.is(CounterCraft.TASER))
				&& ((Object) this instanceof Player || (Object) this instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) {
			ci.cancel();
		}
	}
}
