package dev.countercraft.mixin;

import dev.countercraft.bot.BotPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Test bots join without "joined the game" in chat (they come back after every death). */
@Mixin(PlayerList.class)
abstract class BotJoinMessageMixin {
	@Redirect(method = "placeNewPlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
	private void countercraft$quietBot(final PlayerList list, final Component message, final boolean overlay, final Connection connection,
			final ServerPlayer player, final CommonListenerCookie cookie) {
		if (!(player instanceof BotPlayer)) {
			list.broadcastSystemMessage(message, overlay);
		}
	}
}
