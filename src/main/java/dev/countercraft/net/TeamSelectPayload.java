package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: join a team ("t" / "ct") from the team select screen. */
public record TeamSelectPayload(String team) implements CustomPacketPayload {
	public static final Type<TeamSelectPayload> TYPE = new Type<>(CounterCraft.id("team_select"));
	public static final StreamCodec<ByteBuf, TeamSelectPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(TeamSelectPayload::new, TeamSelectPayload::team);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
