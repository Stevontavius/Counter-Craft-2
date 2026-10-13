package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: "the trigger is pulled this tick". {@code alt} = secondary fire (R8 Revolver fan fire);
 * {@code fresh} = the button went down this tick (a new click: no catching up on a shot that was due earlier; for a
 * knife, the packet the client swung on, the only kind the server swings on);
 * {@code motion} = the shooter's movement this tick, for CS2's movement / air inaccuracy. The server decides whether
 * a shot actually happens. {@code backstab}: a knife swing the client animated as a backstab, the entity it saw it on
 * (else -1): the server takes it when that's what it hits and you're behind it (see KnifeItem.attack).
 */
public record FirePayload(boolean alt, boolean fresh, Motion motion, int backstab) implements CustomPacketPayload {
	public static final Type<FirePayload> TYPE = new Type<>(CounterCraft.id("fire"));

	public FirePayload(boolean alt, boolean fresh, Motion motion) {
		this(alt, fresh, motion, -1);
	}

	/** Velocity in CS units/s (Minecraft axes), on the ground, walking (CS2's walk key), duck amount 0..1. */
	public record Motion(float vx, float vy, float vz, boolean onGround, boolean walking, float duck) {
		public static final StreamCodec<ByteBuf, Motion> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, Motion::vx,
				ByteBufCodecs.FLOAT, Motion::vy,
				ByteBufCodecs.FLOAT, Motion::vz,
				ByteBufCodecs.BOOL, Motion::onGround,
				ByteBufCodecs.BOOL, Motion::walking,
				ByteBufCodecs.FLOAT, Motion::duck,
				Motion::new);
	}

	public static final StreamCodec<ByteBuf, FirePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, FirePayload::alt,
			ByteBufCodecs.BOOL, FirePayload::fresh,
			Motion.CODEC, FirePayload::motion,
			ByteBufCodecs.VAR_INT, FirePayload::backstab,
			FirePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
