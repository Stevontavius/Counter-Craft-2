package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: refund a buy menu item bought this round ({@link dev.countercraft.team.Refunds}), or "*" for all of them. */
public record RefundPayload(String item) implements CustomPacketPayload {
	public static final Type<RefundPayload> TYPE = new Type<>(CounterCraft.id("refund"));
	public static final StreamCodec<ByteBuf, RefundPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(RefundPayload::new, RefundPayload::item);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
