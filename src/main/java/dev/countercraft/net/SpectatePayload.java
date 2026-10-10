package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to a player waiting for Another Chance ({@link dev.countercraft.team.AnotherChance}): who they're spectating
 * (entity id) and what the client can't see of them (food, CS2 kevlar and helmet, Minecraft armor points, whether they
 * use the CS slots: their held CS weapon then shows as its viewmodel), and the players still alive to spectate or
 * respawn on.
 */
public record SpectatePayload(int target, int food, int kevlar, boolean helmet, int armor, boolean csMode, List<Mate> mates)
		implements CustomPacketPayload {
	public record Mate(UUID id, String name, String team) {
		static final StreamCodec<ByteBuf, Mate> CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Mate::id, ByteBufCodecs.STRING_UTF8, Mate::name,
				ByteBufCodecs.STRING_UTF8, Mate::team, Mate::new);
	}

	public static final Type<SpectatePayload> TYPE = new Type<>(CounterCraft.id("spectate"));
	public static final StreamCodec<ByteBuf, SpectatePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SpectatePayload::target, ByteBufCodecs.VAR_INT, SpectatePayload::food, ByteBufCodecs.VAR_INT, SpectatePayload::kevlar,
			ByteBufCodecs.BOOL, SpectatePayload::helmet, ByteBufCodecs.VAR_INT, SpectatePayload::armor, ByteBufCodecs.BOOL, SpectatePayload::csMode,
			Mate.CODEC.apply(ByteBufCodecs.list()), SpectatePayload::mates, SpectatePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
