package dev.countercraft.client.crosshair;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.countercraft.CounterCraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * CS2's crosshair settings (the 2025 crosshair update: 10 styles, pixel sizes at a reference screen height, RGBA
 * colours for crosshair and outline). Names, ranges and defaults are CS2's own (settings_crosshair.xml and
 * `help <cvar>` in CS2's console). Saved to config/countercraft_crosshair.json; every cvar is also a chat command
 * (e.g. {@code /cl_crosshair_gap 2}). Unknown default: cl_crosshairstyle (CS2 prints none) -> Static Cross.
 */
public final class CrosshairSettings {
	/** One cvar: name, CS2 default, min, max, integer-valued. */
	public record Cvar(String name, float dflt, float min, float max, boolean integer) {
	}

	public static final Map<String, Cvar> CVARS = new LinkedHashMap<>();

	static {
		add("cl_crosshairstyle", 4, 0, 9, true);
		add("cl_crosshair_thickness", 2, 0, 32, true);
		add("cl_crosshair_length", 8, 0, 255, true);
		add("cl_crosshair_gap", 4, -3840, 3840, true); // negative gaps: CS2 30/09/2026 update (+-3840, a 16-bit field in codes)
		add("cl_crosshairdot", 0, 0, 1, true);
		add("cl_crosshair_t", 0, 0, 1, true);
		add("cl_crosshair_drawoutline", 1, 0, 2, true);
		add("cl_crosshaircolor_r", 0, 0, 255, true);
		add("cl_crosshaircolor_g", 255, 0, 255, true);
		add("cl_crosshaircolor_b", 0, 0, 255, true);
		add("cl_crosshaircolor_a", 255, 0, 255, true);
		add("cl_crosshairoutline_r", 0, 0, 255, true);
		add("cl_crosshairoutline_g", 0, 0, 255, true);
		add("cl_crosshairoutline_b", 0, 0, 255, true);
		add("cl_crosshairoutline_a", 255, 0, 255, true);
		add("cl_crosshair_dynamic_spread_limit", 255, 0, 255, true);
		add("cl_crosshair_dynamic_splitdist", 3, 0, 127, true);
		add("cl_crosshair_dynamic_splitalpha_innermod", 0, 0, 1, false);
		add("cl_crosshair_dynamic_splitalpha_outermod", 1, 0.3f, 1, false);
		add("cl_crosshair_dynamic_maxdist_splitratio", 1, 0, 1, false);
		add("cl_crosshair_recoil", 1, 0, 1, true);
		add("cl_crosshair_screen_height", 1080, 240, 4320, true);
		add("cl_crosshair_sniper_width", 1, 1, 6, true);
	}

	private static final Map<String, Float> VALUES = new LinkedHashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Bumped on every change (the renderer caches by it). */
	public static int version;

	private CrosshairSettings() {
	}

	private static void add(String name, float dflt, float min, float max, boolean integer) {
		CVARS.put(name, new Cvar(name, dflt, min, max, integer));
	}

	public static float get(String name) {
		Float v = VALUES.get(name);
		return v != null ? v : CVARS.get(name).dflt();
	}

	public static int i(String name) {
		return Math.round(get(name));
	}

	public static void set(String name, float value) {
		Cvar c = CVARS.get(name);
		if (c == null) {
			return;
		}
		float v = Math.max(c.min(), Math.min(c.max(), value));
		VALUES.put(name, c.integer() ? Math.round(v) : v);
		version++;
	}

	public static void resetAll() {
		VALUES.clear();
		version++;
	}

	public static Map<String, Float> snapshot() {
		Map<String, Float> m = new LinkedHashMap<>();
		for (String n : CVARS.keySet()) {
			m.put(n, get(n));
		}
		return m;
	}

	public static void restore(Map<String, Float> m) {
		VALUES.clear();
		VALUES.putAll(m);
		version++;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_crosshair.json");
	}

	public static void load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				JsonObject j = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
				for (String n : CVARS.keySet()) {
					if (j.has(n)) {
						set(n, j.get(n).getAsFloat());
					}
				}
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] crosshair settings unreadable: {}", e.toString());
		}
	}

	public static void save() {
		try {
			JsonObject j = new JsonObject();
			snapshot().forEach(j::addProperty);
			Files.writeString(file(), GSON.toJson(j), StandardCharsets.UTF_8);
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] crosshair settings not saved: {}", e.toString());
		}
	}

	/**
	 * Import the crosshair from the user's own CS2 config (Steam userdata .../730/local/cfg/cs2_user_convars_0_slot0
	 * .vcfg, read only). Returns how many cvars were read, or -1 if no CS2 config was found.
	 */
	public static int importFromCs2() {
		Path cfg = findCs2File("cs2_user_convars_0_slot0.vcfg");
		if (cfg == null) {
			return -1;
		}
		try {
			String text = Files.readString(cfg, StandardCharsets.UTF_8);
			Matcher m = Pattern.compile("\"(cl_crosshair[a-z_]*)\"\\s+\"([^\"]*)\"").matcher(text);
			int n = 0;
			while (m.find()) {
				if (!CVARS.containsKey(m.group(1))) {
					continue;
				}
				String v = m.group(2);
				float f = "true".equals(v) ? 1f : "false".equals(v) ? 0f : Float.parseFloat(v);
				set(m.group(1), f);
				n++;
			}
			save();
			return n;
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] CS2 crosshair import failed: {}", e.toString());
			return -1;
		}
	}

	/** The newest CS2 config file of this name (.../730/local/cfg/) under any Steam userdata folder on the usual Steam locations. */
	public static Path findCs2File(String name) {
		Path best = null;
		long newest = 0;
		for (String root : new String[] {"C:/Program Files (x86)/Steam", "C:/Program Files/Steam", "D:/Steam", "E:/Steam",
				"D:/SteamLibrary", System.getProperty("user.home") + "/.steam/steam"}) {
			Path users = Path.of(root, "userdata");
			if (!Files.isDirectory(users)) {
				continue;
			}
			try (var dirs = Files.list(users)) {
				for (Path u : (Iterable<Path>) dirs::iterator) {
					Path f = u.resolve("730/local/cfg/cs2_user_convars_0_slot0.vcfg");
					if (Files.exists(f) && Files.getLastModifiedTime(f).toMillis() > newest) {
						newest = Files.getLastModifiedTime(f).toMillis();
						best = f;
					}
				}
			} catch (Exception ignored) {
				// unreadable folder: skip
			}
		}
		return best;
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		for (Cvar c : CVARS.values()) {
			d.register(ClientCommands.literal(c.name())
					.executes(ctx -> {
						ctx.getSource().sendFeedback(Component.literal(c.name() + " = " + fmt(get(c.name())) + " (default " + fmt(c.dflt()) + ")"));
						return 1;
					})
					.then(ClientCommands.argument("value", FloatArgumentType.floatArg()).executes(ctx -> {
						set(c.name(), FloatArgumentType.getFloat(ctx, "value"));
						save();
						return 1;
					})));
		}
		d.register(ClientCommands.literal("cs_crosshair").executes(ctx -> {
			CrosshairScreen.openLater();
			return 1;
		}));
	}

	static String fmt(float v) {
		return v == Math.round(v) ? Integer.toString(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.2f", v);
	}
}
