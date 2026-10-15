package dev.countercraft.mixin;

import dev.countercraft.gun.GrenadeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hive in a smoke grenade's cloud counts as smoked, like one over a campfire: take the honey without angering the bees. */
@Mixin(CampfireBlock.class)
abstract class SmokeyHiveMixin {
	@Inject(method = "isSmokeyPos", at = @At("HEAD"), cancellable = true)
	private static void countercraft$smokeGrenade(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (GrenadeEntity.inSmoke(level, pos)) {
			cir.setReturnValue(true);
		}
	}
}
