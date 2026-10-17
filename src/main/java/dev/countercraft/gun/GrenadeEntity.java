package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.net.FlashPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A thrown CS2 grenade with CS's own grenade physics (see {@link #step}): 64 Hz steps, gravity 0.4 x 800 u/s^2, a
 * 4-unit hull, reflection x 0.45 on impact (x 0.3 off players), stopping on floors below 30 u/s. HE and flash detonate
 * 1.5 s after the throw; smoke and decoy once they have stopped (after 1.5 s); molotov/incendiary on hitting a floor
 * (or fizzle in the air after 2 s).
 */
public class GrenadeEntity extends ThrowableItemProjectile {
	public static final int FLYING = 0;
	public static final int SMOKING = 1;
	/** CS2 weapon_hegrenade m_flArmorRatio (weapons.vdata). */
	private static final float HE_ARMOR_RATIO = 1.2f;
	public static final int BURNING = 2;
	public static final int DECOYING = 3;

	private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(GrenadeEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> STATE_AGE = SynchedEntityData.defineId(GrenadeEntity.class, EntityDataSerializers.INT);

	/** Hammer units per block. */
	public static final double UNITS = 39.37;
	/** CS grenade hull: a 4-unit box (half extent in blocks); the entity's position is the bottom of it. */
	public static final double HALF = 2.0 / UNITS;
	private static final int FUSE = 30;           // HE / flash: 1.5 s
	private static final int SMOKE_TICKS = 360;   // 18 s
	private static final int FIRE_TICKS = 140;    // 7 s
	private static final int DECOY_TICKS = 300;   // 15 s
	private static final int MOLOTOV_AIR = 40;    // molotov_throw_detonate_time 2 s
	/**
	 * Smoke cloud and fire pool radii, blocks (CS2: ~144 u smoke, ~150 u fire). The smoke is 1.5x CS2's (216 u, ~5.5
	 * blocks): Counter-Craft balance, not CS2 data, since mobs and open terrain made CS2's cloud too small to hide in.
	 */
	public static final double SMOKE_RADIUS = 1.5 * 144 / 39.37;
	/** Height of the smoke sphere's centre above the grenade, blocks. */
	public static final double SMOKE_CENTER = 1.5;
	public static final double FIRE_RADIUS = 150 / 39.37;

	private String kind = "hegrenade";
	private int age;
	private int nextDecoyShot;
	private int decoyShotsLeft;
	private String decoyGun = "ak47";
	/** CS physics state (units, units/s) and simulated time not yet spent. */
	private final GrenadePhysics.State sim = new GrenadePhysics.State(Vec3.ZERO, Vec3.ZERO);
	private final GrenadePhysics.Trace trace = this::trace;
	private double carry;
	/** Where physics() last put the entity's centre: anything else moved it (spawn, a server correction, a push). */
	private Vec3 shownCentre;
	private int stillTicks;
	/** Client: end-over-end tumble (degrees) while it flies, settling on its side; heading of travel. */
	private float spin;
	private float spinO;
	private float travelYaw;
	private boolean resting;

	/** CS damage of a grenade hitting someone in flight. */
	private static final float IMPACT_DAMAGE = 1f;
	/** A grenade hitting a mob in flight: half a heart (Counter-Craft's rule; players take CS2's 1 damage). */
	private static final float MOB_IMPACT_HP = 1f;
	/**
	 * The decoy's pop against mobs (Counter-Craft's rule; it doesn't hurt players): mobs within this many blocks take
	 * {@link #DECOY_POP_HP} at the centre, half that at the edge, not through walls.
	 */
	private static final double DECOY_POP_RADIUS = 3.0;
	private static final float DECOY_POP_HP = 6f;
	/** Times it bounced off a wall (a kill after one is a bank shot). */
	private int wallBounces;
	/** It smashed through glass on its way (a kill after is "through a window"). */
	private boolean throughWindow;
	/** The creature the last hull trace stopped at (null for the world), and the last one this grenade hurt. */
	private Entity lastHitEntity;
	private Entity lastDamaged;
	/**
	 * Client: the server's latest position and velocity for this grenade, kept aside. The client flies its own copy of
	 * the same CS physics; snapping to every server update (every 2 ticks, always up to a tick out of step with the
	 * client's own steps, at up to a block per tick) made flying grenades jump back and forth. They're used only when
	 * the copies drift more than {@link #RESYNC} blocks apart, and to settle a grenade at rest where the server's lies.
	 */
	private Vec3 serverPos;
	private Vec3 serverVel;
	private static final double RESYNC = 2.0;
	/** Client, for the grenade debug: record each physics step's position ({@link #trail}), and the bounces so far. */
	public static boolean recordTrail;
	public final java.util.List<Vec3> trail = new java.util.ArrayList<>();
	public final java.util.List<Vec3> bounceAt = new java.util.ArrayList<>();
	private int bounces;

	public GrenadeEntity(EntityType<? extends GrenadeEntity> type, Level level) {
		super(type, level);
	}

	public GrenadeEntity(Level level, LivingEntity owner, ItemStack stack, String kind) {
		super(CounterCraft.GRENADE_ENTITY, owner, level, stack);
		this.kind = kind;
	}

	@Override
	protected Item getDefaultItem() {
		return CounterCraft.GRENADES.values().iterator().next();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(STATE, FLYING);
		builder.define(STATE_AGE, 0);
	}

	/** The gun a decoy imitates (CS2: the thrower's primary, else their pistol). */
	public void setDecoyGun(String gunId) {
		decoyGun = gunId;
	}

	/** Client: tumble angle for rendering, interpolated. */
	public float spin(float partialTick) {
		return spinO + (spin - spinO) * partialTick;
	}

	public float travelYaw() {
		return travelYaw;
	}

	/** Client: stopped (lying on its side). */
	public boolean resting() {
		return resting;
	}

	/** Client: bounces seen so far (the grenade debug). */
	public int bounces() {
		return bounces;
	}

	/** Ticks since it popped (smoke, fire, decoy) or since it was thrown while flying. */
	public int stateAge() {
		return entityData.get(STATE_AGE);
	}

	/** How long its effect lasts once it pops (seconds): smoke 18, fire 7, decoy 15; HE / flash go off at once. */
	public static double effectSeconds(int state) {
		return switch (state) {
			case SMOKING -> SMOKE_TICKS / 20.0;
			case BURNING -> FIRE_TICKS / 20.0;
			case DECOYING -> DECOY_TICKS / 20.0;
			default -> 0;
		};
	}

	/** In-air fuse: HE / flash go off, a molotov bursts in the air (seconds); smoke / decoy wait to stop after it. */
	public static double fuseSeconds(String kind) {
		return "molotov".equals(kind) || "incgrenade".equals(kind) ? MOLOTOV_AIR / 20.0 : FUSE / 20.0;
	}

	public int state() {
		return entityData.get(STATE);
	}

	public String kind() {
		ItemStack s = getItem();
		return s.getItem() instanceof GrenadeItem g ? g.weaponId() : kind;
	}

	private Guns.GrenadeDef def() {
		return Guns.GRENADES.get(kind());
	}

	@Override
	protected InterpolationHandler createInterpolationHandler() {
		// (called from Entity's constructor: only stores, reads this entity later)
		return new InterpolationHandler() {
			@Override
			public net.minecraft.core.PositionAndRotation target() {
				return net.minecraft.core.PositionAndRotation.of(serverPos != null ? serverPos : position(), getYRot(), getXRot());
			}

			@Override
			public boolean interpolateTo(net.minecraft.world.entity.PositionPath path, float yRot, float xRot, boolean x) {
				serverPos = path.endPosition();
				return true; // handled: no snap (see serverPos)
			}

			@Override
			public void interpolate() {
			}

			@Override
			public void applyPredictedMovement(Vec3 movement) {
			}

			@Override
			public boolean hasActiveInterpolation() {
				return false;
			}

			@Override
			public void cancel() {
			}
		};
	}

	/**
	 * Drawn as far as it's tracked (8 chunks, scaled by Entity Distance): Minecraft sizes a thrown item's draw distance
	 * by its hitbox, which for a 0.1-block grenade is about 25 blocks, so long throws vanished in mid-air.
	 */
	@Override
	public boolean shouldRenderAtSqrDistance(double distanceSqr) {
		double r = 128.0 * getViewScale();
		return distanceSqr < r * r;
	}

	@Override
	public void lerpMotion(Vec3 movement) {
		if (level().isClientSide()) {
			serverVel = movement; // kept aside, like the position
			return;
		}
		super.lerpMotion(movement);
	}

	/** Start flying from {@code centre} (the CS throw source) at {@code velocity} (units/s). */
	public void launch(Vec3 centre, Vec3 velocity) {
		setPos(centre.subtract(0, HALF, 0));
		sim.pos = centre.scale(UNITS);
		sim.vel = velocity;
		setDeltaMovement(velocity.scale(1.0 / (20.0 * UNITS)));
	}

	/**
	 * Server and client run the same CS physics ({@link GrenadePhysics}, 64 Hz) from the entity's position and the
	 * velocity the server last sent.
	 */
	private void physics() {
		if (level().isClientSide() && serverPos != null && serverPos.distanceTo(position()) > RESYNC) {
			// the copies drifted apart (something the client didn't see moved it): take the server's
			setPos(serverPos);
			if (serverVel != null) {
				setDeltaMovement(serverVel);
			}
			setOldPosAndRot();
		}
		Vec3 c = centre();
		if (shownCentre == null || c.distanceToSqr(shownCentre) > 1e-10) {
			// moved from outside: the CS state starts again from here
			sim.pos = c.scale(UNITS);
			sim.vel = getDeltaMovement().scale(20.0 * UNITS);
			carry = 0;
		}
		carry += 1.0 / 20.0;
		while (carry >= GrenadePhysics.TICK - 1e-9) {
			carry -= GrenadePhysics.TICK;
			GrenadePhysics.step(sim, trace);
			if (level().isClientSide()) {
				// the grenade debug's tracer: every 1/64 s step of the flight, bounces where they happened
				if (sim.hit != null) {
					bounces++;
					if (recordTrail && bounceAt.size() < 256) {
						bounceAt.add(sim.pos.scale(1.0 / UNITS));
					}
				}
				if (recordTrail && !sim.stopped && trail.size() < 4096) {
					trail.add(sim.pos.scale(1.0 / UNITS));
				}
			}
			if (sim.hit != null && impact(sim.hit)) {
				break;
			}
		}
		// the entity shows the grenade at this tick's own time: the CS state stops on a 1/64 s step, so it's carried
		// forward by the time not yet stepped (a tick holds 3 or 4 steps; showing the state as it was made the flight
		// lurch by up to a third every few ticks), stopping at whatever it would hit
		Vec3 at = sim.pos;
		if (!sim.stopped && carry > 1e-9) {
			Vec3 ahead = sim.pos.add(sim.vel.scale(carry)).add(0, -0.5 * GrenadePhysics.GRAVITY * carry * carry, 0);
			TraceHit h = hullTrace(level(), this, sim.pos.scale(1.0 / UNITS), ahead.scale(1.0 / UNITS));
			at = h.normal() == null ? ahead : h.end().scale(UNITS);
		}
		setPos(at.scale(1.0 / UNITS).subtract(0, HALF, 0));
		shownCentre = centre();
		setDeltaMovement(sim.vel.scale(1.0 / (20.0 * UNITS)));
	}

	/** After a bounce: its sound, and a molotov bursting on a floor (CS2's weapon_molotov_maxdetonateslope 30). */
	private boolean impact(GrenadePhysics.Hit hit) {
		if (level().isClientSide()) {
			return false;
		}
		String k = kind();
		if (hit.player() && lastHitEntity != null && state() == FLYING && lastHitEntity != lastDamaged) {
			// CS2: a grenade that hits someone in flight does 1 damage (once per creature it bounces off); a mob: half a heart
			lastDamaged = lastHitEntity;
			Entity target = lastHitEntity;
			ServerLevel sl = (ServerLevel) level();
			dev.countercraft.Combat.markHit(getOwner(), target, k, dev.countercraft.net.KillFeedPayload.F_IMPACT | killFlags(), getId());
			target.hurtServer(sl, new DamageSource(CounterCraft.damageType(sl, CounterCraft.GRENADE), this, getOwner()),
					target instanceof net.minecraft.world.entity.player.Player ? IMPACT_DAMAGE * Ballistics.CS_TO_MC : MOB_IMPACT_HP);
		}
		if (("molotov".equals(k) || "incgrenade".equals(k)) && state() == FLYING && !hit.player()
				&& hit.normal().y >= Math.cos(Math.toRadians(30))) {
			sim.pos = hit.pos();
			sim.vel = Vec3.ZERO;
			sim.stopped = true;
			startEffect(BURNING);
			sound(def().sounds().get("detonate"), 1.5f);
			return true;
		}
		if (!hit.player() && Math.abs(hit.normal().y) < 0.7) {
			wallBounces++;
		}
		if (sim.impactSpeed > 100.0) {
			sound(def().sounds().get("bounce"), (float) Math.min(1.0, 0.3 + sim.impactSpeed / 1000.0));
		}
		return false;
	}

	private Vec3 centre() {
		return position().add(0, HALF, 0);
	}

	/** A grenade this fast (u/s) smashes the glass it hits and flies on, as through a CS2 window. */
	private static final double GLASS_BREAK_SPEED = 100.0;

	/** CS's hull trace through this world (units in, units out); on the server, glass in the way is smashed. */
	private GrenadePhysics.Hit trace(Vec3 a, Vec3 b) {
		TraceHit h = hullTrace(level(), this, a.scale(1.0 / UNITS), b.scale(1.0 / UNITS));
		for (int i = 0; i < 4 && h.block() != null && level() instanceof ServerLevel sl && sim.vel.length() > GLASS_BREAK_SPEED
				&& Ballistics.glass(sl.getBlockState(h.block())) && getOwner() instanceof LivingEntity thrower && Ballistics.mayBreak(sl, thrower, h.block()); i++) {
			sl.destroyBlock(h.block(), false, this);
			throughWindow = true;
			h = hullTrace(level(), this, a.scale(1.0 / UNITS), b.scale(1.0 / UNITS));
		}
		if (h.normal() != null) {
			lastHitEntity = h.entity();
		}
		return h.normal() == null ? null : new GrenadePhysics.Hit(h.end().scale(UNITS), h.normal(), h.fraction(), h.entity() != null);
	}

	/** Where a swept hull stopped; normal null if nothing was hit. */
	public record TraceHit(Vec3 end, Vec3 normal, double fraction, Entity entity, BlockPos block) {
	}

	/**
	 * Sweep a 4-unit box (CS's grenade hull) from {@code a} to {@code b} (blocks) through block collision shapes and
	 * living entities (not {@code self} or the grenade's thrower); stops a hair short of what it hits.
	 */
	public static TraceHit hullTrace(Level level, Entity self, Vec3 a, Vec3 b) {
		return hullTrace(level, self, a, b, HALF, true);
	}

	/** {@link #hullTrace} with another hull half-size (blocks); {@code entities} false: blocks only. */
	public static TraceHit hullTrace(Level level, Entity self, Vec3 a, Vec3 b, double half, boolean entities) {
		AABB sweep = new AABB(a, b).inflate(half + 1e-3);
		double len = a.distanceTo(b);
		BlockHitResult best = null;
		Entity bestEntity = null;
		double bestD = Double.MAX_VALUE;
		CollisionContext ctx = self == null ? CollisionContext.empty() : CollisionContext.of(self);
		for (BlockPos p : BlockPos.betweenClosed(Mth.floor(sweep.minX), Mth.floor(sweep.minY), Mth.floor(sweep.minZ),
				Mth.floor(sweep.maxX), Mth.floor(sweep.maxY), Mth.floor(sweep.maxZ))) {
			BlockState st = level.getBlockState(p);
			if (st.isAir()) {
				continue;
			}
			VoxelShape shape = st.getCollisionShape(level, p, ctx);
			if (shape.isEmpty()) {
				continue;
			}
			List<AABB> boxes = new ArrayList<>();
			for (AABB box : shape.toAabbs()) {
				boxes.add(box.inflate(half));
			}
			BlockHitResult r = AABB.clip(boxes, a, b, p.immutable());
			if (r != null) {
				double d = r.getLocation().distanceToSqr(a);
				if (d < bestD) {
					bestD = d;
					best = r;
				}
			}
		}
		Entity owner = self instanceof GrenadeEntity g ? g.getOwner() : self;
		for (Entity e : !entities ? List.<Entity>of() : level.getEntities(self, sweep.inflate(0.5), x -> (x instanceof LivingEntity || x instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal) && x.isAlive() && !x.isSpectator() && x != owner)) {
			AABB box = e.getBoundingBox().inflate(half);
			if (box.contains(a)) {
				continue;
			}
			BlockHitResult r = AABB.clip(List.of(box), a, b, BlockPos.ZERO);
			if (r != null) {
				double d = r.getLocation().distanceToSqr(a);
				if (d < bestD) {
					bestD = d;
					best = r;
					bestEntity = e;
				}
			}
		}
		if (best == null) {
			return new TraceHit(b, null, 1.0, null, null);
		}
		Direction face = best.getDirection();
		Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		// stop a hair short of the surface (Source's DIST_EPSILON is 1/32 unit)
		return new TraceHit(best.getLocation().add(n.scale(0.03125 / UNITS)), n, len < 1e-9 ? 0 : Math.sqrt(bestD) / len, bestEntity,
				bestEntity == null ? best.getBlockPos() : null);
	}

	@Override
	public void tick() {
		int st = state();
		baseTick();
		if (st == FLYING || !sim.stopped) {
			physics();
		} else {
			setDeltaMovement(Vec3.ZERO);
		}
		if (level().isClientSide() && serverPos != null && (sim.stopped || getDeltaMovement().lengthSqr() < 1e-6)) {
			setPos(position().lerp(serverPos, 0.3)); // at rest: settle where the server's lies (the copies can stop apart)
		}
		age++;
		if (level().isClientSide()) {
			tumble(st);
			clientEffects(st);
			return;
		}
		ServerLevel level = (ServerLevel) level();
		int stAge = entityData.get(STATE_AGE) + 1;
		entityData.set(STATE_AGE, stAge);
		String k = kind();
		// CS2: smokes and decoys go off once the grenade has stopped (checked after their 1.5 s timer); a slow wall hit
		// zeroes the speed for an instant before it drops, so that alone doesn't count
		stillTicks = sim.vel.lengthSqr() <= 0.1 * 0.1 ? stillTicks + 1 : 0;
		boolean still = sim.stopped || stillTicks >= 3;
		switch (st) {
			case FLYING -> {
				if (("hegrenade".equals(k) || "flashbang".equals(k)) && age >= FUSE) {
					if ("hegrenade".equals(k)) {
						explodeHe(level);
					} else {
						flash(level);
						dev.countercraft.BossArmor.flashbang(level, position(), getOwner());
					}
					discard();
				} else if ("smokegrenade".equals(k) && ((age >= FUSE && still) || inFire(level))) {
					// CS2: a smoke that touches a fire pops on the spot (and so puts the fire out)
					startEffect(SMOKING);
					sound(def().sounds().get("detonate"), 1.2f);
					dev.countercraft.BossArmor.smokePopped(level, position(), getOwner());
				} else if ("decoy".equals(k) && age >= FUSE && still) {
					startEffect(DECOYING);
				} else if (("molotov".equals(k) || "incgrenade".equals(k)) && isInWater()) {
					sound("molotov.extinguish", 1f); // into water: it never lights
					discard();
				} else if (("molotov".equals(k) || "incgrenade".equals(k)) && age >= MOLOTOV_AIR) {
					sound(def().sounds().get("fail"), 1f);
					level.sendParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 20, 0.4, 0.4, 0.4, 0.02);
					discard();
				} else if (age > 20 * 60) {
					discard();
				}
			}
			case SMOKING -> {
				if (stAge % 20 == 0) {
					dev.countercraft.BossArmor.smokeHiss(level, position(), SMOKE_RADIUS);
					calmBees(level);
				}
				if (stAge >= SMOKE_TICKS) {
					sound(def().sounds().get("clear"), 0.8f);
					discard();
				}
			}
			case BURNING -> burn(level, stAge);
			case DECOYING -> decoy(level, stAge);
			default -> discard();
		}
	}

	private void startEffect(int st) {
		entityData.set(STATE, st);
		entityData.set(STATE_AGE, 0);
		setDeltaMovement(Vec3.ZERO);
	}

	/** Glass within this many blocks of an HE blast breaks. */
	private static final double HE_GLASS_RADIUS = 2.5;
	/** TNT within this many blocks of an HE blast goes off. */
	private static final double HE_TNT_RADIUS = 2.5;
	/** An HE in water: this much more damage and radius against mobs (Counter-Craft's rule, not CS2's: water carries the blast). */
	private static final double UNDERWATER = 2.0;
	/** How hard an HE shoves the mobs it doesn't kill (blocks/tick, at the centre) and how long they're dazed. */
	private static final double STAGGER_PUSH = 0.9;
	private static final int STAGGER_TICKS = 20;
	/** Slimes and magma cubes an HE killed: they don't split (SlimeSplitMixin). */
	public static final java.util.Set<Entity> NO_SPLIT = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	/**
	 * HE: CS damage 99 within 350 units, Gaussian falloff (sigma = radius / 3), blocked by walls. Against mobs (Counter-
	 * Craft's rules; players always take CS2's): {@code sv_grenade_mob_damage} times the damage, survivors are shoved
	 * and dazed, creepers caught in it blow up, slimes it kills don't split, silverfish and endermites always die; in
	 * water it's twice as strong and wide against them, and kills the fish and squid in it. TNT next to it goes off.
	 */
	private void explodeHe(ServerLevel level) {
		Guns.GrenadeDef d = def();
		Vec3 c = position().add(0, 0.1, 0);
		boolean wet = level.getFluidState(BlockPos.containing(c)).is(net.minecraft.tags.FluidTags.WATER);
		double radius = d.radius() * (wet ? UNDERWATER : 1.0) / 39.37; // (the furthest anything can be hit)
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		level.sendParticles(wet ? ParticleTypes.BUBBLE : ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, wet ? 120 : 30, 1.2, 0.8, 1.2, 0.02);
		sound(d.sounds().get("detonate"), 3f);
		// the blast smashes the glass right around it, and sets off TNT
		if (getOwner() instanceof LivingEntity thrower) {
			int r = (int) Math.ceil(Math.max(HE_GLASS_RADIUS, HE_TNT_RADIUS));
			for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(c).offset(-r, -r, -r), BlockPos.containing(c).offset(r, r, r))) {
				double dist = Vec3.atCenterOf(p).distanceTo(c);
				BlockState st = level.getBlockState(p);
				if (dist <= HE_GLASS_RADIUS && Ballistics.glass(st) && Ballistics.mayBreak(level, thrower, p)) {
					level.destroyBlock(p, false, this);
				} else if (dist <= HE_TNT_RADIUS && st.getBlock() instanceof net.minecraft.world.level.block.TntBlock && Ballistics.mayBreak(level, thrower, p)
						&& net.minecraft.world.level.block.TntBlock.prime(level, p.immutable())) {
					level.removeBlock(p.immutable(), false);
				}
			}
		}
		// living things, end crystals and the dragon's parts; a creature is hurt once, through its nearest part
		java.util.Map<Entity, Entity> nearest = new java.util.HashMap<>();
		for (Entity e : level.getEntities((Entity) null, new AABB(c, c).inflate(radius + 4), e -> Ballistics.hittable(e, null))) {
			Entity owner = Ballistics.owner(e);
			Entity best = nearest.get(owner);
			if (best == null || e.getBoundingBox().getCenter().distanceToSqr(c) < best.getBoundingBox().getCenter().distanceToSqr(c)) {
				nearest.put(owner, e);
			}
		}
		float mobScale = (float) dev.countercraft.ServerCvars.value("sv_grenade_mob_damage");
		int flags = killFlags();
		List<net.minecraft.world.entity.monster.Creeper> creepers = new ArrayList<>();
		for (Entity e : nearest.values()) {
			Vec3 to = e.getBoundingBox().getCenter();
			double distUnits = to.distanceTo(c) * 39.37;
			// in water, mobs are hit twice as hard over twice the radius; players always as in CS2
			double water = wet && !(e instanceof net.minecraft.world.entity.player.Player) ? UNDERWATER : 1.0;
			double radiusUnits = d.radius() * water;
			double sigma = radiusUnits / 3.0;
			if (distUnits > radiusUnits || !visible(level, c, to)) {
				continue;
			}
			double falloff = Math.exp(-(distUnits * distUnits) / (2 * sigma * sigma));
			double cs = d.damage() * water * falloff;
			cs = dev.countercraft.Combat.afterArmor(e, (float) cs, HE_ARMOR_RATIO);
			float hp = (float) cs * Ballistics.CS_TO_MC;
			LivingEntity mob = e instanceof LivingEntity le && !(e instanceof net.minecraft.world.entity.player.Player)
					&& dev.countercraft.BossFights.bounty(le) <= 0 ? le : null; // (bosses keep their own balance)
			if (mob != null) {
				hp *= mobScale;
				if (mob instanceof net.minecraft.world.entity.monster.Silverfish || mob instanceof net.minecraft.world.entity.monster.Endermite
						|| (wet && (mob instanceof net.minecraft.world.entity.animal.fish.AbstractFish || mob instanceof net.minecraft.world.entity.animal.squid.Squid))) {
					hp = Math.max(hp, Ballistics.lethal(mob));
				}
			}
			dev.countercraft.Combat.markHit(getOwner(), e, "hegrenade", flags, getId());
			e.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.GRENADE), this, getOwner()), hp);
			if (mob == null) {
				continue;
			}
			if (mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob && mob.isDeadOrDying()) {
				NO_SPLIT.add(mob);
			}
			if (mob instanceof net.minecraft.world.entity.monster.Creeper cr) {
				creepers.add(cr);
			} else if (mob instanceof net.minecraft.world.entity.Mob m && !mob.isDeadOrDying()) {
				// staggered: shoved away from the blast, and dazed for a moment
				Vec3 away = to.subtract(c).multiply(1, 0, 1);
				away = away.lengthSqr() < 1e-6 ? Vec3.ZERO : away.normalize();
				double k = STAGGER_PUSH * falloff;
				m.push(away.x * k, 0.2 + 0.25 * k, away.z * k);
				dev.countercraft.MobVision.stun(m, STAGGER_TICKS);
			}
		}
		// a chain reaction: creepers caught in it go off (the ones it killed too, after dropping their loot)
		for (net.minecraft.world.entity.monster.Creeper cr : creepers) {
			if (!cr.isRemoved()) {
				((dev.countercraft.mixin.CreeperAccessor) cr).countercraft$explode();
			}
		}
	}

	/** What its kills carry: a bank shot (it bounced off a wall), through a window (it smashed glass). */
	private int killFlags() {
		return (wallBounces > 0 ? dev.countercraft.net.KillFeedPayload.F_BANK : 0) | (throughWindow ? dev.countercraft.net.KillFeedPayload.F_WINDOW : 0);
	}

	/** Smoke calms bees, as a campfire does (SmokeyHiveMixin lets you take honey in it): angry bees in the cloud stop. */
	private void calmBees(ServerLevel level) {
		Vec3 centre = position().add(0, SMOKE_CENTER, 0);
		for (net.minecraft.world.entity.animal.bee.Bee b : level.getEntitiesOfClass(net.minecraft.world.entity.animal.bee.Bee.class,
				getBoundingBox().inflate(SMOKE_RADIUS + 1), b -> b.isAngry() && b.position().distanceTo(centre) <= SMOKE_RADIUS + 1)) {
			b.stopBeingAngry();
			b.setTarget(null);
		}
	}

	/** A smoke cloud covers this block (bees calm down, as over a campfire: SmokeyHiveMixin). */
	public static boolean inSmoke(Level level, BlockPos pos) {
		Vec3 at = Vec3.atCenterOf(pos);
		return !level.getEntitiesOfClass(GrenadeEntity.class, new AABB(pos).inflate(SMOKE_RADIUS + 2),
				g -> g.state() == SMOKING && g.position().add(0, SMOKE_CENTER, 0).distanceTo(at) <= SMOKE_RADIUS + 1).isEmpty();
	}

	/** Flashbang: every player who can see it goes white, longer when facing it and close (up to ~4.9 s). */
	private void flash(ServerLevel level) {
		Vec3 c = position().add(0, 0.2, 0);
		level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, 0.1, 0.1, 0.1, 0.35);
		sound(def().sounds().get("detonate"), 3f);
		Entity owner = getOwner();
		float teamFlash = (float) dev.countercraft.ServerCvars.value("sv_teamflash");
		for (ServerPlayer p : level.players()) {
			Vec3 eye = p.getEyePosition();
			double dist = eye.distanceTo(c);
			if (dist > 60 || !visible(level, c, eye) || dev.countercraft.ServerCvars.immuneToFlash(p)) {
				continue;
			}
			Vec3 toFlash = c.subtract(eye).normalize();
			double dot = p.getViewVector(1f).dot(toFlash);
			double facing = dot >= 0.8 ? 1.0 : Math.max(0.12, 0.12 + (dot + 1.0) / 1.8 * 0.88);
			double near = dist <= 10 ? 1.0 : Math.max(0.0, 1.0 - (dist - 10) / 50);
			float seconds = (float) (4.9 * facing * near);
			// a teammate's flashbang (Counter-Craft's co-op rule): weaker and shorter, by sv_teamflash; your own: CS2's
			float strength = p != owner && owner instanceof net.minecraft.world.entity.player.Player op && dev.countercraft.team.Teams.allies(p, op)
					? teamFlash : 1f;
			seconds *= strength;
			if (seconds > 0.15f) {
				ServerPlayNetworking.send(p, new FlashPayload(seconds, strength));
				dev.countercraft.Combat.flashed(p, seconds, strength);
			}
		}
		// mobs: the same exposure (distance, sight, facing: where the mob's head looks)
		int hostiles = 0;
		for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, getBoundingBox().inflate(60))) {
			Vec3 eye = m.getEyePosition();
			double dist = eye.distanceTo(c);
			if (!m.isAlive() || dist > 60 || !visible(level, c, eye)) {
				continue;
			}
			double dot = m.getViewVector(1f).dot(c.subtract(eye).normalize());
			double facing = dot >= 0.8 ? 1.0 : Math.max(0.12, 0.12 + (dot + 1.0) / 1.8 * 0.88);
			double near = dist <= 10 ? 1.0 : Math.max(0.0, 1.0 - (dist - 10) / 50);
			float seconds = (float) (4.9 * facing * near);
			if (seconds > 0.15f) {
				dev.countercraft.MobVision.flash(m, seconds, owner);
				if (m instanceof net.minecraft.world.entity.monster.Enemy && dev.countercraft.MobVision.blinded(m)) {
					hostiles++;
				}
			}
		}
		if (hostiles >= 5 && owner instanceof ServerPlayer sp) {
			dev.countercraft.Achievements.award(sp, "flash_photography");
		}
	}

	/** Current radius of a fire pool (it grows over its first second), blocks. */
	private double fireRadius(int stAge) {
		return FIRE_RADIUS * Math.min(1.0, 0.35 + stAge / 20.0);
	}

	/** This (flying) grenade is inside a burning fire pool. */
	private boolean inFire(ServerLevel level) {
		for (GrenadeEntity fire : level.getEntitiesOfClass(GrenadeEntity.class, getBoundingBox().inflate(FIRE_RADIUS, 2, FIRE_RADIUS))) {
			if (fire != this && fire.state() == BURNING) {
				double dx = getX() - fire.getX();
				double dz = getZ() - fire.getZ();
				double r = fire.fireRadius(fire.entityData.get(STATE_AGE));
				if (dx * dx + dz * dz <= r * r && getY() - fire.getY() < 1.5 && getY() - fire.getY() > -0.5) {
					return true;
				}
			}
		}
		return false;
	}

	/** A burning molotov / incendiary goes out at once (CS2's bomb shockwave puts them out completely). */
	public void extinguish() {
		if (state() == BURNING) {
			sound("molotov.extinguish", 1f);
			discard();
		}
	}

	/**
	 * Molotov / incendiary: a fire pool that grows over 1 s, hurts 40 CS HP/s, and dies in smoke, in water and in rain
	 * (Minecraft's rain: none in deserts, badlands or savannas, and snow doesn't put it out).
	 */
	private void burn(ServerLevel level, int stAge) {
		BlockPos at = blockPosition();
		if (level.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER) || level.isRainingAt(at) || level.isRainingAt(at.above())) {
			sound("molotov.extinguish", 1f);
			discard();
			return;
		}
		double r = fireRadius(stAge);
		for (GrenadeEntity smoke : level.getEntitiesOfClass(GrenadeEntity.class, getBoundingBox().inflate(SMOKE_RADIUS + FIRE_RADIUS))) {
			if (smoke.state() == SMOKING && smoke.position().distanceTo(position()) < SMOKE_RADIUS + r * 0.5) {
				sound("molotov.extinguish", 1f);
				discard();
				return;
			}
		}
		if (stAge % 40 == 1) {
			sound(def().sounds().get("loop"), 1f);
		}
		FIRES.add(this);
		if (stAge == 1 || stAge == 20) {
			// mobs on their way re-plan around it (FirePathMixin), as it lights and once it has spread
			for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, getBoundingBox().inflate(24),
					m -> !m.getNavigation().isDone())) {
				m.getNavigation().recomputePath();
			}
		}
		if (stAge % 10 == 0) {
			cook(level, r);
			Guns.GrenadeDef d = def();
			float mobScale = (float) dev.countercraft.ServerCvars.value("sv_inferno_mob_damage");
			int flags = killFlags();
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r, 1.5, r))) {
				double dx = e.getX() - getX();
				double dz = e.getZ() - getZ();
				// fire-immune mobs (and Fire Resistance) ignore the flames entirely: no damage, hit marker or burning
				boolean immune = e.fireImmune() || e.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
				if (!immune && dx * dx + dz * dz <= r * r && Math.abs(e.getY() - getY()) < 1.5) {
					dev.countercraft.Combat.markHit(getOwner(), e, "inferno", flags, getId());
					float fire = dev.countercraft.Combat.afterArmor(e, d.damage() * 0.5f, "molotov".equals(d.id()) ? 1.8f : 1.475f);
					// mobs (Counter-Craft's rule): sv_inferno_mob_damage times as hot, and still burning a while after they leave
					boolean mob = !(e instanceof net.minecraft.world.entity.player.Player) && dev.countercraft.BossFights.bounty(e) <= 0;
					e.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.FIRE), this, getOwner()),
							fire * Ballistics.CS_TO_MC * (mob ? mobScale : 1f));
					e.igniteForSeconds(mob ? LINGER_SECONDS : 1f);
				}
			}
		}
		if (stAge >= FIRE_TICKS) {
			sound(def().sounds().get("fadeout"), 1f);
			discard();
		}
	}

	/** Mobs that leave a fire pool keep burning this long (s). */
	private static final float LINGER_SECONDS = 4f;
	/** Items lying in a fire pool this long (ticks) come out as a furnace would make them. */
	private static final int COOK_TICKS = 40;
	/** Burning fire pools, for mob pathing and spawning (FirePathMixin, FireSpawnMixin). */
	private static final java.util.Set<GrenadeEntity> FIRES = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Nothing hostile spawns this close to a burning fire pool (blocks): its light. */
	public static final double FIRE_LIGHT = 10;
	/** Item entity id -> ticks it has lain in this pool. */
	private final java.util.Map<Integer, Integer> cooking = new java.util.HashMap<>();

	/** Smelts the item drops that have lain in the pool for {@link #COOK_TICKS} (raw iron to ingots, sand to glass). */
	private void cook(ServerLevel level, double r) {
		for (net.minecraft.world.entity.item.ItemEntity it : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
				getBoundingBox().inflate(r, 1.5, r))) {
			double dx = it.getX() - getX();
			double dz = it.getZ() - getZ();
			if (dx * dx + dz * dz > r * r || Math.abs(it.getY() - getY()) >= 1.5 || cooking.merge(it.getId(), 10, Integer::sum) < COOK_TICKS) {
				continue;
			}
			cooking.remove(it.getId());
			ItemStack raw = it.getItem();
			var in = new net.minecraft.world.item.crafting.SingleRecipeInput(raw);
			level.recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, in, level).ifPresent(h -> {
				ItemStack out = h.value().assemble(in);
				if (!out.isEmpty()) {
					out.setCount(Math.min(out.getMaxStackSize(), out.getCount() * raw.getCount()));
					it.setItem(out);
					level.sendParticles(ParticleTypes.SMOKE, it.getX(), it.getY() + 0.2, it.getZ(), 6, 0.1, 0.1, 0.1, 0.01);
				}
			});
		}
	}

	/** A burning fire pool covers this spot (mobs path around it: FirePathMixin). */
	public static boolean inFirePool(Level level, double x, double y, double z) {
		for (GrenadeEntity f : FIRES) {
			if (f.isRemoved() || f.state() != BURNING) {
				FIRES.remove(f);
			} else if (f.level() == level && Math.abs(y - f.getY()) < 1.5) {
				double dx = x - f.getX();
				double dz = z - f.getZ();
				double r = f.fireRadius(f.entityData.get(STATE_AGE)) + 0.5;
				if (dx * dx + dz * dz <= r * r) {
					return true;
				}
			}
		}
		return false;
	}

	/** A burning fire pool lights this spot: no hostile mob spawns here (FireSpawnMixin). */
	public static boolean litByFire(Level level, BlockPos pos) {
		for (GrenadeEntity f : FIRES) {
			if (!f.isRemoved() && f.level() == level && Vec3.atCenterOf(pos).distanceToSqr(f.position()) <= FIRE_LIGHT * FIRE_LIGHT) {
				return true;
			}
		}
		return false;
	}

	/** Decoy: bursts of the imitated gun's shots at its real fire rate, a pause, repeat; then pops (hurting mobs nearby). */
	private void decoy(ServerLevel level, int stAge) {
		GunSpec gun = Guns.ALL.getOrDefault(decoyGun, Guns.ALL.get("ak47"));
		if (stAge >= nextDecoyShot && stAge < DECOY_TICKS) {
			if (decoyShotsLeft <= 0) {
				decoyShotsLeft = gun.automatic() ? 2 + random.nextInt(5) : 1 + random.nextInt(3);
			}
			sound(gun.fireSound(), 2f);
			dev.countercraft.BossArmor.decoyShot(level, this); // a Warden goes to look
			dev.countercraft.MobVision.decoyShot(level, position()); // so does anything hostile that can't see you
			level.sendParticles(ParticleTypes.CRIT, getX(), getY() + 0.15, getZ(), 4, 0.05, 0.05, 0.05, 0.15);
			decoyShotsLeft--;
			int gap = (int) Math.max(1, Math.round(gun.cycleTicks(false)));
			nextDecoyShot = stAge + (decoyShotsLeft > 0 ? Math.max(gap, gun.automatic() ? 1 : 5) : 15 + random.nextInt(35));
		}
		if (stAge >= DECOY_TICKS) {
			level.sendParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 15, 0.2, 0.2, 0.2, 0.02);
			sound(def().sounds().get("detonate"), 0.6f);
			Vec3 c = position().add(0, 0.1, 0);
			int flags = killFlags();
			for (net.minecraft.world.entity.Mob m : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(c, c).inflate(DECOY_POP_RADIUS),
					Entity::isAlive)) {
				Vec3 to = m.getBoundingBox().getCenter();
				double dist = to.distanceTo(c);
				if (dist <= DECOY_POP_RADIUS && visible(level, c, to)) {
					dev.countercraft.Combat.markHit(getOwner(), m, "decoy", flags | dev.countercraft.net.KillFeedPayload.F_POP, getId());
					m.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.GRENADE), this, getOwner()),
							(float) (DECOY_POP_HP * (1 - 0.5 * dist / DECOY_POP_RADIUS)));
				}
			}
			discard();
		}
	}

	/** Spin end over end about the axis across its path (~1.7 turns/s); once still, settle on its side. */
	private void tumble(int st) {
		spinO = spin;
		Vec3 v = getDeltaMovement();
		resting = st != FLYING || sim.stopped || v.lengthSqr() < 1e-6;
		if (!resting) {
			spin += 30f;
			if (v.horizontalDistanceSqr() > 1e-6) {
				travelYaw = (float) Math.toDegrees(Math.atan2(v.x, v.z));
			}
		} else {
			float side = Math.round((spin - 90f) / 180f) * 180f + 90f;
			spin += (side - spin) * 0.5f;
		}
	}

	private void clientEffects(int st) {
		int stAge = entityData.get(STATE_AGE);
		if (st == FLYING && "molotov".equals(kind())) {
			// the rag keeps burning in flight
			level().addParticle(ParticleTypes.FLAME, getX(), getY() + 0.15, getZ(), 0, 0.01, 0);
			if (random.nextInt(3) == 0) {
				level().addParticle(ParticleTypes.SMOKE, getX(), getY() + 0.2, getZ(), 0, 0.02, 0);
			}
		}
		if (st == SMOKING) {
			double grow = Math.min(1.0, 0.3 + stAge / 30.0);
			int n = stAge < 30 ? 40 : 18;
			for (int i = 0; i < n; i++) {
				// a dense dome: points in the sphere around the centre, kept above the ground
				double rr = SMOKE_RADIUS * grow * Math.cbrt(random.nextDouble());
				double a = random.nextDouble() * Math.PI * 2;
				double up = random.nextDouble() * 2 - 1;
				double flat = Math.sqrt(1 - up * up);
				double y = Math.max(0.1, SMOKE_CENTER + up * rr * 0.75);
				level().addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
						getX() + Math.cos(a) * rr * flat, getY() + y, getZ() + Math.sin(a) * rr * flat, 0, 0.002, 0);
			}
		} else if (st == BURNING) {
			double r = FIRE_RADIUS * Math.min(1.0, 0.35 + stAge / 20.0);
			for (int i = 0; i < 8; i++) {
				double rr = r * Math.sqrt(random.nextDouble());
				double a = random.nextDouble() * Math.PI * 2;
				level().addParticle(ParticleTypes.FLAME, getX() + Math.cos(a) * rr, getY() + 0.1, getZ() + Math.sin(a) * rr, 0, 0.03, 0);
			}
			if (random.nextInt(3) == 0) {
				level().addParticle(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.5, getZ(), 0, 0.05, 0);
			}
		}
	}

	private static boolean visible(ServerLevel level, Vec3 from, Vec3 to) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType() == HitResult.Type.MISS;
	}

	private void sound(String id, float volume) {
		if (id != null) {
			level().playSound(null, getX(), getY(), getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(id)), SoundSource.PLAYERS, volume, 1f);
		}
	}

}
