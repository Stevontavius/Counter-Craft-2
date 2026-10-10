package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Client to server, creative only: set CS slot {@code slot} (vanilla's creative slot packet stops at slot 45). */
public record CreativeCsSlotPayload(int slot, ItemStack stack) implements CustomPacketPayload {
	public static final Type<CreativeCsSlotPayload> TYPE = new Type<>(CounterCraft.id("creative_cs_slot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CreativeCsSlotPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CreativeCsSlotPayload::slot,
			ItemStack.OPTIONAL_STREAM_CODEC, CreativeCsSlotPayload::stack,
			CreativeCsSlotPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
