package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: Counter-Craft, you survived day {@code day} (the round is won) and got {@code reward}. */
public record DaySurvivedPayload(int day, int reward) implements CustomPacketPayload {
	public static final Type<DaySurvivedPayload> TYPE = new Type<>(CounterCraft.id("day_survived"));
	public static final StreamCodec<ByteBuf, DaySurvivedPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DaySurvivedPayload::day, ByteBufCodecs.VAR_INT, DaySurvivedPayload::reward, DaySurvivedPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
