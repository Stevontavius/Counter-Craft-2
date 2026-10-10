package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: CS2 +use (E) on a dropped weapon: pick it up, swapping out what's in its slot. */
public record PickupPayload(int entity) implements CustomPacketPayload {
	public static final Type<PickupPayload> TYPE = new Type<>(CounterCraft.id("pickup"));
	public static final StreamCodec<ByteBuf, PickupPayload> CODEC = ByteBufCodecs.VAR_INT.map(PickupPayload::new, PickupPayload::entity);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
