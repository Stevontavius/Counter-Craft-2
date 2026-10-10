package dev.countercraft.mixin;

import dev.countercraft.bot.BotPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Test bots leave without "left the game" in chat. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class BotLeaveMessageMixin {
	@Shadow
	public ServerPlayer player;

	@Redirect(method = "removePlayerFromWorld", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void countercraft$quietBot(final PlayerList list, final Component message, final boolean overlay) {
		if (!(player instanceof BotPlayer)) {
			list.broadcastSystemMessage(message, overlay);
		}
	}
}
