package dev.countercraft.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test bots ({@link dev.countercraft.bot.BotConnection}) give their connection a channel that goes nowhere. */
@Mixin(Connection.class)
public interface ConnectionAccessor {
	@Accessor("channel")
	void countercraft$setChannel(Channel channel);
}
