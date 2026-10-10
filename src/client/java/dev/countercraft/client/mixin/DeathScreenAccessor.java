package dev.countercraft.client.mixin;

import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The vanilla death screen's cause and hardcore flag, and its Title Screen handling (CsDeathScreen reuses them). */
@Mixin(DeathScreen.class)
public interface DeathScreenAccessor {
	@Accessor("causeOfDeath")
	Component countercraft$causeOfDeath();

	@Accessor("hardcore")
	boolean countercraft$hardcore();

	@Invoker("handleExitToTitleScreen")
	void countercraft$handleExitToTitleScreen();
}
