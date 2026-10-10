package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server, while waiting for Another Chance: spectate the next / previous player alive, spectate {@code who},
 * or (once the team has made it to dawn) respawn on {@code who} or at your bed / the world spawn.
 */
public record ChancePayload(int action, UUID who) implements CustomPacketPayload {
	public static final int NEXT = 0;
	public static final int PREVIOUS = 1;
	public static final int SPECTATE = 2;
	public static final int RESPAWN_ON = 3;
	public static final int RESPAWN_HOME = 4;
	public static final UUID NOBODY = new UUID(0, 0);

	public static final Type<ChancePayload> TYPE = new Type<>(CounterCraft.id("another_chance"));
	public static final StreamCodec<ByteBuf, ChancePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ChancePayload::action, UUIDUtil.STREAM_CODEC, ChancePayload::who, ChancePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
