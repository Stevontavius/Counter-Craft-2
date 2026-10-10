package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: CS2 "drop" (G): toss the CS weapon in hand. */
public record CsDropPayload() implements CustomPacketPayload {
	public static final CsDropPayload INSTANCE = new CsDropPayload();
	public static final Type<CsDropPayload> TYPE = new Type<>(CounterCraft.id("cs_drop"));
	public static final StreamCodec<ByteBuf, CsDropPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
