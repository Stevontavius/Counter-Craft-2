package dev.countercraft.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A creeper's charged flag, for the Zeus (charging it without a real lightning strike, which would start fires). */
@Mixin(Creeper.class)
public interface CreeperAccessor {
	@Accessor("DATA_IS_POWERED")
	static EntityDataAccessor<Boolean> countercraft$powered() {
		throw new AssertionError();
	}
}
