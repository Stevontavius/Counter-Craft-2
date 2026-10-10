package dev.countercraft.bot;

import dev.countercraft.mixin.ConnectionAccessor;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/** A test bot's connection: nobody on the other end, so everything sent to it is dropped. */
public final class BotConnection extends Connection {
	public BotConnection() {
		super(PacketFlow.SERVERBOUND);
		((ConnectionAccessor) (Object) this).countercraft$setChannel(new EmbeddedChannel());
	}

	@Override
	public void send(Packet<?> packet) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
	}

	@Override
	public void setReadOnly() {
	}

	@Override
	public void handleDisconnection() {
	}
}
