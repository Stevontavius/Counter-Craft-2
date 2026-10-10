package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the alt-fire key was pressed (zoom, burst toggle or silencer, depending on the gun). */
public record AltPayload() implements CustomPacketPayload {
	public static final AltPayload INSTANCE = new AltPayload();
	public static final Type<AltPayload> TYPE = new Type<>(CounterCraft.id("alt"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AltPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
