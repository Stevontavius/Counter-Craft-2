package dev.countercraft.mixin;

import dev.countercraft.team.FriendlyFire;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** In hardcore, pets don't target teammates or their pets ({@link FriendlyFire#petSpares}): every goal sets targets here. */
@Mixin(Mob.class)
abstract class PetTeammateMixin {
	@ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
	private LivingEntity countercraft$spareTeammates(LivingEntity target) {
		return FriendlyFire.petSpares((Mob) (Object) this, target) ? null : target;
	}
}
