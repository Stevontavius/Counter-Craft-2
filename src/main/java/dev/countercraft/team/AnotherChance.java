package dev.countercraft.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.countercraft.Combat;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.ChancePayload;
import dev.countercraft.net.SpectatePayload;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Counter-Craft's Another Chance (hardcore, with more than one player): dying while another player is still alive
 * doesn't end your run. After the death cam you spectate the players still alive, locked to them (no free roam); if
 * they make it to the next dawn you get half the day's reward and choose where to come back: on one of them, or at your
 * bed (the world spawn without one), which is where you go after {@link #AUTO_RESPAWN_SECONDS} without a choice. The run ends, GAME OVER for everyone, when the last player alive dies.
 */
public final class AnotherChance {
	public static final int NONE = 0;
	/** Dead: spectating the players still alive until dawn. */
	public static final int DOWN = 1;
	/** Dawn came with someone still alive: choosing where to respawn. */
	public static final int READY = 2;
	/** The run is over (everyone died). */
	public static final int OUT = 3;
	/** The part of the day's reward a player who died that day gets when the team makes it to dawn. */
	public static final float DEAD_SHARE = 0.5f;
	/** Seconds to choose where to respawn before it happens at your bed / the world spawn. */
	public static final int AUTO_RESPAWN_SECONDS = 30;
	/** When each READY player's choice appeared (server ticks; not saved: a rejoin restarts the 30 s). */
	private static final java.util.Map<java.util.UUID, Integer> READY_SINCE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * @param state {@link #NONE}, {@link #DOWN}, {@link #READY} or {@link #OUT}
	 * @param days the days the team survived (the GAME OVER banner's)
	 * @param bed whether "Respawn at Bed" applies (they have a bed / respawn anchor); else the world spawn
	 * @param since when they went down (overworld game time; server only)
	 */
	public record State(int state, int days, boolean bed, long since) {
		public static final State NO = new State(NONE, 0, false, 0);
		static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("state").forGetter(State::state),
				Codec.INT.fieldOf("days").forGetter(State::days),
				Codec.BOOL.fieldOf("bed").forGetter(State::bed),
				Codec.LONG.optionalFieldOf("since", 0L).forGetter(State::since)).apply(i, State::new));
		static final StreamCodec<ByteBuf, State> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, State::state,
				ByteBufCodecs.VAR_INT, State::days, ByteBufCodecs.BOOL, State::bed, (s, d, b) -> new State(s, d, b, 0));

		/** Dead and waiting for dawn, or choosing where to come back. */
		public boolean waiting() {
			return state == DOWN || state == READY;
		}
	}

	public static final AttachmentType<State> STATE = AttachmentRegistry.<State>builder()
			.persistent(State.CODEC)
			.copyOnDeath()
			.syncWith(State.STREAM_CODEC, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("another_chance"));
	/**
	 * On the overworld once a run is over: when (game time) and the days the team survived. Players who were already
	 * down then (offline at the time) are out too when they come back; someone dying after it is a new story.
	 */
	private record RunOver(long time, int days) {
		static final Codec<RunOver> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("time").forGetter(RunOver::time),
				Codec.INT.fieldOf("days").forGetter(RunOver::days)).apply(i, RunOver::new));
	}

	private static final AttachmentType<RunOver> RUN_OVER = AttachmentRegistry.<RunOver>builder()
			.persistent(RunOver.CODEC)
			.buildAndRegister(CounterCraft.id("run_ended"));

	private AnotherChance() {
	}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(SpectatePayload.TYPE, SpectatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ChancePayload.TYPE, ChancePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ChancePayload.TYPE, (payload, context) -> choose(context.player(), payload));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive && of(newPlayer).state() == DOWN) {
				lock(newPlayer, alive(newPlayer.level().getServer()));
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(AnotherChance::tick);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> READY_SINCE.remove(handler.getPlayer().getUUID()));
	}

	public static State of(Player p) {
		return Objects.requireNonNullElse(p.getAttached(STATE), State.NO);
	}

	public static boolean waiting(Player p) {
		return of(p).waiting();
	}

	/** In the round: alive, playing (not spectating) and on a team. */
	public static boolean inRound(ServerPlayer p) {
		return p.isAlive() && !p.isSpectator() && p.hasAttached(Teams.TEAM) && !TeamLock.pending(p);
	}

	/** The players in the round, in a fixed order (by name) to cycle through. */
	private static List<ServerPlayer> alive(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (inRound(p)) {
				out.add(p);
			}
		}
		out.sort(Comparator.comparing(p -> p.getGameProfile().name()));
		return out;
	}

	/** Someone {@code p} may watch: another player in the round. */
	private static boolean valid(ServerPlayer p, Entity e) {
		return e instanceof ServerPlayer t && t != p && inRound(t);
	}

	/** The team's days survived: the dawns this world has seen (DayCycle's day count). */
	private static int days(MinecraftServer server) {
		return (int) Math.max(0, Math.floorDiv(server.overworld().getOverworldClockTime(), DayCycle.DAY));
	}

	/**
	 * {@code p} died (before their death panel goes out). Counter-Craft: with another player still alive they get
	 * Another Chance (true); otherwise the run is over, for them and for everyone waiting.
	 */
	public static boolean died(ServerPlayer p) {
		if (!p.level().getLevelData().isHardcore() || !p.hasAttached(Teams.TEAM) || TeamLock.pending(p)) {
			return false;
		}
		MinecraftServer server = p.level().getServer();
		int days = days(server);
		long now = server.overworld().getGameTime();
		if (!alive(server).isEmpty()) { // p is dead, so not among them
			p.setAttached(STATE, new State(DOWN, days, false, now));
			return true;
		}
		server.overworld().setAttached(RUN_OVER, new RunOver(now, days));
		p.setAttached(STATE, new State(OUT, days, false, now));
		for (ServerPlayer o : server.getPlayerList().getPlayers()) {
			if (o != p && waiting(o)) {
				out(o, days);
			}
		}
		return false;
	}

	/** The run is over: it ended (everyone died) and nobody has started playing again since. */
	public static boolean runOver(MinecraftServer server) {
		return server.overworld().hasAttached(RUN_OVER) && alive(server).isEmpty();
	}

	/** The run is over: free to roam as a spectator (vanilla hardcore's), the client shows GAME OVER. */
	private static void out(ServerPlayer p, int days) {
		p.setAttached(STATE, new State(OUT, days, false, of(p).since()));
		p.setCamera(p);
	}

	/** Spectate the first player alive if not watching one. */
	private static void lock(ServerPlayer p, List<ServerPlayer> alive) {
		if (!valid(p, p.getCamera()) && !alive.isEmpty()) {
			p.setCamera(alive.getFirst());
		}
	}

	/**
	 * ServerPlayer.setCamera (SpectateLockMixin): a player waiting for Another Chance only watches players alive: no
	 * sneaking out to roam, no clicking a mob to ride its view. With nobody alive to watch, anything goes.
	 */
	public static boolean allowCamera(ServerPlayer p, Entity e) {
		return !waiting(p) || !p.isSpectator() || valid(p, e) || alive(p.level().getServer()).isEmpty();
	}

	private static void tick(MinecraftServer server) {
		List<ServerPlayer> alive = null;
		RunOver over = server.overworld().getAttached(RUN_OVER);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!waiting(p) || !p.isSpectator()) {
				continue;
			}
			if (over != null && of(p).since() <= over.time()) {
				out(p, over.days()); // the run ended while they were away
				continue;
			}
			if (alive == null) {
				alive = alive(server);
			}
			Entity before = p.getCamera();
			lock(p, alive);
			if (p.getCamera() == before && p.getCamera() != p && server.getTickCount() % 20 == 0) {
				// again: a camera packet for a player the client hadn't loaded yet (just teleported there) is dropped
				p.connection.send(new ClientboundSetCameraPacket(p.getCamera()));
			}
			if (of(p).state() == READY) {
				int since = READY_SINCE.computeIfAbsent(p.getUUID(), u -> server.getTickCount());
				if (server.getTickCount() - since >= AUTO_RESPAWN_SECONDS * 20) {
					respawn(p, p.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING));
					continue;
				}
			}
			if (server.getTickCount() % 5 == 0 && ServerPlayNetworking.canSend(p, SpectatePayload.TYPE)) {
				ServerPlayNetworking.send(p, info(p, alive));
			}
		}
	}

	private static SpectatePayload info(ServerPlayer p, List<ServerPlayer> alive) {
		int food = 0;
		int kevlar = 0;
		boolean helmet = false;
		int armor = 0;
		int target = -1;
		boolean csMode = false;
		if (p.getCamera() instanceof ServerPlayer t && t != p) {
			target = t.getId();
			csMode = CsInventory.active(t);
			food = t.getFoodData().getFoodLevel();
			Combat.Kevlar k = t.getAttached(Combat.KEVLAR);
			if (k != null) {
				kevlar = k.armor();
				helmet = k.helmet();
			}
			armor = t.getArmorValue();
		}
		List<SpectatePayload.Mate> mates = new ArrayList<>();
		for (ServerPlayer t : alive) {
			mates.add(new SpectatePayload.Mate(t.getUUID(), t.getGameProfile().name(), Objects.requireNonNullElse(t.getAttached(Teams.TEAM), "")));
		}
		return new SpectatePayload(target, food, kevlar, helmet, armor, csMode, mates);
	}

	/**
	 * Dawn (DayCycle): if anyone made it, everyone waiting gets half the day's reward and may come back.
	 * {@code survivors}: the players in the round at dawn.
	 */
	public static void dawn(MinecraftServer server, int day, List<ServerPlayer> survivors) {
		if (survivors.isEmpty()) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (of(p).state() == DOWN) {
				DayCycle.daySurvived(p, day, DEAD_SHARE);
				p.setAttached(STATE, new State(READY, day, p.getRespawnConfig() != null, of(p).since()));
			}
		}
	}

	private static void choose(ServerPlayer p, ChancePayload c) {
		State s = of(p);
		if (!s.waiting() || !p.isSpectator()) {
			return;
		}
		MinecraftServer server = p.level().getServer();
		ServerPlayer who = server.getPlayerList().getPlayer(c.who());
		switch (c.action()) {
			case ChancePayload.NEXT, ChancePayload.PREVIOUS -> {
				List<ServerPlayer> alive = alive(server);
				if (!alive.isEmpty()) {
					int i = alive.indexOf(p.getCamera());
					int step = c.action() == ChancePayload.NEXT ? 1 : -1;
					p.setCamera(alive.get(i < 0 ? 0 : Math.floorMod(i + step, alive.size())));
				}
			}
			case ChancePayload.SPECTATE -> {
				if (valid(p, who)) {
					p.setCamera(who);
				}
			}
			case ChancePayload.RESPAWN_ON -> {
				if (s.state() == READY && valid(p, who)) {
					respawn(p, new TeleportTransition(who.level(), who.position(), Vec3.ZERO, who.getYRot(), 0f, TeleportTransition.DO_NOTHING));
				}
			}
			case ChancePayload.RESPAWN_HOME -> {
				if (s.state() == READY) {
					// where vanilla would respawn them: their bed or respawn anchor (using a charge), else the world spawn
					respawn(p, p.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING));
				}
			}
			default -> {
			}
		}
	}

	private static void respawn(ServerPlayer p, TeleportTransition to) {
		READY_SINCE.remove(p.getUUID());
		p.setAttached(STATE, new State(NONE, of(p).days(), false, 0));
		p.setCamera(p);
		p.teleport(to);
		p.setGameMode(GameType.SURVIVAL);
		p.setHealth(p.getMaxHealth());
		CsInventory.refreshKnife(p);
	}
}
