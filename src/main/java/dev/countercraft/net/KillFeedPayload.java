package dev.countercraft.net;

import dev.countercraft.CounterCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to every player: one CS2 death notice. {@code weapon} is a CS2 kill feed icon name ("ak47", "inferno"...) or
 * "item:<id>" for a Minecraft item; flags are the {@code F_*} bits. Entity ids let clients mark their own kills / deaths.
 */
/**
 * Server to client: one kill feed line. {@code attackerTeam} / {@code victimTeam}: a player's faction ("t", "ct", or ""
 * without one; "" for anything that isn't a player), for the name colours.
 */
public record KillFeedPayload(String attacker, boolean attackerPlayer, int attackerId, String victim, boolean victimPlayer, int victimId,
		String weapon, int flags, String attackerTeam, String victimTeam) implements CustomPacketPayload {
	public static final int F_HEADSHOT = 1;
	public static final int F_PENETRATE = 2;
	public static final int F_NOSCOPE = 4;
	public static final int F_SMOKE = 8;
	public static final int F_BLIND = 16;
	public static final int F_INAIR = 32;
	public static final int F_SUICIDE = 64;
	/** The kill feed's own flags; above them, the server's (bounty bonuses), never sent. */
	public static final int FEED_FLAGS = 127;
	/** Server only: a grenade's impact killed (it hit the creature in flight, CS2's 1 damage). */
	public static final int F_IMPACT = 128;
	/** Server only: a grenade that had bounced off a wall before it went off. */
	public static final int F_BANK = 256;
	/** Server only: the decoy's pop (not its impact). */
	public static final int F_POP = 512;
	/** Server only: a grenade that had smashed through glass (a window) on its way. */
	public static final int F_WINDOW = 1024;
	/** Server only: the flags that only count when the grenade itself dealt the killing hit. */
	public static final int GRENADE_DIRECT = F_IMPACT | F_POP | F_WINDOW;

	public static final Type<KillFeedPayload> TYPE = new Type<>(CounterCraft.id("kill_feed"));
	public static final StreamCodec<ByteBuf, KillFeedPayload> CODEC = StreamCodec.of((buf, p) -> {
		ByteBufCodecs.STRING_UTF8.encode(buf, p.attacker);
		buf.writeBoolean(p.attackerPlayer);
		ByteBufCodecs.VAR_INT.encode(buf, p.attackerId);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.victim);
		buf.writeBoolean(p.victimPlayer);
		ByteBufCodecs.VAR_INT.encode(buf, p.victimId);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.weapon);
		ByteBufCodecs.VAR_INT.encode(buf, p.flags);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.attackerTeam);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.victimTeam);
	}, buf -> new KillFeedPayload(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf),
			ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
			ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
