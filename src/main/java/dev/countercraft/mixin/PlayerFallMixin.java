package dev.countercraft.mixin;

import dev.countercraft.CounterCraft;
import dev.countercraft.gun.Ballistics;
import dev.countercraft.movement.CsMove;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * CS2 fall damage for players on CS movement: (impact speed - 580) x 100 / 444 CS HP, from the fall height
 * (Minecraft's own threshold is 3 blocks; CS2's is 210 units = 5.3 blocks, lethal at 16.6). Minecraft's own effects on a
 * fall still count: Slow Falling takes none; extra safe fall distance (Jump Boost) is taken off the fall; the fall
 * damage multiplier attribute scales it; Feather Falling and Protection reduce it as they do any fall (the damage goes
 * through the usual armour and enchantments).
 */
@Mixin(Player.class)
abstract class PlayerFallMixin {
	/** Minecraft's safe fall distance (the attribute's default): anything above it is a bonus, such as Jump Boost's. */
	private static final double MINECRAFT_SAFE_FALL = 3.0;

	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void countercraft$csFallDamage(final double fallDistance, final float multiplier, final DamageSource source,
			final CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (!(self.level() instanceof ServerLevel level) || !CsMove.enabled(self) || self.getAbilities().mayfly) {
			return;
		}
		if (self.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING) || dev.countercraft.block.SurfRamps.touchesRamp(self)) {
			cir.setReturnValue(false); // a surf ramp: no fall damage, as in CS2
			return;
		}
		double extraSafe = self.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE) - MINECRAFT_SAFE_FALL;
		double fall = Math.max(0, fallDistance - extraSafe);
		float damage = (float) (CsMove.fallDamage(fall) * multiplier
				* self.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FALL_DAMAGE_MULTIPLIER) * Ballistics.CS_TO_MC);
		if (damage > 0f) {
			self.hurtServer(level, source, damage);
			// CS2's Player.DamageFall
			level.playSound(null, self.getX(), self.getY(), self.getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id("player.damagefall")),
					SoundSource.PLAYERS, 1f, 1f);
		}
		cir.setReturnValue(damage > 0f);
	}
}
