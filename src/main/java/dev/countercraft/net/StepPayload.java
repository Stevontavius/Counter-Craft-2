package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the player's CS movement made a sound (a footstep, a jump or a landing) on this surface; the
 * server passes it on to everyone nearby ({@link StepFxPayload}). Kinds: {@link #STEP}, {@link #JUMP}, {@link #LAND}.
 */
public record StepPayload(int kind, int surface) implements CustomPacketPayload {
	public static final int STEP = 0;
	public static final int JUMP = 1;
	public static final int LAND = 2;

	public static final Type<StepPayload> TYPE = new Type<>(CounterCraft.id("step"));
	public static final StreamCodec<ByteBuf, StepPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, StepPayload::kind, ByteBufCodecs.VAR_INT, StepPayload::surface, StepPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
