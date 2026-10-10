package dev.countercraft.client.skins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.countercraft.CounterCraft;
import dev.countercraft.ModComponents;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.gun.CsWeapon;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * CS2 weapon / knife finishes on the client (skins/index.json from converter/skins.py): what each paint kit is called,
 * its rarity, which model it goes on (CS2 body_hd or the CS:GO body_legacy mesh) and its baked texture; and the render
 * side: the parts to draw for a skinned weapon with the skinned material's texture swapped.
 */
public final class Skins {
	/** One finish on one weapon. */
	public record Skin(int id, String item, String kit, String name, String rarity, boolean legacy, String material, String collection,
			boolean thumb) {
		public Identifier texture() {
			return CounterCraft.id("textures/skins/" + item + "/" + kit + ".png");
		}

		public Identifier thumbnail() {
			return CounterCraft.id("textures/gui/skins/" + item + "/" + kit + ".png");
		}

		/** "AK-47 | Redline" style, with the weapon's display name. */
		public String fullName(String weaponName) {
			return weaponName + " | " + name;
		}
	}

	/** A rarity's display name and colour (catalog rarities, CS2 desc_* colours). */
	public record Rarity(String name, int color) {
	}

	/** A glove finish (skins/gloves.json). */
	public record Glove(int id, String type, String kit, String name, String rarity, boolean thumb) {
		public Identifier texture() {
			return CounterCraft.id("textures/skins/gloves/" + type + "/" + kit + ".png");
		}

		public Identifier thumbnail() {
			return CounterCraft.id("textures/gui/skins/gloves/" + type + "/" + kit + ".png");
		}
	}

	private static Map<String, Map<Integer, Skin>> byItem;
	private static Map<String, Map<Integer, Glove>> gloves;
	/** Glove type -> display name ("Sport Gloves") and its glove materials. */
	private static final Map<String, String> GLOVE_NAMES = new LinkedHashMap<>();
	private static final Map<String, List<String>> GLOVE_MATS = new HashMap<>();
	private static Map<String, Rarity> rarities = new HashMap<>();
	private static final Map<String, Optional<ViewmodelAsset.Part[]>> LEGACY = new HashMap<>();
	private static final Map<ViewmodelAsset.Part, Map<Identifier, ViewmodelAsset.Part>> RETEX = new HashMap<>();

	private Skins() {
	}

	public static void clear() {
		byItem = null;
		gloves = null;
		LEGACY.clear();
		RETEX.clear();
	}

	private static void load() {
		if (byItem != null) {
			return;
		}
		byItem = new HashMap<>();
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("skins/index.json"))) {
			JsonObject j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement e : j.getAsJsonArray("skins")) {
				JsonObject o = e.getAsJsonObject();
				Skin s = new Skin(o.get("id").getAsInt(), o.get("item").getAsString(), o.get("kit").getAsString(), o.get("name").getAsString(),
						str(o, "rarity"), o.get("legacy").getAsBoolean(), o.get("material").getAsString(), str(o, "collection"),
						o.has("thumb") && o.get("thumb").getAsBoolean());
				byItem.computeIfAbsent(s.item(), k -> new LinkedHashMap<>()).put(s.id(), s);
			}
			for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("rarities").entrySet()) {
				JsonObject r = e.getValue().getAsJsonObject();
				String hex = r.get("color").getAsString().replace("#", "");
				rarities.put(e.getKey(), new Rarity(r.get("name").getAsString(), 0xFF000000 | Integer.parseInt(hex, 16)));
			}
			CounterCraft.LOG.info("[countercraft] skins: {} finishes on {} weapons", byItem.values().stream().mapToInt(Map::size).sum(), byItem.size());
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] no skins index ({}): run converter --step skins and enable the CS2 asset pack", e.toString());
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	private static void loadGloves() {
		if (gloves != null) {
			return;
		}
		gloves = new LinkedHashMap<>();
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("skins/gloves.json"))) {
			JsonObject j = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("types").entrySet()) {
				JsonObject t = e.getValue().getAsJsonObject();
				GLOVE_NAMES.put(e.getKey(), t.get("name").getAsString());
				List<String> m = new ArrayList<>();
				t.getAsJsonArray("materials").forEach(x -> m.add(x.getAsString()));
				GLOVE_MATS.put(e.getKey(), m);
			}
			for (JsonElement e : j.getAsJsonArray("skins")) {
				JsonObject o = e.getAsJsonObject();
				Glove gl = new Glove(o.get("id").getAsInt(), o.get("type").getAsString(), o.get("kit").getAsString(), o.get("name").getAsString(),
						str(o, "rarity"), o.has("thumb") && o.get("thumb").getAsBoolean());
				gloves.computeIfAbsent(gl.type(), k -> new LinkedHashMap<>()).put(gl.id(), gl);
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] no glove list ({}): run converter --step gloves", e.toString());
		}
	}

	/** Glove type -> display name. */
	public static Map<String, String> gloveTypes() {
		loadGloves();
		return GLOVE_NAMES;
	}

	public static List<Glove> gloves(String type) {
		loadGloves();
		Map<Integer, Glove> g = gloves.get(type);
		return g == null ? List.of() : new ArrayList<>(g.values());
	}

	public static Glove glove(String type, int id) {
		loadGloves();
		Map<Integer, Glove> g = gloves.get(type);
		return g == null ? null : g.get(id);
	}

	private static final Map<String, Optional<ViewmodelAsset.Part[]>> GLOVE_PARTS = new HashMap<>();

	/**
	 * First-person arms with gloves: the glove model's parts (its finish on the glove materials) plus the agent's
	 * sleeves (the agent arms' "_sleeves_" parts). Null if the gloves aren't in the pack.
	 */
	public static ViewmodelAsset.Part[] armsWithGloves(ViewmodelAsset.Part[] agentArms, Glove glove) {
		ViewmodelAsset.Part[] parts = GLOVE_PARTS.computeIfAbsent(glove.type(), t -> {
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("viewmodels/gloves_" + t + ".cva"))) {
				return Optional.of(ViewmodelAsset.readArms(in));
			} catch (Exception e) {
				return Optional.empty();
			}
		}).orElse(null);
		if (parts == null) {
			return null;
		}
		List<ViewmodelAsset.Part> out = new ArrayList<>();
		List<String> mats = GLOVE_MATS.getOrDefault(glove.type(), List.of());
		for (ViewmodelAsset.Part p : parts) {
			boolean glovePart = mats.stream().anyMatch(m -> p.texture().getPath().endsWith("_" + m + ".png"));
			out.add(glovePart ? RETEX.computeIfAbsent(p, k -> new HashMap<>()).computeIfAbsent(glove.texture(),
					t -> new ViewmodelAsset.Part(t, p.vertexCount(), p.pos(), p.nrm(), p.uv(), p.joints(), p.weights(), p.indices())) : p);
		}
		if (agentArms != null) {
			for (ViewmodelAsset.Part p : agentArms) {
				if (p.texture().getPath().contains("_sleeves_")) {
					out.add(p);
				}
			}
		}
		return out.toArray(new ViewmodelAsset.Part[0]);
	}

	/** Every finish for a weapon / knife (mod id: "ak47", "knife_karambit"), in catalog order. */
	public static List<Skin> forItem(String item) {
		load();
		Map<Integer, Skin> m = byItem.get(item);
		return m == null ? List.of() : new ArrayList<>(m.values());
	}

	public static Skin get(String item, int id) {
		load();
		Map<Integer, Skin> m = byItem.get(item);
		return m == null ? null : m.get(id);
	}

	public static Map<String, Map<Integer, Skin>> all() {
		load();
		return byItem;
	}

	public static Rarity rarity(String key) {
		load();
		return key == null ? null : rarities.get(key);
	}

	/** The finish on a stack, or null. */
	public static Skin of(ItemStack stack) {
		Integer id = stack.get(ModComponents.SKIN);
		if (id == null || id == 0 || !(stack.getItem() instanceof CsWeapon w)) {
			return null;
		}
		return get(w.weaponId(), id);
	}

	/** The CS:GO-model mesh of a weapon (viewmodels/&lt;id&gt;_legacy.cva), or null. */
	public static ViewmodelAsset.Part[] legacyParts(String gun) {
		return LEGACY.computeIfAbsent(gun, g -> {
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(CounterCraft.id("viewmodels/" + g + "_legacy.cva"))) {
				return Optional.of(ViewmodelAsset.readArms(in));
			} catch (Exception e) {
				return Optional.empty();
			}
		}).orElse(null);
	}

	/**
	 * The gun's parts (not the arms) to draw with {@code skin}: the legacy mesh's parts for a CS:GO-model finish, the
	 * skinned material's texture swapped for the finish. Null skin: the asset's own gun parts.
	 */
	public static List<ViewmodelAsset.Part> gunParts(ViewmodelAsset asset, String gun, Skin skin) {
		List<ViewmodelAsset.Part> out = new ArrayList<>();
		ViewmodelAsset.Part[] legacy = skin != null && skin.legacy() ? legacyParts(gun) : null;
		if (legacy != null) {
			for (ViewmodelAsset.Part p : legacy) {
				out.add(retex(p, gun, skin));
			}
			return out;
		}
		for (ViewmodelAsset.Part p : asset.parts) {
			if (!ViewmodelAsset.isArms(p)) {
				out.add(skin == null ? p : retex(p, gun, skin));
			}
		}
		return out;
	}

	private static ViewmodelAsset.Part retex(ViewmodelAsset.Part p, String gun, Skin skin) {
		if (!p.texture().getPath().equals("textures/viewmodel/" + gun + "_" + skin.material() + ".png")) {
			return p;
		}
		Identifier tex = skin.texture();
		return RETEX.computeIfAbsent(p, k -> new HashMap<>()).computeIfAbsent(tex,
				t -> new ViewmodelAsset.Part(t, p.vertexCount(), p.pos(), p.nrm(), p.uv(), p.joints(), p.weights(), p.indices()));
	}

}
