package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: whether this player's client runs CS2 movement (sent on join and when /cs_movement changes), so
 * the server measures their speed in CS units, uses CS fall damage and CS's crouched eye height.
 */
public record MovementModePayload(boolean cs) implements CustomPacketPayload {
	public static final Type<MovementModePayload> TYPE = new Type<>(CounterCraft.id("movement_mode"));
	public static final StreamCodec<ByteBuf, MovementModePayload> CODEC = ByteBufCodecs.BOOL.map(MovementModePayload::new, MovementModePayload::cs);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
