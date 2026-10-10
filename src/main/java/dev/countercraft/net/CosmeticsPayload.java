package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import dev.countercraft.team.Cosmetics;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the player's equipped cosmetics, each team's starting pistol from the loadout, and whether
 * Skinchanger Mode is on (no Genuine Skins ✓ then); on join and whenever any of it changes.
 */
public record CosmeticsPayload(Cosmetics.Loadout loadout, String tPistol, String ctPistol, boolean skinchanger, String musicKit) implements CustomPacketPayload {
	public static final Type<CosmeticsPayload> TYPE = new Type<>(CounterCraft.id("cosmetics"));
	public static final StreamCodec<ByteBuf, CosmeticsPayload> CODEC = StreamCodec.composite(
			Cosmetics.Loadout.STREAM_CODEC, CosmeticsPayload::loadout,
			ByteBufCodecs.stringUtf8(64), CosmeticsPayload::tPistol,
			ByteBufCodecs.stringUtf8(64), CosmeticsPayload::ctPistol,
			ByteBufCodecs.BOOL, CosmeticsPayload::skinchanger,
			ByteBufCodecs.stringUtf8(64), CosmeticsPayload::musicKit, CosmeticsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
