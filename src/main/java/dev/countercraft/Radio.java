package dev.countercraft;

import dev.countercraft.net.PingPayload;
import dev.countercraft.net.RadioFxPayload;
import dev.countercraft.net.RadioPayload;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of CS2's radio, chat wheel and pings. Everyone hears the radio and sees pings (there are no teams yet:
 * everybody is a teammate). The speaker's location in the chat line is the biome they're in (Minecraft has no map
 * callouts yet). The voice is the default T agent's (Phoenix), chosen on each client from the same seed.
 */
public final class Radio {
	/** CS2 radio spam guard: at most this many calls in {@link #SPAM_WINDOW} ticks. */
	private static final int SPAM_CALLS = 3;
	private static final long SPAM_WINDOW = 60;
	/** Ping tokens: CS2's player_ping_token_cooldown is 20 s; the number of tokens isn't exposed, 3 assumed. */
	private static final int PING_TOKENS = 3;
	private static final long PING_TOKEN_TICKS = 20 * 20;

	private static final Map<UUID, Deque<Long>> CALLS = new ConcurrentHashMap<>();
	private static final Map<UUID, Deque<Long>> PINGS = new ConcurrentHashMap<>();

	private Radio() {
	}

	/** A chat wheel command from a player. */
	public static void command(ServerPlayer from, RadioPayload p) {
		if (!(p.concept().startsWith("CW.") || p.concept().startsWith("Radio.")) || !allow(CALLS, from, SPAM_CALLS, SPAM_WINDOW)) {
			return;
		}
		speak(from, p.concept(), p.label());
	}

	/** The thrower's grenade call (CS2: "Fire in the hole!", "Flashbang out!"...): voice only, no chat line. */
	public static void grenade(ServerPlayer from, String grenade) {
		String concept = switch (grenade) {
			case "flashbang" -> "Radio.Flashbang";
			case "smokegrenade" -> "Radio.Smoke";
			case "molotov", "incgrenade" -> "Radio.Molotov";
			case "decoy" -> "Radio.Decoy";
			default -> "Radio.FireInTheHole";
		};
		speak(from, concept, "");
	}

	/**
	 * Your agent says this by itself ({@link AutoRadio}): no spam limit; {@code label} is the chat wheel option it's
	 * logged in chat as (CS2's localisation key, e.g. Chatwheel_cheer).
	 */
	public static void auto(ServerPlayer from, String concept, String label) {
		speak(from, concept, label);
	}

	private static void speak(ServerPlayer from, String concept, String label) {
		String location = from.level().getBiome(from.blockPosition()).unwrapKey()
				.map(k -> "biome." + k.identifier().getNamespace() + "." + k.identifier().getPath()).orElse("");
		RadioFxPayload fx = new RadioFxPayload(from.getId(), from.getName().getString(), concept, label, location, from.getRandom().nextInt());
		broadcast(from.level().getServer().getPlayerList().getPlayers(), fx);
	}

	/** A ping: shown to everyone, in the pinger's colour; limited by tokens. */
	public static void ping(ServerPlayer from, PingPayload p) {
		Vec3 at = new Vec3(p.x(), p.y(), p.z());
		if (!Double.isFinite(at.x) || at.distanceTo(from.getEyePosition()) > 512 || !allow(PINGS, from, PING_TOKENS, PING_TOKEN_TICKS)) {
			return;
		}
		List<ServerPlayer> all = from.level().getServer().getPlayerList().getPlayers();
		int color = Math.max(0, all.indexOf(from)) % 5;
		broadcast(all, new PingPayload(from.getId(), color, at.x, at.y, at.z, p.urgent()));
	}

	private static boolean allow(Map<UUID, Deque<Long>> log, ServerPlayer p, int max, long window) {
		long now = p.level().getGameTime();
		Deque<Long> q = log.computeIfAbsent(p.getUUID(), u -> new ArrayDeque<>());
		while (!q.isEmpty() && now - q.peekFirst() >= window) {
			q.pollFirst();
		}
		if (q.size() >= max) {
			return false;
		}
		q.addLast(now);
		return true;
	}

	private static void broadcast(List<ServerPlayer> players, CustomPacketPayload fx) {
		for (ServerPlayer p : players) {
			if (ServerPlayNetworking.canSend(p, fx.type())) {
				ServerPlayNetworking.send(p, fx);
			}
		}
	}

	public static void forget(UUID player) {
		CALLS.remove(player);
		PINGS.remove(player);
	}
}
