package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to the attacker, the victim and players nearby: a bullet / knife hit, for CS2's hit and kill feedback sounds
 * (Player.(Damage|Death)(HeadShot|Body)[Armor].(AttackerFeedback|Victim|Onlooker)). Flags: {@code F_*}.
 */
public record HitSoundPayload(int attacker, int victim, int flags, float x, float y, float z) implements CustomPacketPayload {
	public static final int F_HEAD = 1;
	public static final int F_LETHAL = 2;
	public static final int F_ARMOR = 4;
	public static final int F_KNIFE = 8;

	public static final Type<HitSoundPayload> TYPE = new Type<>(CounterCraft.id("hit_sound"));
	public static final StreamCodec<ByteBuf, HitSoundPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, HitSoundPayload::attacker, ByteBufCodecs.VAR_INT, HitSoundPayload::victim, ByteBufCodecs.VAR_INT,
			HitSoundPayload::flags, ByteBufCodecs.FLOAT, HitSoundPayload::x, ByteBufCodecs.FLOAT, HitSoundPayload::y, ByteBufCodecs.FLOAT,
			HitSoundPayload::z, HitSoundPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
