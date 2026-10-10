package dev.countercraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.contextualbar.LocatorBar;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Minecraft's player locator bar (the bar with a dot per player) sits at the top of the screen instead of over the
 * hotbar, where CS2's weapon list is; the compass ({@link dev.countercraft.client.hud.CompassHud}) hangs below it.
 * Its background, dots and up / down arrows all place themselves from {@code top}.
 */
@Mixin(LocatorBar.class)
abstract class LocatorBarMixin {
	public int top(final Window window) {
		return dev.countercraft.client.hud.CompassHud.LOCATOR_TOP;
	}
}
