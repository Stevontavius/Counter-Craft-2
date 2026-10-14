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
			// CS2: a grenade that hits someone in flight does 1 damage (once per creature it bounces off)
			lastDamaged = lastHitEntity;
			Entity target = lastHitEntity;
			ServerLevel sl = (ServerLevel) level();
			target.hurtServer(sl, new DamageSource(CounterCraft.damageType(sl, CounterCraft.GRENADE), this, getOwner()),
					IMPACT_DAMAGE * Ballistics.CS_TO_MC);
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

	/** HE: CS damage 99 within 350 units, Gaussian falloff (sigma = radius / 3), blocked by walls. */
	private void explodeHe(ServerLevel level) {
		Guns.GrenadeDef d = def();
		double radius = d.radius() / 39.37;
		double sigma = d.radius() / 3.0;
		Vec3 c = position().add(0, 0.1, 0);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 30, 1.2, 0.8, 1.2, 0.02);
		sound(d.sounds().get("detonate"), 3f);
		// the blast smashes the glass right around it
		if (getOwner() instanceof LivingEntity thrower) {
			int r = (int) Math.ceil(HE_GLASS_RADIUS);
			for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(c).offset(-r, -r, -r), BlockPos.containing(c).offset(r, r, r))) {
				if (Vec3.atCenterOf(p).distanceTo(c) <= HE_GLASS_RADIUS && Ballistics.glass(level.getBlockState(p)) && Ballistics.mayBreak(level, thrower, p)) {
					level.destroyBlock(p, false, this);
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
		for (Entity e : nearest.values()) {
			Vec3 to = e.getBoundingBox().getCenter();
			double distUnits = to.distanceTo(c) * 39.37;
			if (distUnits > d.radius() || !visible(level, c, to)) {
				continue;
			}
			double cs = d.damage() * Math.exp(-(distUnits * distUnits) / (2 * sigma * sigma));
			cs = dev.countercraft.Combat.afterArmor(e, (float) cs, HE_ARMOR_RATIO);
			dev.countercraft.Combat.markHit(getOwner(), e, "hegrenade", 0);
			e.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.GRENADE), this, getOwner()), (float) cs * Ballistics.CS_TO_MC);
		}
	}

	/** Flashbang: every player who can see it goes white, longer when facing it and close (up to ~4.9 s). */
	private void flash(ServerLevel level) {
		Vec3 c = position().add(0, 0.2, 0);
		level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 40, 0.1, 0.1, 0.1, 0.35);
		sound(def().sounds().get("detonate"), 3f);
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
			if (seconds > 0.15f) {
				ServerPlayNetworking.send(p, new FlashPayload(seconds));
				dev.countercraft.Combat.flashed(p, seconds);
			}
		}
		// mobs: the same exposure (distance, sight, facing: where the mob's head looks)
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
				dev.countercraft.MobVision.flash(m, seconds);
			}
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
		if (stAge % 10 == 0) {
			Guns.GrenadeDef d = def();
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(r, 1.5, r))) {
				double dx = e.getX() - getX();
				double dz = e.getZ() - getZ();
				// fire-immune mobs (and Fire Resistance) ignore the flames entirely: no damage, hit marker or burning
				boolean immune = e.fireImmune() || e.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
				if (!immune && dx * dx + dz * dz <= r * r && Math.abs(e.getY() - getY()) < 1.5) {
					dev.countercraft.Combat.markHit(getOwner(), e, "inferno", 0);
					float fire = dev.countercraft.Combat.afterArmor(e, d.damage() * 0.5f, "molotov".equals(d.id()) ? 1.8f : 1.475f);
					e.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.FIRE), this, getOwner()),
							fire * Ballistics.CS_TO_MC);
					e.igniteForSeconds(1f);
				}
			}
		}
		if (stAge >= FIRE_TICKS) {
			sound(def().sounds().get("fadeout"), 1f);
			discard();
		}
	}

	/** Decoy: fake AK fire in short bursts for 15 s, then a small pop. */
	/** Decoy: bursts of the imitated gun's shots at its real fire rate, a pause, repeat; then pops. */
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
