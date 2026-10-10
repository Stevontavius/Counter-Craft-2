package dev.countercraft;

import dev.countercraft.gun.GrenadeEntity;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What smokes and flashbangs do to mobs (players get CS2's own effects).
 * <ul>
 * <li>Smoke: a mob can't see through at least {@link #SMOKE_DEPTH} blocks of smoke (its Sensing line-of-sight check
 * fails, so targeting goals and brain sensors can't pick you). A mob tracking you when the smoke cuts its view drops you
 * and goes to where it last saw you for {@link #SEARCH_TICKS}; it only targets you again once it sees you.</li>
 * <li>Flashbang: as for players, longer when close and facing the flash (and only if the flash is in sight); the mob
 * drops its target, sees nothing, and stumbles about at random until its sight comes back.</li>
 * <li>Decoy: each fake shot sends the hostile mobs within {@link #DECOY_HEARING} blocks that don't see their target
 * (or have none) to investigate the decoy for {@link #DECOY_INVESTIGATE_TICKS}. They don't get a target from it; a mob
 * that spots someone on the way goes for them as usual. A mob that sees you keeps fighting.</li>
 * </ul>
 * Tuning (not CS2 data): the smoke depth, the search time and the blind wandering.
 */
public final class MobVision {
	/** Smoke a sight line must cross to be blocked, blocks (about 20 units: any real pass through the cloud). */
	public static final double SMOKE_DEPTH = 0.5;
	/** How long a mob that lost you in smoke keeps going to where it last saw you. */
	private static final int SEARCH_TICKS = 40;
	public static final double DECOY_HEARING = 40;
	private static final int DECOY_INVESTIGATE_TICKS = 200;

	private record Search(Vec3 lastSeen, long until) {
	}

	private static final Map<UUID, Search> SEARCH = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> BLIND_UNTIL = new ConcurrentHashMap<>();
	/** Re-entry guard: our own checks call Sensing too. */
	private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);

	private MobVision() {
	}

	public static boolean blinded(Mob m) {
		Long until = BLIND_UNTIL.get(m.getUUID());
		return until != null && m.level().getGameTime() < until;
	}

	/** The sight line from the mob's eyes to {@code target} crosses enough smoke. */
	public static boolean smokeBlocks(Mob m, Entity target) {
		return m.level() instanceof ServerLevel level
				&& Combat.smokeDepth(level, m.getEyePosition(), target.getEyePosition()) >= SMOKE_DEPTH;
	}

	/** Sensing.hasLineOfSight's answer, after smoke and flashes (SensingMixin). */
	public static boolean canSee(Mob m, Entity target, boolean vanilla) {
		if (!vanilla || CHECKING.get()) {
			return vanilla;
		}
		if (BossArmor.ignoresVisionUtility(m)) {
			return true; // a Warden follows you through smoke
		}
		CHECKING.set(true);
		try {
			return !blinded(m) && !smokeBlocks(m, target);
		} finally {
			CHECKING.set(false);
		}
	}

	/** A decoy's fake shot at {@code at}: hostile mobs that can't see a target walk over to look. */
	public static void decoyShot(ServerLevel level, Vec3 at) {
		long until = level.getGameTime() + DECOY_INVESTIGATE_TICKS;
		for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(DECOY_HEARING),
				x -> x instanceof net.minecraft.world.entity.monster.Enemy && !(x instanceof net.minecraft.world.entity.NeutralMob)
						&& x instanceof net.minecraft.world.entity.PathfinderMob && x.isAlive() && !x.isNoAi()
						&& !BossArmor.ignoresVisionUtility(x) && x.position().distanceTo(at) <= DECOY_HEARING)) {
			LivingEntity t = target(m);
			if (t != null && m.getSensing().hasLineOfSight(t)) {
				continue; // it sees you: not fooled
			}
			if (t != null) {
				dropTarget(m);
			}
			// a spot near the decoy, so a crowd doesn't stack on one block
			var r = m.getRandom();
			Vec3 look = at.add((r.nextDouble() - 0.5) * 3, 0, (r.nextDouble() - 0.5) * 3);
			Search old = SEARCH.put(m.getUUID(), new Search(look, until));
			if (old == null || old.lastSeen().distanceToSqr(at) > 9) {
				m.getNavigation().moveTo(look.x, look.y, look.z, 1.0);
			}
		}
	}

	/** A flashbang went off: blind this mob for {@code seconds} (not a Warden: it doesn't hunt by sight). */
	public static void flash(Mob m, float seconds) {
		if (BossArmor.ignoresVisionUtility(m)) {
			return;
		}
		long until = m.level().getGameTime() + Math.round(seconds * 20f);
		BLIND_UNTIL.merge(m.getUUID(), until, Math::max);
		dropTarget(m);
		SEARCH.remove(m.getUUID());
		m.getNavigation().stop();
	}

	private static void dropTarget(Mob m) {
		m.setTarget(null);
		m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
	}

	/** Every server tick. */
	public static void tick(MinecraftServer server) {
		long tick = server.getTickCount();
		for (ServerLevel level : server.getAllLevels()) {
			// mobs tracking someone through a smoke lose them
			if (tick % 4 == 0) {
				for (GrenadeEntity g : level.getEntities(CounterCraft.GRENADE_ENTITY, x -> x.state() == GrenadeEntity.SMOKING)) {
					AABB around = g.getBoundingBox().inflate(GrenadeEntity.SMOKE_RADIUS + 40);
					for (Mob m : level.getEntitiesOfClass(Mob.class, around, x -> x.isAlive() && target(x) != null)) {
						LivingEntity t = target(m);
						if (!BossArmor.ignoresVisionUtility(m) && smokeBlocks(m, t)) {
							SEARCH.put(m.getUUID(), new Search(t.position(), level.getGameTime() + SEARCH_TICKS));
							dropTarget(m);
							m.getNavigation().moveTo(t.getX(), t.getY(), t.getZ(), 1.0);
						}
					}
				}
			}
		}
		long now = server.overworld().getGameTime();
		// searching: keep heading for the last known position until the search runs out or they see you again
		for (Iterator<Map.Entry<UUID, Search>> it = SEARCH.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Search> en = it.next();
			Mob m = find(server, en.getKey());
			if (m == null || target(m) != null || now > en.getValue().until()) {
				it.remove();
				continue;
			}
			if (m.getNavigation().isDone()) {
				Vec3 p = en.getValue().lastSeen();
				if (m.position().distanceToSqr(p) > 2.0) {
					m.getNavigation().moveTo(p.x, p.y, p.z, 1.0);
				}
			}
		}
		// blinded: no target, stumbling about
		for (Iterator<Map.Entry<UUID, Long>> it = BLIND_UNTIL.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> en = it.next();
			Mob m = find(server, en.getKey());
			if (m == null || now >= en.getValue()) {
				it.remove();
				continue;
			}
			if (target(m) != null) {
				dropTarget(m);
			}
			if ((now + m.getId()) % 20 == 0) {
				var r = m.getRandom();
				m.getNavigation().moveTo(m.getX() + (r.nextDouble() - 0.5) * 8, m.getY(), m.getZ() + (r.nextDouble() - 0.5) * 8, 0.7);
				m.setYHeadRot(m.getYHeadRot() + (r.nextFloat() - 0.5f) * 120f);
			}
		}
	}

	/** A mob's current target (goal-based or brain-based). */
	private static LivingEntity target(Mob m) {
		if (m.getTarget() != null) {
			return m.getTarget();
		}
		return m.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET) ? m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) : null;
	}

	private static Mob find(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.getEntity(id) instanceof Mob m && m.isAlive()) {
				return m;
			}
		}
		return null;
	}
}
