package dev.countercraft.team;

import com.mojang.serialization.Codec;
import dev.countercraft.CounterCraft;
import dev.countercraft.ServerCvars;
import dev.countercraft.gun.GunItem;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * CS2's cash: every player has money (mp_startmoney to start, capped at mp_maxmoney) that the buy menu spends, and
 * earns CS2's kill award of the weapon used (weapons.vdata m_nKillAward: $300 most guns, $600 SMGs, $900 shotguns,
 * $100 AWP / Zeus, $1500 knives, $300 grenades) for enemy players; killing a teammate costs $300 like CS2. Creative mode buys for free. Money is
 * per player and per world, kept through death; a future round / co-op mode can call {@link #add} for its round rewards.
 * Mobs pay a bounty whatever the weapon ({@link #mobAward}): each hostile mob its own ({@link #MOB_AWARDS}, any other
 * {@link #MOB_AWARD}), plus bonuses for how it died; the Wither {@link #WITHER_AWARD}, the Ender Dragon and the Warden
 * {@link #BOSS_AWARD}, to everyone who fought them ({@link dev.countercraft.BossFights}). In Counter-Craft (hardcore
 * co-op) killing any player costs {@link #HARDCORE_KILL_PENALTY}.
 */
public final class Economy {
	/** Knife kill award (weapons.vdata knife prefab). */
	private static final int KNIFE_AWARD = 1500;
	/** HE / molotov / incendiary kill award. */
	private static final int GRENADE_AWARD = 300;
	/** A hostile mob not in {@link #MOB_AWARDS}. */
	public static final int MOB_AWARD = 15;
	/** Hostile mobs' bounties (Counter-Craft's own, any weapon). */
	private static final java.util.Map<net.minecraft.world.entity.EntityType<?>, Integer> MOB_AWARDS = java.util.Map.ofEntries(
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.CREEPER, 25),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.ZOMBIE, 10),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.HUSK, 10),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.DROWNED, 10),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.ZOMBIE_VILLAGER, 10),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.SKELETON, 30),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.STRAY, 30),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.PARCHED, 30),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.BOGGED, 30),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.ENDERMAN, 50),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.WITHER_SKELETON, 25),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.PILLAGER, 35),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.EVOKER, 100),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.RAVAGER, 150),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.GHAST, 75),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.BLAZE, 40),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.WITCH, 50),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.VINDICATOR, 40),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.SPIDER, 10),
			java.util.Map.entry(net.minecraft.world.entity.EntityTypes.CAVE_SPIDER, 10));
	/** On top of a mob's bounty, by the kill feed's flags: the killer in the air, a headshot, through a wall, blinded. */
	private static final int AIRBORNE_BONUS = 10;
	private static final int HEADSHOT_BONUS = 5;
	private static final int WALLBANG_BONUS = 10;
	private static final int BLIND_BONUS = 25;
	/** A mob from a monster spawner pays a quarter (-75%), bonuses included; an enderman in the End a flat $5. */
	private static final float SPAWNER_SHARE = 0.25f;
	private static final int END_ENDERMAN_AWARD = 5;
	/** A mob a monster spawner made (set as it spawns, kept with it). */
	public static final AttachmentType<Boolean> FROM_SPAWNER = AttachmentRegistry.<Boolean>builder()
			.persistent(Codec.BOOL)
			.buildAndRegister(CounterCraft.id("from_spawner"));
	public static final int WITHER_AWARD = 1500;
	public static final int BOSS_AWARD = 2500;
	/** Counter-Craft (hardcore co-op): killing any player costs this, and players resist other players' damage 3x. */
	public static final int HARDCORE_KILL_PENALTY = 500;
	public static final float HARDCORE_PVP_RESISTANCE = 3f;
	/** CS2's teammate kill penalty. */
	private static final int TEAMKILL_PENALTY = 300;

	public static final AttachmentType<Integer> MONEY = AttachmentRegistry.<Integer>builder()
			.persistent(Codec.INT)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("money"));

	private Economy() {
	}

	/** Load the class at startup: {@link #MONEY} must be registered before anyone joins. */
	public static void init() {
	}

	/** First join in this world: mp_startmoney. */
	public static void join(ServerPlayer p) {
		if (!p.hasAttached(MONEY)) {
			p.setAttached(MONEY, Math.min(ServerCvars.get("mp_startmoney"), ServerCvars.get("mp_maxmoney")));
			Loadouts.firstSpawn(p); // first spawn in this world: the starting pistol once the client sent its loadout
		}
	}

	public static int money(Player p) {
		Integer m = p.getAttached(MONEY);
		return m != null ? m : ServerCvars.get("mp_startmoney");
	}

	/** Creative mode buys for free; survival and adventure pay. */
	public static boolean freeBuy(Player p) {
		return p.isCreative();
	}

	public static boolean canAfford(Player p, int price) {
		return freeBuy(p) || money(p) >= price;
	}

	/** Pay for a purchase (nothing in creative). */
	public static void spend(ServerPlayer p, int price) {
		if (!freeBuy(p)) {
			dev.countercraft.RunStats.spent(p, Math.min(price, money(p)));
			p.setAttached(MONEY, Math.max(0, money(p) - price));
		}
	}

	/** A refund (CS2's sell back): the money comes back and the run's spending goes down by it; it isn't earnings. */
	public static void refund(ServerPlayer p, int amount) {
		if (freeBuy(p) || amount <= 0) {
			return;
		}
		int before = money(p);
		set(p, before + amount);
		dev.countercraft.RunStats.unspent(p, money(p) - before);
	}

	/** Add (or with a negative amount take) money, within 0 .. mp_maxmoney. */
	public static void add(ServerPlayer p, int amount) {
		int before = money(p);
		p.setAttached(MONEY, Math.max(0, Math.min(ServerCvars.get("mp_maxmoney"), before + amount)));
		dev.countercraft.RunStats.earned(p, money(p) - before);
	}

	public static void set(ServerPlayer p, int amount) {
		p.setAttached(MONEY, Math.max(0, Math.min(ServerCvars.get("mp_maxmoney"), amount)));
	}

	/** CS2's kill award for the kill feed's weapon id (a gun id, its icon variant, a knife, a grenade), or 0. */
	public static int killAward(String weapon) {
		if (weapon == null || weapon.isEmpty() || weapon.startsWith("item:")) {
			return 0;
		}
		if (weapon.startsWith("knife") || weapon.equals("bayonet")) {
			return KNIFE_AWARD;
		}
		if (weapon.equals("hegrenade") || weapon.equals("inferno") || weapon.equals("molotov") || weapon.equals("incgrenade")) {
			return GRENADE_AWARD;
		}
		GunItem gun = CounterCraft.GUNS.get(weapon);
		for (String id = weapon; gun == null && id.contains("_"); ) {
			id = id.substring(0, id.lastIndexOf('_')); // icon variants: "usp_silencer_off" -> "usp_silencer"
			gun = CounterCraft.GUNS.get(id);
		}
		return gun != null ? gun.spec().killAward() : 0;
	}

	/**
	 * A hostile mob's bounty for a kill with these kill feed {@code flags}, and what it's for ("" if nothing): its own
	 * bounty plus the bonuses, a quarter of that from a spawner; an enderman in the End pays a flat
	 * {@link #END_ENDERMAN_AWARD}. 0 for anything that isn't hostile.
	 */
	static int mobAward(LivingEntity victim, int flags, StringBuilder why) {
		String name = victim.getType().getDescription().getString();
		if (victim.getType() == net.minecraft.world.entity.EntityTypes.ENDERMAN && victim.level().dimension() == net.minecraft.world.level.Level.END) {
			why.append("for killing an ").append(name).append(" in the End");
			return END_ENDERMAN_AWARD;
		}
		Integer base = MOB_AWARDS.get(victim.getType());
		if (base == null) {
			if (!(victim instanceof Enemy)) {
				return 0;
			}
			base = MOB_AWARD;
		}
		int award = base;
		java.util.List<String> extra = new java.util.ArrayList<>();
		if ((flags & dev.countercraft.net.KillFeedPayload.F_HEADSHOT) != 0) {
			award += HEADSHOT_BONUS;
			extra.add("headshot +$" + HEADSHOT_BONUS);
		}
		if ((flags & dev.countercraft.net.KillFeedPayload.F_INAIR) != 0) {
			award += AIRBORNE_BONUS;
			extra.add("airborne +$" + AIRBORNE_BONUS);
		}
		if ((flags & dev.countercraft.net.KillFeedPayload.F_PENETRATE) != 0) {
			award += WALLBANG_BONUS;
			extra.add("wallbang +$" + WALLBANG_BONUS);
		}
		if ((flags & dev.countercraft.net.KillFeedPayload.F_BLIND) != 0) {
			award += BLIND_BONUS;
			extra.add("blind +$" + BLIND_BONUS);
		}
		boolean spawner = Boolean.TRUE.equals(victim.getAttached(FROM_SPAWNER));
		if (spawner) {
			award = Math.round(award * SPAWNER_SHARE);
			extra.add("from a spawner: -75%");
		}
		why.append("for killing ").append(name.matches("(?i)^[aeiou].*") ? "an " : "a ").append(name);
		if (!extra.isEmpty()) {
			why.append(" (").append(String.join(", ", extra)).append(")");
		}
		return award;
	}

	/** A player killed something with {@code weapon}: CS2's kill award, or the teammate penalty; a mob's bounty. */
	public static void onKill(ServerPlayer killer, LivingEntity victim, String weapon, int flags) {
		if (killer == victim || freeBuy(killer)) {
			return;
		}
		int award;
		String why;
		if (victim instanceof Player && killer.level().getLevelData().isHardcore()) {
			award = -HARDCORE_KILL_PENALTY; // co-op: every player is on your side
			why = "for killing a player";
		} else if (victim instanceof Player vp) {
			boolean bothPicked = vp.hasAttached(Teams.TEAM) && killer.hasAttached(Teams.TEAM);
			if (bothPicked && Teams.of(vp).equals(Teams.of(killer))) {
				award = -TEAMKILL_PENALTY;
				why = "for killing a teammate";
			} else {
				award = killAward(weapon);
				why = "for neutralizing an enemy";
			}
		} else if (dev.countercraft.BossFights.bounty(victim) > 0) {
			return; // everyone who fought it is paid (BossFights)
		} else {
			StringBuilder w = new StringBuilder();
			award = mobAward(victim, flags, w);
			why = w.toString();
		}
		if (award == 0) {
			return;
		}
		int before = money(killer);
		add(killer, award);
		int got = money(killer) - before;
		if (got != 0) {
			// CS2's chat line ("+$300: Award for neutralizing an enemy.")
			killer.sendSystemMessage(Component.literal((got > 0 ? "+$" + got + ": Award " : "-$" + -got + ": Penalty ") + why + ".")
					.withColor(got > 0 ? 0x6FB86F : 0xE05050), true);
		}
	}
}
