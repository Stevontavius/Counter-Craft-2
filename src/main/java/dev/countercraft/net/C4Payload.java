package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to every player in the dimension: a planted C4's news (PlantedC4); {@code seconds}: its fuse; {@code planter}
 * and {@code team} (t, ct or empty): who planted it.
 */
public record C4Payload(int event, int seconds, String planter, String team) implements CustomPacketPayload {
	public static final int PLANTED = 0;
	public static final int TEN_SECONDS = 1;
	public static final int DEFUSED = 2;
	public static final int EXPLODED = 3;

	public static final Type<C4Payload> TYPE = new Type<>(CounterCraft.id("c4"));
	public static final StreamCodec<ByteBuf, C4Payload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, C4Payload::event,
			ByteBufCodecs.VAR_INT, C4Payload::seconds, ByteBufCodecs.STRING_UTF8, C4Payload::planter, ByteBufCodecs.STRING_UTF8, C4Payload::team,
			C4Payload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
