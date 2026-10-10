package dev.countercraft.client.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.countercraft.CounterCraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Which cosmetics the player may equip (config/countercraft_skins.json). Skinchanger mode unlocks everything locally;
 * otherwise only CS2's defaults and what "Copy Inventory" took from a Steam inventory are unlocked. Keys:
 * {@code w:<weapon or knife>:<paint kit id>} a finish, {@code k:<knife>} a knife (vanilla finish), {@code g:<gloves>:<paint kit id>}
 * gloves, {@code a:<agent id>} an agent, {@code m:<music kit>} a music kit.
 */
public final class SkinsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static boolean loaded;
	private static boolean skinchanger;
	private static final Set<String> OWNED = new LinkedHashSet<>();
	/** The profile the owned items came from (shown in Skins Options). */
	private static String source = "";
	/** The SteamID64 the owned items came from ("" = unknown: copied before this was recorded). */
	private static String sourceId = "";

	private SkinsConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_skins.json");
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			if (Files.exists(file())) {
				JsonObject j = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
				skinchanger = j.has("skinchanger") && j.get("skinchanger").getAsBoolean();
				source = j.has("source") ? j.get("source").getAsString() : "";
				sourceId = j.has("source_id") ? j.get("source_id").getAsString() : "";
				if (j.has("owned")) {
					j.getAsJsonArray("owned").forEach(e -> OWNED.add(e.getAsString()));
				}
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] skins config unreadable: {}", e.toString());
		}
	}

	private static void save() {
		try {
			JsonObject j = new JsonObject();
			j.addProperty("skinchanger", skinchanger);
			j.addProperty("source", source);
			j.addProperty("source_id", sourceId);
			JsonArray a = new JsonArray();
			OWNED.forEach(a::add);
			j.add("owned", a);
			Files.writeString(file(), GSON.toJson(j), StandardCharsets.UTF_8);
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] skins config not saved: {}", e.toString());
		}
	}

	public static boolean skinchanger() {
		load();
		return skinchanger;
	}

	public static void setSkinchanger(boolean on) {
		load();
		skinchanger = on;
		save();
		dev.countercraft.client.loadout.LoadoutConfig.sync(); // the server drops (or may restore) the Genuine Skins tick
	}

	public static String source() {
		load();
		return source;
	}

	public static int ownedCount() {
		load();
		return OWNED.size();
	}

	/** Replace the owned items with a copied inventory (from the account {@code fromId}, a SteamID64). */
	public static void setOwned(Collection<String> keys, String from, String fromId) {
		load();
		OWNED.clear();
		OWNED.addAll(keys);
		source = from;
		sourceId = fromId == null ? "" : fromId;
		save();
	}

	/**
	 * The unlocked items aren't the linked account's own: copied from another account (or before the source was
	 * recorded). Like Skinchanger Mode, this turns the Genuine Skins tick off.
	 */
	public static boolean foreignInventory() {
		load();
		if (OWNED.isEmpty()) {
			return false;
		}
		String linked = dev.countercraft.client.steam.SteamLinkClient.steamId();
		return sourceId.isEmpty() || !sourceId.equals(linked);
	}

	/** No Genuine Skins tick: Skinchanger Mode is on, or the unlocked inventory is someone else's. */
	public static boolean noGenuine() {
		return skinchanger() || foreignInventory();
	}

	/** Reset Inventory: forget the copied inventory (only CS2's defaults stay unlocked, unless Skinchanger Mode is on). */
	public static void resetOwned() {
		load();
		OWNED.clear();
		source = "";
		sourceId = "";
		save();
	}

	public static boolean owns(String key) {
		load();
		return skinchanger || OWNED.contains(key);
	}

	public static boolean ownsFinish(String item, int kit) {
		return owns("w:" + item + ":" + kit);
	}
}
