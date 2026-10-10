package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to clients: another player's footstep / jump / landing sound ({@link StepPayload} kinds) at their feet. */
public record StepFxPayload(int entity, int kind, int surface, float x, float y, float z) implements CustomPacketPayload {
	public static final Type<StepFxPayload> TYPE = new Type<>(CounterCraft.id("step_fx"));
	public static final StreamCodec<ByteBuf, StepFxPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, StepFxPayload::entity, ByteBufCodecs.VAR_INT, StepFxPayload::kind, ByteBufCodecs.VAR_INT, StepFxPayload::surface,
			ByteBufCodecs.FLOAT, StepFxPayload::x, ByteBufCodecs.FLOAT, StepFxPayload::y, ByteBufCodecs.FLOAT, StepFxPayload::z, StepFxPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
