package dev.countercraft.team;

import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GunItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CS2's loadout rules, from items_game.txt (the user's install): each weapon's flexible_loadout_group (secondary0 =
 * starting pistol, secondary = other pistols, smg = mid-tier incl. shotguns and machine guns, rifle = rifles and
 * snipers), its team (used_by_classes) and whether CS2 equips it by default (flexible_loadout_default). A team's
 * loadout has 1 starting pistol, 4 other pistols, 5 mid-tier and 5 rifles; equipment and grenades are fixed.
 * Default slots are ordered by price (CS2's own order isn't in the item data: approximation).
 */
public final class Loadouts {
	/** One weapon CS2 lets a team put in a loadout group. */
	public record Choice(String item, String group, Set<String> teams, boolean byDefault) {
	}

	/** One buyable item that isn't a loadout weapon (equipment, grenades). */
	public record Fixed(String item, int price, Set<String> teams) {
	}

	private static final Set<String> BOTH = Set.of(Teams.T, Teams.CT);
	private static final Set<String> T = Set.of(Teams.T);
	private static final Set<String> CT = Set.of(Teams.CT);

	public static final List<Choice> CHOICES = List.of(
			new Choice("weapon_glock", "secondary0", T, true),
			new Choice("weapon_hkp2000", "secondary0", CT, true),
			new Choice("weapon_usp_silencer", "secondary0", CT, false),
			new Choice("weapon_elite", "secondary", BOTH, true),
			new Choice("weapon_p250", "secondary", BOTH, true),
			new Choice("weapon_tec9", "secondary", T, true),
			new Choice("weapon_fiveseven", "secondary", CT, true),
			new Choice("weapon_cz75a", "secondary", BOTH, false),
			new Choice("weapon_deagle", "secondary", BOTH, true),
			new Choice("weapon_revolver", "secondary", BOTH, false),
			new Choice("weapon_nova", "smg", BOTH, true),
			new Choice("weapon_xm1014", "smg", BOTH, true),
			new Choice("weapon_sawedoff", "smg", T, false),
			new Choice("weapon_mag7", "smg", CT, false),
			new Choice("weapon_m249", "smg", BOTH, false),
			new Choice("weapon_negev", "smg", BOTH, false),
			new Choice("weapon_mac10", "smg", T, true),
			new Choice("weapon_mp9", "smg", CT, true),
			new Choice("weapon_mp7", "smg", BOTH, false),
			new Choice("weapon_mp5sd", "smg", BOTH, true),
			new Choice("weapon_ump45", "smg", BOTH, false),
			new Choice("weapon_p90", "smg", BOTH, true),
			new Choice("weapon_bizon", "smg", BOTH, false),
			new Choice("weapon_galilar", "rifle", T, true),
			new Choice("weapon_famas", "rifle", CT, true),
			new Choice("weapon_ak47", "rifle", T, true),
			new Choice("weapon_m4a1", "rifle", CT, false),
			new Choice("weapon_m4a1_silencer", "rifle", CT, true),
			new Choice("weapon_ssg08", "rifle", BOTH, true),
			new Choice("weapon_sg556", "rifle", T, true),
			new Choice("weapon_aug", "rifle", CT, true),
			new Choice("weapon_awp", "rifle", BOTH, true),
			new Choice("weapon_g3sg1", "rifle", T, false),
			new Choice("weapon_scar20", "rifle", CT, false));

	/** CS2's buy menu equipment column (prices: CS2 2026). */
	public static final List<Fixed> EQUIPMENT = List.of(
			new Fixed("item_kevlar", 650, BOTH),
			new Fixed("item_assaultsuit", 1000, BOTH),
			new Fixed("weapon_taser", 200, BOTH));
	// the defuse kit (item_defuser, $400, CT) is left out until there's a bomb to defuse

	/** Spare magazines (CS 1.6 style, not in CS2; prices are the mod's own): 3 magazines for the primary / pistol. */
	public static final List<Fixed> AMMO = List.of(
			new Fixed(ExtraAmmo.PRIMARY_ITEM, 100, BOTH),
			new Fixed(ExtraAmmo.SECONDARY_ITEM, 50, BOTH));

	/** Kevlar + Helmet when you already have full kevlar: only the helmet is bought (CS2). */
	public static final int HELMET_PRICE = 350;

	/** Grenades in the buy menu's order (HE, flash, smoke, molotov / incendiary, decoy). */
	public static final List<Fixed> GRENADES = List.of(
			new Fixed("weapon_hegrenade", 300, BOTH),
			new Fixed("weapon_flashbang", 200, BOTH),
			new Fixed("weapon_smokegrenade", 300, BOTH),
			new Fixed("weapon_molotov", 400, T),
			new Fixed("weapon_incgrenade", 500, CT),
			new Fixed("weapon_decoy", 50, BOTH));

	/** Slot names and how many of each a team's loadout has. */
	public static final Map<String, Integer> SLOTS = new LinkedHashMap<>();

	static {
		SLOTS.put("secondary0", 1);
		SLOTS.put("secondary", 4);
		SLOTS.put("smg", 5);
		SLOTS.put("rifle", 5);
	}

	private Loadouts() {
	}

	/** The mod's id for a CS2 item name ("weapon_ak47" -> "ak47"). */
	public static String modId(String item) {
		return item.startsWith("weapon_") ? item.substring(7) : item;
	}

	public static int price(String item) {
		GunItem gun = CounterCraft.GUNS.get(modId(item));
		if (gun != null) {
			return gun.spec().price();
		}
		for (Fixed f : EQUIPMENT) {
			if (f.item().equals(item)) {
				return f.price();
			}
		}
		for (Fixed f : GRENADES) {
			if (f.item().equals(item)) {
				return f.price();
			}
		}
		for (Fixed f : AMMO) {
			if (f.item().equals(item)) {
				return f.price();
			}
		}
		return 0;
	}

	/** Weapons a team may put in a group ("secondary0", "secondary", "smg", "rifle"), cheapest first. */
	public static List<String> options(String team, String group) {
		List<String> out = new ArrayList<>();
		for (Choice c : CHOICES) {
			if (c.group().equals(group) && c.teams().contains(team)) {
				out.add(c.item());
			}
		}
		out.sort(Comparator.comparingInt(Loadouts::price));
		return out;
	}

	/** CS2's default loadout for a team: slot ("secondary0", "secondary1".."secondary4", "smg0".., "rifle0"..) -> item. */
	public static Map<String, String> defaults(String team) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String group : SLOTS.keySet()) {
			List<String> picks = new ArrayList<>();
			for (String item : options(team, group)) {
				if (CHOICES.stream().anyMatch(c -> c.item().equals(item) && c.byDefault())) {
					picks.add(item);
				}
			}
			int first = group.equals("secondary") ? 1 : 0;
			for (int i = 0; i < SLOTS.get(group) && i < picks.size(); i++) {
				out.put(group + (group.equals("secondary0") ? "" : Integer.toString(first + i)), picks.get(i));
			}
		}
		return out;
	}

	/** Each team's starting pistol as the player's loadout has it (team -> weapon item), sent by the client. */
	public static final net.fabricmc.fabric.api.attachment.v1.AttachmentType<Map<String, String>> START_PISTOL =
			net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.<Map<String, String>>builder()
					.persistent(com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.STRING))
					.copyOnDeath()
					.buildAndRegister(dev.countercraft.CounterCraft.id("start_pistol"));

	/** The player's starting pistol for their team (their loadout's, else CS2's default), as a weapon item id. */
	public static String startingPistol(net.minecraft.world.entity.player.Player p) {
		String team = Teams.of(p);
		Map<String, String> chosen = p.getAttached(START_PISTOL);
		String item = chosen == null ? null : chosen.get(team);
		if (item == null || !options(team, "secondary0").contains(item)) {
			item = defaults(team).get("secondary0");
		}
		return item;
	}

	private static final Set<java.util.UUID> FIRST_SPAWN = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Loads the class at mod init ({@link #START_PISTOL} must be registered before anyone joins). */
	public static void init() {
	}

	/** A player's first spawn in this world: they get their pistol when their loadout arrives ({@link #loadoutArrived}). */
	public static void firstSpawn(net.minecraft.server.level.ServerPlayer p) {
		FIRST_SPAWN.add(p.getUUID());
	}

	public static void loadoutArrived(net.minecraft.server.level.ServerPlayer p) {
		if (FIRST_SPAWN.remove(p.getUUID())) {
			giveStartingPistol(p);
		}
	}

	/**
	 * Spawning (first join, every respawn): like a CS2 round start, the player gets their starting pistol if their
	 * pistol slot is empty. Not in Counter-Craft (hardcore co-op), where everything is earned.
	 */
	public static void giveStartingPistol(net.minecraft.server.level.ServerPlayer p) {
		if (p.level().getLevelData().isHardcore() || p.isSpectator() || !CsInventory.of(p).get(CsInventory.SECONDARY).isEmpty()) {
			return;
		}
		String item = startingPistol(p);
		dev.countercraft.gun.GunItem gun = item == null ? null : dev.countercraft.CounterCraft.GUNS.get(modId(item));
		if (gun != null) {
			CsInventory.give(p, Buying.gunStack(p, gun));
		}
	}

	/** Whether a team may buy this item at all (the buy menu only offers its loadout, but the server checks). */
	public static boolean allowed(String team, String item) {
		for (Choice c : CHOICES) {
			if (c.item().equals(item)) {
				return c.teams().contains(team);
			}
		}
		for (Fixed f : EQUIPMENT) {
			if (f.item().equals(item)) {
				return f.teams().contains(team);
			}
		}
		for (Fixed f : GRENADES) {
			if (f.item().equals(item)) {
				return f.teams().contains(team);
			}
		}
		for (Fixed f : AMMO) {
			if (f.item().equals(item)) {
				return f.teams().contains(team);
			}
		}
		return false;
	}
}
