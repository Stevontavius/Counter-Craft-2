package dev.countercraft.mixin;

import dev.countercraft.server.CcServerGui;
import dev.countercraft.server.StatusCollector;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dedicated server with a window (not started with nogui) opens Counter-Craft 2's ({@link CcServerGui}) instead of
 * Minecraft's: the same console and commands, closing it stops the server.
 */
@Mixin(DedicatedServer.class)
abstract class DedicatedServerGuiMixin {
	@Inject(method = "showGui", at = @At("HEAD"), cancellable = true)
	private void countercraft$ccWindow(final CallbackInfo ci) {
		DedicatedServer server = (DedicatedServer) (Object) this;
		CcServerGui gui = CcServerGui.open("Counter-Craft 2 Server", StatusCollector::status,
				line -> server.handleConsoleInput(line, server.createCommandSourceStack()), () -> server.halt(true));
		// the log lines Minecraft's own window gets (its log4j "ServerGuiConsole" queue)
		Thread log = new Thread(() -> {
			String line;
			while ((line = com.mojang.logging.LogQueues.getNextLogEvent("ServerGuiConsole")) != null) {
				gui.log(line);
			}
		}, "CC2 server window log");
		log.setDaemon(true);
		log.start();
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> gui.stopping());
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> gui.close());
		ci.cancel();
	}
}
