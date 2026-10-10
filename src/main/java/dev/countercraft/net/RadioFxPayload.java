package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to clients: someone spoke on the radio. The talker concept picks the voice line ({@code seed} makes every
 * client pick the same one); {@code label} (a chat wheel label key, empty for a grenade call) is shown in chat with the
 * speaker's name and location.
 */
public record RadioFxPayload(int speaker, String name, String concept, String label, String location, int seed) implements CustomPacketPayload {
	public static final Type<RadioFxPayload> TYPE = new Type<>(CounterCraft.id("radio_fx"));
	public static final StreamCodec<ByteBuf, RadioFxPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RadioFxPayload::speaker, ByteBufCodecs.stringUtf8(64), RadioFxPayload::name,
			ByteBufCodecs.stringUtf8(64), RadioFxPayload::concept, ByteBufCodecs.stringUtf8(64), RadioFxPayload::label,
			ByteBufCodecs.stringUtf8(64), RadioFxPayload::location, ByteBufCodecs.INT, RadioFxPayload::seed, RadioFxPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
