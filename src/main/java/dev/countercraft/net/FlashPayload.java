package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: you've been flashed for this many seconds (white, then a fade). {@code strength}: how white it gets
 * at its peak, 1 = fully white (a teammate's flashbang in co-op: {@code sv_teamflash}).
 */
public record FlashPayload(float seconds, float strength) implements CustomPacketPayload {
	public static final Type<FlashPayload> TYPE = new Type<>(CounterCraft.id("flash"));
	public static final StreamCodec<ByteBuf, FlashPayload> CODEC = StreamCodec.composite(ByteBufCodecs.FLOAT, FlashPayload::seconds,
			ByteBufCodecs.FLOAT, FlashPayload::strength, FlashPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
