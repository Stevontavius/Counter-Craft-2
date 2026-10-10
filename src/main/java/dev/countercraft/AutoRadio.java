package dev.countercraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.ElderGuardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;

/**
 * Voice lines your agent says on its own (through the radio, so everyone hears it, and logged in chat like the chat
 * wheel's calls):
 * <ul>
 * <li>Under attack and dropping below {@link #LOW_HEALTH} of your health: "Need backup" (not for falls, fire, drowning
 * or anything else nobody did to you), at most every {@link #LOW_COOLDOWN_SECONDS}.</li>
 * <li>Counter-Craft (hardcore), each dawn survived: one random surviving player cheers.</li>
 * <li>Counter-Craft, the buy phase ending: one random player in the round gives a pep talk.</li>
 * <li>A boss encounter starting (the Ender Dragon, Wither, Warden or an Elder Guardian first hurting, or hurt by, a
 * player): that player calls "Enemy spotted", once per encounter (it ends after {@link #ENCOUNTER_SECONDS} without
 * a fight).</li>
 * <li>A boss killed: one random surviving player nearby cheers.</li>
 * </ul>
 */
public final class AutoRadio {
	public static final float LOW_HEALTH = 0.4f;
	private static final int LOW_COOLDOWN_SECONDS = 20;
	private static final int ENCOUNTER_SECONDS = 60;
	private static final double NEARBY = 128;

	private static final Map<UUID, Long> LOW_LAST = new ConcurrentHashMap<>();
	/** Boss -> game time it last fought a player (an encounter in progress). */
	private static final Map<UUID, Long> ENCOUNTERS = new ConcurrentHashMap<>();

	private AutoRadio() {
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register(AutoRadio::damaged);
		ServerLivingEntityEvents.AFTER_DEATH.register(AutoRadio::died);
	}

	private static boolean boss(LivingEntity e) {
		return e instanceof EnderDragon || e instanceof WitherBoss || e instanceof Warden || e instanceof ElderGuardian;
	}

	private static void damaged(LivingEntity victim, DamageSource source, float base, float taken, boolean blocked) {
		if (taken <= 0 || !(victim.level() instanceof ServerLevel level)) {
			return;
		}
		LivingEntity attacker = source.getEntity() instanceof LivingEntity le && le != victim ? le : null;
		// a boss fight: whoever's in it calls the threat, once per encounter
		if (victim instanceof ServerPlayer p && attacker != null && boss(attacker)) {
			encounter(level, attacker, p);
		} else if (attacker instanceof ServerPlayer p && boss(victim)) {
			encounter(level, victim, p);
		}
		// low on health while something is hurting you
		if (victim instanceof ServerPlayer p && attacker != null && p.isAlive()) {
			float max = p.getMaxHealth();
			float after = p.getHealth();
			if (after > 0 && after < max * LOW_HEALTH && after + taken >= max * LOW_HEALTH) {
				long now = level.getGameTime();
				Long last = LOW_LAST.get(p.getUUID());
				if (last == null || now - last >= LOW_COOLDOWN_SECONDS * 20L) {
					LOW_LAST.put(p.getUUID(), now);
					Radio.auto(p, "Radio.NeedBackup", "Chatwheel_needbackup");
				}
			}
		}
	}

	private static void encounter(ServerLevel level, LivingEntity boss, ServerPlayer p) {
		long now = level.getGameTime();
		Long last = ENCOUNTERS.put(boss.getUUID(), now);
		if (last == null || now - last > ENCOUNTER_SECONDS * 20L) {
			Radio.auto(p, "Radio.EnemySpotted", "Chatwheel_enemyspotted");
		}
	}

	private static void died(LivingEntity e, DamageSource source) {
		if (!boss(e) || !(e.level() instanceof ServerLevel level)) {
			return;
		}
		ENCOUNTERS.remove(e.getUUID());
		cheer(level.getEntitiesOfClass(ServerPlayer.class, new AABB(e.blockPosition()).inflate(NEARBY), AutoRadio::standing));
	}

	/** Counter-Craft: the day was survived (the players alive and on a team at dawn). */
	public static void dawn(List<ServerPlayer> survivors) {
		cheer(survivors);
	}

	/** Counter-Craft: the buy phase is over (the players alive and on a team). */
	public static void buyPhaseOver(List<ServerPlayer> players) {
		say(players, "CW.PepTalk", "Chatwheel_peptalk");
	}

	private static boolean standing(ServerPlayer p) {
		return p.isAlive() && !p.isSpectator();
	}

	private static void cheer(List<ServerPlayer> players) {
		say(players, "Radio.Cheer", "Chatwheel_cheer");
	}

	/** One random standing player of {@code players} says this. */
	private static void say(List<ServerPlayer> players, String concept, String label) {
		List<ServerPlayer> alive = new ArrayList<>();
		for (ServerPlayer p : players) {
			if (standing(p)) {
				alive.add(p);
			}
		}
		if (!alive.isEmpty()) {
			ServerPlayer who = alive.get(alive.get(0).getRandom().nextInt(alive.size()));
			Radio.auto(who, concept, label);
		}
	}

	public static void leave(ServerPlayer p) {
		LOW_LAST.remove(p.getUUID());
	}
}
