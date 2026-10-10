package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: you've been flashed for this many seconds (full white, then a fade). */
public record FlashPayload(float seconds) implements CustomPacketPayload {
	public static final Type<FlashPayload> TYPE = new Type<>(CounterCraft.id("flash"));
	public static final StreamCodec<ByteBuf, FlashPayload> CODEC = ByteBufCodecs.FLOAT.map(FlashPayload::new, FlashPayload::seconds);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
