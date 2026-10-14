package dev.countercraft.gun;

import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A CS2 knife. Every knife has the same stats (CS hard-codes melee in game code, not weapons.vdata): slash 40
 * (25 for a follow-up slash), backstab 90; stab 65, backstab 180. Left = slash, right = stab, through the same
 * {@code FirePayload} as guns.
 */
public class KnifeItem extends Item implements CsWeapon {
	static final float SLASH = 40f;
	static final float SLASH_FOLLOWUP = 25f;
	static final float SLASH_BACKSTAB = 90f;
	static final float STAB = 65f;
	static final float STAB_BACKSTAB = 180f;
	/** CS reach in units (slash 48, stab 32), plus a little for Minecraft's eye-to-hitbox measure. */
	public static final double SLASH_RANGE = 48 / 39.37 + 0.35;
	public static final double STAB_RANGE = 32 / 39.37 + 0.35;
	/**
	 * Against mobs (not players) the knife reaches further: CS2's 1.2 m is shorter than a zombie's own reach, which made
	 * hitting mobs without taking a hit a gamble. Gameplay tuning, not CS2 data.
	 */
	public static final double SLASH_RANGE_MOB = 2.2;
	public static final double STAB_RANGE_MOB = 1.8;
	/** Seconds until the next attack: slash hit 0.5 / miss 0.4, stab hit 1.1 / miss 1.0. */
	static final double SLASH_HIT = 0.5;
	static final double SLASH_MISS = 0.4;
	static final double STAB_HIT = 1.1;
	static final double STAB_MISS = 1.0;
	/** A slash within this long of the previous one is a follow-up (25 instead of 40). */
	static final double FOLLOWUP_WINDOW = 0.6;
	/**
	 * The client times each swing by its own clock and marks the packet it swung on; the server takes it up to this many
	 * ticks before its own cooldown ends (network jitter), keeping the cadence: an early swing counts from when it was due.
	 */
	public static final int LEEWAY_TICKS = 3;
	/**
	 * Backstab: anywhere behind the target, up to this many degrees from straight behind (90: level with their
	 * shoulders), facing it within {@link #FACING_ARC}.
	 */
	static final double BACK_ARC = 90;
	static final double FACING_ARC = 75;
	/**
	 * What the client saw its swing hit counts if the target is still within the reach plus this much (blocks) and along
	 * the swing: a target that moved a little between the swing and the server hearing of it (ping) is still hit.
	 */
	static final double CLAIM_SLACK = 0.75;
	private static final double COS_BACK = Math.cos(Math.toRadians(BACK_ARC));
	private static final double COS_FACING = Math.cos(Math.toRadians(FACING_ARC));
	private static final double COS_80 = Math.cos(Math.toRadians(80));
	private static final double COS_100 = Math.cos(Math.toRadians(100));

	private static final Map<UUID, Long> LAST_SLASH = new ConcurrentHashMap<>();

	private final String id;

	public KnifeItem(Properties properties, String id) {
		super(properties);
		this.id = id;
	}

	@Override
	public String weaponId() {
		return id;
	}

	@Override
	public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
		return false;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		return InteractionResult.CONSUME;
	}

	public static void forget(UUID player) {
		LAST_SLASH.remove(player);
	}

	/**
	 * Server: one swing ({@code stab} = right click), on the packet the client swung on. {@code claimedTarget}: what the
	 * client saw the swing hit (-1: nothing), taken when the server's own trace finds something else or nothing but the
	 * claim checks out ({@link #claim}); {@code claimedBackstab}: the entity the client animated a backstab on (-1: none),
	 * see {@link #behindLoosely}. So a hit (and a backstab) you saw is the hit you get.
	 */
	public void attack(ServerPlayer player, ItemStack stack, boolean stab, int claimedTarget, int claimedBackstab) {
		ServerLevel level = player.level();
		long now = level.getGameTime();
		double due = stack.getOrDefault(ModComponents.NEXT_SHOT, 0.0);
		if (now + LEEWAY_TICKS + 1e-6 < due) {
			return;
		}
		double at = Math.max(now, due);
		double csRange = stab ? STAB_RANGE : SLASH_RANGE;
		double range = stab ? STAB_RANGE_MOB : SLASH_RANGE_MOB;
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1f).scale(range));
		BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 reach = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		AABB sweep = player.getBoundingBox().expandTowards(reach.subtract(eye)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, reach, sweep, e -> Ballistics.hittable(e, player),
				eye.distanceToSqr(reach));

		if (hit != null && Ballistics.owner(hit.getEntity()) instanceof Player && hit.getLocation().distanceTo(eye) > csRange) {
			hit = null; // players: CS2's own reach
		}
		if (claimedTarget >= 0 && (hit == null || hit.getEntity().getId() != claimedTarget)) {
			EntityHitResult claimed = claim(level, player, claimedTarget, eye, stab);
			if (claimed != null) {
				hit = claimed;
			}
		}
		boolean landed = hit != null;
		boolean backHit = false;
		if (landed) {
			Entity target = hit.getEntity();
			// what the client animated counts when you're behind the target, players included: it saw the target a moment
			// earlier than this, and a turn in between mustn't undo a backstab it already showed
			boolean back = Ballistics.owner(target) instanceof LivingEntity victim
					&& ((victim instanceof Player ? backstab(player, victim) : backZone(player, victim, hit.getLocation()))
							|| victim.getId() == claimedBackstab && behindLoosely(player, victim));
			backHit = back;
			if (back && Ballistics.owner(target) instanceof net.minecraft.world.entity.monster.Enemy) {
				dev.countercraft.Achievements.award(player, "knife_to_meet_you");
			}
			float cs;
			if (stab) {
				cs = back ? STAB_BACKSTAB : STAB;
			} else {
				Long last = LAST_SLASH.get(player.getUUID());
				boolean followup = last != null && (now - last) / 20.0 <= FOLLOWUP_WINDOW;
				cs = back ? SLASH_BACKSTAB : (followup ? SLASH_FOLLOWUP : SLASH);
			}
			Entity victim = Ballistics.owner(target);
			boolean armored = dev.countercraft.Combat.armored(victim);
			cs = dev.countercraft.Combat.afterArmor(victim, cs, dev.countercraft.Combat.KNIFE_ARMOR_RATIO);
			dev.countercraft.Combat.markHit(player, victim, dev.countercraft.Combat.knifeIcon(id), dev.countercraft.Combat.attackerFlags(player));
			boolean wasAlive = victim.isAlive();
			target.hurtServer(level, new DamageSource(CounterCraft.damageType(level, CounterCraft.KNIFE), player, player), mcDamage(victim, cs, back));
			boolean lethal = wasAlive && (!victim.isAlive() || victim instanceof LivingEntity le && le.isDeadOrDying());
			dev.countercraft.Combat.hitSound(level, player, victim, false, lethal, armored, true, hit.getLocation());
			sound(level, player, stab ? "weapon.knife.stab" : "weapon.knife.hit");
		} else if (block.getType() != HitResult.Type.MISS) {
			sound(level, player, "weapon.knife.hitwall");
		} else {
			sound(level, player, "weapon.knife.slash");
		}
		if (!stab) {
			LAST_SLASH.put(player.getUUID(), now);
		}
		double cycle = stab ? (landed ? STAB_HIT : STAB_MISS) : (landed ? SLASH_HIT : SLASH_MISS);
		stack.set(ModComponents.NEXT_SHOT, at + cycle * 20.0);
		dev.countercraft.AgentAnims.broadcast(player, "action_attack", attackType(stab, landed, landed && backHit), (float) cycle);
	}

	/**
	 * The entity the client saw its swing hit, if that holds up here: something a knife can hit, within the reach (CS2's
	 * against players, the longer one against mobs) plus {@link #CLAIM_SLACK}, its box (grown by the slack) on the line of
	 * the swing, and no block between. The hit point is where that line meets the box (or the box's nearest point).
	 */
	private static EntityHitResult claim(ServerLevel level, ServerPlayer player, int id, Vec3 eye, boolean stab) {
		Entity e = level.getEntity(id);
		if (e == null || !Ballistics.hittable(e, player)) {
			return null;
		}
		boolean isPlayer = Ballistics.owner(e) instanceof Player;
		double reach = (isPlayer ? (stab ? STAB_RANGE : SLASH_RANGE) : (stab ? STAB_RANGE_MOB : SLASH_RANGE_MOB)) + CLAIM_SLACK;
		Vec3 end = eye.add(player.getViewVector(1f).scale(reach));
		AABB box = e.getBoundingBox().inflate(CLAIM_SLACK);
		Vec3 at = box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
		if (at == null) {
			return null;
		}
		AABB real = e.getBoundingBox();
		Vec3 point = new Vec3(Math.max(real.minX, Math.min(real.maxX, at.x)), Math.max(real.minY, Math.min(real.maxY, at.y)),
				Math.max(real.minZ, Math.min(real.maxZ, at.z)));
		if (point.distanceTo(eye) > reach || level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
				.getType() != HitResult.Type.MISS) {
			return null;
		}
		return new EntityHitResult(e, point);
	}

	/**
	 * Knife damage in Minecraft health. Players: CS2's own damage. Mobs: a knife isn't a sword, so a front slash hits like
	 * a wooden sword (CS2's 40 becomes 4 health; the other attacks keep CS2's ratios to it), but a backstab kills any
	 * common mob outright (not the Ender Dragon, Wither, Warden or Elder Guardian, which take the scaled backstab).
	 */
	public static float mcDamage(Entity victim, float cs, boolean back) {
		if (victim instanceof Player) {
			return cs * Ballistics.CS_TO_MC;
		}
		if (back && victim instanceof LivingEntity le && !boss(le)) {
			return Math.max(1000f, le.getMaxHealth() * 10f);
		}
		return cs * MOB_SCALE;
	}

	/** Mob knife damage per CS2 point: CS2's slash (40) hits like a wooden sword (4 health). */
	public static final float MOB_SCALE = 4f / 40f;

	private static boolean boss(LivingEntity e) {
		return e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || e instanceof net.minecraft.world.entity.boss.wither.WitherBoss
				|| e instanceof net.minecraft.world.entity.monster.warden.Warden || e instanceof net.minecraft.world.entity.monster.ElderGuardian;
	}

	/** CS2's third-person attack type for a knife swing (agent animation graph). */
	public static String attackType(boolean stab, boolean hit, boolean back) {
		String kind = stab ? "heavy" : "light";
		return "attack_knife_" + kind + (back ? "backstab" : hit ? "hit" : "miss");
	}

	/**
	 * Counter-Craft's backstab rule for players (its own, not CS2's code), flat on the ground plane between body centres:
	 * you stand within {@link #BACK_ARC} degrees of straight behind the target, judged by where its head faces (a player
	 * turns their whole view), and you face the target within {@link #FACING_ARC} degrees.
	 */
	public static boolean backstab(LivingEntity attacker, LivingEntity target) {
		Vec3 toTarget = new Vec3(target.getX() - attacker.getX(), 0, target.getZ() - attacker.getZ());
		if (toTarget.lengthSqr() < 1e-6) {
			return false;
		}
		toTarget = toTarget.normalize();
		Vec3 targetForward = Vec3.directionFromRotation(0f, target.getYHeadRot());
		Vec3 look = Vec3.directionFromRotation(0f, attacker.getYHeadRot());
		return toTarget.dot(targetForward) > COS_BACK && toTarget.dot(look) > COS_FACING;
	}

	/**
	 * Mobs' backstab zone: Minecraft mobs turn their heads to watch you, so CS's look-direction test almost never passes
	 * on them. Instead the knife must land on the rear half of the mob's box (by its body direction, see
	 * {@link #backZoneDepth}) with you behind its body and facing it.
	 */
	public static boolean backZone(LivingEntity attacker, LivingEntity target, Vec3 hitPoint) {
		Vec3 body = Vec3.directionFromRotation(0f, target.yBodyRot);
		Vec3 toTarget = new Vec3(target.getX() - attacker.getX(), 0, target.getZ() - attacker.getZ());
		if (toTarget.lengthSqr() < 1e-6) {
			return false;
		}
		toTarget = toTarget.normalize();
		Vec3 look = Vec3.directionFromRotation(0f, attacker.getYHeadRot());
		// behind the body (within 80 degrees of straight behind) and facing it (within 75)
		if (toTarget.dot(body) < COS_80 || toTarget.dot(look) < COS_FACING) {
			return false;
		}
		double along = (hitPoint.x - target.getX()) * body.x + (hitPoint.z - target.getZ()) * body.z;
		return along <= backZoneDepth(target);
	}

	/**
	 * The client played a backstab: it saw the target a moment earlier and with its own (interpolated) facing, so the
	 * server's exact test can disagree by a few degrees (a target turning as you swing). The backstab counts when you're
	 * behind the target, up to 100 degrees from straight behind (its head's facing for players, its body's for mobs), and
	 * roughly facing it (within 80): what you saw is what you get, but a client can't claim one from the front.
	 */
	static boolean behindLoosely(LivingEntity attacker, LivingEntity target) {
		Vec3 toTarget = new Vec3(target.getX() - attacker.getX(), 0, target.getZ() - attacker.getZ());
		if (toTarget.lengthSqr() < 1e-6) {
			return false;
		}
		toTarget = toTarget.normalize();
		Vec3 facing = Vec3.directionFromRotation(0f, target instanceof Player ? target.getYHeadRot() : target.yBodyRot);
		Vec3 look = Vec3.directionFromRotation(0f, attacker.getYHeadRot());
		return toTarget.dot(facing) > COS_100 && toTarget.dot(look) > COS_80;
	}

	/** The back zone: everything behind this distance along the body's facing (the rear half, and a quarter more). */
	public static double backZoneDepth(LivingEntity target) {
		return target.getBbWidth() * 0.25;
	}

	private static void sound(ServerLevel level, ServerPlayer player, String id) {
		level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvent.createVariableRangeEvent(CounterCraft.id(id)),
				SoundSource.PLAYERS, 1f, 1f);
	}
}
