package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.net.ShockPayload;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.particles.ParticleTypes;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * Zeus's own hit (a creeper gets charged, as by the Zeus). The shooter too, if they're in the water, and that always
 * kills ({@link Ballistics#zeusKills}). Other players aren't shocked: on a server that would be team damage.
 */
final class WaterShock {
	static final double RADIUS = 20;
	/** Players this close to the shock see its lightning (the shock's radius and a good view past it). */
	private static final double FX_RANGE = 128;

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
			}
		}
		if (shooter.isInWater() && shooter.isAlive() && shooter.distanceToSqr(at) <= RADIUS * RADIUS && Ballistics.zeusKills(shooter)) {
			done.add(shooter);
			shooter.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.SHOCK)), Ballistics.lethal(shooter));
		}
		// everyone who can see it draws the lightning on the shocked creatures' own models (client: ShockLayer)
		ShockPayload fx = new ShockPayload(done.stream().mapToInt(Entity::getId).toArray());
		for (ServerPlayer p : PlayerLookup.around(level, at, FX_RANGE)) {
			if (ServerPlayNetworking.canSend(p, ShockPayload.TYPE)) {
				ServerPlayNetworking.send(p, fx);
			}
		}
	}
}
