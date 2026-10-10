package dev.countercraft.client.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Creative's inventory tab rebuilds slot positions: the CS slots get theirs back. */
@Mixin(Slot.class)
public interface SlotAccessor {
	@Mutable
	@Accessor("x")
	void countercraft$setX(int x);

	@Mutable
	@Accessor("y")
	void countercraft$setY(int y);
}
