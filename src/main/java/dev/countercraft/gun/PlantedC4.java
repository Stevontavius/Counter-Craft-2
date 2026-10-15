package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.net.C4Payload;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * A planted C4 (C4Item). Planting tells every player in the dimension ({@link C4Payload}): CS2's "The bomb has been
 * planted" notice and announcer line, and their music kit's bomb countdown. It beeps for {@link #FUSE_TICKS} (CS2's
 * mp_c4timer, 40 s), faster as it runs out, the kit's ten second warning at 10 s; then it explodes ({@link #explode}). Anyone can defuse it, whatever their team: hold E (use) aiming at it, standing on the ground,
 * for 10 s (5 s with a defuse kit, as CS2). Not saved with the world. No round is won or lost yet.
 * <p>Counter-Craft's values (not CS2 data): the beep rate, the blast's size and damage.</p>
 */
public class PlantedC4 extends Entity implements ItemSupplier {
	public static final int FUSE_TICKS = 40 * 20;
	public static final int DEFUSE_TICKS = 10 * 20;
	public static final int KIT_DEFUSE_TICKS = 5 * 20;
	/**
	 * The blast (Counter-Craft's values), in bands by distance: everything living within a band's outer edge (blocks)
	 * takes its damage (Minecraft health; 20 is a player's), walls or not (as CS2's bomb). The first band kills.
	 * The damage gets past armor, effects and enchantments and isn't scaled by difficulty ({@link CounterCraft#C4_BLAST}),
	 * so the HUD's warning ({@link #damageAt}) is exact; a Totem of Undying still saves you. Never blocks.
	 */
	/** The first band's damage: more than anything but a boss has. */
	public static final float LETHAL = 1000f;
	public static final double[] BAND_EDGES = {22, 33, 44, 55, 68.75};
	public static final float[] BAND_DAMAGE = {LETHAL, 16f, 10f, 6f, 2f};
	public static final double BLAST_RADIUS = 68.75;
	/** Bosses (the Wither, the Ender Dragon, the Warden) take at most this (their fights keep their balance). */
	private static final float BOSS_CAP = 50f;
	/** A defuse stops if the defuser's use (E) hasn't reached the bomb for this long. */
	private static final int HOLD_GRACE = 4;
	/** How close the defuser must be (blocks). */
	public static final double DEFUSE_REACH = 3.0;

	private static final EntityDataAccessor<Integer> DEFUSER = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DEFUSE_PROGRESS = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.FLOAT);
	/** Each beep: one more, and the fuse it beeped at (clients flash the HUD's warning with it). */
	private static final EntityDataAccessor<Integer> BEEPS = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> BEEP_FUSE = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT);
	/** The last seconds of the fuse: the HUD's warning stops fading and flashes on and off with the beeps. */
	public static final int HARD_FLASH_TICKS = 5 * 20;
	/** Client: when the last beep came (System.nanoTime). */
	private long beepAt = System.nanoTime();

	private int fuse = FUSE_TICKS;
	private int nextBeep;
	private UUID planter;
	private int defuseTicks;
	private int defuseTotal;
	private long lastUse;

	public PlantedC4(EntityType<? extends PlantedC4> type, Level level) {
		super(type, level);
	}

	/** {@code p} finished planting. */
	public static void plant(ServerLevel level, ServerPlayer p) {
		PlantedC4 bomb = new PlantedC4(CounterCraft.PLANTED_C4, level);
		bomb.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0f);
		bomb.planter = p.getUUID();
		level.addFreshEntity(bomb);
		sound(level, bomb.position(), "c4.plant", 1f);
		bomb.tell(level, C4Payload.PLANTED);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DEFUSER, -1);
		builder.define(DEFUSE_PROGRESS, 0f);
		builder.define(BEEPS, 0);
		builder.define(BEEP_FUSE, FUSE_TICKS);
	}

	/** The player defusing it (entity id), or -1. */
	public int defuser() {
		return entityData.get(DEFUSER);
	}

	/** Ticks between beeps at this much fuse left: every second at first, every 3 ticks at the end. */
	public static int beepInterval(int fuse) {
		return 3 + Math.round(17f * fuse / FUSE_TICKS);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (BEEPS.equals(key)) {
			beepAt = System.nanoTime();
		}
	}

	/**
	 * Client: the HUD's lethal warning brightness, 0..1, in time with the beeps: full on each beep, fading until the
	 * next; in the last {@link #HARD_FLASH_TICKS}, on for the first half of each beat and off for the second.
	 */
	public float warningFlash() {
		int fuse = entityData.get(BEEP_FUSE);
		double beat = beepInterval(fuse) / 20.0;
		double since = (System.nanoTime() - beepAt) / 1e9;
		if (fuse <= HARD_FLASH_TICKS) {
			return since % beat < beat / 2 ? 1f : 0f;
		}
		return (float) Math.max(0.15, 1 - since / beat);
	}

	/** Fuse left at the last beep (ticks). */
	public int fuseAtBeep() {
		return entityData.get(BEEP_FUSE);
	}

	public float defuseProgress() {
		return entityData.get(DEFUSE_PROGRESS);
	}

	@Override
	public ItemStack getItem() {
		return new ItemStack(CounterCraft.C4);
	}

	@Override
	public boolean isPickable() {
		return true; // so it can be aimed at to defuse
	}

	@Override
	public boolean skipAttackInteraction(Entity attacker) {
		return true;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	/**
	 * {@code player} holds use (E) on it (C4ActionPayload, sent every tick while held): starts or keeps a defuse going.
	 * Within reach and on the ground only; one defuser at a time.
	 */
	public void hold(ServerPlayer player) {
		if (player.isSpectator() || !player.isAlive() || player.distanceTo(this) > DEFUSE_REACH || !(level() instanceof ServerLevel level)) {
			return;
		}
		if (!player.onGround()) {
			player.sendSystemMessage(Component.literal("You must be on the ground to defuse the bomb"), true); // CS2's notice
			return;
		}
		int current = defuser();
		if (current != -1 && current != player.getId()) {
			return; // someone else is defusing it
		}
		if (current == -1) {
			entityData.set(DEFUSER, player.getId());
			defuseTicks = 0;
			// a defuse kit (bought while the C4 test is on: /beta test_c4) halves it, as CS2's
			defuseTotal = dev.countercraft.Combat.hasDefuser(player) && dev.countercraft.team.CsInventory.c4Test(player)
					? KIT_DEFUSE_TICKS : DEFUSE_TICKS;
			sound(level, position(), "c4.disarmstart", 1f);
		}
		lastUse = level.getGameTime();
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		if (!dev.countercraft.team.CsInventory.c4Test(level)) {
			discard(); // the C4 test is off (/beta test_c4 0): no bombs in this world
			return;
		}
		if (defuser() != -1 && defuseTick(level)) {
			return;
		}
		fuse--;
		if (fuse == 10 * 20) {
			tell(level, C4Payload.TEN_SECONDS);
		}
		if (fuse == 20) {
			sound(level, position(), "c4.explodewarning", 2f);
		}
		if (--nextBeep <= 0 && fuse > 20) {
			// faster as it runs out: every second at first, every 3 ticks at the end
			nextBeep = beepInterval(fuse);
			entityData.set(BEEP_FUSE, fuse);
			entityData.set(BEEPS, entityData.get(BEEPS) + 1);
			sound(level, position(), fuse > 10 * 20 ? "c4.plantsound" : "c4.plantsound_10sec", 2f);
			level.sendParticles(new DustParticleOptions(0xFF2020, 0.6f), getX(), getY() + 0.15, getZ(), 1, 0, 0, 0, 0);
		}
		if (fuse <= 0) {
			explode(level);
		}
	}

	/** A defuse in progress: true once it's defused (the bomb is gone). */
	private boolean defuseTick(ServerLevel level) {
		Entity e = level.getEntity(defuser());
		if (!(e instanceof Player p) || !p.isAlive() || level.getGameTime() - lastUse > HOLD_GRACE || p.distanceTo(this) > DEFUSE_REACH + 0.5 || !p.onGround()) {
			entityData.set(DEFUSER, -1);
			entityData.set(DEFUSE_PROGRESS, 0f);
			return false;
		}
		defuseTicks++;
		entityData.set(DEFUSE_PROGRESS, Math.min(1f, (float) defuseTicks / defuseTotal));
		if (defuseTicks < defuseTotal) {
			return false;
		}
		sound(level, position(), "c4.disarmfinish", 1.5f);
		tell(level, C4Payload.DEFUSED);
		discard();
		return true;
	}

	/** The blast's damage at {@code distance} blocks from a bomb (0 outside its reach). */
	public static float damageAt(double distance) {
		for (int i = 0; i < BAND_EDGES.length; i++) {
			if (distance <= BAND_EDGES[i]) {
				return BAND_DAMAGE[i];
			}
		}
		return 0f;
	}

	/** Where the blast is measured from. */
	public Vec3 blastCentre() {
		return position().add(0, 0.25, 0);
	}

	/** What the planted bombs around would do to {@code e} if they went off now (the HUD's warning). */
	public static float damageTo(LivingEntity e) {
		float total = 0f;
		Vec3 at = e.getBoundingBox().getCenter();
		for (PlantedC4 bomb : e.level().getEntitiesOfClass(PlantedC4.class, e.getBoundingBox().inflate(BLAST_RADIUS))) {
			total += damageAt(at.distanceTo(bomb.blastCentre()));
		}
		return total;
	}

	/**
	 * Through walls, in bands out to {@link #BLAST_RADIUS}. Mobs it kills are the planter's (bounty, kill feed); players
	 * aren't (no team damage or team kill against the planter): the kill feed shows the planted C4 alone.
	 */
	private void explode(ServerLevel level) {
		Entity owner = planter == null ? null : level.getEntity(planter);
		Vec3 c = blastCentre();
		var type = CounterCraft.damageType(level, CounterCraft.C4_BLAST);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(BLAST_RADIUS), Entity::isAlive)) {
			double d = e.getBoundingBox().getCenter().distanceTo(c);
			float damage = damageAt(d);
			if (damage <= 0f) {
				continue;
			}
			boolean player = e instanceof Player;
			if (dev.countercraft.BossFights.bounty(e) > 0) {
				damage = Math.min(damage, BOSS_CAP);
			}
			dev.countercraft.Combat.markHit(player ? null : owner, e, "planted_c4", 0, getId());
			e.hurtServer(level, new DamageSource(type, this, player ? null : owner), damage);
			if (!player) {
				float push = (float) Math.max(0, 1 - d / BLAST_RADIUS);
				Vec3 away = e.position().subtract(c).normalize();
				e.push(away.x * push * 2, 0.3 + push, away.z * push * 2);
			}
		}
		sound(level, position(), "c4.explode", 8f);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 6, 2.5, 1.5, 2.5, 0);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 120, 4, 2, 4, 0.05);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, c.x, c.y, c.z, 80, 2, 1, 2, 0.2);
		tell(level, C4Payload.EXPLODED);
		discard();
	}

	/** Tell every player in this dimension (CS2's bomb events are map-wide). */
	private void tell(ServerLevel level, int event) {
		C4Payload payload = new C4Payload(event, FUSE_TICKS / 20);
		for (ServerPlayer p : level.players()) {
			if (ServerPlayNetworking.canSend(p, C4Payload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** A CS2 sound from the pack at {@code at}, heard {@code volume} x 16 blocks away. */
	static void sound(ServerLevel level, Vec3 at, String id, float volume) {
		level.playSound(null, at.x, at.y, at.z, SoundEvent.createVariableRangeEvent(CounterCraft.id(id)), SoundSource.BLOCKS, volume, 1f);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
