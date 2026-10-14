package dev.countercraft.mixin;

import dev.countercraft.team.Economy;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A mob a monster spawner made is marked ({@link Economy#FROM_SPAWNER}): its bounty is a quarter. */
@Mixin(Mob.class)
abstract class MobSpawnerMixin {
	@Inject(method = "finalizeSpawn", at = @At("HEAD"))
	private void countercraft$fromSpawner(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data,
			CallbackInfoReturnable<SpawnGroupData> cir) {
		if (reason == EntitySpawnReason.SPAWNER) {
			((Mob) (Object) this).setAttached(Economy.FROM_SPAWNER, true);
		}
	}
}
