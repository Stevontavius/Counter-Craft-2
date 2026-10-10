package dev.countercraft;

import dev.countercraft.net.ScoreboardPayload;
import dev.countercraft.steam.GenuineSkins;
import dev.countercraft.team.Economy;
import dev.countercraft.team.Teams;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

/**
 * Feeds the CS2-style scoreboard (hold the player list key): once a second, everyone online with their team, kills
 * (mobs and players), deaths, days survived in a row, ping, and, for teammates only, money (in Counter-Craft everyone
 * is on the same side, so everyone's). The ✓: a linked Steam account with Skinchanger Mode off. Every mode,
 * singleplayer or not.
 */
public final class Scoreboard {
	private Scoreboard() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (ServerPlayer to : players) {
			if (!ServerPlayNetworking.canSend(to, ScoreboardPayload.TYPE)) {
				continue;
			}
			String myTeam = to.getAttached(Teams.TEAM);
			List<ScoreboardPayload.Row> rows = new ArrayList<>(players.size());
			for (ServerPlayer p : players) {
				String team = p.getAttached(Teams.TEAM);
				var s = p.getStats();
				int kills = s.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)) + s.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS));
				boolean mate = p == to || team != null && team.equals(myTeam) || to.level().getLevelData().isHardcore();
				rows.add(new ScoreboardPayload.Row(p.getUUID(), p.getGameProfile().name(), team == null ? "" : team, kills,
						s.getValue(Stats.CUSTOM.get(Stats.DEATHS)), SurvivalRecord.of(p).streak(), mate && !Economy.freeBuy(p) ? Economy.money(p) : -1,
						p.connection.latency(), p.isAlive() && !p.isSpectator(), GenuineSkins.linkedWithoutSkinchanger(p)));
			}
			ServerPlayNetworking.send(to, new ScoreboardPayload(rows));
		}
	}
}
