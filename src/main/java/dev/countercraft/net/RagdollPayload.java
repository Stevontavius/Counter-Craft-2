package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to everyone who sees a player die: turn them into a ragdoll, pushed by the killing hit ({@code dx, dy, dz}
 * unit direction, {@code speed} m/s given to the hit body, {@code head}: the hit was to the head).
 */
public record RagdollPayload(int entity, float dx, float dy, float dz, float speed, boolean head) implements CustomPacketPayload {
	public static final Type<RagdollPayload> TYPE = new Type<>(CounterCraft.id("ragdoll"));
	public static final StreamCodec<ByteBuf, RagdollPayload> CODEC = StreamCodec.of((b, p) -> {
		ByteBufCodecs.VAR_INT.encode(b, p.entity());
		b.writeFloat(p.dx());
		b.writeFloat(p.dy());
		b.writeFloat(p.dz());
		b.writeFloat(p.speed());
		b.writeBoolean(p.head());
	}, b -> new RagdollPayload(ByteBufCodecs.VAR_INT.decode(b), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
