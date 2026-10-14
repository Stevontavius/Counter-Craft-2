package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Hardcore: every player on the server, where they are (block position, dimension) or that they're dead (TeamList). */
public record TeamListPayload(List<Entry> players) implements CustomPacketPayload {
	public static final Type<TeamListPayload> TYPE = new Type<>(CounterCraft.id("team_list"));

	public record Entry(UUID id, String name, int x, int y, int z, String dimension, boolean dead) {
		public static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Entry::id,
				ByteBufCodecs.STRING_UTF8, Entry::name,
				ByteBufCodecs.VAR_INT, Entry::x,
				ByteBufCodecs.VAR_INT, Entry::y,
				ByteBufCodecs.VAR_INT, Entry::z,
				ByteBufCodecs.STRING_UTF8, Entry::dimension,
				ByteBufCodecs.BOOL, Entry::dead,
				Entry::new);
	}

	public static final StreamCodec<ByteBuf, TeamListPayload> CODEC = Entry.CODEC.apply(ByteBufCodecs.list())
			.map(TeamListPayload::new, TeamListPayload::players);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
