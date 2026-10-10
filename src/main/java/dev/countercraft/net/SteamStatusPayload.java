package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: where this player's Steam link stands here: the linked SteamID64 ("" = none), the inventory's
 * state ("OK", "PRIVATE", "UNAVAILABLE", "" = not read yet) and a message for the link screen ("" = none).
 */
public record SteamStatusPayload(String steamId, String inventory, String message) implements CustomPacketPayload {
	public static final Type<SteamStatusPayload> TYPE = new Type<>(CounterCraft.id("steam_status"));
	public static final StreamCodec<ByteBuf, SteamStatusPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(32), SteamStatusPayload::steamId,
			ByteBufCodecs.stringUtf8(16), SteamStatusPayload::inventory,
			ByteBufCodecs.stringUtf8(256), SteamStatusPayload::message, SteamStatusPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
