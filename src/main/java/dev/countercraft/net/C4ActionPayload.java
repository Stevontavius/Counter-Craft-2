package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: the C4. Attack pressed / let go with it out (plant), or use (E) held on planted bomb {@code target}
 * (sent every tick while held: defuse).
 */
public record C4ActionPayload(int action, int target) implements CustomPacketPayload {
	public static final int PLANT_START = 0;
	public static final int PLANT_STOP = 1;
	public static final int DEFUSE = 2;

	public static final Type<C4ActionPayload> TYPE = new Type<>(CounterCraft.id("c4_action"));
	public static final StreamCodec<ByteBuf, C4ActionPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, C4ActionPayload::action,
			ByteBufCodecs.VAR_INT, C4ActionPayload::target, C4ActionPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
