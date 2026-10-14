package dev.countercraft;

import dev.countercraft.net.MvpPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.level.ServerPlayer;

/**
 * Challenges as CS2 round MVPs: when a player completes a challenge advancement, everyone on the server gets the MVP
 * banner (who did it and which challenge) and hears the achiever's own music kit's MVP anthem, as CS2 plays the round
 * MVP's kit for the whole server. Each player's equipped kit comes from their client with the loadout. The achiever
 * also earns {@link #CHALLENGE_REWARD}.
 */
public final class Mvp {
	private static final Map<UUID, String> KITS = new ConcurrentHashMap<>();

	private Mvp() {
	}

	public static void setKit(ServerPlayer p, String kit) {
		if (kit != null && !kit.isEmpty()) {
			KITS.put(p.getUUID(), kit);
		}
	}

	public static void leave(ServerPlayer p) {
		KITS.remove(p.getUUID());
	}

	/** {@code p}'s equipped music kit ("" = the default). */
	public static String kit(ServerPlayer p) {
		return KITS.getOrDefault(p.getUUID(), "");
	}

	/** Money for each challenge completed. */
	public static final int CHALLENGE_REWARD = 500;

	/** {@code p} just completed a challenge: {@link #CHALLENGE_REWARD}, and tell everyone. */
	public static void challenge(ServerPlayer p, DisplayInfo display) {
		int before = dev.countercraft.team.Economy.money(p);
		dev.countercraft.team.Economy.add(p, CHALLENGE_REWARD);
		int got = dev.countercraft.team.Economy.money(p) - before;
		p.sendSystemMessage(net.minecraft.network.chat.Component.literal("+$" + got + ": Award for completing the challenge " + display.title().getString() + ".")
				.withColor(0x6FB86F));
		String team = p.getAttached(dev.countercraft.team.Teams.TEAM);
		MvpPayload payload = new MvpPayload("CHALLENGE COMPLETE", p.getGameProfile().name(), team == null ? "" : team, display.title().getString(),
				display.description().getString(), KITS.getOrDefault(p.getUUID(), ""));
		for (ServerPlayer to : p.level().getServer().getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(to, MvpPayload.TYPE)) {
				ServerPlayNetworking.send(to, payload);
			}
		}
	}
}
