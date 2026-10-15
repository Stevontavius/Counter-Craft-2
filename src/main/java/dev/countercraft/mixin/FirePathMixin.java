package dev.countercraft.mixin;

import dev.countercraft.gun.GrenadeEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mobs path around a burning molotov / incendiary pool as around fire (unless fire can't hurt them). */
@Mixin(WalkNodeEvaluator.class)
abstract class FirePathMixin {
	@Inject(method = "getPathTypeOfMob", at = @At("RETURN"), cancellable = true)
	private void countercraft$firePool(PathfindingContext ctx, int x, int y, int z, Mob mob, CallbackInfoReturnable<PathType> cir) {
		if (cir.getReturnValue() == PathType.WALKABLE && mob != null && GrenadeEntity.inFirePool(mob.level(), x + 0.5, y, z + 0.5)) {
			cir.setReturnValue(PathType.FIRE);
		}
	}
}
