package dev.countercraft;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Gunfire draws mobs (Counter-Craft's survival balance, not CS2 data): every shot aggroes the hostile mobs within
 * {@link #LOUD} blocks ({@link #SUPPRESSED} for a silenced gun: USP-S / M4A1-S with the silencer on, MP5-SD). They target
 * the shooter, and for {@link #CHASE_SECONDS} their follow range stretches to reach them (a zombie would otherwise drop a
 * target 35 blocks off). Loud shots are also a vibration (like an arrow being shot), so sculk sensors, shriekers and
 * Wardens hear them; suppressed shots aren't.
 * <p>Neutral mobs (endermen, zombified piglins...) don't care. A mob already fighting keeps its target. Creative and
 * spectator shooters draw nothing. Bosses have their own AI (a Warden goes by the vibration). Full auto is heard at most
 * every {@link #HEARD_EVERY_TICKS} ticks per shooter.</p>
 */
public final class GunNoise {
	public static final double LOUD = 40;
	public static final double SUPPRESSED = 12;
	private static final int CHASE_SECONDS = 30;
	private static final int HEARD_EVERY_TICKS = 10;
	private static final Identifier CHASE = CounterCraft.id("gunfire_chase");

	/** Mob -> game time its stretched follow range ends. */
	private static final Map<UUID, Long> CHASING = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HEARD = new ConcurrentHashMap<>();

	private GunNoise() {
	}

	/** A shot from {@code shooter}. */
	public static void shot(ServerPlayer shooter, boolean silenced) {
		if (shooter.isCreative() || shooter.isSpectator()) {
			return;
		}
		ServerLevel level = shooter.level();
		long now = level.getGameTime();
		Long last = LAST_HEARD.get(shooter.getUUID());
		if (last != null && now - last < HEARD_EVERY_TICKS) {
			return;
		}
		LAST_HEARD.put(shooter.getUUID(), now);
		Vec3 at = shooter.position();
		if (!silenced) {
			level.gameEvent(shooter, GameEvent.PROJECTILE_SHOOT, at);
		}
		double range = silenced ? SUPPRESSED : LOUD;
		for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(range),
				x -> x instanceof Enemy && !(x instanceof net.minecraft.world.entity.NeutralMob) && x instanceof PathfinderMob && x.isAlive()
						&& !x.isNoAi() && x.position().distanceTo(at) <= range)) {
			if (m.getTarget() != null || m instanceof net.minecraft.world.entity.monster.warden.Warden || !m.canAttack(shooter)) {
				continue;
			}
			stretchFollowRange(m, m.distanceTo(shooter) + 16.0);
			CHASING.put(m.getUUID(), now + CHASE_SECONDS * 20L);
			m.setTarget(shooter);
		}
	}

	/** Make sure the mob keeps a target this far away (a temporary modifier, removed by {@link #tick}). */
	private static void stretchFollowRange(Mob m, double need) {
		AttributeInstance follow = m.getAttribute(Attributes.FOLLOW_RANGE);
		if (follow == null) {
			return;
		}
		follow.removeModifier(CHASE);
		double base = follow.getValue();
		if (base < need) {
			follow.addTransientModifier(new AttributeModifier(CHASE, need - base, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	/** Every server tick: chases that have run their time get their normal follow range back. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0 || CHASING.isEmpty()) {
			return;
		}
		long now = server.overworld().getGameTime();
		for (Iterator<Map.Entry<UUID, Long>> it = CHASING.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Long> e = it.next();
			if (now < e.getValue()) {
				continue;
			}
			it.remove();
			for (ServerLevel level : server.getAllLevels()) {
				Entity found = level.getEntity(e.getKey());
				if (found instanceof Mob m && m.getAttribute(Attributes.FOLLOW_RANGE) != null) {
					m.getAttribute(Attributes.FOLLOW_RANGE).removeModifier(CHASE);
					break;
				}
			}
		}
	}

	public static void leave(ServerPlayer p) {
		LAST_HEARD.remove(p.getUUID());
	}
}
