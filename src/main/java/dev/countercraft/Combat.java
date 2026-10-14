package dev.countercraft;

import dev.countercraft.net.HitSoundPayload;
import dev.countercraft.net.KillFeedPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * CS2 combat bookkeeping on the server:
 * <ul>
 * <li>Armor, for mobs only: a mob wearing an iron or diamond helmet counts as wearing CS2 kevlar + helmet (100 armor).
 * Damage then follows CS2: health takes damage x armorRatio / 2, armor takes half of the rest (if the armor can't
 * absorb that, the remainder goes to health); every hit group but the legs is armored.</li>
 * <li>Hit / kill feedback sounds: who hit what, where, lethal or not, armored or not ({@link HitSoundPayload}).</li>
 * <li>The kill feed: the last CS weapon hit on each victim (with headshot / no-scope / through smoke / blind / in-air)
 * becomes a {@link KillFeedPayload} when it dies; other deaths of players, and anything a player kills, too.</li>
 * </ul>
 */
public final class Combat {
	/** CS2 ARMOR_BONUS / ARMOR_RATIO halving (CCSPlayerPawn damage code). */
	private static final float ARMOR_BONUS = 0.5f;
	private static final float START_ARMOR = 100f;
	/** A hit counts for the kill feed if the victim dies within this many ticks of it. */
	private static final long HIT_MEMORY = 100;
	/** CS2 knives' m_flArmorRatio (weapons.vdata). */
	public static final float KNIFE_ARMOR_RATIO = 1.7f;

	private record Hit(UUID attacker, String weapon, int flags, long tick) {
	}

	private static final Map<UUID, Hit> LAST_HIT = new ConcurrentHashMap<>();
	private static final Map<UUID, Float> ARMOR = new ConcurrentHashMap<>();
	/** Players who bought a helmet (Kevlar + Helmet); mobs with armor always have one. */
	private static final java.util.Set<UUID> HELMET = ConcurrentHashMap.newKeySet();
	/** Players carrying a defuse kit (no bomb yet: kept for later). */
	private static final java.util.Set<UUID> DEFUSER = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, Long> BLIND_UNTIL = new ConcurrentHashMap<>();

	/** A player's CS2 armor as their own HUD shows it (armor 0-100, helmet). */
	public record Kevlar(int armor, boolean helmet) {
		static final net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, Kevlar> STREAM_CODEC =
				net.minecraft.network.codec.StreamCodec.composite(net.minecraft.network.codec.ByteBufCodecs.VAR_INT, Kevlar::armor,
						net.minecraft.network.codec.ByteBufCodecs.BOOL, Kevlar::helmet, Kevlar::new);
	}

	/** Synced to the player only (their health / armor HUD); the server's maps stay the source of truth. */
	public static final net.fabricmc.fabric.api.attachment.v1.AttachmentType<Kevlar> KEVLAR =
			net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.<Kevlar>builder()
					.syncWith(Kevlar.STREAM_CODEC, net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate.targetOnly())
					.buildAndRegister(CounterCraft.id("kevlar"));

	/** Push a player's armor to their HUD. */
	private static void syncArmor(Entity e) {
		if (!(e instanceof net.minecraft.server.level.ServerPlayer p)) {
			return;
		}
		float a = ARMOR.getOrDefault(p.getUUID(), 0f);
		Kevlar k = new Kevlar((int) Math.ceil(a - 1e-3f), HELMET.contains(p.getUUID()));
		if (k.armor() <= 0) {
			p.removeAttached(KEVLAR);
		} else if (!k.equals(p.getAttached(KEVLAR))) {
			p.setAttached(KEVLAR, k);
		}
	}

	private Combat() {
	}

	/** Load the class at startup: {@link #KEVLAR} must be registered before anyone joins, or it never syncs. */
	/** Airborne Scout / AWP kill: no fall damage until this game time. */
	private static final Map<UUID, Long> NO_FALL_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long NO_FALL_TICKS = 5 * 20;

	public static void init() {
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			Long until = NO_FALL_UNTIL.get(entity.getUUID());
			return until == null || !source.is(net.minecraft.tags.DamageTypeTags.IS_FALL) || entity.level().getGameTime() >= until;
		});
	}

	/** Buy menu: Kevlar (100 armor) or Kevlar + Helmet. False if it would change nothing (CS2 refuses the purchase). */
	public static boolean giveArmor(Player player, boolean helmet) {
		boolean full = ARMOR.getOrDefault(player.getUUID(), 0f) >= START_ARMOR;
		boolean hasHelmet = HELMET.contains(player.getUUID());
		if (full && (hasHelmet || !helmet)) {
			return false;
		}
		ARMOR.put(player.getUUID(), START_ARMOR);
		if (helmet) {
			HELMET.add(player.getUUID());
		}
		syncArmor(player);
		return true;
	}

	/** Full kevlar but no helmet: Kevlar + Helmet costs just the helmet. */
	public static boolean fullArmorNoHelmet(Player player) {
		return ARMOR.getOrDefault(player.getUUID(), 0f) >= START_ARMOR && !HELMET.contains(player.getUUID());
	}

	public static boolean giveDefuser(Player player) {
		return DEFUSER.add(player.getUUID());
	}

	public static boolean hasDefuser(Player player) {
		return DEFUSER.contains(player.getUUID());
	}

	public static void takeDefuser(Player player) {
		DEFUSER.remove(player.getUUID());
	}

	/** A player's CS2 armor (0-100). */
	public static float armorOf(Player player) {
		return ARMOR.getOrDefault(player.getUUID(), 0f);
	}

	public static boolean hasHelmet(Player player) {
		return HELMET.contains(player.getUUID());
	}

	/** Put a player's armor back (a refund: Refunds). */
	public static void setArmor(Player player, float armor, boolean helmet) {
		if (armor > 0f) {
			ARMOR.put(player.getUUID(), armor);
		} else {
			ARMOR.remove(player.getUUID());
		}
		if (helmet) {
			HELMET.add(player.getUUID());
		} else {
			HELMET.remove(player.getUUID());
		}
		syncArmor(player);
	}

	/**
	 * Armor applies: a mob with an iron / diamond helmet (CS2 kevlar + helmet) or a player who bought kevlar, with armor
	 * left.
	 */
	public static boolean armored(Entity e) {
		if (e instanceof Player) {
			return ARMOR.getOrDefault(e.getUUID(), 0f) > 0f;
		}
		if (!(e instanceof Mob mob)) {
			return false;
		}
		ItemStack head = mob.getItemBySlot(EquipmentSlot.HEAD);
		return (head.is(Items.IRON_HELMET) || head.is(Items.DIAMOND_HELMET)) && ARMOR.getOrDefault(e.getUUID(), START_ARMOR) > 0f;
	}

	/** CS2 damage after armor (CS HP), taking the armor's share off the mob's armor. */
	/** As {@link #afterArmor(Entity, float, float)} for a hit on a CS2 hit group: legs aren't armored. */
	public static float afterArmor(Entity victim, float csDamage, float armorRatio, int hitGroup) {
		return armorCovers(victim, hitGroup) ? afterArmor(victim, csDamage, armorRatio) : csDamage;
	}

	/** Armor covers this hit group on this victim: not the legs, and the head only with a helmet. */
	public static boolean armorCovers(Entity victim, int hitGroup) {
		if (!dev.countercraft.gun.Hitboxes.armorCovers(hitGroup)) {
			return false;
		}
		return hitGroup != dev.countercraft.gun.Hitboxes.HEAD || !(victim instanceof Player) || HELMET.contains(victim.getUUID());
	}

	public static float afterArmor(Entity victim, float csDamage, float armorRatio) {
		if (!armored(victim)) {
			return csDamage;
		}
		float armor = ARMOR.getOrDefault(victim.getUUID(), START_ARMOR);
		float toHealth = csDamage * armorRatio * 0.5f;
		float toArmor = (csDamage - toHealth) * ARMOR_BONUS;
		if (toArmor > armor) {
			toHealth = csDamage - armor / ARMOR_BONUS;
			toArmor = armor;
		}
		ARMOR.put(victim.getUUID(), armor - toArmor);
		syncArmor(victim);
		return toHealth;
	}

	/** The player was flashed (for the kill feed's blind-kill icon). */
	public static void flashed(ServerPlayer p, float seconds) {
		long now = p.level().getGameTime();
		BLIND_UNTIL.put(p.getUUID(), now + (long) Math.ceil(seconds * 20f));
		// the white-out: the first 45 % of the flash (as the flash overlay draws it)
		long white = now + (long) Math.ceil(seconds * 0.45f * 20f);
		WHITE_UNTIL.merge(p.getUUID(), white, Math::max);
	}

	private static final Map<UUID, Long> WHITE_UNTIL = new ConcurrentHashMap<>();

	/** Fully flashed: still in the flash's full white-out. */
	public static boolean fullyFlashed(LivingEntity e) {
		Long w = WHITE_UNTIL.get(e.getUUID());
		return w != null && e.level().getGameTime() < w;
	}

	/** Flags the attacker contributes to a kill notice: blind, in the air. */
	public static int attackerFlags(LivingEntity attacker) {
		int f = 0;
		Long blind = BLIND_UNTIL.get(attacker.getUUID());
		if (blind != null && attacker.level().getGameTime() < blind) {
			f |= KillFeedPayload.F_BLIND;
		}
		if (!attacker.onGround() && !attacker.isInWater() && !attacker.onClimbable()) {
			f |= KillFeedPayload.F_INAIR;
		}
		return f;
	}

	/** Remember a CS weapon hit on a victim (by kill feed icon name) for its death notice. */
	public static void markHit(Entity attacker, Entity victim, String weapon, int flags) {
		LAST_HIT.put(victim.getUUID(), new Hit(attacker == null ? null : attacker.getUUID(), weapon, flags, victim.level().getGameTime()));
	}

	/** Hit / kill feedback: to the attacker, the victim and everyone near the victim. */
	public static void hitSound(ServerLevel level, Entity attacker, Entity victim, boolean head, boolean lethal, boolean armored, boolean knife,
			Vec3 at) {
		int flags = (head ? HitSoundPayload.F_HEAD : 0) | (lethal ? HitSoundPayload.F_LETHAL : 0) | (armored ? HitSoundPayload.F_ARMOR : 0)
				| (knife ? HitSoundPayload.F_KNIFE : 0);
		HitSoundPayload p = new HitSoundPayload(attacker == null ? -1 : attacker.getId(), victim.getId(), flags, (float) at.x, (float) at.y,
				(float) at.z);
		java.util.Set<ServerPlayer> to = new java.util.HashSet<>(PlayerLookup.tracking(victim));
		if (attacker instanceof ServerPlayer sp) {
			to.add(sp);
		}
		if (victim instanceof ServerPlayer sp) {
			to.add(sp);
		}
		for (ServerPlayer sp : to) {
			if (ServerPlayNetworking.canSend(sp, HitSoundPayload.TYPE)) {
				ServerPlayNetworking.send(sp, p);
			}
		}
	}

	/** A living thing died: post a death notice if a player was involved (as victim or killer), or something blew it up. */
	public static void onDeath(LivingEntity victim, DamageSource source) {
		if (!(victim.level() instanceof ServerLevel level)) {
			return;
		}
		Entity killer = source.getEntity();
		Hit hit = LAST_HIT.remove(victim.getUUID());
		ARMOR.remove(victim.getUUID());
		HELMET.remove(victim.getUUID());
		syncArmor(victim);
		DEFUSER.remove(victim.getUUID());
		if (hit != null && level.getGameTime() - hit.tick() > HIT_MEMORY) {
			hit = null;
		}
		if (killer == null && hit != null && hit.attacker() != null) {
			killer = level.getEntity(hit.attacker());
		}
		boolean victimPlayer = victim instanceof Player;
		boolean killerPlayer = killer instanceof Player;
		if (victimPlayer && killer instanceof net.minecraft.server.level.ServerPlayer tk && killer != victim) {
			dev.countercraft.team.FriendlyFire.teamKill(tk);
		}
		if (victim instanceof net.minecraft.server.level.ServerPlayer vp) {
			// CS2's death cam / panel and ragdoll (the weapon as the kill feed would name it)
			Entity k = killer;
			String w = k == null || k == victim ? "" : hit != null && (hit.attacker() == null || hit.attacker().equals(k.getUUID())) ? hit.weapon() : "";
			int fl = hit != null ? hit.flags() : 0;
			DeathTracker.died(vp, source, k, w, fl);
		}
		// explosions (a creeper, TNT; not CS2's HE grenade, which has its own icon) show as "<who> [TNT] <victim>"
		boolean blast = source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) && !source.is(CounterCraft.GRENADE);
		if (!victimPlayer && !killerPlayer && !(blast && killer != null && killer != victim)) {
			return;
		}
		int flags = 0;
		String weapon;
		if (killer == null || killer == victim) {
			flags |= KillFeedPayload.F_SUICIDE;
			weapon = "";
		} else if (hit != null && (hit.attacker() == null || hit.attacker().equals(killer.getUUID()))) {
			weapon = hit.weapon();
			flags |= hit.flags();
		} else if (blast) {
			weapon = "item:minecraft:tnt";
		} else if (killer instanceof LivingEntity le && !le.getMainHandItem().isEmpty()) {
			weapon = "item:" + BuiltInRegistries.ITEM.getKey(le.getMainHandItem().getItem());
		} else {
			weapon = "";
		}
		if (killer instanceof net.minecraft.server.level.ServerPlayer sp && killer != victim) {
			dev.countercraft.team.Economy.onKill(sp, victim, weapon, hit != null ? hit.flags() : 0);
			dev.countercraft.Achievements.onKill(sp, victim, weapon, hit != null ? hit.flags() : 0);
			dev.countercraft.RunStats.onKill(sp, victim, weapon, hit != null ? hit.flags() : 0);
			// an airborne Scout / AWP kill: 5 s without fall damage
			if (hit != null && (hit.flags() & KillFeedPayload.F_INAIR) != 0 && (weapon.equals("ssg08") || weapon.equals("awp"))) {
				NO_FALL_UNTIL.put(sp.getUUID(), level.getGameTime() + NO_FALL_TICKS);
			}
		}
		String killerName = killer == null || killer == victim ? "" : killer.getDisplayName().getString();
		KillFeedPayload p = new KillFeedPayload(killerName, killerPlayer, killer == null ? -1 : killer.getId(), victim.getDisplayName().getString(),
				victimPlayer, victim.getId(), weapon, flags, faction(killer == victim ? null : killer), faction(victim));
		for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(sp, KillFeedPayload.TYPE)) {
				ServerPlayNetworking.send(sp, p);
			}
		}
	}

	/** CS2's kill feed icon for a knife item id. */
	public static String knifeIcon(String knife) {
		return switch (knife) {
			case "knife_ct" -> "knife";
			case "knife_navaja" -> "knife_gypsy_jackknife";
			case "knife_talon" -> "knife_widowmaker";
			case "knife_bowie" -> "knife_survival_bowie";
			default -> knife;
		};
	}

	/** Did a bullet from {@code from} to {@code to} pass through an active smoke cloud? */
	public static boolean throughSmoke(ServerLevel level, Vec3 from, Vec3 to) {
		var box = new net.minecraft.world.phys.AABB(from, to).inflate(dev.countercraft.gun.GrenadeEntity.SMOKE_RADIUS);
		for (dev.countercraft.gun.GrenadeEntity g : level.getEntitiesOfClass(dev.countercraft.gun.GrenadeEntity.class, box)) {
			if (g.state() != dev.countercraft.gun.GrenadeEntity.SMOKING) {
				continue;
			}
			Vec3 c = g.position().add(0, dev.countercraft.gun.GrenadeEntity.SMOKE_CENTER, 0);
			Vec3 d = to.subtract(from);
			double t = Math.max(0, Math.min(1, c.subtract(from).dot(d) / Math.max(1e-9, d.lengthSqr())));
			if (from.add(d.scale(t)).distanceTo(c) < dev.countercraft.gun.GrenadeEntity.SMOKE_RADIUS) {
				return true;
			}
		}
		return false;
	}

	/** How far (blocks) the segment runs through active smoke clouds (summed over clouds). */
	public static double smokeDepth(ServerLevel level, Vec3 from, Vec3 to) {
		var box = new net.minecraft.world.phys.AABB(from, to).inflate(dev.countercraft.gun.GrenadeEntity.SMOKE_RADIUS);
		double r = dev.countercraft.gun.GrenadeEntity.SMOKE_RADIUS;
		Vec3 d = to.subtract(from);
		double len = d.length();
		if (len < 1e-6) {
			return 0;
		}
		Vec3 u = d.scale(1 / len);
		double total = 0;
		for (dev.countercraft.gun.GrenadeEntity g : level.getEntitiesOfClass(dev.countercraft.gun.GrenadeEntity.class, box)) {
			if (g.state() != dev.countercraft.gun.GrenadeEntity.SMOKING) {
				continue;
			}
			Vec3 c = g.position().add(0, dev.countercraft.gun.GrenadeEntity.SMOKE_CENTER, 0);
			double t = c.subtract(from).dot(u);
			double perp2 = c.subtract(from).lengthSqr() - t * t;
			if (perp2 >= r * r) {
				continue;
			}
			double half = Math.sqrt(r * r - perp2);
			total += Math.max(0, Math.min(len, t + half) - Math.max(0, t - half));
		}
		return total;
	}

	public static void forget(UUID player) {
		BLIND_UNTIL.remove(player);
		WHITE_UNTIL.remove(player);
	}

	/** A player's faction for the kill feed ("t" / "ct"; "" without one, or for anything that isn't a player). */
	private static String faction(Entity e) {
		if (!(e instanceof Player p)) {
			return "";
		}
		String t = p.getAttached(dev.countercraft.team.Teams.TEAM);
		return t == null ? "" : t;
	}
}
