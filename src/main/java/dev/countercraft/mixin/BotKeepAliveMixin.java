package dev.countercraft.mixin;

import dev.countercraft.bot.BotConnection;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test bots never answer keep-alives: don't time them out. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class BotKeepAliveMixin {
	@Shadow
	@Final
	protected Connection connection;

	@Inject(method = "keepConnectionAlive", at = @At("HEAD"), cancellable = true)
	private void countercraft$botKeepAlive(final CallbackInfo ci) {
		if (connection instanceof BotConnection) {
			ci.cancel();
		}
	}
}
