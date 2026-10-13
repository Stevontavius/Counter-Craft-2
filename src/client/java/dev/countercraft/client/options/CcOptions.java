package dev.countercraft.client.options;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.countercraft.CounterCraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Counter-Craft 2 Options (config/countercraft_options.json): which music plays, CS2's music volume cvars, the
 * challenge MVP anthem, the kill feed and the first-person legs. Volumes keep CS2's own convar values, which CS2's
 * sliders store squared (a slider at 50 % is 0.25); {@link #slider} / {@link #fromSlider} convert.
 */
public final class CcOptions {
	/** Which music plays: Minecraft's, the CS2 music kit's, both (CS2 kit in menus, Minecraft's in the world) or none. */
	public enum MusicMode {
		BOTH("Minecraft + CS2 Music Kits"), CS2("CS2 Music Kits Only"), MINECRAFT("Minecraft Music Only"), NONE("No Music");

		public final String label;

		MusicMode(String label) {
			this.label = label;
		}
	}

	/** The HUD paperdoll above the vitals (survival / adventure). */
	public enum Paperdoll {
		DISABLED("Disabled"), MINECRAFT("Minecraft Skin"), AGENT("Equipped Agent Skin");

		public final String label;

		Paperdoll(String label) {
			this.label = label;
		}
	}

	/** Video > Texture Quality: the largest side Counter-Craft's own textures load at (0: as they are in the pack). */
	public enum TextureQuality {
		LOW("Low (512 px)", 512), MEDIUM("Medium (1024 px)", 1024), HIGH("High (2048 px)", 2048), ULTRA("Ultra (original)", 0);

		public final String label;
		public final int maxSize;

		TextureQuality(String label, int maxSize) {
			this.label = label;
			this.maxSize = maxSize;
		}
	}

	/** Video > Anti-Aliasing. */
	public enum AntiAliasing {
		OFF("Off"), FXAA("FXAA"), TAA("TAA");

		public final String label;

		AntiAliasing(String label) {
			this.label = label;
		}
	}

	/** Whose weapon sounds play: CS2's, or CS:GO's when the asset pack has them (CsgoSounds). */
	public enum WeaponSoundStyle {
		CS2("Counter-Strike 2", "Counter-Strike 2"), CSGO("Counter-Strike: Global Offensive", "CS: Global Offensive");

		public final String label;
		/** What fits on the options button. */
		public final String button;

		WeaponSoundStyle(String label, String button) {
			this.label = label;
			this.button = button;
		}
	}

	/** CS2's music volume cvars (settings Audio > Music) and their labels, in CS2's order. */
	public static final Map<String, String> VOLUMES = new LinkedHashMap<>();

	static {
		VOLUMES.put("snd_musicvolume", "Master Music Volume");
		VOLUMES.put("snd_menumusic_volume", "Main Menu Volume");
		VOLUMES.put("snd_roundstart_volume", "Round Start Volume");
		VOLUMES.put("snd_roundaction_volume", "Round Action Volume");
		VOLUMES.put("snd_roundend_volume", "Round End Volume");
		VOLUMES.put("snd_mvp_volume", "MVP Volume");
		VOLUMES.put("snd_mapobjective_volume", "Bomb / Hostage Volume");
		VOLUMES.put("snd_tensecondwarning_volume", "Ten Second Warning Volume");
		VOLUMES.put("snd_deathcamera_volume", "Death Camera Volume");
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Float> VOLUME = new LinkedHashMap<>();
	private static boolean loaded;
	private static MusicMode musicMode = MusicMode.BOTH;
	private static boolean challengeMvp = true;
	private static boolean killFeed = true;
	private static boolean compass = true;
	private static WeaponSoundStyle weaponSoundStyle = WeaponSoundStyle.CS2;
	/** Printstream's pearlescent sheen, x CS2's own strength (0: off, 1: as CS2, 2: double). */
	private static float pearlescence = 1f;
	private static boolean gpuAgents = true;
	private static boolean firstPersonLegs = true;
	private static boolean viewmodelShaders = true;
	private static boolean agentShaders = true;
	private static boolean gpuEnvironment = true;
	/** GPU Environment Rendering as the game started with it (it needs a restart to change). */
	private static boolean gpuEnvironmentAtStart;
	private static AntiAliasing antiAliasing = AntiAliasing.OFF;
	private static TextureQuality textureQuality = TextureQuality.HIGH;
	private static Paperdoll paperdoll = Paperdoll.DISABLED;
	private static boolean hotbarRarity;

	private CcOptions() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft_options.json");
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			if (Files.exists(file())) {
				JsonObject j = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
				if (j.has("music")) {
					musicMode = MusicMode.valueOf(j.get("music").getAsString());
				}
				challengeMvp = !j.has("challenge_mvp") || j.get("challenge_mvp").getAsBoolean();
				killFeed = !j.has("kill_feed") || j.get("kill_feed").getAsBoolean();
				compass = !j.has("compass") || j.get("compass").getAsBoolean();
				hotbarRarity = j.has("hotbar_rarity") && j.get("hotbar_rarity").getAsBoolean();
				gpuAgents = !j.has("gpu_agents") || j.get("gpu_agents").getAsBoolean();
				firstPersonLegs = !j.has("first_person_legs") || j.get("first_person_legs").getAsBoolean();
				viewmodelShaders = !j.has("viewmodel_shaders") || j.get("viewmodel_shaders").getAsBoolean();
				agentShaders = !j.has("agent_shaders") || j.get("agent_shaders").getAsBoolean();
				gpuEnvironment = !j.has("gpu_environment") || j.get("gpu_environment").getAsBoolean();
				if (j.has("pearlescence")) {
					pearlescence = Math.max(0f, Math.min(2f, j.get("pearlescence").getAsFloat()));
				}
				if (j.has("weapon_sound_style")) {
					weaponSoundStyle = WeaponSoundStyle.valueOf(j.get("weapon_sound_style").getAsString());
				}
				if (j.has("anti_aliasing")) {
					antiAliasing = AntiAliasing.valueOf(j.get("anti_aliasing").getAsString());
				}
				if (j.has("texture_quality")) {
					textureQuality = TextureQuality.valueOf(j.get("texture_quality").getAsString());
				}
				if (j.has("paperdoll")) {
					paperdoll = Paperdoll.valueOf(j.get("paperdoll").getAsString());
				}
				for (String v : VOLUMES.keySet()) {
					if (j.has(v)) {
						VOLUME.put(v, Math.max(0f, Math.min(1f, j.get(v).getAsFloat())));
					}
				}
			}
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] options unreadable: {}", e.toString());
		}
		gpuEnvironmentAtStart = gpuEnvironment;
	}

	public static void save() {
		try {
			JsonObject j = new JsonObject();
			j.addProperty("music", musicMode.name());
			j.addProperty("challenge_mvp", challengeMvp);
			j.addProperty("kill_feed", killFeed);
			j.addProperty("compass", compass);
			j.addProperty("hotbar_rarity", hotbarRarity);
			j.addProperty("gpu_agents", gpuAgents);
			j.addProperty("first_person_legs", firstPersonLegs);
			j.addProperty("viewmodel_shaders", viewmodelShaders);
			j.addProperty("agent_shaders", agentShaders);
			j.addProperty("gpu_environment", gpuEnvironment);
			j.addProperty("weapon_sound_style", weaponSoundStyle.name());
			j.addProperty("pearlescence", pearlescence);
			j.addProperty("anti_aliasing", antiAliasing.name());
			j.addProperty("texture_quality", textureQuality.name());
			j.addProperty("paperdoll", paperdoll.name());
			for (String v : VOLUMES.keySet()) {
				j.addProperty(v, volume(v));
			}
			Files.writeString(file(), GSON.toJson(j), StandardCharsets.UTF_8);
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] options not saved: {}", e.toString());
		}
	}

	public static MusicMode musicMode() {
		load();
		return musicMode;
	}

	public static void setMusicMode(MusicMode m) {
		load();
		musicMode = m;
	}

	/** CS2 music kits may play (menus, MVP). */
	public static boolean kitMusic() {
		return musicMode() == MusicMode.BOTH || musicMode() == MusicMode.CS2;
	}

	/** Minecraft's own music may play. */
	public static boolean minecraftMusic() {
		return musicMode() == MusicMode.BOTH || musicMode() == MusicMode.MINECRAFT;
	}

	public static boolean challengeMvp() {
		load();
		return challengeMvp;
	}

	public static void setChallengeMvp(boolean on) {
		load();
		challengeMvp = on;
	}

	public static boolean killFeed() {
		load();
		return killFeed;
	}

	public static void setKillFeed(boolean on) {
		load();
		killFeed = on;
	}

	public static boolean gpuAgents() {
		load();
		return gpuAgents;
	}

	public static void setGpuAgents(boolean on) {
		load();
		gpuAgents = on;
	}

	public static boolean compass() {
		load();
		return compass;
	}

	public static void setCompass(boolean on) {
		load();
		compass = on;
	}

	/** The CS hotbar underlines each weapon's icon in its finish's rarity colour (off by default, as CS2). */
	public static boolean hotbarRarity() {
		load();
		return hotbarRarity;
	}

	public static void setHotbarRarity(boolean on) {
		load();
		hotbarRarity = on;
	}

	public static Paperdoll paperdoll() {
		load();
		return paperdoll;
	}

	public static void setPaperdoll(Paperdoll p) {
		load();
		paperdoll = p;
	}

	public static boolean firstPersonLegs() {
		load();
		return firstPersonLegs;
	}

	public static void setFirstPersonLegs(boolean on) {
		load();
		firstPersonLegs = on;
	}

	/** The viewmodel lit the CS:GO way (agent_vm.fsh); off: plain Minecraft shading, same textures. */
	public static boolean viewmodelShaders() {
		load();
		return viewmodelShaders;
	}

	public static void setViewmodelShaders(boolean on) {
		load();
		viewmodelShaders = on;
	}

	/** Agents (players, ragdolls, menu previews) and the guns they hold lit like the viewmodel; off: plain shading. */
	public static boolean agentShaders() {
		load();
		return agentShaders;
	}

	public static void setAgentShaders(boolean on) {
		load();
		agentShaders = on;
	}

	/** GPU Environment Rendering as set (applies after a restart; see {@link #gpuEnvironmentActive}). */
	public static boolean gpuEnvironment() {
		load();
		return gpuEnvironment;
	}

	public static void setGpuEnvironment(boolean on) {
		load();
		gpuEnvironment = on;
	}

	/** Whether mobs draw on the GPU this session: the setting the game started with. */
	public static boolean gpuEnvironmentActive() {
		load();
		return gpuEnvironmentAtStart;
	}

	/** Anti-aliasing as chosen (it only runs where {@code AntiAliasingPass.available()} allows it). */
	/** The style in effect: CS:GO only while the asset pack has CS:GO's weapon sounds. */
	public static WeaponSoundStyle weaponSoundStyle() {
		load();
		if (dev.countercraft.client.CsgoSounds.exclusive()) {
			return WeaponSoundStyle.CSGO;
		}
		return dev.countercraft.client.CsgoSounds.available() ? weaponSoundStyle : WeaponSoundStyle.CS2;
	}

	public static void setWeaponSoundStyle(WeaponSoundStyle s) {
		load();
		weaponSoundStyle = s;
	}

	public static float pearlescence() {
		load();
		return pearlescence;
	}

	public static void setPearlescence(float v) {
		load();
		pearlescence = Math.max(0f, Math.min(2f, v));
	}

	public static AntiAliasing antiAliasing() {
		load();
		return antiAliasing;
	}

	public static void setAntiAliasing(AntiAliasing aa) {
		load();
		antiAliasing = aa;
	}

	public static TextureQuality textureQuality() {
		load();
		return textureQuality;
	}

	public static void setTextureQuality(TextureQuality q) {
		load();
		textureQuality = q;
	}

	/** A CS2 music volume cvar's value (0-1, CS2's squared scale; full volume until changed). */
	public static float volume(String cvar) {
		load();
		return VOLUME.getOrDefault(cvar, 1f);
	}

	public static void setVolume(String cvar, float v) {
		load();
		VOLUME.put(cvar, Math.max(0f, Math.min(1f, v)));
	}

	/** Gain for one kind of music track: that track's volume times the master music volume. */
	public static float gain(String cvar) {
		return volume("snd_musicvolume") * volume(cvar);
	}

	/** CS2 slider position (0-1) of a volume value. */
	public static double slider(float value) {
		return Math.sqrt(value);
	}

	public static float fromSlider(double pos) {
		return (float) (pos * pos);
	}

	/**
	 * Read the music volumes from the user's own CS2 config (cs2_machine_convars.vcfg, read only; the competitive value,
	 * "name$4"). Returns how many were found, or -1 without a CS2 config.
	 */
	public static int importVolumesFromCs2() {
		Path cfg = dev.countercraft.client.crosshair.CrosshairSettings.findCs2File("cs2_machine_convars.vcfg");
		if (cfg == null) {
			return -1;
		}
		try {
			String text = Files.readString(cfg, StandardCharsets.UTF_8);
			int n = 0;
			for (String v : VOLUMES.keySet()) {
				Matcher m = Pattern.compile("\"" + Pattern.quote(v) + "(?:\\$\\d+)?\"\\s+\"([0-9.eE+-]+)\"").matcher(text);
				if (m.find()) {
					setVolume(v, Float.parseFloat(m.group(1)));
					n++;
				}
			}
			save();
			return n;
		} catch (Exception e) {
			CounterCraft.LOG.warn("[countercraft] CS2 music volume import failed: {}", e.toString());
			return -1;
		}
	}
}
