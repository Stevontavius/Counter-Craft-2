package dev.countercraft;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.warden.Warden;

/**
 * How Minecraft's bosses stand up to CS2 gunfire (Counter-Craft's own balance, not CS2 data):
 * <ul>
 * <li>Ender Dragon: 6x resistant to bullets; while perched on the portal, only half the bullets that hit it do damage.</li>
 * <li>Wither: 4x resistant to bullets, and headshots do body damage (no head multiplier).</li>
 * <li>Warden: 3x resistant to bullets; flashbangs don't blind it and smokes don't hide you from it (it hunts by
 * vibration and smell, not sight). Worse: they set it on you. Throw a flashbang within {@link #WARDEN_HEARING} blocks
 * of a Warden and it stops dead and stares at you (an angry listening growl) for as long as the flash is in the air;
 * when the flash pops, it charges straight at you: fully enraged, no roar first, Speed III for 15 s. A smoke popping
 * enrages it at the thrower, and while the smoke hisses, anyone standing in it near a Warden draws more of its anger
 * every second. Decoys turn its blindness against it: a fake shot within {@link #DECOY_HEARING} blocks fixes it on the
 * decoy for as long as the decoy fires. It drops its target, forgets its anger at every player, storms over and
 * pounds the decoy; footsteps don't pull it away. Only a player hurting it breaks the spell (or a flashbang). Once the
 * decoy pops it sniffs around the spot.</li>
 * </ul>
 * Knives and grenades aren't affected.
 */
public final class BossArmor {
	public static final float DRAGON_RESISTANCE = 6f;
	public static final float WITHER_RESISTANCE = 4f;
	public static final float WARDEN_RESISTANCE = 3f;
	/** Chance a bullet that hits a perched dragon does damage. */
	public static final float PERCHED_HIT_CHANCE = 0.5f;

	private BossArmor() {
	}

	/** Bullet damage is divided by this for {@code owner} (1 for everything that isn't a boss). */
	public static float bulletResistance(Entity owner) {
		if (owner instanceof EnderDragon) {
			return DRAGON_RESISTANCE;
		}
		if (owner instanceof WitherBoss) {
			return WITHER_RESISTANCE;
		}
		if (owner instanceof Warden) {
			return WARDEN_RESISTANCE;
		}
		return 1f;
	}

	/** Headshots count as body shots on this entity. */
	public static boolean noHeadshots(Entity owner) {
		return owner instanceof WitherBoss;
	}

	/** A bullet that hit this entity glances off without damage (a perched dragon: half the time). */
	public static boolean deflects(Entity owner, RandomSource random) {
		return owner instanceof EnderDragon d && d.getPhaseManager().getCurrentPhase().isSitting() && random.nextFloat() >= PERCHED_HIT_CHANCE;
	}

	/** How far a Warden hears a flashbang or a smoke go off, blocks. */
	public static final double WARDEN_HEARING = 48;
	/** Anger at the thrower: a flash maxes it, a smoke makes it angry (80 = hunting), each second in a hissing smoke adds more. */
	private static final int FLASH_ANGER = 150;
	private static final int SMOKE_ANGER = 80;
	private static final int SMOKE_HISS_ANGER = 20;

	/** A Warden staring down a flashbang's thrower until the flash pops. */
	private record Stare(java.util.UUID thrower, java.util.UUID grenade) {
	}

	private static final java.util.Map<java.util.UUID, Stare> STARES = new java.util.concurrent.ConcurrentHashMap<>();

	/** A flashbang was thrown: every Warden in earshot of the thrower stops and stares at them. */
	public static void flashThrown(net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.LivingEntity thrower, Entity grenade) {
		for (Warden w : wardens(level, thrower.position())) {
			DECOYED.remove(w.getUUID()); // a flash breaks a decoy's spell
			STARES.put(w.getUUID(), new Stare(thrower.getUUID(), grenade.getUUID()));
			w.getNavigation().stop();
			level.playSound(null, w.getX(), w.getY(), w.getZ(), net.minecraft.sounds.SoundEvents.WARDEN_LISTENING_ANGRY,
					net.minecraft.sounds.SoundSource.HOSTILE, 3f, 1f);
		}
	}

	/** Every server tick: staring Wardens stand still, eyes locked on the thrower, until the flash is gone. */
	public static void tick(net.minecraft.server.MinecraftServer server) {
		tickDecoyed(server);
		if (STARES.isEmpty()) {
			return;
		}
		for (var it = STARES.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Warden w = null;
			Entity thrower = null;
			Entity grenade = null;
			for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(e.getKey()) instanceof Warden found) {
					w = found;
					thrower = level.getEntity(e.getValue().thrower());
					grenade = level.getEntity(e.getValue().grenade());
					break;
				}
			}
			if (w == null || !w.isAlive() || thrower == null || !thrower.isAlive() || grenade == null || grenade.isRemoved()) {
				it.remove();
				continue;
			}
			w.getNavigation().stop();
			double dx = thrower.getX() - w.getX();
			double dz = thrower.getZ() - w.getZ();
			double dy = thrower.getEyeY() - w.getEyeY();
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
			float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
			w.setYRot(yaw);
			w.setYHeadRot(yaw);
			w.setYBodyRot(yaw);
			w.setXRot(pitch);
			w.getLookControl().setLookAt(thrower, 360f, 360f);
		}
	}

	/** A flashbang went off at {@code at}: every Warden in earshot (or staring at the thrower) charges the thrower. */
	public static void flashbang(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 at, Entity thrower) {
		java.util.Set<Warden> chargers = new java.util.HashSet<>(wardens(level, at));
		if (thrower != null) {
			for (var e : STARES.entrySet()) {
				if (e.getValue().thrower().equals(thrower.getUUID()) && level.getEntity(e.getKey()) instanceof Warden w) {
					chargers.add(w);
				}
			}
		}
		for (Warden w : chargers) {
			STARES.remove(w.getUUID());
			if (thrower instanceof net.minecraft.world.entity.LivingEntity t && t.isAlive() && w.canTargetEntity(t)) {
				charge(w, t);
			}
		}
	}

	/** Straight at them: fully enraged, the roar skipped, fast. */
	private static void charge(Warden w, net.minecraft.world.entity.LivingEntity t) {
		w.increaseAngerAt(t, FLASH_ANGER, true);
		var brain = w.getBrain();
		brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ROAR_TARGET);
		brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET, t);
		brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);
		net.minecraft.world.entity.monster.warden.WardenAi.updateActivity(brain);
		w.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 300, 2));
		w.level().playSound(null, w.getX(), w.getY(), w.getZ(), net.minecraft.sounds.SoundEvents.WARDEN_ROAR, net.minecraft.sounds.SoundSource.HOSTILE, 3f, 1f);
	}

	/** A smoke popped at {@code at}: Wardens in earshot hunt the thrower. */
	public static void smokePopped(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 at, Entity thrower) {
		for (Warden w : wardens(level, at)) {
			if (thrower instanceof net.minecraft.world.entity.LivingEntity t && t.isAlive()) {
				w.increaseAngerAt(t, SMOKE_ANGER, true);
			}
		}
	}

	/** Once a second while a smoke hisses: anyone inside it draws the anger of Wardens in earshot. */
	public static void smokeHiss(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 at, double radius) {
		java.util.List<Warden> near = wardens(level, at);
		if (near.isEmpty()) {
			return;
		}
		for (net.minecraft.world.entity.player.Player p : level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,
				new net.minecraft.world.phys.AABB(at, at).inflate(radius), p -> p.isAlive() && !p.isSpectator() && !p.isCreative()
						&& p.position().distanceTo(at) <= radius)) {
			for (Warden w : near) {
				w.increaseAngerAt(p, SMOKE_HISS_ANGER, false);
			}
		}
	}

	private static java.util.List<Warden> wardens(net.minecraft.server.level.ServerLevel level, net.minecraft.world.phys.Vec3 at) {
		return level.getEntitiesOfClass(Warden.class, new net.minecraft.world.phys.AABB(at, at).inflate(WARDEN_HEARING),
				w -> w.isAlive() && w.position().distanceTo(at) <= WARDEN_HEARING);
	}

	/** How far a Warden hears a decoy's fake gunfire, blocks. */
	public static final double DECOY_HEARING = 32;

	/** Warden -> the decoy it's fixed on. */
	private static final java.util.Map<java.util.UUID, java.util.UUID> DECOYED = new java.util.concurrent.ConcurrentHashMap<>();

	/** A decoy fired one of its fake shots: Wardens in earshot fix on it (see the class notes). */
	public static void decoyShot(net.minecraft.server.level.ServerLevel level, Entity decoy) {
		net.minecraft.world.phys.Vec3 at = decoy.position();
		for (Warden w : level.getEntitiesOfClass(Warden.class, new net.minecraft.world.phys.AABB(at, at).inflate(DECOY_HEARING),
				x -> x.isAlive() && x.position().distanceTo(at) <= DECOY_HEARING)) {
			STARES.remove(w.getUUID());
			if (DECOYED.put(w.getUUID(), decoy.getUUID()) == null) {
				level.playSound(null, w.getX(), w.getY(), w.getZ(), net.minecraft.sounds.SoundEvents.WARDEN_LISTENING_ANGRY,
						net.minecraft.sounds.SoundSource.HOSTILE, 3f, 1f);
			}
		}
	}

	/** Every server tick: decoyed Wardens ignore players and go for the decoy. */
	private static void tickDecoyed(net.minecraft.server.MinecraftServer server) {
		for (var it = DECOYED.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			Warden w = null;
			Entity decoy = null;
			for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(e.getKey()) instanceof Warden found) {
					w = found;
					decoy = level.getEntity(e.getValue());
					break;
				}
			}
			if (w == null || !w.isAlive()) {
				it.remove();
				continue;
			}
			var brain = w.getBrain();
			// a player hurting it breaks the spell (vanilla anger from the hit takes over)
			boolean hurtByPlayer = w.getLastHurtByMob() instanceof net.minecraft.world.entity.player.Player
					&& w.tickCount - w.getLastHurtByMobTimestamp() <= 1;
			if (hurtByPlayer || decoy == null || decoy.isRemoved()) {
				it.remove();
				if (!hurtByPlayer && decoy != null) {
					net.minecraft.world.entity.monster.warden.WardenAi.setDisturbanceLocation(w, decoy.blockPosition());
				}
				continue;
			}
			var anger = w.getAngerManagement();
			for (net.minecraft.server.level.ServerPlayer p : ((net.minecraft.server.level.ServerLevel) w.level()).players()) {
				if (anger.getActiveAnger(p) > 0) {
					anger.clearAnger(p);
				}
			}
			brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ATTACK_TARGET);
			brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.ROAR_TARGET);
			brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.DISTURBANCE_LOCATION);
			net.minecraft.world.entity.monster.warden.WardenAi.updateActivity(brain);
			if (w.distanceTo(decoy) > 2.0) {
				brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
						new net.minecraft.world.entity.ai.memory.WalkTarget(decoy, 1.2f, 1));
			} else {
				// on it: pound it
				brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
				w.getNavigation().stop();
				w.getLookControl().setLookAt(decoy, 360f, 360f);
				if ((w.tickCount + w.getId()) % 18 == 0) {
					w.level().broadcastEntityEvent(w, (byte) 4);
					w.level().playSound(null, decoy.getX(), decoy.getY(), decoy.getZ(), net.minecraft.sounds.SoundEvents.WARDEN_ATTACK_IMPACT,
							net.minecraft.sounds.SoundSource.HOSTILE, 1.5f, 1f);
				}
			}
		}
	}

	/** Mobs that don't hunt by sight: flashes don't blind them, smokes don't hide you. */
	public static boolean ignoresVisionUtility(Mob m) {
		return m instanceof Warden;
	}
}
