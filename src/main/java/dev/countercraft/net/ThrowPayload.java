package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: throw the held grenade now (CS2 throws 0.1 s after the button is let go). Strength 1 = full throw,
 * 0.5 = both buttons, 0 = lob; the view angles and the thrower's own velocity (units/s, Minecraft axes) at that moment,
 * since CS2 adds 1.25 x the player's velocity to the throw and the server doesn't know the client's movement.
 */
public record ThrowPayload(float strength, float yaw, float pitch, float vx, float vy, float vz) implements CustomPacketPayload {
	public static final Type<ThrowPayload> TYPE = new Type<>(CounterCraft.id("throw"));
	public static final StreamCodec<ByteBuf, ThrowPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeFloat(p.strength);
		buf.writeFloat(p.yaw);
		buf.writeFloat(p.pitch);
		buf.writeFloat(p.vx);
		buf.writeFloat(p.vy);
		buf.writeFloat(p.vz);
	}, buf -> new ThrowPayload(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
