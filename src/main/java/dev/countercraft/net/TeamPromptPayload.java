package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: first spawn in a Counter-Craft world, pick a team (locked for {@code lockDays} days after). */
public record TeamPromptPayload(int lockDays) implements CustomPacketPayload {
	public static final Type<TeamPromptPayload> TYPE = new Type<>(CounterCraft.id("team_prompt"));
	public static final StreamCodec<ByteBuf, TeamPromptPayload> CODEC = ByteBufCodecs.VAR_INT.map(TeamPromptPayload::new, TeamPromptPayload::lockDays);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
