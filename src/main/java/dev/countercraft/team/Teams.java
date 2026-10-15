package dev.countercraft.team;

import com.mojang.serialization.Codec;
import dev.countercraft.CounterCraft;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.player.Player;

/**
 * CS2 teams: each player is a Terrorist ("t") or Counter-Terrorist ("ct"). Stored on the player (saved, kept through
 * death) and synced to every client, which picks the team's agent model and loadout from it. Default: T.
 */
public final class Teams {
	public static final String T = "t";
	public static final String CT = "ct";
	/** CS2's team colours (the HUD's T gold and CT blue). */
	public static final int T_COLOR = 0xEABE54;
	public static final int CT_COLOR = 0x6F9CE6;

	public static int color(Player player) {
		return CT.equals(of(player)) ? CT_COLOR : T_COLOR;
	}

	public static final AttachmentType<String> TEAM = AttachmentRegistry.<String>builder()
			.persistent(Codec.STRING)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.STRING_UTF8, AttachmentSyncPredicate.all())
			.buildAndRegister(CounterCraft.id("team"));

	private Teams() {
	}

	/** Forces class init (and so registration) during mod init. */
	public static void init() {
	}

	/**
	 * On the same side (as the kill award sees it): in hardcore every player is; otherwise anyone not on the other team
	 * (a player who hasn't picked one is everyone's ally).
	 */
	public static boolean allies(Player a, Player b) {
		return a.level().getLevelData().isHardcore() || !a.hasAttached(TEAM) || !b.hasAttached(TEAM) || of(a).equals(of(b));
	}

	public static String of(Player player) {
		String t = player.getAttached(TEAM);
		return CT.equals(t) ? CT : T;
	}

	public static void set(Player player, String team) {
		player.setAttached(TEAM, CT.equals(team) ? CT : T);
	}
}
