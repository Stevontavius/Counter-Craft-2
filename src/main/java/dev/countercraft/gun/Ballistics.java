package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.projectile.ProjectileUtil;

/** Hitscan shots with CS2 damage rules. */
public final class Ballistics {
	/** Max shot distance in blocks. */
	public static final double RANGE = 200;
	/** Hammer units per block (1 block = 1 m). */
	public static final double UNITS_PER_BLOCK = 39.37;
	/** CS2 HP to Minecraft HP: 100 CS HP = 20 MC HP. */
	public static final float CS_TO_MC = 0.2f;
	/** A hit at or above (eye height - this) counts as a headshot. */
	public static final double HEAD_MARGIN = 0.25;
	/** Set by the gun before its pellets: kill feed icon of this shot and flags (no-scope, blind, in-air). Server thread. */
	public static String shotIcon = "";
	public static int shotFlags;

	/** What one shot did, for logs and tests. */
	public record ShotResult(Entity target, double distance, boolean headshot, float damageMc, Vec3 hitPos) {
		public static ShotResult miss(Vec3 hitPos, double distance) {
			return new ShotResult(null, distance, false, 0f, hitPos);
		}
	}

	/**
	 * Fire one bullet from the shooter's eyes along their look direction pushed by recoil
	 * ({@code recoilOffset} = {pitchUp, yawRight} degrees) and by inaccuracy ({@code spreadOffset} = {right, up}
	 * tangent offsets, see {@link Accuracy#offset}).
	 */
	public static ShotResult shoot(ServerLevel level, LivingEntity shooter, GunSpec spec, float[] recoilOffset, float[] spreadOffset) {
		Vec3 aim = direction(shooter.getXRot() - recoilOffset[0], shooter.getYRot() + recoilOffset[1]);
		Vec3 right = aim.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 up = right.cross(aim).normalize();
		Vec3 dir = aim.add(right.scale(spreadOffset[0])).add(up.scale(spreadOffset[1])).normalize();
		ShotResult r = shoot(level, shooter, spec, shooter.getEyePosition(), dir);
		if (spec.taser()) {
			WaterShock.after(level, shooter, spec, shooter.getEyePosition(), r);
		}
		return r;
	}

	/** Minecraft rotation (degrees; xRot positive = down) to a unit look vector. */
	public static Vec3 direction(float xRot, float yRot) {
		double p = Math.toRadians(xRot);
		double y = Math.toRadians(yRot);
		return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
	}

	/**
	 * Counter-Craft's wallbangs (its own rules, not CS2's material table): glass shatters under any bullet and the bullet
	 * carries on; rifles, snipers, machine guns and heavy pistols (Desert Eagle, R8) go through one wooden block (planks,
	 * logs, wooden slabs / stairs / doors / trapdoors / fences / gates / shelves, bamboo) at {@link #WOOD_DAMAGE} of their
	 * damage; only the AWP goes through iron bars ({@link #BARS_DAMAGE}). Every gun goes through leaves, up to
	 * {@link #MAX_LEAVES} of them, at full damage (it counts as a wallbang). Anything else stops the bullet.
	 */
	public static final float WOOD_DAMAGE = 0.75f;
	public static final float BARS_DAMAGE = 0.9f;
	public static final int MAX_LEAVES = 24;

	/** This gun can shoot through one wooden block. */
	public static boolean woodbang(GunSpec spec) {
		String t = spec.type();
		return t.equals("rifle") || t.equals("sniper_rifle") || t.equals("machinegun") || spec.id().equals("deagle") || spec.id().equals("revolver");
	}

	private static boolean wood(net.minecraft.world.level.block.state.BlockState st) {
		return st.is(net.minecraft.tags.BlockTags.PLANKS) || st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)
				|| st.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS) || st.is(net.minecraft.tags.BlockTags.WOODEN_DOORS)
				|| st.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS) || st.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
				|| st.is(net.minecraft.tags.BlockTags.FENCE_GATES) || st.is(net.minecraft.tags.BlockTags.WOODEN_SHELVES)
				|| st.is(net.minecraft.tags.BlockTags.BAMBOO_BLOCKS);
	}

	static boolean glass(net.minecraft.world.level.block.state.BlockState st) {
		return st.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags.GLASS_BLOCKS)
				|| st.is(net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags.GLASS_PANES);
	}

	/** The shooter may break this block (not in adventure mode or spawn protection; mobs: the mobGriefing rule). */
	static boolean mayBreak(ServerLevel level, LivingEntity shooter, net.minecraft.core.BlockPos pos) {
		if (shooter instanceof net.minecraft.world.entity.player.Player p) {
			return p.mayBuild() && level.mayInteract(p, pos);
		}
		return level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING);
	}

	/** Where the ray leaves this block's collision shape (just past it), or null. */
	private static Vec3 exit(ServerLevel level, net.minecraft.core.BlockPos pos, Vec3 at, Vec3 dir) {
		var shape = level.getBlockState(pos).getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return at.add(dir.scale(0.01));
		}
		AABB box = shape.bounds().move(pos);
		// from beyond the box back towards the entry: the first face crossed is the exit
		Vec3 far = at.add(dir.scale(2.0));
		var back = box.clip(far, at);
		return back.map(v -> v.add(dir.scale(0.01))).orElse(null);
	}

	private static void impact(ServerLevel level, Vec3 p) {
		level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 3, 0.02, 0.02, 0.02, 0.01);
		level.sendParticles(ParticleTypes.CRIT, p.x, p.y, p.z, 2, 0.0, 0.0, 0.0, 0.1);
	}

	/**
	 * Collaterals (Counter-Craft's rule, like the wallbangs above; CS2's own loss through a body isn't measured): a bullet
	 * carries on through a body into whatever is behind it, through as many bodies as the gun's CS2 penetration rounded
	 * down (pistols, SMGs and shotguns 1, rifles and snipers 2, the Zeus none), keeping {@link #BODY_DAMAGE} of its damage
	 * each time. A hit behind a body counts as a penetration (the kill feed's wallbang icon, as CS2 shows it).
	 */
	public static final float BODY_DAMAGE = 0.75f;

	/**
	 * Fire one bullet from {@code from} along {@code dir} (unit vector). The result is the first thing it hit, with
	 * {@code hitPos} where the bullet ended (past the bodies it went through).
	 */
	public static ShotResult shoot(ServerLevel level, LivingEntity shooter, GunSpec spec, Vec3 from, Vec3 dir) {
		bulletKills = 0;
		ShotResult r = bullet(level, shooter, spec, from, dir);
		if (bulletKills >= 3 && shooter instanceof net.minecraft.server.level.ServerPlayer sp) {
			dev.countercraft.Achievements.award(sp, "collateral_damage");
		}
		return r;
	}

	/** Mobs this bullet has killed so far (server thread). */
	private static int bulletKills;

	private static ShotResult bullet(ServerLevel level, LivingEntity shooter, GunSpec spec, Vec3 from, Vec3 dir) {
		Vec3 to = from.add(dir.scale(Math.min(RANGE, spec.range() / UNITS_PER_BLOCK)));
		boolean woodLeft = woodbang(spec);
		boolean awp = spec.id().equals("awp");
		int leavesLeft = MAX_LEAVES;
		int bodiesLeft = spec.taser() ? 0 : (int) spec.penetration();
		float through = 1f;          // damage left after what the bullet went through
		boolean penetrated = false;
		Vec3 cur = from;
		ShotResult first = null;
		java.util.Set<Entity> hitAlready = new java.util.HashSet<>();
		for (int guard = 0; guard < MAX_LEAVES + 16; guard++) {
			BlockHitResult block = level.clip(new ClipContext(cur, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
			Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
			EntityHit e = traceEntity(level, shooter, cur, dir, cur.distanceTo(end), hitAlready);
			if (e != null) {
				// distances count from the muzzle (CS2's falloff)
				ShotResult r = hit(level, shooter, spec, from, new EntityHit(e.entity(), e.point(), from.distanceTo(e.point()), e.group()), through,
						penetrated);
				first = first == null ? r : first;
				hitAlready.add(owner(e.entity())); // all of a dragon: not its next part too
				if (r.target() == null || bodiesLeft-- <= 0) { // deflected (a perched dragon), or no penetration left
					return withEnd(first, e.point());
				}
				through *= BODY_DAMAGE;
				penetrated = true;
				cur = e.point();
				continue;
			}
			if (block.getType() == HitResult.Type.MISS) {
				return first != null ? withEnd(first, end) : ShotResult.miss(end, from.distanceTo(end));
			}
			net.minecraft.core.BlockPos pos = block.getBlockPos();
			net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
			Vec3 next = null;
			if (glass(st) && mayBreak(level, shooter, pos)) {
				level.destroyBlock(pos, false, shooter); // shatters; the bullet carries on
				next = end;
			} else if (woodLeft && wood(st)) {
				next = exit(level, pos, end, dir);
				woodLeft = false;
				through *= WOOD_DAMAGE;
			} else if (awp && st.is(net.minecraft.world.level.block.Blocks.IRON_BARS)) {
				next = exit(level, pos, end, dir);
				through *= BARS_DAMAGE;
			} else if (leavesLeft > 0 && st.is(net.minecraft.tags.BlockTags.LEAVES)) {
				next = exit(level, pos, end, dir);
				leavesLeft--;
			}
			impact(level, end);
			if (next == null) {
				return first != null ? withEnd(first, end) : ShotResult.miss(end, from.distanceTo(end));
			}
			if (!glass(st)) {
				penetrated = true;
			}
			cur = next;
		}
		return first != null ? withEnd(first, cur) : ShotResult.miss(cur, from.distanceTo(cur));
	}

	private static ShotResult withEnd(ShotResult r, Vec3 end) {
		return new ShotResult(r.target(), r.distance(), r.headshot(), r.damageMc(), end);
	}

	/** The bullet hits {@code entity} with {@code through} of its damage left; a miss result when it's deflected. */
	static ShotResult hit(ServerLevel level, LivingEntity shooter, GunSpec spec, Vec3 from, EntityHit entity, float through,
			boolean penetrated) {
		Entity target = entity.entity();
		Vec3 hit = entity.point();
		double dist = entity.distance();
		Entity owner = owner(target);
		// bosses: a perched dragon shrugs off half the bullets; the Wither takes no headshots ({@link BossArmor})
		if (dev.countercraft.BossArmor.deflects(owner, level.getRandom())) {
			level.sendParticles(ParticleTypes.CRIT, hit.x, hit.y, hit.z, 4, 0.05, 0.05, 0.05, 0.2);
			return ShotResult.miss(hit, dist);
		}
		// the Zeus charges a creeper instead of hurting it (as lightning would, but no strike: nothing catches fire);
		// one already charged takes the shot as anything else does
		if (spec.taser() && owner instanceof net.minecraft.world.entity.monster.Creeper creeper && !creeper.isPowered() && creeper.isAlive()) {
			creeper.getEntityData().set(dev.countercraft.mixin.CreeperAccessor.countercraft$powered(), true);
			if (shooter instanceof net.minecraft.server.level.ServerPlayer sp) {
				dev.countercraft.Achievements.award(sp, "electrician");
			}
			level.playSound(null, creeper.getX(), creeper.getY(), creeper.getZ(), net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_IMPACT,
					net.minecraft.sounds.SoundSource.HOSTILE, 0.6f, 1.6f);
			return new ShotResult(target, dist, false, 0f, hit);
		}
		// the Zeus has no hit groups (never a headshot); the Wither takes no headshots
		int group = spec.taser() ? Hitboxes.GENERIC
				: dev.countercraft.BossArmor.noHeadshots(owner) && entity.group() == Hitboxes.HEAD ? Hitboxes.GENERIC : entity.group();
		boolean head = group == Hitboxes.HEAD;

		boolean armored = dev.countercraft.Combat.armored(owner) && dev.countercraft.Combat.armorCovers(owner, group);
		float csDamage = dev.countercraft.Combat.afterArmor(owner, csDamage(spec, dist, group), spec.armorRatio(), group);
		float mc = csDamage * through * CS_TO_MC / dev.countercraft.BossArmor.bulletResistance(owner);
		int flags = shotFlags | (head ? dev.countercraft.net.KillFeedPayload.F_HEADSHOT : 0)
				| (penetrated ? dev.countercraft.net.KillFeedPayload.F_PENETRATE : 0)
				| (dev.countercraft.Combat.throughSmoke(level, from, hit) ? dev.countercraft.net.KillFeedPayload.F_SMOKE : 0);
		dev.countercraft.Combat.markHit(shooter, owner, shotIcon.isEmpty() ? spec.id() : shotIcon, flags);
		dev.countercraft.RunStats.hit(shooter);
		boolean wasAlive = owner.isAlive();

		boolean kills = spec.taser() && zeusKills(owner);
		DamageSource source = new DamageSource(kills ? CounterCraft.damageType(level, CounterCraft.ZEUS_KILL)
				: spec.taser() ? CounterCraft.damageType(level, CounterCraft.TASER) : CounterCraft.bulletDamage(level), shooter, shooter);
		// a dragon part passes the hit on to the dragon (vanilla: x 1/4 off the head); an end crystal explodes
		target.hurtServer(level, source, kills ? Math.max(mc, lethal((LivingEntity) owner)) : mc);
		if (owner instanceof LivingEntity) {
			boolean lethal = wasAlive && (!owner.isAlive() || owner instanceof LivingEntity le && le.isDeadOrDying());
			if (lethal && owner instanceof net.minecraft.world.entity.Mob) {
				bulletKills++;
			}
			dev.countercraft.Combat.hitSound(level, shooter, owner, head, lethal, armored, false, hit);
		}
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, hit.x, hit.y, hit.z, head ? 4 : 1, 0.05, 0.05, 0.05, 0.1);
		return new ShotResult(target, dist, head, mc, hit);
	}

	/**
	 * A Zeus shock always kills a player (Counter-Craft's rule: being shocked is a catastrophic mistake): its damage types
	 * (countercraft:zeus_kill, and :shock for zapping the water you're in) get past armor, Resistance, Protection and
	 * shields, hardcore's lighter player damage doesn't apply, and the damage is more than all their health and absorption.
	 * Only a Totem of Undying in hand saves them (it pops). Creative players and anyone still picking a team are exempt, as
	 * from any damage. Mobs take the Zeus's hit.
	 */
	public static boolean zeusKills(Entity e) {
		return e instanceof net.minecraft.server.level.ServerPlayer p && !p.getAbilities().invulnerable && !dev.countercraft.team.TeamLock.pending(p);
	}

	/** More damage than {@code e} can take. */
	static float lethal(LivingEntity e) {
		return e.getHealth() + e.getAbsorptionAmount() + 1f;
	}

	/** An entity a ray hit: where, how far along the ray (blocks), and CS2's hit group there. */
	public record EntityHit(Entity entity, Vec3 point, double distance, int group) {
	}

	/**
	 * The nearest hittable entity along the ray from {@code from} (unit {@code dir}) within {@code maxDist}: CS2 capsules
	 * for players ({@link Hitboxes}; a ray between the capsules misses); for mobs their model's head cubes ({@link MobHeads})
	 * then their Minecraft box (legs / generic); without model data, head above eye height - {@link #HEAD_MARGIN}.
	 */
	public static EntityHit traceEntity(ServerLevel level, Entity shooter, Vec3 from, Vec3 dir, double maxDist) {
		return traceEntity(level, shooter, from, dir, maxDist, java.util.Set.of());
	}

	/**
	 * As above, passing over {@code skip} (bodies the bullet already went through). Each target is traced where the shooter
	 * saw it ({@link LagCompensation}): at the first of those moments the ray hits it.
	 */
	public static EntityHit traceEntity(ServerLevel level, Entity shooter, Vec3 from, Vec3 dir, double maxDist, java.util.Set<Entity> skip) {
		AABB sweep = new AABB(from, from.add(dir.scale(maxDist))).inflate(LagCompensation.REACH);
		EntityHit best = null;
		for (Entity e : level.getEntities(shooter, sweep, x -> hittable(x, shooter) && !skip.contains(owner(x)))) {
			for (Vec3 shift : LagCompensation.shifts(e, shooter)) {
				// the target where it was = the ray moved by how far it has gone since, against the target as it is
				EntityHit h = traceOne(e, from.add(shift), dir, maxDist);
				if (h != null) {
					if (best == null || h.distance() < best.distance()) {
						best = new EntityHit(e, h.point().subtract(shift), h.distance(), h.group());
					}
					break;
				}
			}
		}
		return best;
	}

	/** One entity: CS2 capsules, a mob's model head, else its box. */
	private static EntityHit traceOne(Entity e, Vec3 from, Vec3 dir, double maxDist) {
		if (Hitboxes.uses(e)) {
			Hitboxes.Hit h = Hitboxes.trace(e, from, dir, maxDist);
			return h == null ? null : new EntityHit(e, h.point(), h.distance(), h.group());
		}
		// the head from the mob's own model (it can stick out of the box), else the box
		MobHeads.Hit head = MobHeads.trace(e, from, dir, maxDist);
		if (head != null) {
			return new EntityHit(e, head.point(), head.distance(), Hitboxes.HEAD);
		}
		AABB box = e.getBoundingBox().inflate(e.getPickRadius());
		Vec3 p = box.contains(from) ? from : box.clip(from, from.add(dir.scale(maxDist))).orElse(null);
		if (p == null) {
			return null;
		}
		int group = MobHeads.has(e) ? MobHeads.bodyGroup(e, p) : headshot(e, p) ? Hitboxes.HEAD : Hitboxes.GENERIC;
		return new EntityHit(e, p, from.distanceTo(p), group);
	}

	/**
	 * What bullets, knives and grenades can hit: living things, the ender dragon's body parts (the dragon itself has no
	 * hitbox) and end crystals.
	 */
	public static boolean hittable(Entity e, Entity shooter) {
		if (e == shooter || e.isSpectator() || !e.isAlive()) {
			return false;
		}
		if (e instanceof EnderDragonPart || e instanceof EndCrystal) {
			return true;
		}
		return e instanceof LivingEntity && e.isPickable();
	}

	/** The creature a hit belongs to (a dragon part's dragon). */
	public static Entity owner(Entity e) {
		return e instanceof EnderDragonPart part ? part.parentMob : e;
	}

	/** Head hit: the dragon's head part, or at / above (eye height - margin) on anything else living. */
	public static boolean headshot(Entity target, Vec3 hit) {
		if (target instanceof EnderDragonPart part) {
			return part == part.parentMob.head;
		}
		return target instanceof LivingEntity && hit.y >= target.getEyeY() - HEAD_MARGIN;
	}

	/** CS2 damage (in CS HP) for a hit at {@code distBlocks}: base * rangeModifier^(units / 500), x headMult on the head. */
	public static float csDamage(GunSpec spec, double distBlocks, boolean head) {
		return csDamage(spec, distBlocks, head ? Hitboxes.HEAD : Hitboxes.GENERIC);
	}

	/** As above, x the hit group's multiplier (head: the gun's, stomach 1.25, legs 0.75). */
	public static float csDamage(GunSpec spec, double distBlocks, int group) {
		double units = distBlocks * UNITS_PER_BLOCK;
		double dmg = spec.damage() * Math.pow(spec.rangeModifier(), units / 500.0);
		return (float) (dmg * Hitboxes.damageMultiplier(group, spec.headMult()));
	}


	private Ballistics() {
	}
}
