package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Server to the player who died: what CS2's death panel shows. The killer (entity id, type, name, whether a player, its
 * health left 0-1), what it held and the kill feed's weapon id, the damage both ways this life (CS2 HP, Minecraft
 * health x 5) and in how many hits, the death message ({@code cause}: "killed", "self", "environment"), the days survived
 * this life (hardcore's Game Over banner) and whether the run goes on without them for now (Counter-Craft's Another
 * Chance: no GAME OVER, the death cam's own music).
 */
public record DeathInfoPayload(int killerId, String killerType, Component killerName, boolean killerPlayer, float killerHealth, ItemStack weapon,
		String csWeapon, int damageTaken, int hitsTaken, int damageGiven, int hitsGiven, Component deathMessage, String cause, int daysSurvived,
		boolean anotherChance)
		implements CustomPacketPayload {
	public static final Type<DeathInfoPayload> TYPE = new Type<>(CounterCraft.id("death_info"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DeathInfoPayload> CODEC = StreamCodec.of((b, p) -> {
		ByteBufCodecs.VAR_INT.encode(b, p.killerId());
		ByteBufCodecs.STRING_UTF8.encode(b, p.killerType());
		ComponentSerialization.TRUSTED_STREAM_CODEC.encode(b, p.killerName());
		b.writeBoolean(p.killerPlayer());
		b.writeFloat(p.killerHealth());
		ItemStack.OPTIONAL_STREAM_CODEC.encode(b, p.weapon());
		ByteBufCodecs.STRING_UTF8.encode(b, p.csWeapon());
		ByteBufCodecs.VAR_INT.encode(b, p.damageTaken());
		ByteBufCodecs.VAR_INT.encode(b, p.hitsTaken());
		ByteBufCodecs.VAR_INT.encode(b, p.damageGiven());
		ByteBufCodecs.VAR_INT.encode(b, p.hitsGiven());
		ComponentSerialization.TRUSTED_STREAM_CODEC.encode(b, p.deathMessage());
		ByteBufCodecs.STRING_UTF8.encode(b, p.cause());
		ByteBufCodecs.VAR_INT.encode(b, p.daysSurvived());
		b.writeBoolean(p.anotherChance());
	}, b -> new DeathInfoPayload(ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.STRING_UTF8.decode(b), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(b),
			b.readBoolean(), b.readFloat(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), ByteBufCodecs.STRING_UTF8.decode(b), ByteBufCodecs.VAR_INT.decode(b),
			ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(b),
			ByteBufCodecs.STRING_UTF8.decode(b), ByteBufCodecs.VAR_INT.decode(b), b.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
