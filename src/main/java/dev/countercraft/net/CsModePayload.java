package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: switch between CS mode (CS slots in hand) and the Minecraft hotbar. */
public record CsModePayload() implements CustomPacketPayload {
	public static final CsModePayload INSTANCE = new CsModePayload();
	public static final Type<CsModePayload> TYPE = new Type<>(CounterCraft.id("cs_mode"));
	public static final StreamCodec<ByteBuf, CsModePayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
