package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: Steam's signed sign-in answer (the openid.* fields Steam sent to the player's browser), for the
 * server to check with Steam itself; or, with no fields, unlink; or, with only {@link #CLAIM}, the SteamID this
 * computer's client already linked (sent on join, trusted only by the player's own singleplayer world). Nothing
 * secret: it only says which SteamID signed in.
 */
public record SteamLinkPayload(Map<String, String> params) implements CustomPacketPayload {
	/** The field of a claim: the client's linked SteamID ("" = none linked). */
	public static final String CLAIM = "cc2.claim";
	/** The field of a request to read the linked inventory from Steam again now. */
	public static final String REFRESH = "cc2.refresh";
	public static final Type<SteamLinkPayload> TYPE = new Type<>(CounterCraft.id("steam_link"));
	public static final StreamCodec<ByteBuf, SteamLinkPayload> CODEC = ByteBufCodecs.<ByteBuf, String, String, Map<String, String>>map(HashMap::new,
			ByteBufCodecs.stringUtf8(64), ByteBufCodecs.stringUtf8(1024), 32).map(SteamLinkPayload::new, SteamLinkPayload::params);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
