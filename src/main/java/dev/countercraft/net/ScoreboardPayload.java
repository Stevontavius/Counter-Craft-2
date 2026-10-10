package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, once a second: the scoreboard's rows (everyone online). Money is only filled in for the receiver's
 * teammates, like CS2's scoreboard (-1 otherwise).
 */
public record ScoreboardPayload(List<Row> rows) implements CustomPacketPayload {
	/**
	 * @param team "t", "ct", or "" (no team picked)
	 * @param days days survived in a row
	 */
	public record Row(UUID id, String name, String team, int kills, int deaths, int days, int money, int ping, boolean alive, boolean genuine) {
	}

	public static final Type<ScoreboardPayload> TYPE = new Type<>(CounterCraft.id("scoreboard"));
	public static final StreamCodec<FriendlyByteBuf, ScoreboardPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.rows().size());
		for (Row r : p.rows()) {
			UUIDUtil.STREAM_CODEC.encode(buf, r.id());
			buf.writeUtf(r.name(), 64);
			buf.writeUtf(r.team(), 8);
			buf.writeVarInt(r.kills());
			buf.writeVarInt(r.deaths());
			buf.writeVarInt(r.days());
			buf.writeVarInt(r.money() + 1);
			buf.writeVarInt(r.ping());
			buf.writeBoolean(r.alive());
			buf.writeBoolean(r.genuine());
		}
	}, buf -> {
		int n = Math.min(buf.readVarInt(), 1024);
		List<Row> rows = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			rows.add(new Row(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64), buf.readUtf(8), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					buf.readVarInt() - 1, buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));
		}
		return new ScoreboardPayload(rows);
	});

	@Override
	public Type<ScoreboardPayload> type() {
		return TYPE;
	}
}
