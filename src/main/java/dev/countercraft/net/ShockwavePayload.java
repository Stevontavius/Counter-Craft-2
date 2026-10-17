package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to a player, the moment a C4's shockwave reaches them: how hard it struck them ({@code strike} 0..1, 0: it
 * didn't hurt them), how loud it is where they are ({@code loudness} 0..1), and which way it was travelling
 * ({@code dx, dy, dz}, a unit vector). The client plays the wave passing (and being hit) and the struck shader.
 */
public record ShockwavePayload(float strike, float loudness, float dx, float dy, float dz) implements CustomPacketPayload {
	public static final Type<ShockwavePayload> TYPE = new Type<>(CounterCraft.id("shockwave"));
	public static final StreamCodec<ByteBuf, ShockwavePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, ShockwavePayload::strike, ByteBufCodecs.FLOAT, ShockwavePayload::loudness, ByteBufCodecs.FLOAT, ShockwavePayload::dx,
			ByteBufCodecs.FLOAT, ShockwavePayload::dy, ByteBufCodecs.FLOAT, ShockwavePayload::dz, ShockwavePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
