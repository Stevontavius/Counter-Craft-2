package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to the players in a dimension, with the C4 test on: a planted bomb's shockwave map for the blast debug view.
 * {@code data} holds 4 bytes a point: the floor block's offset from {@code (x, y, z)} (signed), and the damage it would
 * take x 4 (255: lethal).
 */
public record BlastMapPayload(int bomb, int x, int y, int z, byte[] data) implements CustomPacketPayload {
	public static final Type<BlastMapPayload> TYPE = new Type<>(CounterCraft.id("blast_map"));
	public static final StreamCodec<ByteBuf, BlastMapPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BlastMapPayload::bomb, ByteBufCodecs.VAR_INT, BlastMapPayload::x, ByteBufCodecs.VAR_INT, BlastMapPayload::y,
			ByteBufCodecs.VAR_INT, BlastMapPayload::z, ByteBufCodecs.BYTE_ARRAY, BlastMapPayload::data, BlastMapPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
