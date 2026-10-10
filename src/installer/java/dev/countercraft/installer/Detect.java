package dev.countercraft.installer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finding Counter-Strike 2 / CS:GO (Steam libraries) and the Minecraft launchers on this PC. */
final class Detect {
	static final int CS2_APP = 730;
	static final int CSGO_APP = 4465480;

	/** A found install: its folder and a short note ("Steam build 22100934"). */
	record Found(Path path, String note) {
	}

	private Detect() {
	}

	static String env(String name, String fallback) {
		String v = System.getenv(name);
		return v == null || v.isEmpty() ? fallback : v;
	}

	/** Steam's folder: the registry's SteamPath, else the default install. */
	static Path steam() {
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			String out;
			try (InputStream in = p.getInputStream()) {
				out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
			if (m.find()) {
				return Path.of(m.group(1).trim());
			}
		} catch (Exception ignored) {
			// default below
		}
		return Path.of(env("ProgramFiles(x86)", "C:\\Program Files (x86)"), "Steam");
	}

	/** Every Steam library folder (libraryfolders.vdf's "path" entries, and Steam's own). */
	static List<Path> libraries() {
		Set<Path> out = new LinkedHashSet<>();
		Path steam = steam();
		out.add(steam);
		try {
			String vdf = Files.readString(steam.resolve("steamapps/libraryfolders.vdf"));
			Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(vdf);
			while (m.find()) {
				out.add(Path.of(m.group(1).replace("\\\\", "\\")));
			}
		} catch (Exception ignored) {
			// just Steam's own
		}
		return new ArrayList<>(out);
	}

	/** An app's install folder from its appmanifest, if {@code check} (relative to it) exists there. */
	static Found app(int appId, String check) {
		for (Path lib : libraries()) {
			Path acf = lib.resolve("steamapps/appmanifest_" + appId + ".acf");
			try {
				String text = Files.readString(acf);
				Matcher dir = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"").matcher(text);
				Matcher build = Pattern.compile("\"buildid\"\\s+\"(\\d+)\"").matcher(text);
				if (dir.find()) {
					Path p = lib.resolve("steamapps/common").resolve(dir.group(1));
					if (Files.exists(p.resolve(check))) {
						return new Found(p, build.find() ? "Steam build " + build.group(1) : "Steam");
					}
				}
			} catch (Exception ignored) {
				// next library
			}
		}
		return null;
	}

	static Found cs2() {
		return app(CS2_APP, "game/csgo/pak01_dir.vpk");
	}

	static Found csgo() {
		return app(CSGO_APP, "csgo/pak01_dir.vpk");
	}

	/** Is this folder a CS2 install (the one with game/)? */
	static boolean isCs2(Path p) {
		return p != null && Files.exists(p.resolve("game/csgo/pak01_dir.vpk"));
	}

	static boolean isCsgo(Path p) {
		return p != null && Files.exists(p.resolve("csgo/pak01_dir.vpk"));
	}

	// ---- Minecraft launchers

	static Path dotMinecraft() {
		return Path.of(env("APPDATA", System.getProperty("user.home")), ".minecraft");
	}

	/** The official launcher's profile files that exist (Windows 10/11 or legacy launcher; Microsoft Store / Xbox app). */
	static List<Path> launcherProfiles() {
		List<Path> out = new ArrayList<>();
		for (String f : new String[] {"launcher_profiles.json", "launcher_profiles_microsoft_store.json"}) {
			Path p = dotMinecraft().resolve(f);
			if (Files.isRegularFile(p)) {
				out.add(p);
			}
		}
		return out;
	}

	/** Which official launcher(s) this PC has, for the label; null if none. */
	static String officialLauncher() {
		List<String> kinds = new ArrayList<>();
		String pf86 = env("ProgramFiles(x86)", "C:\\Program Files (x86)");
		if (Files.exists(Path.of(pf86, "Minecraft Launcher", "MinecraftLauncher.exe"))
				|| Files.exists(Path.of(env("LOCALAPPDATA", ""), "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe"))
				|| Files.exists(Path.of("C:\\XboxGames\\Minecraft Launcher\\Content\\Minecraft.exe"))
				|| Files.exists(dotMinecraft().resolve("launcher_profiles_microsoft_store.json"))) {
			kinds.add("Windows 10/11");
		}
		if (Files.exists(Path.of(pf86, "Minecraft", "MinecraftLauncher.exe")) || Files.exists(Path.of(pf86, "Minecraft", "Minecraft.exe"))) {
			kinds.add("Legacy");
		}
		if (kinds.isEmpty() && !launcherProfiles().isEmpty()) {
			kinds.add("Minecraft Launcher");
		}
		return kinds.isEmpty() ? null : String.join(" + ", kinds);
	}

	/** Is the official launcher running (it rewrites its profiles when it closes)? */
	static boolean launcherRunning() {
		return ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(c -> {
			String n = Path.of(c).getFileName().toString().toLowerCase();
			return n.equals("minecraftlauncher.exe") || n.equals("minecraft.exe");
		}).orElse(false));
	}

	/** A Prism / MultiMC root from what the user picked: the root, its instances folder, or an instance. */
	static Path launcherRoot(Path picked) {
		if (picked == null) {
			return null;
		}
		for (Path p = picked; p != null; p = p.getParent()) {
			if (Files.isDirectory(p.resolve("instances"))) {
				return p;
			}
		}
		return null;
	}
}
