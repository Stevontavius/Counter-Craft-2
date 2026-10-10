package dev.countercraft.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counter-Craft (hardcore co-op): players take a third of the damage other players deal them (guns, knives,
 * grenades, fire, melee), so friendly fire stops being an instant run-ender (the mod's own rule, not CS2's). A player
 * still picking their first team ({@link dev.countercraft.team.TeamLock}) isn't in the world yet and takes no damage.
 */
@Mixin(ServerPlayer.class)
abstract class HardcorePvpMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void countercraft$notSpawnedYet(final ServerLevel level, final DamageSource source, final float amount, final CallbackInfoReturnable<Boolean> cir) {
		if (dev.countercraft.team.TeamLock.pending((ServerPlayer) (Object) this) && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			cir.setReturnValue(false);
		}
	}

	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true)
	private float countercraft$pvpResistance(final float amount, final ServerLevel level, final DamageSource source) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (level.getLevelData().isHardcore() && source.getEntity() instanceof Player attacker && attacker != self) {
			return amount / dev.countercraft.team.Economy.HARDCORE_PVP_RESISTANCE;
		}
		return amount;
	}
}
