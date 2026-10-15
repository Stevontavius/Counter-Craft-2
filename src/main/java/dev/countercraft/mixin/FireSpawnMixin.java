package dev.countercraft.mixin;

import dev.countercraft.gun.GrenadeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A burning fire pool lights its surroundings: no monster spawns near one, naturally or from a spawner. */
@Mixin(SpawnPlacements.class)
abstract class FireSpawnMixin {
	@Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
	private static void countercraft$fireLight(EntityType<?> type, ServerLevelAccessor level, EntitySpawnReason reason, BlockPos pos, RandomSource random,
			CallbackInfoReturnable<Boolean> cir) {
		if (type.getCategory() == MobCategory.MONSTER && (reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.SPAWNER)
				&& GrenadeEntity.litByFire(level.getLevel(), pos)) {
			cir.setReturnValue(false);
		}
	}
}
