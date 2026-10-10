package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: a chat wheel command (CS2 talker concept such as "CW.GoGoGo" and its label key for the chat line). */
public record RadioPayload(String concept, String label) implements CustomPacketPayload {
	public static final Type<RadioPayload> TYPE = new Type<>(CounterCraft.id("radio"));
	public static final StreamCodec<ByteBuf, RadioPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(64), RadioPayload::concept, ByteBufCodecs.stringUtf8(64), RadioPayload::label, RadioPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
