package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.team.CsDrop;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A CS2 weapon lying in the world (dropped with G / Q, on death, or swapped out by a pick-up), like CS2's dropped
 * weapons: it keeps its state (ammo, silencer...), is tossed with Source's drop arc ({@link CsDrop#toss}), tumbles
 * (Source's drop angular impulse 200, 200, 200 deg/s) and comes to rest lying on its side, passes through players, and
 * never despawns (CS2 clears them only between rounds). Walking over it picks it up if that CS slot is free; E
 * (CS2 +use) picks it up and swaps out what's in its slot.
 *
 * <p>Physics: CS units at 64 Hz through {@link GrenadePhysics} with full sv_gravity 800 (weapons are VPhysics props,
 * not grenades) and a low bounce. Approximation: CS2 simulates these with its physics engine (friction, inertia); the
 * bounce constants here are not measured.
 */
public class DroppedWeaponEntity extends Entity implements net.minecraft.world.entity.projectile.ItemSupplier {
	private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(DroppedWeaponEntity.class, EntityDataSerializers.ITEM_STACK);
	private static final EntityDataAccessor<Boolean> RESTING = SynchedEntityData.defineId(DroppedWeaponEntity.class, EntityDataSerializers.BOOLEAN);

	/** Weapon physics (not measured: see the class comment). */
	public static final GrenadePhysics.Params PHYSICS = new GrenadePhysics.Params(800.0, 0.25, 1.0, 40.0);
	/** Collision hull half-size (blocks): a 4-unit box like a grenade's (approximation). */
	private static final double HALF = 2.0 / GrenadeEntity.UNITS;
	/**
	 * Pick-up reach around the weapon (Source CBaseCombatWeapon: trigger bounds bloated 36 units "for player pickup").
	 */
	public static final double TRIGGER = 36.0 / GrenadeEntity.UNITS;
	/** The dropper can't walk-over pick it straight back up for this long (ticks; approximation). */
	private static final int OWNER_DELAY = 20;

	private final GrenadePhysics.State sim = new GrenadePhysics.State(Vec3.ZERO, Vec3.ZERO);
	private double carry;
	private UUID dropper;
	private long droppedAt;
	private boolean simulated;
	/** Client: tumble angle (degrees) while it flies; heading it lies at once it rests. */
	private float spin;
	private float spinO;
	private float restYaw;

	public DroppedWeaponEntity(EntityType<? extends DroppedWeaponEntity> type, Level level) {
		super(type, level);
		restYaw = level.getRandom().nextFloat() * 360f;
	}

	/** A weapon leaving a player (or a dead player): at {@code pos} (blocks), moving at {@code velocity} (u/s). */
	public static DroppedWeaponEntity spawn(ServerLevel level, ItemStack stack, Vec3 pos, Vec3 velocity, ServerPlayer from) {
		DroppedWeaponEntity e = new DroppedWeaponEntity(CounterCraft.DROPPED_WEAPON, level);
		e.setItem(stack);
		e.setPos(pos.subtract(0, HALF, 0));
		e.sim.pos = pos.scale(GrenadeEntity.UNITS);
		e.sim.vel = velocity;
		e.simulated = true;
		e.dropper = from == null ? null : from.getUUID();
		e.droppedAt = level.getGameTime();
		if (from != null) {
			e.restYaw = from.getYRot() + 90f; // lies across the direction it was thrown
		}
		level.addFreshEntity(e);
		return e;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(ITEM, ItemStack.EMPTY);
		builder.define(RESTING, false);
	}

	@Override
	public ItemStack getItem() {
		return entityData.get(ITEM);
	}

	public void setItem(ItemStack stack) {
		entityData.set(ITEM, stack);
	}

	public boolean resting() {
		return entityData.get(RESTING);
	}

	public float spin(float partialTick) {
		return spinO + (spin - spinO) * partialTick;
	}

	public float restYaw() {
		return restYaw;
	}

	/** The CS item name ("ak47", "hegrenade"...) of what's lying here. */
	public String weaponId() {
		return getItem().getItem() instanceof CsWeapon w ? w.weaponId() : "";
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) {
			spinO = spin;
			if (!resting()) {
				spin += 346f / 20f; // |(200, 200, 200)| deg/s
			} else {
				spin = spinO = 0f;
			}
			return;
		}
		if (getItem().isEmpty()) {
			discard();
			return;
		}
		if (!simulated) {
			// loaded from disk: carry on from where it lies
			sim.pos = position().add(0, HALF, 0).scale(GrenadeEntity.UNITS);
			sim.vel = Vec3.ZERO;
			simulated = true;
		}
		boolean wasStopped = sim.stopped;
		carry += 1.0 / 20.0;
		while (carry >= GrenadePhysics.TICK - 1e-9) {
			carry -= GrenadePhysics.TICK;
			GrenadePhysics.step(sim, (a, b) -> {
				GrenadeEntity.TraceHit h = GrenadeEntity.hullTrace(level(), this, a.scale(1.0 / GrenadeEntity.UNITS), b.scale(1.0 / GrenadeEntity.UNITS), HALF, false);
				return h.normal() == null ? null : new GrenadePhysics.Hit(h.end().scale(GrenadeEntity.UNITS), h.normal(), h.fraction(), false);
			}, PHYSICS);
			if (sim.hit != null && sim.impactSpeed > 60.0) {
				impactSound(Math.min(1f, (float) (sim.impactSpeed / 400.0)));
			}
		}
		setPos(sim.pos.scale(1.0 / GrenadeEntity.UNITS).subtract(0, HALF, 0));
		setDeltaMovement(sim.vel.scale(1.0 / (20.0 * GrenadeEntity.UNITS)));
		if (sim.stopped != wasStopped || resting() != sim.stopped) {
			entityData.set(RESTING, sim.stopped);
		}
		if (getY() < level().getMinY() - 64) {
			discard();
			return;
		}
		touchPickup((ServerLevel) level());
	}

	/** CS2's opportunistic pick-up: a player whose CS slot for this weapon is free takes it by walking over it. */
	private void touchPickup(ServerLevel level) {
		AABB reach = getBoundingBox().inflate(TRIGGER);
		for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, reach, p -> p.isAlive() && !p.isSpectator())) {
			if (p.getUUID().equals(dropper) && level.getGameTime() - droppedAt < OWNER_DELAY) {
				continue;
			}
			if (CsDrop.walkOverPickup(p, this)) {
				return;
			}
		}
	}

	private void impactSound(float volume) {
		String id = CsDrop.impactSound(getItem());
		if (id != null) {
			level().playSound(null, getX(), getY(), getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(id)), SoundSource.PLAYERS, volume, 1f);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		in.read("item", ItemStack.CODEC).ifPresent(this::setItem);
		entityData.set(RESTING, in.getBooleanOr("resting", false));
		restYaw = in.getFloatOr("rest_yaw", restYaw);
		Optional<UUID> who = in.read("dropper", UUIDUtil.CODEC);
		dropper = who.orElse(null);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		if (!getItem().isEmpty()) {
			out.store("item", ItemStack.CODEC, getItem());
		}
		out.putBoolean("resting", resting());
		out.putFloat("rest_yaw", restYaw);
		if (dropper != null) {
			out.store("dropper", UUIDUtil.CODEC, dropper);
		}
	}
}
