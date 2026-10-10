package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import dev.countercraft.gun.MobHeads;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the head hitboxes read from the client's entity models ({@link MobHeads}); a server keeps the first. */
public record MobHeadsPayload(Map<String, MobHeads.Shape> shapes) implements CustomPacketPayload {
	public static final Type<MobHeadsPayload> TYPE = new Type<>(CounterCraft.id("mob_heads"));
	public static final StreamCodec<ByteBuf, MobHeadsPayload> CODEC = MobHeads.CODEC.map(MobHeadsPayload::new, MobHeadsPayload::shapes);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
