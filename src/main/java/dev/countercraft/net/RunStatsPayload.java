package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the player's Counter-Craft run stats ({@link dev.countercraft.RunStats}): counters, kills per
 * weapon, and kills per mob type.
 */
public record RunStatsPayload(Map<String, Long> counts, Map<String, Integer> weapons, Map<String, Integer> mobs) implements CustomPacketPayload {
	public static final Type<RunStatsPayload> TYPE = new Type<>(CounterCraft.id("run_stats"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RunStatsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_LONG), RunStatsPayload::counts,
			ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), RunStatsPayload::weapons,
			ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), RunStatsPayload::mobs,
			RunStatsPayload::new);

	@Override
	public Type<RunStatsPayload> type() {
		return TYPE;
	}
}
