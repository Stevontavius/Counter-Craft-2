package dev.countercraft.mixin;

import dev.countercraft.team.Teams;
import net.minecraft.network.chat.ChatType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player's chat messages show their name in their team's colour (CS2's T gold / CT blue), as CS2's chat does. */
@Mixin(ChatType.class)
abstract class ChatNameMixin {
	@Inject(method = "bind(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/ChatType$Bound;",
			at = @At("RETURN"), cancellable = true)
	private static void countercraft$teamColour(final ResourceKey<ChatType> type, final Entity entity, final CallbackInfoReturnable<ChatType.Bound> cir) {
		if (entity instanceof Player player) {
			ChatType.Bound b = cir.getReturnValue();
			cir.setReturnValue(new ChatType.Bound(b.chatType(), b.name().copy().withColor(Teams.color(player)), b.targetName()));
		}
	}
}
