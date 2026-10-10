package dev.countercraft.team;

import com.mojang.serialization.Codec;
import dev.countercraft.CounterCraft;
import dev.countercraft.SurvivalRecord;
import dev.countercraft.net.TeamPromptPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Counter-Craft (the hardcore co-op mode): a player's first spawn in the world asks them to pick a team, and the team
 * is then locked for {@link #LOCK_DAYS} days. Until they pick, they aren't really in the world: held where they
 * spawned, invisible and unhurt (HardcorePvpMixin), and their run (survival days) hasn't started. Cancelling the pick leaves the world;
 * they're asked again when they come back. Other modes keep CS2's free team switching.
 */
public final class TeamLock {
	public static final int LOCK_DAYS = 50;
	private static final long DAY = 24000L;

	/** The day (overworld clock / 24000) the player last joined a team in a Counter-Craft world. */
	public static final AttachmentType<Long> TEAM_DAY = AttachmentRegistry.<Long>builder()
			.persistent(Codec.LONG)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("team_day"));

	/** Players waiting to pick their first team, and where they're held. */
	private static final Map<UUID, Vec3> PENDING = new ConcurrentHashMap<>();

	private TeamLock() {
	}

	/** Loads the class at mod init ({@link #TEAM_DAY} must be registered before anyone joins). */
	public static void init() {
	}

	private static long today(MinecraftServer server) {
		return server.overworld().getOverworldClockTime() / DAY;
	}

	public static boolean hardcore(ServerPlayer p) {
		return p.level().getLevelData().isHardcore();
	}

	/** Not yet on a team in a Counter-Craft world: not fully spawned. */
	public static boolean pending(ServerPlayer p) {
		return PENDING.containsKey(p.getUUID());
	}

	/** Days until this player may change team (0 = free to). */
	public static int daysLeft(ServerPlayer p) {
		if (!hardcore(p) || !p.hasAttached(Teams.TEAM)) {
			return 0;
		}
		Long since = p.getAttached(TEAM_DAY);
		return since == null ? 0 : (int) Math.max(0, since + LOCK_DAYS - today(p.level().getServer()));
	}

	/** Joined the server: in Counter-Craft, a player without a team is held and asked to pick one. */
	public static void join(ServerPlayer p) {
		if (!hardcore(p) || p.hasAttached(Teams.TEAM) || !p.isAlive() || p.isSpectator()) {
			return;
		}
		PENDING.put(p.getUUID(), p.position());
		p.setInvisible(true);
		if (ServerPlayNetworking.canSend(p, TeamPromptPayload.TYPE)) {
			ServerPlayNetworking.send(p, new TeamPromptPayload(LOCK_DAYS));
		}
	}

	/** Every server tick: waiting players stay where they spawned. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Vec3 at = PENDING.get(p.getUUID());
			if (at == null) {
				continue;
			}
			p.setDeltaMovement(Vec3.ZERO);
			if (p.position().distanceToSqr(at) > 0.01) {
				p.teleportTo(at.x, at.y, at.z);
			}
		}
	}

	/** Left before picking: nothing of the hold stays on the saved player. */
	public static void leave(ServerPlayer p) {
		if (PENDING.remove(p.getUUID()) != null) {
			p.setInvisible(false);
		}
	}

	/**
	 * A team pick (team select screen or /team). In Counter-Craft: refused while the team is locked; the first pick
	 * spawns the player for real and starts their run. Returns whether the team was set.
	 */
	public static boolean choose(ServerPlayer p, String team) {
		int left = daysLeft(p);
		if (left > 0) {
			p.sendSystemMessage(Component.literal("Your team is locked for " + left + (left == 1 ? " more day." : " more days.")).withColor(0xE05050));
			return false;
		}
		Teams.set(p, team);
		CsInventory.refreshKnife(p);
		if (hardcore(p)) {
			long today = today(p.level().getServer());
			p.setAttached(TEAM_DAY, today);
			if (PENDING.remove(p.getUUID()) != null) {
				p.setInvisible(false);
				SurvivalRecord.update(p, r -> r.restartAll(today)); // the run starts now
			}
		}
		return true;
	}
}
