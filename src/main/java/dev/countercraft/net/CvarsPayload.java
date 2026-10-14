package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the server's CS2 cvars the client predicts with (sv_infinite_ammo, sv_maxvelocity, sv_airaccelerate). */
public record CvarsPayload(int infiniteAmmo, int maxVelocity, int airAccelerate) implements CustomPacketPayload {
	public static final Type<CvarsPayload> TYPE = new Type<>(CounterCraft.id("cvars"));
	public static final StreamCodec<ByteBuf, CvarsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CvarsPayload::infiniteAmmo, ByteBufCodecs.VAR_INT, CvarsPayload::maxVelocity,
			ByteBufCodecs.VAR_INT, CvarsPayload::airAccelerate, CvarsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
