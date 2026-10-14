package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Zeus and water (Counter-Craft's own rule; CS2 has no water to shock): a Zeus shot that hits a mob standing or
 * swimming in water, or that reaches water itself, shocks every mob in the water within {@link #RADIUS} blocks with the
 * Zeus's own hit (a creeper gets charged, as by the Zeus). The shooter too, if they're in the water. Other players
 * aren't: on a server that would be team damage.
 */
final class WaterShock {
	static final double RADIUS = 20;

	private WaterShock() {
	}

	/** After a Zeus shot from {@code from}: the water it hit, or the mob in water it hit, shocks the water around. */
	static void after(ServerLevel level, LivingEntity shooter, GunSpec spec, Vec3 from, Ballistics.ShotResult shot) {
		Entity hit = shot.target() == null ? null : Ballistics.owner(shot.target());
		Vec3 at = null;
		BlockHitResult water = level.clip(new ClipContext(from, shot.hitPos(), ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, shooter));
		if (water.getType() != HitResult.Type.MISS && level.getFluidState(water.getBlockPos()).is(FluidTags.WATER)) {
			at = water.getLocation(); // the bolt reached water before (or without) hitting anything
		}
		if (hit != null && hit.isInWater()) {
			at = hit.position();
		}
		if (at != null) {
			shock(level, shooter, spec, at, hit);
		}
	}

	private static void shock(ServerLevel level, LivingEntity shooter, GunSpec spec, Vec3 at, Entity alreadyHit) {
		level.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1f, 1.6f);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 30, 0.6, 0.3, 0.6, 0.4);
		Set<Entity> done = new HashSet<>();
		if (alreadyHit != null) {
			done.add(alreadyHit);
		}
		for (Entity e : level.getEntities(shooter, new AABB(at, at).inflate(RADIUS), x -> x instanceof Mob && x.isAlive() && x.isInWater()
				&& x.distanceToSqr(at) <= RADIUS * RADIUS)) {
			if (done.add(e)) {
				Vec3 c = e.getBoundingBox().getCenter();
				Ballistics.hit(level, shooter, spec, at, new Ballistics.EntityHit(e, c, 0, Hitboxes.GENERIC), 1f, false);
				spark(level, e);
			}
		}
		if (shooter.isInWater() && shooter.isAlive() && shooter.distanceToSqr(at) <= RADIUS * RADIUS) {
			float cs = dev.countercraft.Combat.afterArmor(shooter, Ballistics.csDamage(spec, 0, Hitboxes.GENERIC), spec.armorRatio(), Hitboxes.GENERIC);
			shooter.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.SHOCK)), cs * Ballistics.CS_TO_MC);
			spark(level, shooter);
		}
	}

	private static void spark(ServerLevel level, Entity e) {
		AABB b = e.getBoundingBox();
		Vec3 c = b.getCenter();
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 12, b.getXsize() / 2, b.getYsize() / 2, b.getZsize() / 2, 0.2);
	}
}
