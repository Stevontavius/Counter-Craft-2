package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to the players around, as a C4 goes off: where its shockwave sets out from. The client draws the wave
 * spreading through the world and rumbles as its front closes in (ShockwaveFx); the server times the hits.
 */
public record BlastWavePayload(double x, double y, double z) implements CustomPacketPayload {
	public static final Type<BlastWavePayload> TYPE = new Type<>(CounterCraft.id("blast_wave"));
	public static final StreamCodec<ByteBuf, BlastWavePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, BlastWavePayload::x, ByteBufCodecs.DOUBLE, BlastWavePayload::y, ByteBufCodecs.DOUBLE, BlastWavePayload::z,
			BlastWavePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
