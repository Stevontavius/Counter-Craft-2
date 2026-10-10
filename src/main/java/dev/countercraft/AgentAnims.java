package dev.countercraft;

import dev.countercraft.net.AgentAnimPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Tells everyone who can see a player about their weapon actions, for the third-person agent animation. */
public final class AgentAnims {
	private AgentAnims() {
	}

	public static void broadcast(ServerPlayer player, String action, String attack, float seconds) {
		AgentAnimPayload p = new AgentAnimPayload(player.getId(), action, attack == null ? "" : attack, seconds);
		for (ServerPlayer o : PlayerLookup.tracking(player)) {
			if (o != player && ServerPlayNetworking.canSend(o, AgentAnimPayload.TYPE)) {
				ServerPlayNetworking.send(o, p);
			}
		}
	}

	/** A client's own inspect (client-side only in the mod): relay it. Nothing else is accepted from clients. */
	public static void fromClient(ServerPlayer player, AgentAnimPayload p) {
		if ("action_inspect".equals(p.action())) {
			broadcast(player, p.action(), "", Math.max(0f, Math.min(10f, p.seconds())));
		}
	}
}
