package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to clients: the Zeus shocked the water and these creatures in it (WaterShock); clients wrap them in lightning. */
public record ShockPayload(int[] ids) implements CustomPacketPayload {
	public static final Type<ShockPayload> TYPE = new Type<>(CounterCraft.id("shock"));
	public static final StreamCodec<FriendlyByteBuf, ShockPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.ids.length);
		for (int id : p.ids) {
			buf.writeVarInt(id);
		}
	}, buf -> {
		int[] ids = new int[Math.min(buf.readVarInt(), 512)];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = buf.readVarInt();
		}
		return new ShockPayload(ids);
	});

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
