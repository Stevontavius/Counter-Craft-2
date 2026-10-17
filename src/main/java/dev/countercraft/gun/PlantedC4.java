package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.net.BlastMapPayload;
import dev.countercraft.net.BlastWavePayload;
import dev.countercraft.net.C4Payload;
import dev.countercraft.net.ShockwavePayload;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A planted C4 (C4Item). Planting tells every player in the dimension ({@link C4Payload}): CS2's "The bomb has been
 * planted" notice and announcer line, and their music kit's bomb countdown. It beeps for {@link #FUSE_TICKS} (CS2's
 * mp_c4timer, 40 s), faster as it runs out, the kit's ten second warning at 10 s; then it explodes ({@link #explode}).
 * Anyone can defuse it, whatever their team: hold E (use) aiming at it, standing on the ground, for 10 s (5 s with a
 * defuse kit, as CS2). Not saved with the world. No round is won or lost yet.
 * <p>The blast is CS2's 2026 shockwave: it travels out from the bomb through open space ({@link BlastField}, worked out
 * while the fuse runs), at {@link #WAVE_SPEED}, stopped by walls and weakened bending round corners, and hits each
 * creature as it arrives. Anyone within {@link #CERTAIN_DEATH} of it dies at once.</p>
 * <p>Counter-Craft's values (not CS2 data): the beep rate, the blast's size, damage and speed.</p>
 */
public class PlantedC4 extends Entity implements ItemSupplier {
	public static final int FUSE_TICKS = 40 * 20;
	public static final int DEFUSE_TICKS = 10 * 20;
	public static final int KIT_DEFUSE_TICKS = 5 * 20;
	/** More damage than anything but a boss has. */
	public static final float LETHAL = 1000f;
	/** Within this distance (blocks, the wave's distance: {@link BlastField#effective}) the blast kills at once. */
	public static final double CERTAIN_DEATH = 11;
	/** How far the blast reaches (blocks). */
	public static final double BLAST_RADIUS = 68.75;
	/**
	 * The blast's damage (Minecraft health; 20 is a player's) by distance, linear between these points: past
	 * {@link #CERTAIN_DEATH}, 40 (twice a player's health) easing to nothing at {@link #BLAST_RADIUS}. Averaged over the
	 * old bands it's the same blast: 22 to 33 blocks still 16 on average, 33 to 44 10, 44 to 55 6, 55 to 68.75 2. It
	 * gets past armor, effects and enchantments and isn't scaled by difficulty ({@link CounterCraft#C4_BLAST}), so the
	 * HUD's warning is exact; a Totem of Undying still saves you.
	 */
	public static final double[] CURVE_DISTANCE = {CERTAIN_DEATH, 16.5, 22, 27.5, 33, 38.5, 44, 49.5, 55, 61.875, BLAST_RADIUS};
	public static final float[] CURVE_DAMAGE = {40f, 28f, 19f, 16f, 13f, 10.5f, 8f, 6f, 4f, 2f, 0f};
	/** How fast the shockwave travels out (blocks a second): about a second and a half to the edge, round corners. */
	public static final double WAVE_SPEED = 60;
	/** The shockwave is heard, seen and felt this far out (blocks), past the reach of its damage: outside the blast it does none. */
	public static final double WAVE_HEARD = BLAST_RADIUS * 1.5;
	/** How hard the wave strikes you at the blast's edge (0..1; the screen's shake and blur, the crack). */
	public static final float STRUCK_EDGE = 0.3f;
	/** Bosses (the Wither, the Ender Dragon, the Warden) take at most this (their fights keep their balance). */
	private static final float BOSS_CAP = 50f;
	/** A defuse stops if the defuser's use (E) hasn't reached the bomb for this long. */
	private static final int HOLD_GRACE = 4;
	/** How close the defuser must be (blocks). */
	public static final double DEFUSE_REACH = 3.0;
	/** Time the shockwave field may take each server tick (nanoseconds). */
	private static final long FIELD_NANOS = 4_000_000;

	private static final EntityDataAccessor<Integer> DEFUSER = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DEFUSE_PROGRESS = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.FLOAT);
	/** The defuser has a kit (5 s instead of 10). */
	private static final EntityDataAccessor<Boolean> DEFUSE_KIT = SynchedEntityData.defineId(PlantedC4.class, EntityDataSerializers.BOOLEAN);
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
	/** Who planted it, as the news names them (kept: they may leave before it goes off). */
	private String planterName = "";
	private String planterTeam = "";
	private int defuseTicks;
	private int defuseTotal;
	private long lastUse;
	/** Server: the shockwave's paths around it. */
	private BlastField field;

	/**
	 * What the planted bombs around would do to a player if they went off now, synced to them every half second: the
	 * damage (the HUD's warning, whenever it's above 0), and for the blast debug view the distances behind it.
	 */
	public record Preview(float damage, float straight, float path, float effective, float progress) {
		public static final StreamCodec<ByteBuf, Preview> CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, Preview::damage, ByteBufCodecs.FLOAT, Preview::straight, ByteBufCodecs.FLOAT, Preview::path,
				ByteBufCodecs.FLOAT, Preview::effective, ByteBufCodecs.FLOAT, Preview::progress, Preview::new);
	}

	public static final AttachmentType<Preview> PREVIEW = AttachmentRegistry.<Preview>builder()
			.syncWith(Preview.CODEC, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("c4_preview"));

	/** Shockwaves on their way out. */
	private static final List<Wave> WAVES = new ArrayList<>();
	/** Players the shockwave is killing right now: their ragdoll's push (DeathTracker). */
	private static final Map<UUID, Vec3> RAGDOLL = new HashMap<>();

	public PlantedC4(EntityType<? extends PlantedC4> type, Level level) {
		super(type, level);
	}

	/** Mod init: the preview attachment, the shockwaves and the previews each server tick. */
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PlantedC4::serverTick);
	}

	/** {@code p} finished planting. */
	public static void plant(ServerLevel level, ServerPlayer p) {
		PlantedC4 bomb = new PlantedC4(CounterCraft.PLANTED_C4, level);
		bomb.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), 0f);
		bomb.planter = p.getUUID();
		bomb.planterName = p.getName().getString();
		bomb.planterTeam = p.hasAttached(dev.countercraft.team.Teams.TEAM) ? dev.countercraft.team.Teams.of(p) : "";
		level.addFreshEntity(bomb);
		sound(level, bomb.position(), "c4.plant", 1f);
		bomb.tell(level, C4Payload.PLANTED);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DEFUSER, -1);
		builder.define(DEFUSE_PROGRESS, 0f);
		builder.define(DEFUSE_KIT, false);
		builder.define(BEEPS, 0);
		builder.define(BEEP_FUSE, FUSE_TICKS);
	}

	/** The player defusing it (entity id), or -1. */
	public int defuser() {
		return entityData.get(DEFUSER);
	}

	/** The defuse under way is with a kit. */
	public boolean defuseKit() {
		return entityData.get(DEFUSE_KIT);
	}

	/** How long the defuse under way takes, seconds. */
	public double defuseSeconds() {
		return (defuseKit() ? KIT_DEFUSE_TICKS : DEFUSE_TICKS) / 20.0;
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
			// a defuse kit (bought while the C4 test is on: /beta test_c4 1) halves it, as CS2's
			boolean kit = dev.countercraft.Combat.hasDefuser(player) && dev.countercraft.team.CsInventory.c4Test(player);
			entityData.set(DEFUSE_KIT, kit);
			defuseTotal = kit ? KIT_DEFUSE_TICKS : DEFUSE_TICKS;
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
		if (field == null) {
			field = new BlastField(level, blastCentre());
		}
		if (field.step(FIELD_NANOS)) {
			if (field.passes() == 1) {
				CounterCraft.LOG.info("[countercraft] C4 #{}: shockwave map worked out in {} s", getId(), String.format("%.1f", (FUSE_TICKS - fuse) / 20.0));
			}
			sendMap(level);
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

	/** The blast's damage at {@code distance} (the wave's distance, blocks): 0 out of reach or where it can't get. */
	public static float damageAt(double distance) {
		if (Double.isNaN(distance) || distance > BLAST_RADIUS) {
			return 0f;
		}
		if (distance <= CERTAIN_DEATH) {
			return LETHAL;
		}
		for (int i = 1; i < CURVE_DISTANCE.length; i++) {
			if (distance <= CURVE_DISTANCE[i]) {
				double t = (distance - CURVE_DISTANCE[i - 1]) / (CURVE_DISTANCE[i] - CURVE_DISTANCE[i - 1]);
				return (float) (CURVE_DAMAGE[i - 1] + (CURVE_DAMAGE[i] - CURVE_DAMAGE[i - 1]) * t);
			}
		}
		return 0f;
	}

	/** Where the blast is measured from. */
	public Vec3 blastCentre() {
		return position().add(0, 0.25, 0);
	}

	/**
	 * What this bomb would do to {@code e} if it went off now: {damage, straight distance, path, effective distance}.
	 * Until its first field pass is done (the first few seconds), as if there were no walls.
	 */
	private double[] predict(LivingEntity e) {
		AABB box = e.getBoundingBox();
		Vec3 c = blastCentre();
		double straight = box.getCenter().distanceTo(c);
		if (field == null || !field.ready()) {
			return new double[] {damageAt(straight), straight, Double.NaN, straight};
		}
		double eff = field.effective(box, c);
		return new double[] {damageAt(eff), straight, field.path(box), eff};
	}

	/** Server tick: the shockwaves going out, and every half second each player's preview. */
	private static void serverTick(MinecraftServer server) {
		for (Iterator<Wave> it = WAVES.iterator(); it.hasNext(); ) {
			if (it.next().tick()) {
				it.remove();
			}
		}
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer p : level.players()) {
				List<PlantedC4> bombs = level.getEntitiesOfClass(PlantedC4.class, p.getBoundingBox().inflate(BLAST_RADIUS + 2));
				if (bombs.isEmpty()) {
					if (p.hasAttached(PREVIEW)) {
						p.removeAttached(PREVIEW);
					}
					continue;
				}
				float damage = 0f;
				double[] nearest = null;
				float progress = 0f;
				for (PlantedC4 bomb : bombs) {
					double[] d = bomb.predict(p);
					damage += (float) d[0];
					if (nearest == null || d[1] < nearest[1]) {
						nearest = d;
						progress = bomb.field == null ? 0f : bomb.field.ready() ? 1f : bomb.field.progress();
					}
				}
				Preview v = new Preview(damage, (float) nearest[1], (float) nearest[2], (float) nearest[3], progress);
				if (!v.equals(p.getAttached(PREVIEW))) {
					p.setAttached(PREVIEW, v);
				}
			}
		}
	}

	/** A field pass is done: with the C4 test on, the floor's damage map for everyone's blast debug view. */
	private void sendMap(ServerLevel level) {
		if (!dev.countercraft.team.CsInventory.c4Test(level)) {
			return;
		}
		List<float[]> floor = field.floor(2, blastCentre());
		int n = Math.min(floor.size(), 12000);
		byte[] data = new byte[n * 4];
		for (int i = 0; i < n; i++) {
			float[] f = floor.get(i);
			data[i * 4] = (byte) f[0];
			data[i * 4 + 1] = (byte) f[1];
			data[i * 4 + 2] = (byte) f[2];
			float dmg = damageAt(f[4]);
			data[i * 4 + 3] = (byte) (dmg >= LETHAL ? 255 : Math.min(254, Math.round(dmg * 4)));
		}
		BlastMapPayload payload = new BlastMapPayload(getId(), field.origin.getX(), field.origin.getY(), field.origin.getZ(), data);
		for (ServerPlayer p : level.players()) {
			if (ServerPlayNetworking.canSend(p, BlastMapPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/**
	 * It goes off: the shockwave sets out ({@link Wave}). Mobs it kills are the planter's (bounty, kill feed); players
	 * aren't (no team damage or team kill against the planter): the kill feed shows the planted C4 alone.
	 */
	private void explode(ServerLevel level) {
		if (field == null) {
			field = new BlastField(level, blastCentre());
		}
		field.finish();
		Vec3 c = blastCentre();
		WAVES.add(new Wave(level, this, field, c, planter));
		// everyone near enough to see or hear it: the fireball and its smoke (C4Explosion), the bang (on each client's own
		// distance curve), the wave spreading through the world, and its rumble as it closes in
		BlastWavePayload wave = new BlastWavePayload(c.x, c.y, c.z);
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceTo(c) <= WAVE_HEARD * 2 && ServerPlayNetworking.canSend(p, BlastWavePayload.TYPE)) {
				ServerPlayNetworking.send(p, wave);
			}
		}
		tell(level, C4Payload.EXPLODED);
		discard();
	}

	/** The push a player killed by the shockwave right now gets (direction x speed, m/s), or null (DeathTracker). */
	public static Vec3 ragdollPush(UUID player) {
		return RAGDOLL.get(player);
	}

	/**
	 * A shockwave going out from a bomb that went off: each tick its front moves {@link #WAVE_SPEED} / 20 blocks further
	 * along the field's paths, hitting what it reaches (once), putting out the molotov fires it reaches and kicking up
	 * dust along the floor as it passes. Anyone within {@link #CERTAIN_DEATH} dies on the first tick.
	 */
	private static final class Wave {
		final ServerLevel level;
		final PlantedC4 bomb;
		final BlastField field;
		final Vec3 centre;
		final UUID planter;
		final Set<Integer> hit = new HashSet<>();
		/** Players it has reached (told: ShockwavePayload), and how hard it struck the ones it hurt (0..1). */
		final Set<UUID> told = new HashSet<>();
		final Map<UUID, Float> struck = new HashMap<>();
		final List<float[]> dust;
		int nextDust;
		int ticks;

		Wave(ServerLevel level, PlantedC4 bomb, BlastField field, Vec3 centre, UUID planter) {
			this.level = level;
			this.bomb = bomb;
			this.field = field;
			this.centre = centre;
			this.planter = planter;
			this.dust = field.floor(3, centre);
			this.dust.sort((a, b) -> Float.compare(a[3], b[3]));
		}

		/** True once it's spent. */
		boolean tick() {
			double front = ticks * WAVE_SPEED / 20.0;
			boolean first = ticks == 0;
			ticks++;
			Entity owner = planter == null ? null : level.getEntity(planter);
			var type = CounterCraft.damageType(level, CounterCraft.C4_BLAST);
			AABB reach = new AABB(centre, centre).inflate(BLAST_RADIUS + 2);
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, reach, Entity::isAlive)) {
				if (hit.contains(e.getId())) {
					continue;
				}
				AABB box = e.getBoundingBox();
				double path = field.path(box);
				if (Double.isNaN(path)) {
					continue; // walled off
				}
				double eff = field.effective(box, centre);
				if (path > front && !(first && eff <= CERTAIN_DEATH)) {
					continue; // not there yet
				}
				hit.add(e.getId());
				float damage = damageAt(eff);
				if (damage <= 0f) {
					continue;
				}
				boolean player = e instanceof Player;
				if (dev.countercraft.BossFights.bounty(e) > 0) {
					damage = Math.min(damage, BOSS_CAP);
				}
				// closer, harder: the push along the wave's way (a ragdoll if it kills, a shove if a mob lives)
				double close = Math.max(0, 1 - eff / BLAST_RADIUS);
				Vec3 dir = field.direction(box, centre);
				// thrown out along the wave and up, steeper the closer (about 45 degrees on the bomb): flat out of it, on
				// top of the bomb (straight up), away from it, else backwards
				Vec3 flat = new Vec3(dir.x, 0, dir.z);
				if (flat.lengthSqr() < 0.09) {
					flat = box.getCenter().subtract(centre).multiply(1, 0, 1);
					flat = flat.lengthSqr() > 1e-4 ? flat : e.getLookAngle().multiply(-1, 0, -1);
				}
				Vec3 fling = flat.normalize().add(0, 0.45 + 0.6 * close + Math.max(0, dir.y), 0).normalize();
				if (player) {
					// a ragdoll's whole body at 3 m/s at the edge to 24 m/s on the bomb (about 28 blocks away)
					RAGDOLL.put(e.getUUID(), fling.scale(3 + 21 * close * close));
					struck.put(e.getUUID(), (float) Math.min(1, STRUCK_EDGE + (1 - STRUCK_EDGE) * damage / e.getMaxHealth()));
				}
				dev.countercraft.Combat.markHit(player ? null : owner, e, "planted_c4", 0, bomb.getId());
				e.hurtServer(level, new DamageSource(type, bomb, player ? null : owner), damage);
				RAGDOLL.remove(e.getUUID());
				if (!player) {
					// (a mob it kills is thrown too, as Minecraft's knockback throws a dying mob)
					e.push(fling.x * close * 2, 0.3 + close, fling.z * close * 2);
				}
			}
			// each player hears it go past (struck or not), where it reaches them: walled off, through the walls, later
			for (ServerPlayer p : level.players()) {
				if (told.contains(p.getUUID())) {
					continue;
				}
				AABB box = p.getBoundingBox();
				double straight = box.getCenter().distanceTo(centre);
				if (straight > WAVE_HEARD) {
					continue;
				}
				double path = field.pathFar(box, centre);
				boolean walled = Double.isNaN(path);
				if (!struck.containsKey(p.getUUID()) && (walled ? straight * 1.3 : path) > front) {
					continue;
				}
				told.add(p.getUUID());
				Vec3 dir = field.direction(box, centre);
				float loud = (float) Math.max(0.12, 1 - straight / WAVE_HEARD) * (walled ? 0.45f : 1f);
				float strike = struck.getOrDefault(p.getUUID(), 0f);
				if (strike == 0f && !walled) {
					// past the blast it still hits you, harmlessly: as hard as the blast's edge, weaker on out to its reach
					double past = Math.max(0, field.effectiveFar(box, centre) - BLAST_RADIUS) / (WAVE_HEARD - BLAST_RADIUS);
					strike = past >= 1 ? 0f : (float) (STRUCK_EDGE * (1 - past));
				}
				if (ServerPlayNetworking.canSend(p, ShockwavePayload.TYPE)) {
					ServerPlayNetworking.send(p, new ShockwavePayload(strike, loud, (float) dir.x, (float) dir.y, (float) dir.z));
				}
			}
			// the wave puts out the fires it reaches
			for (GrenadeEntity g : level.getEntitiesOfClass(GrenadeEntity.class, reach, g -> g.state() == GrenadeEntity.BURNING)) {
				double path = field.path(g.getBoundingBox().inflate(0.3));
				if (!Double.isNaN(path) && path <= front) {
					g.extinguish();
				}
			}
			// dust along the floor where the front is passing
			Vec3 o = Vec3.atLowerCornerOf(field.origin);
			while (nextDust < dust.size() && dust.get(nextDust)[3] <= front) {
				float[] d = dust.get(nextDust++);
				Vec3 at = o.add(d[0] + 0.5, d[1] + 0.1, d[2] + 0.5);
				Vec3 out = at.subtract(centre).multiply(1, 0, 1);
				out = out.lengthSqr() < 1e-6 ? Vec3.ZERO : out.normalize();
				level.sendParticles(ParticleTypes.POOF, at.x, at.y, at.z, 0, out.x, 0.15, out.z, 0.35);
			}
			return front > Math.max(BlastField.PATH_MAX, WAVE_HEARD * 1.3);
		}
	}

	/** Tell every player in this dimension (CS2's bomb events are map-wide). */
	private void tell(ServerLevel level, int event) {
		C4Payload payload = new C4Payload(event, FUSE_TICKS / 20, planterName, planterTeam);
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
