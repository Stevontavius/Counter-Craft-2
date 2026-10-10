package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * A ping (CS2 player_ping). Client to server: where the player pinged and whether it's urgent (pinged twice quickly).
 * Server to clients: the same, plus who pinged ({@code player} entity id, 0 from a client) and their colour slot.
 */
public record PingPayload(int player, int color, double x, double y, double z, boolean urgent) implements CustomPacketPayload {
	public static final Type<PingPayload> TYPE = new Type<>(CounterCraft.id("ping"));
	public static final StreamCodec<ByteBuf, PingPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PingPayload::player, ByteBufCodecs.VAR_INT, PingPayload::color, ByteBufCodecs.DOUBLE, PingPayload::x,
			ByteBufCodecs.DOUBLE, PingPayload::y, ByteBufCodecs.DOUBLE, PingPayload::z, ByteBufCodecs.BOOL, PingPayload::urgent, PingPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
