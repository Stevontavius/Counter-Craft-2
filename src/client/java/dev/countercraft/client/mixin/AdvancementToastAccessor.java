package dev.countercraft.client.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The advancement an advancement toast is for. */
@Mixin(AdvancementToast.class)
public interface AdvancementToastAccessor {
	@Accessor("advancement")
	AdvancementHolder countercraft$advancement();
}
