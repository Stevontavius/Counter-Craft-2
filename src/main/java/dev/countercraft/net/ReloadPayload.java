package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the reload key was pressed. */
public record ReloadPayload() implements CustomPacketPayload {
	public static final ReloadPayload INSTANCE = new ReloadPayload();
	public static final Type<ReloadPayload> TYPE = new Type<>(CounterCraft.id("reload"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ReloadPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
