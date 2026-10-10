package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: buy one item from the buy menu (CS2 item name: "weapon_ak47", "item_assaultsuit"...). */
public record BuyPayload(String item) implements CustomPacketPayload {
	public static final Type<BuyPayload> TYPE = new Type<>(CounterCraft.id("buy"));
	public static final StreamCodec<ByteBuf, BuyPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(BuyPayload::new, BuyPayload::item);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
