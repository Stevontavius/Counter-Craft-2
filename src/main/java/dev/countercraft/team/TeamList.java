package dev.countercraft.team;

import dev.countercraft.net.TeamListPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hardcore co-op: twice a second, every player on the server (by name) with where they are, or DEAD while they wait for
 * Another Chance (or are out of the run), for the clients' player list. Not in other worlds: there, players' positions
 * are their own business.
 */
public final class TeamList {
	private TeamList() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(TeamListPayload.TYPE, TeamListPayload.CODEC);
		ServerTickEvents.END_SERVER_TICK.register(TeamList::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0 || !server.overworld().getLevelData().isHardcore()) {
			return;
		}
		List<ServerPlayer> all = new ArrayList<>(server.getPlayerList().getPlayers());
		all.sort(Comparator.comparing(p -> p.getGameProfile().name(), String.CASE_INSENSITIVE_ORDER));
		List<TeamListPayload.Entry> entries = new ArrayList<>();
		for (ServerPlayer p : all) {
			boolean dead = !p.isAlive() || p.isSpectator() || AnotherChance.waiting(p);
			entries.add(new TeamListPayload.Entry(p.getUUID(), p.getGameProfile().name(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
					p.level().dimension().identifier().toString(), dead));
		}
		TeamListPayload payload = new TeamListPayload(entries);
		for (ServerPlayer p : all) {
			if (ServerPlayNetworking.canSend(p, TeamListPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}
}
