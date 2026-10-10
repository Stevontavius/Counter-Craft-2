package dev.countercraft.client.loadout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.countercraft.CounterCraft;
import dev.countercraft.team.Loadouts;
import dev.countercraft.team.Teams;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's CS2 loadout per team (client side, config/countercraft_loadout.json): which weapon sits in each slot
 * (secondary0 = starting pistol, secondary1-4, smg0-4, rifle0-4), plus the music kit. Starts as CS2's defaults
 * ({@link Loadouts#defaults}). The buy menu shows the current team's loadout.
 */
public final class LoadoutConfig {
	public static final String DEFAULT_MUSIC_KIT = "valve_cs2_01";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Map<String, String>> SLOTS = new LinkedHashMap<>();
	private static String musicKit = DEFAULT_MUSIC_KIT;
	/** Equipped finishes, knife, gloves and agent per team (sent to the server: {@link #sync}). */
	private static dev.countercraft.team.Cosmetics.Loadout cosmetics = dev.countercraft.team.Cosmetics.Loadout.DEFAULT;
	private static boolean loaded;

	private LoadoutConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_loadout.json");
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		for (String team : List.of(Teams.T, Teams.CT)) {
			SLOTS.put(team, new LinkedHashMap<>(Loadouts.defaults(team)));
		}
		try {
			Path f = file();
			if (Files.exists(f)) {
				JsonObject j = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
				for (String team : List.of(Teams.T, Teams.CT)) {
					if (j.has(team)) {
						JsonObject t = j.getAsJsonObject(team);
						for (String slot : t.keySet()) {
							String item = t.get(slot).getAsString();
							if (valid(team, slot, item)) {
								SLOTS.get(team).put(slot, item);
							}
						}
					}
				}
				if (j.has("musickit")) {
					musicKit = j.get("musickit").getAsString();
				}
				if (j.has("cosmetics")) {
					cosmetics = dev.countercraft.team.Cosmetics.Loadout.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, j.get("cosmetics"))
							.result().orElse(cosmetics);
				}
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] loadout unreadable: {}", e.toString());
		}
	}

	public static void save() {
		ensureLoaded();
		try {
			JsonObject j = new JsonObject();
			for (Map.Entry<String, Map<String, String>> t : SLOTS.entrySet()) {
				JsonObject o = new JsonObject();
				t.getValue().forEach(o::addProperty);
				j.add(t.getKey(), o);
			}
			j.addProperty("musickit", musicKit);
			dev.countercraft.team.Cosmetics.Loadout.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, cosmetics).result()
					.ifPresent(e -> j.add("cosmetics", e));
			Files.writeString(file(), GSON.toJson(j), StandardCharsets.UTF_8);
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] loadout not saved: {}", e.toString());
		}
	}

	/** The group of a slot name ("smg3" -> "smg", "secondary0" -> "secondary0", "secondary2" -> "secondary"). */
	public static String group(String slot) {
		if (slot.equals("secondary0")) {
			return "secondary0";
		}
		return slot.replaceAll("[0-9]+$", "");
	}

	private static boolean valid(String team, String slot, String item) {
		return Loadouts.options(team, group(slot)).contains(item);
	}

	/** The item in a slot, or null. */
	public static String get(String team, String slot) {
		ensureLoaded();
		return SLOTS.get(team).get(slot);
	}

	/**
	 * Put an item in a slot. Like CS2, a weapon can only be in a team's loadout once: if it already sits in another slot
	 * of the same group, the two swap.
	 */
	public static void set(String team, String slot, String item) {
		ensureLoaded();
		if (!valid(team, slot, item)) {
			return;
		}
		Map<String, String> m = SLOTS.get(team);
		String old = m.get(slot);
		for (Map.Entry<String, String> e : m.entrySet()) {
			if (!e.getKey().equals(slot) && item.equals(e.getValue())) {
				e.setValue(old);
			}
		}
		m.put(slot, item);
		save();
		if (slot.equals("secondary0")) {
			sync(); // the server hands out the starting pistol on spawn
		}
	}

	public static String musicKit() {
		ensureLoaded();
		return musicKit;
	}

	public static void setMusicKit(String kit) {
		ensureLoaded();
		musicKit = kit;
		save();
		sync(); // the server plays your kit's MVP anthem for everyone when you complete a challenge
	}

	public static dev.countercraft.team.Cosmetics.Loadout cosmetics() {
		ensureLoaded();
		return cosmetics;
	}

	public static dev.countercraft.team.Cosmetics.Side side(String team) {
		return cosmetics().side(team);
	}

	/** Change one team's side, save, and tell the server. */
	public static void setSide(String team, java.util.function.UnaryOperator<dev.countercraft.team.Cosmetics.Side> change) {
		ensureLoaded();
		dev.countercraft.team.Cosmetics.Side s = change.apply(cosmetics.side(team));
		cosmetics = Teams.CT.equals(team) ? new dev.countercraft.team.Cosmetics.Loadout(cosmetics.t(), s) : new dev.countercraft.team.Cosmetics.Loadout(s, cosmetics.ct());
		save();
		sync();
	}

	/** A weapon's finish (0 = none) on a team. */
	public static int skin(String team, String weaponId) {
		return side(team).skins().getOrDefault(weaponId, 0);
	}

	public static void setSkin(String team, String weaponId, int kit) {
		setSide(team, s -> {
			java.util.Map<String, Integer> m = new java.util.HashMap<>(s.skins());
			if (kit > 0) {
				m.put(weaponId, kit);
			} else {
				m.remove(weaponId);
			}
			return new dev.countercraft.team.Cosmetics.Side(m, s.knife(), s.knifeSkin(), s.gloves(), s.gloveSkin(), s.agent(), s.voice());
		});
	}

	/**
	 * Take off cosmetics that are no longer unlocked (after Reset Inventory, a new Copy Inventory or Skinchanger Mode off):
	 * like CS2, you can only equip what you own; those slots go back to CS2's defaults.
	 */
	public static void unequipUnowned() {
		ensureLoaded();
		for (String team : List.of(Teams.T, Teams.CT)) {
			setSide(team, s -> {
				java.util.Map<String, Integer> skins = new java.util.HashMap<>();
				s.skins().forEach((w, kit) -> {
					if (dev.countercraft.client.skins.SkinsConfig.ownsFinish(w, kit)) {
						skins.put(w, kit);
					}
				});
				String knife = s.knife();
				int knifeSkin = s.knifeSkin();
				if (!knife.isEmpty() && knifeSkin > 0 && !dev.countercraft.client.skins.SkinsConfig.ownsFinish(knife, knifeSkin)) {
					knifeSkin = 0;
				}
				if (!knife.isEmpty() && knifeSkin == 0 && !dev.countercraft.client.skins.SkinsConfig.owns("k:" + knife)) {
					knife = "";
				}
				String gloves = s.gloves();
				int gloveSkin = s.gloveSkin();
				if (!gloves.isEmpty() && !dev.countercraft.client.skins.SkinsConfig.owns("g:" + gloves + ":" + gloveSkin)) {
					gloves = "";
					gloveSkin = 0;
				}
				String agent = s.agent();
				String voice = s.voice();
				var a = agent.isEmpty() ? null : dev.countercraft.client.skins.Agents.byModel(agent, team);
				if (!agent.isEmpty() && a == null && !dev.countercraft.client.skins.Agents.forTeam(team).isEmpty()
						|| a != null && !(a.rarity() == null || "common".equals(a.rarity()) || dev.countercraft.client.skins.SkinsConfig.owns("a:" + a.id()))) {
					agent = ""; // not owned, or no longer offered (CS:GO's old default agents): the team default
					voice = "";
				} else if (a != null) {
					voice = a.voice(); // the agent's voice as the pack has it now (it can change: the generic FBI agents')
				}
				return new dev.countercraft.team.Cosmetics.Side(skins, knife, knifeSkin, gloves, gloveSkin, agent, voice);
			});
		}
		if (!musicKit.equals(DEFAULT_MUSIC_KIT) && !dev.countercraft.client.skins.SkinsConfig.owns("m:" + musicKit)) {
			setMusicKit(DEFAULT_MUSIC_KIT);
		}
	}

	/** Send the equipped cosmetics to the server (on join and whenever they change). */
	public static void sync() {
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.getConnection() != null) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.countercraft.net.CosmeticsPayload(cosmetics(),
					java.util.Objects.requireNonNullElse(get(Teams.T, "secondary0"), ""), java.util.Objects.requireNonNullElse(get(Teams.CT, "secondary0"), ""),
					dev.countercraft.client.skins.SkinsConfig.noGenuine(), musicKit()));
		}
	}
}
