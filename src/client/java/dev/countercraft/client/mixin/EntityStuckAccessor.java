package dev.countercraft.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The slow-down a cobweb, berry bush or powder snow put on the entity this tick (CS movement applies it itself). */
@Mixin(Entity.class)
public interface EntityStuckAccessor {
	@Accessor("stuckSpeedMultiplier")
	Vec3 countercraft$stuck();

	@Accessor("stuckSpeedMultiplier")
	void countercraft$setStuck(Vec3 v);
}
