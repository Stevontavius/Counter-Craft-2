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
 * Mobs pay a flat bounty whatever the weapon: hostile mobs {@link #MOB_AWARD}, the Wither {@link #WITHER_AWARD}, the Ender
 * Dragon and the Warden {@link #BOSS_AWARD}. In Counter-Craft (hardcore co-op) killing any player costs
 * {@link #HARDCORE_KILL_PENALTY}.
 */
public final class Economy {
	/** Knife kill award (weapons.vdata knife prefab). */
	private static final int KNIFE_AWARD = 1500;
	/** HE / molotov / incendiary kill award. */
	private static final int GRENADE_AWARD = 300;
	/** Mob bounties (any weapon). */
	public static final int MOB_AWARD = 15;
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

	/** A player killed something with {@code weapon}: CS2's kill award, or the teammate penalty. */
	public static void onKill(ServerPlayer killer, LivingEntity victim, String weapon) {
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
		} else if (victim instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
			award = WITHER_AWARD;
			why = "for killing the Wither";
		} else if (victim instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon) {
			award = BOSS_AWARD;
			why = "for killing the Ender Dragon";
		} else if (victim instanceof net.minecraft.world.entity.monster.warden.Warden) {
			award = BOSS_AWARD;
			why = "for killing the Warden";
		} else if (victim instanceof Enemy) {
			award = MOB_AWARD;
			why = "for killing a hostile mob";
		} else {
			return;
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
