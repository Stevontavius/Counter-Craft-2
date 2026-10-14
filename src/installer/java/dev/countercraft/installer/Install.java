package dev.countercraft.installer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Putting Counter-Craft 2 where it's played: the mod and Fabric API (bundled in the installer), the asset pack and
 * options.txt turning the pack on, all in Counter-Craft 2's own game folder. Targets: the official Minecraft Launcher (a
 * version file for Fabric built from Fabric Loader's own library list, and a profile with its own game folder and the
 * CC2 icon), or a Prism Launcher / MultiMC instance. Each install is marked by RECORD in its game folder, so an update
 * finds it again; anything else is never touched: a new install gets a free name, and the launcher's profiles file is
 * backed up first.
 * <p>
 * The pack is copied by its manifest (cc2-manifest.json, written by build_pack.py) when both sides have one: only files
 * whose hash changed are copied, files the new manifest no longer lists are removed, and nothing else is. Each file is
 * copied to a temporary name and moved into place; the manifest goes last, and UPDATING marks a copy in progress, so an
 * interrupted copy is redone (by size and date) the next time.
 */
final class Install {
	static final String RELEASE = "BETA 1.2.2";
	static final String PACK = "CounterCraft-CS2-Assets";
	static final String MC = "26.3";
	static final String LOADER = "0.19.5";
	static final String NAME = "Counter-Craft 2";
	static final String MANIFEST = "cc2-manifest.json";
	static final String RECORD = "cc2-install.json";
	/** The weapon table in the pack (made from the player's own CS2); the mod reads its copy in config/countercraft. */
	static final String WEAPONS = "assets/countercraft/weapons.json";
	private static final String UPDATING = ".cc2-updating";
	private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";

	/** A Counter-Craft 2 install the installer made: its game folder and its launcher's name for it. */
	record Found(Path gameDir, String name) {
	}

	private Install() {
	}

	// ---- the bundled files

	/** The installer's own jar (it carries the mod, Fabric API and Fabric Loader's library list under bundle/). */
	static Path self() {
		try {
			return Path.of(Install.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The jars under bundle/mods/ in the installer: name -> bytes. */
	static Map<String, byte[]> bundledMods() throws IOException {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipFile z = new ZipFile(self().toFile())) {
			for (ZipEntry e : java.util.Collections.list(z.entries())) {
				if (e.getName().startsWith("bundle/mods/") && e.getName().endsWith(".jar")) {
					try (InputStream in = z.getInputStream(e)) {
						out.put(e.getName().substring("bundle/mods/".length()), in.readAllBytes());
					}
				}
			}
		}
		return out;
	}

	static String bundled(String name) throws IOException {
		try (InputStream in = Install.class.getResourceAsStream("/bundle/" + name)) {
			if (in == null) {
				throw new IOException("the installer is missing bundle/" + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** A text file inside one of the bundled jars (a license), or null. */
	static String inBundledJar(String jarPrefix, String entry) {
		try {
			for (Map.Entry<String, byte[]> j : bundledMods().entrySet()) {
				if (j.getKey().startsWith(jarPrefix)) {
					try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(j.getValue()))) {
						for (ZipEntry e; (e = z.getNextEntry()) != null;) {
							if (e.getName().equals(entry)) {
								return new String(z.readAllBytes(), StandardCharsets.UTF_8);
							}
						}
					}
				}
			}
		} catch (IOException ignored) {
			// not there
		}
		return null;
	}

	// ---- an asset pack that's already built

	/** What the mod needs from a pack (as build_pack.py's validate); empty: it's complete. */
	static List<String> problems(Path pack) {
		List<String> out = new ArrayList<>();
		if (pack == null || !Files.isDirectory(pack)) {
			return List.of("no pack there");
		}
		for (String f : new String[] {"pack.mcmeta", "assets/countercraft/sounds.json", WEAPONS, "assets/countercraft/agents/agents.json",
				"assets/countercraft/agents/index.json"}) {
			if (!Files.isRegularFile(pack.resolve(f))) {
				out.add("missing " + f);
			}
		}
		try (var s = Files.list(pack.resolve("assets/countercraft/viewmodels"))) {
			if (s.noneMatch(p -> p.toString().endsWith(".cvm"))) {
				out.add("no viewmodels");
			}
		} catch (IOException e) {
			out.add("no viewmodels");
		}
		if (Files.exists(pack.resolve(".cc2-build.json"))) {
			out.add("its build didn't finish");
		}
		return out;
	}

	/** Weapon sounds in a built pack: 0 CS2's, 1 CS2's and CS:GO's, 2 CS:GO's only. */
	static int audio(Path pack) {
		try {
			return Json.object(Files.readString(pack.resolve("assets/countercraft/csgo/weapon_sounds.json"))).get("exclusive") == Boolean.TRUE ? 2 : 1;
		} catch (Exception e) {
			return 0;
		}
	}

	/** A pack's manifest, or null (built before manifests, or unreadable). */
	static Map<String, Object> manifest(Path pack) {
		try {
			return Json.object(Files.readString(pack.resolve(MANIFEST)));
		} catch (Exception e) {
			return null;
		}
	}

	// ---- what a release changes (for updates)

	/** Steps a pack may leave out (by its options), so a pack without them isn't out of date. */
	private static final List<String> OPTIONAL_STEPS = List.of("legacy", "skins", "gloves", "musickits", "csgo");
	private static final java.util.regex.Pattern STEP = java.util.regex.Pattern.compile("^\\s*\"(\\w+)\":\\s*\\(\"[^\"]*\",\\s*(\\d+)",
			java.util.regex.Pattern.MULTILINE);

	/** This release's conversion version of each asset pack step: STEPS in the converter's build_pack.py (step id -> version). */
	static Map<String, Integer> stepVersions(Path converter) throws IOException {
		String py = Files.readString(converter.resolve("build_pack.py"));
		int a = py.indexOf("STEPS = {");
		int b = a < 0 ? -1 : py.indexOf("\n}", a);
		Map<String, Integer> out = new LinkedHashMap<>();
		if (b > a) {
			java.util.regex.Matcher m = STEP.matcher(py.substring(a, b));
			while (m.find()) {
				out.put(m.group(1), Integer.parseInt(m.group(2)));
			}
		}
		if (out.isEmpty()) {
			throw new IOException("can't read the asset pack's steps from " + converter.resolve("build_pack.py"));
		}
		return out;
	}

	/**
	 * Does this release change an installed asset pack? Empty: no, it's current (only the mod and its loader need updating);
	 * otherwise why it has to be built again: a step's conversion version changed, or the pack has no manifest to tell.
	 */
	static String packChange(Path gameDir, Map<String, Integer> steps) {
		Map<String, Object> m = manifest(gameDir.resolve("resourcepacks").resolve(PACK));
		if (m == null) {
			return "its asset pack was built before updates existed (it has no manifest)";
		}
		Map<?, ?> have = m.get("steps") instanceof Map<?, ?> s ? s : Map.of();
		List<String> changed = new ArrayList<>();
		for (Map.Entry<String, Integer> e : steps.entrySet()) {
			if (have.get(e.getKey()) instanceof Map<?, ?> h) {
				if (!(h.get("version") instanceof Double v) || v.intValue() != e.getValue()) {
					changed.add(e.getKey());
				}
			} else if (!OPTIONAL_STEPS.contains(e.getKey())) {
				changed.add(e.getKey()); // a step this release added, which every pack has
			}
		}
		return changed.isEmpty() ? "" : "this release changed its asset pack (" + String.join(", ", changed) + ")";
	}

	/** The release an install was made or last updated with (its record), or null. */
	static String releaseOf(Path gameDir) {
		try {
			return (String) Json.object(Files.readString(gameDir.resolve(RECORD))).get("release");
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Bring an install to this release when its asset pack is already current ({@link #packChange} empty): the bundled mod
	 * and Fabric API into mods/, the launcher's Minecraft and Fabric Loader versions, the weapon table from its pack, and
	 * its record. The pack, worlds and settings aren't touched.
	 */
	static void modUpdate(Path dir, Consumer<String> log) throws IOException {
		Path record = dir.resolve(RECORD);
		Map<String, Object> r = Json.object(Files.readString(record));
		List<String> jars = mods(dir.resolve("mods"), record, log);
		Object launcher = r.get("launcher");
		if ("Minecraft Launcher".equals(launcher)) {
			officialVersion(dir, log);
		} else if (Files.isRegularFile(dir.getParent().resolve("mmc-pack.json"))) {
			instanceVersion(dir.getParent().resolve("mmc-pack.json"), log);
		}
		Path table = dir.resolve("config/countercraft/weapons.json");
		Files.createDirectories(table.getParent());
		copy(dir.resolve("resourcepacks").resolve(PACK).resolve(WEAPONS), table);
		log.accept("  config/countercraft/weapons.json: the weapon table, from the installed pack");
		r.put("release", RELEASE);
		r.put("updated", Instant.now().toString());
		r.put("mods", jars);
		Files.writeString(record, Json.write(r));
	}

	/** The Minecraft Launcher: this release's Fabric version file, and the install's profiles set to it. */
	private static void officialVersion(Path game, Consumer<String> log) throws IOException {
		String id = "fabric-loader-" + LOADER + "-" + MC;
		Path vdir = Detect.dotMinecraft().resolve("versions").resolve(id);
		Files.createDirectories(vdir);
		Files.writeString(vdir.resolve(id + ".json"), Json.write(fabricVersion(id)));
		if (!Files.exists(vdir.resolve(id + ".jar"))) {
			try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(vdir.resolve(id + ".jar")))) {
				z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
				z.write("Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.US_ASCII));
			}
		}
		for (Path f : Detect.launcherProfiles()) {
			Map<String, Object> root = Json.object(Files.readString(f));
			boolean changed = false;
			if (root.get("profiles") instanceof Map<?, ?> all) {
				for (Object p : all.values()) {
					if (p instanceof Map<?, ?> m && game.toString().equals(m.get("gameDir")) && !id.equals(m.get("lastVersionId"))) {
						@SuppressWarnings("unchecked")
						Map<String, Object> mm = (Map<String, Object>) m;
						mm.put("lastVersionId", id);
						changed = true;
					}
				}
			}
			if (changed) {
				Files.copy(f, f.resolveSibling(f.getFileName() + ".cc2-backup"), StandardCopyOption.REPLACE_EXISTING);
				Files.writeString(f, Json.write(root));
			}
		}
		log.accept("  versions/" + id + " (Fabric Loader " + LOADER + " for Minecraft " + MC + ")");
	}

	/** A Prism Launcher / MultiMC instance: this release's Minecraft and Fabric Loader versions in its mmc-pack.json. */
	private static void instanceVersion(Path mmcPack, Consumer<String> log) throws IOException {
		Map<String, Object> pack = Json.object(Files.readString(mmcPack));
		if (pack.get("components") instanceof List<?> comps) {
			for (Object o : comps) {
				if (o instanceof Map<?, ?> c) {
					@SuppressWarnings("unchecked")
					Map<String, Object> cm = (Map<String, Object>) c;
					switch (String.valueOf(cm.get("uid"))) {
						case "net.minecraft", "net.fabricmc.intermediary" -> cm.put("version", MC);
						case "net.fabricmc.fabric-loader" -> cm.put("version", LOADER);
						default -> {
						}
					}
				}
			}
		}
		Files.writeString(mmcPack, Json.write(pack));
		log.accept("  mmc-pack.json: Minecraft " + MC + ", Fabric Loader " + LOADER);
	}

	// ---- finding Counter-Craft 2 installs (for updates)

	/** Counter-Craft 2's game folders in the official launcher's folder. */
	static List<Found> officialInstalls() {
		List<Found> out = new ArrayList<>();
		try (var s = Files.list(Detect.dotMinecraft())) {
			s.filter(d -> Files.isRegularFile(d.resolve(RECORD))).sorted().forEach(d -> out.add(new Found(d, d.getFileName().toString())));
		} catch (IOException ignored) {
			// none
		}
		return out;
	}

	/** Counter-Craft 2's instances in a Prism Launcher / MultiMC folder. */
	static List<Found> instanceInstalls(Path root) {
		List<Found> out = new ArrayList<>();
		if (root == null) {
			return out;
		}
		try (var s = Files.list(root.resolve("instances"))) {
			s.sorted().forEach(i -> {
				for (String g : new String[] {"minecraft", ".minecraft"}) {
					if (Files.isRegularFile(i.resolve(g).resolve(RECORD))) {
						out.add(new Found(i.resolve(g), i.getFileName().toString()));
					}
				}
			});
		} catch (IOException ignored) {
			// none
		}
		return out;
	}

	// ---- shared pieces

	/** The bundled mod and Fabric API into mods/, and out with jars an earlier Counter-Craft 2 install put there. */
	static List<String> mods(Path modsDir, Path record, Consumer<String> log) throws IOException {
		Files.createDirectories(modsDir);
		Map<String, byte[]> jars = bundledMods();
		try {
			Object old = Json.object(Files.readString(record)).get("mods");
			if (old instanceof List<?> l) {
				for (Object o : l) {
					if (o instanceof String n && !jars.containsKey(n) && !n.contains("/") && !n.contains("\\") && n.endsWith(".jar")) {
						Files.deleteIfExists(modsDir.resolve(n));
						log.accept("  mods/" + n + " (from the last install) removed");
					}
				}
			}
		} catch (Exception ignored) {
			// no earlier install
		}
		for (Map.Entry<String, byte[]> e : jars.entrySet()) {
			Path t = modsDir.resolve(e.getKey());
			Path tmp = t.resolveSibling(e.getKey() + ".cc2tmp");
			Files.write(tmp, e.getValue());
			Files.move(tmp, t, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			log.accept("  mods/" + e.getKey());
		}
		return new ArrayList<>(jars.keySet());
	}

	/**
	 * The pack into resourcepacks/: by the manifests when both sides have one (only what changed), else every file whose
	 * size or date differs. Each file goes to a temporary name first and is moved into place.
	 */
	static void pack(Path src, Path resourcepacks, Consumer<String> log) throws IOException {
		Path dst = resourcepacks.resolve(PACK);
		Files.createDirectories(dst);
		Map<String, Object> want = manifest(src);
		Map<String, Object> have = Files.exists(dst.resolve(UPDATING)) ? null : manifest(dst);
		Files.writeString(dst.resolve(UPDATING), Instant.now().toString());
		long[] n = {0, 0, 0}; // looked at, copied, removed
		if (want != null && have != null) {
			Map<?, ?> wf = (Map<?, ?>) want.get("files");
			Map<?, ?> hf = (Map<?, ?>) have.get("files");
			for (Map.Entry<?, ?> e : wf.entrySet()) {
				String rel = (String) e.getKey();
				Map<?, ?> w = (Map<?, ?>) e.getValue();
				Map<?, ?> h = (Map<?, ?>) hf.get(rel);
				Path t = dst.resolve(rel);
				if (h == null || !w.get("sha256").equals(h.get("sha256")) || !Files.exists(t) || Files.size(t) != ((Double) w.get("size")).longValue()) {
					copy(src.resolve(rel), t);
					n[1]++;
				}
				progress(++n[0], log);
			}
			for (Object rel : hf.keySet()) {
				if (!wf.containsKey(rel) && Files.deleteIfExists(dst.resolve((String) rel))) {
					n[2]++;
				}
			}
		} else {
			Files.walkFileTree(src, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
					if (dir.getFileName().toString().startsWith(".cc2")) {
						return FileVisitResult.SKIP_SUBTREE; // a build's leftovers
					}
					Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
					String name = f.getFileName().toString();
					if (name.startsWith(".cc2") || name.equals(MANIFEST) || name.endsWith(".tmp") || name.endsWith(".cc2tmp")) {
						return FileVisitResult.CONTINUE;
					}
					Path t = dst.resolve(src.relativize(f).toString());
					if (!Files.exists(t) || Files.size(t) != a.size() || !Files.getLastModifiedTime(t).equals(a.lastModifiedTime())) {
						copy(f, t);
						n[1]++;
					}
					progress(++n[0], log);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		if (want != null) {
			copy(src.resolve(MANIFEST), dst.resolve(MANIFEST)); // last: it says the copy is complete
		}
		Files.delete(dst.resolve(UPDATING));
		log.accept("  resourcepacks/" + PACK + ": " + n[0] + " files, " + n[1] + " copied" + (n[2] > 0 ? ", " + n[2] + " removed" : "")
				+ (want != null && have != null ? " (by the manifest)" : ""));
	}

	private static void copy(Path from, Path to) throws IOException {
		Files.createDirectories(to.getParent());
		Path tmp = to.resolveSibling(to.getFileName() + ".cc2tmp");
		Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
		Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void progress(long n, Consumer<String> log) {
		if (n % 5000 == 0) {
			log.accept("  asset pack: " + n + " files...");
		}
	}

	/** options.txt with the pack turned on (other settings kept; a new file holds just that). */
	static void options(Path gameDir) throws IOException {
		Path f = gameDir.resolve("options.txt");
		String line = "resourcePacks:[\"vanilla\",\"file/" + PACK + "\"]";
		List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f)) : new ArrayList<>();
		boolean done = false;
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith("resourcePacks:")) {
				lines.set(i, line);
				done = true;
			}
		}
		if (!done) {
			lines.add(line);
		}
		Files.createDirectories(gameDir);
		Files.write(f, lines);
	}

	/** {@code name}, or "name 2", "name 3"... whichever doesn't exist in {@code parent}. */
	static String free(Path parent, String name) {
		String n = name;
		for (int i = 2; Files.exists(parent.resolve(n)); i++) {
			n = name + " " + i;
		}
		return n;
	}

	/** The mod, the pack and options.txt into a Counter-Craft 2 game folder, and the record that marks it as one. */
	private static void gameDir(Path dir, Path pack, String launcher, Consumer<String> log) throws IOException {
		Path record = dir.resolve(RECORD);
		List<String> jars = mods(dir.resolve("mods"), record, log);
		pack(pack, dir.resolve("resourcepacks"), log);
		options(dir);
		log.accept("  options.txt: the pack turned on");
		Path table = dir.resolve("config/countercraft/weapons.json");
		Files.createDirectories(table.getParent());
		copy(pack.resolve(WEAPONS), table);
		log.accept("  config/countercraft/weapons.json: the weapon table");
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("release", RELEASE);
		r.put("installed", Instant.now().toString());
		r.put("launcher", launcher);
		r.put("mods", jars);
		r.put("pack", PACK);
		Files.writeString(record, Json.write(r));
	}

	/** What's wrong with an installed game folder (empty: it's all there). */
	static List<String> verify(Path dir) {
		List<String> out = new ArrayList<>();
		try {
			for (String jar : bundledMods().keySet()) {
				if (!Files.isRegularFile(dir.resolve("mods").resolve(jar))) {
					out.add("mods/" + jar + " is missing");
				}
			}
		} catch (IOException e) {
			out.add("the installer's own files can't be read: " + e.getMessage());
		}
		Path pack = dir.resolve("resourcepacks").resolve(PACK);
		for (String p : problems(pack)) {
			out.add("the asset pack: " + p);
		}
		Map<String, Object> m = manifest(pack);
		if (m == null && Files.exists(pack.resolve(MANIFEST))) {
			out.add("the asset pack's manifest (" + MANIFEST + ") can't be read");
		}
		if (m != null) {
			long bad = ((Map<?, ?>) m.get("files")).entrySet().stream().filter(e -> {
				Path f = pack.resolve((String) e.getKey());
				try {
					return Files.size(f) != ((Double) ((Map<?, ?>) e.getValue()).get("size")).longValue();
				} catch (IOException x) {
					return true;
				}
			}).count();
			if (bad > 0) {
				out.add("the asset pack: " + bad + " file(s) missing or the wrong size");
			}
		}
		if (Files.exists(pack.resolve(UPDATING))) {
			out.add("the asset pack's copy didn't finish");
		}
		try {
			if (!Files.readString(dir.resolve("options.txt")).contains("file/" + PACK)) {
				out.add("options.txt doesn't turn the pack on");
			}
		} catch (IOException e) {
			out.add("options.txt is missing");
		}
		if (!Files.isRegularFile(dir.resolve(RECORD))) {
			out.add(RECORD + " is missing");
		}
		if (!Files.isRegularFile(dir.resolve("config/countercraft/weapons.json"))) {
			out.add("the weapon table (config/countercraft/weapons.json) is missing");
		}
		return out;
	}

	// ---- targets

	/**
	 * A Prism Launcher / MultiMC instance (both read instance.cfg + mmc-pack.json): a new one, or the Counter-Craft 2
	 * instance {@code existing} (its game folder) brought up to date.
	 */
	static Path instance(Path root, boolean prism, int memMb, Path pack, Path existing, Consumer<String> log) throws IOException {
		String launcher = prism ? "Prism Launcher" : "MultiMC";
		if (existing != null) {
			log.accept("  " + launcher + " instance \"" + existing.getParent().getFileName() + "\" (updating)");
			gameDir(existing, pack, launcher, log);
			return existing;
		}
		Path instances = root.resolve("instances");
		String name = free(instances, NAME);
		Path dir = instances.resolve(name);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("instance.cfg"), String.join("\n",
				"[General]", "ConfigVersion=1.2", "InstanceType=OneSix", "name=" + name, "iconKey=countercraft2",
				"OverrideMemory=true", "MinMemAlloc=512", "MaxMemAlloc=" + memMb, ""));
		Files.writeString(dir.resolve("mmc-pack.json"), Json.write(Map.of("formatVersion", 1.0, "components", List.of(
				comp("net.minecraft", MC, true, false), comp("net.fabricmc.intermediary", MC, false, true),
				comp("net.fabricmc.fabric-loader", LOADER, false, false)))));
		Path icons = root.resolve("icons");
		Files.createDirectories(icons);
		ImageIO.write(logo(128), "png", icons.resolve("countercraft2.png").toFile());
		log.accept("  " + launcher + " instance \"" + name + "\" (Minecraft " + MC + ", Fabric Loader " + LOADER + ")");
		Path game = dir.resolve(prism ? "minecraft" : ".minecraft");
		gameDir(game, pack, launcher, log);
		return game;
	}

	private static Map<String, Object> comp(String uid, String version, boolean important, boolean dependencyOnly) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uid", uid);
		m.put("version", version);
		if (important) {
			m.put("important", true);
		}
		if (dependencyOnly) {
			m.put("dependencyOnly", true);
		}
		return m;
	}

	/**
	 * The official Minecraft Launcher (Windows 10/11, Microsoft Store or legacy): Fabric's version file, a game folder of
	 * its own (or the Counter-Craft 2 one {@code existing}, brought up to date), and a profile in every
	 * launcher_profiles*.json there is (unless one already uses that folder).
	 */
	static Path officialLauncher(int memMb, Path pack, Path existing, Consumer<String> log) throws IOException {
		Path mc = Detect.dotMinecraft();
		List<Path> profiles = Detect.launcherProfiles();
		if (profiles.isEmpty()) {
			throw new IOException("the Minecraft Launcher has no profiles file in " + mc + ": start the launcher once, then try again");
		}
		String id = "fabric-loader-" + LOADER + "-" + MC;
		Path vdir = mc.resolve("versions").resolve(id);
		Files.createDirectories(vdir);
		Files.writeString(vdir.resolve(id + ".json"), Json.write(fabricVersion(id)));
		if (!Files.exists(vdir.resolve(id + ".jar"))) {
			try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(vdir.resolve(id + ".jar")))) {
				z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF")); // the launcher wants a jar; Fabric's own installer writes an empty one
				z.write("Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.US_ASCII));
			}
		}
		log.accept("  versions/" + id + " (Fabric Loader " + LOADER + " for Minecraft " + MC + ")");
		Path game = existing != null ? existing : mc.resolve(free(mc, NAME));
		if (existing != null) {
			log.accept("  game folder \"" + game.getFileName() + "\" (updating)");
		}
		gameDir(game, pack, "Minecraft Launcher", log);
		String now = Instant.now().toString();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(logo(128), "png", png);
		for (Path f : profiles) {
			Map<String, Object> root = Json.object(Files.readString(f));
			@SuppressWarnings("unchecked")
			Map<String, Object> all = (Map<String, Object>) root.computeIfAbsent("profiles", k -> new LinkedHashMap<>());
			boolean has = all.values().stream().anyMatch(p -> p instanceof Map<?, ?> m && game.toString().equals(m.get("gameDir")));
			if (has) {
				log.accept("  the profile for \"" + game.getFileName() + "\" is already in " + f.getFileName());
				continue;
			}
			Files.copy(f, f.resolveSibling(f.getFileName() + ".cc2-backup"), StandardCopyOption.REPLACE_EXISTING);
			String key = "countercraft2";
			for (int i = 2; all.containsKey(key); i++) {
				key = "countercraft2_" + i;
			}
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("created", now);
			p.put("gameDir", game.toString());
			p.put("icon", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
			p.put("javaArgs", "-Xmx" + memMb + "M -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 "
					+ "-XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M");
			p.put("lastUsed", now);
			p.put("lastVersionId", id);
			p.put("name", game.getFileName().toString());
			p.put("type", "custom");
			all.put(key, p);
			Files.writeString(f, Json.write(root));
			log.accept("  profile \"" + game.getFileName() + "\" in " + f.getFileName() + " (backup: " + f.getFileName() + ".cc2-backup)");
		}
		return game;
	}

	/** The launcher's own files for a Counter-Craft 2 game folder: what's missing (empty: all there). */
	static List<String> verifyOfficial(Path game) {
		List<String> out = new ArrayList<>();
		String id = "fabric-loader-" + LOADER + "-" + MC;
		if (!Files.isRegularFile(Detect.dotMinecraft().resolve("versions").resolve(id).resolve(id + ".json"))) {
			out.add("the launcher's Fabric version file is missing");
		}
		for (Path f : Detect.launcherProfiles()) {
			try {
				Map<?, ?> all = (Map<?, ?>) Json.object(Files.readString(f)).get("profiles");
				if (all == null || all.values().stream().noneMatch(p -> p instanceof Map<?, ?> m && game.toString().equals(m.get("gameDir")))) {
					out.add("no Counter-Craft 2 profile in " + f.getFileName());
				}
			} catch (Exception e) {
				out.add(f.getFileName() + " can't be read");
			}
		}
		return out;
	}

	/** An instance's own files: what's missing (empty: all there). */
	static List<String> verifyInstance(Path game) {
		List<String> out = new ArrayList<>();
		for (String f : new String[] {"instance.cfg", "mmc-pack.json"}) {
			if (!Files.isRegularFile(game.getParent().resolve(f))) {
				out.add("the instance's " + f + " is missing");
			}
		}
		return out;
	}

	/** Fabric's version file, from the library list Fabric Loader ships (fabric-installer.json): as meta.fabricmc.net's profile for 26.3 (no intermediary, 26.x isn't obfuscated). */
	static Map<String, Object> fabricVersion(String id) throws IOException {
		Map<String, Object> inst = Json.object(bundled("fabric-installer.json"));
		List<Object> libs = new ArrayList<>();
		Map<?, ?> groups = (Map<?, ?>) inst.get("libraries");
		for (String side : new String[] {"common", "client"}) {
			if (groups.get(side) instanceof List<?> l) {
				for (Object o : l) {
					Map<?, ?> lib = (Map<?, ?>) o;
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("name", lib.get("name"));
					e.put("url", lib.get("url") != null ? lib.get("url") : FABRIC_MAVEN);
					if (lib.get("sha1") != null) {
						e.put("sha1", lib.get("sha1"));
					}
					if (lib.get("size") != null) {
						e.put("size", lib.get("size"));
					}
					libs.add(e);
				}
			}
		}
		libs.add(new LinkedHashMap<>(Map.of("name", "net.fabricmc:fabric-loader:" + LOADER, "url", FABRIC_MAVEN)));
		Object main = inst.get("mainClass");
		String mainClass = main instanceof Map<?, ?> m ? (String) m.get("client") : (String) main;
		String now = Instant.now().toString();
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("id", id);
		v.put("inheritsFrom", MC);
		v.put("releaseTime", now);
		v.put("time", now);
		v.put("type", "release");
		v.put("mainClass", mainClass);
		v.put("arguments", new LinkedHashMap<>(Map.of("game", List.of(), "jvm", List.of("-DFabricMcEmu= net.minecraft.client.main.Main "))));
		v.put("libraries", libs);
		return v;
	}

	// ---- the CC2 emblem

	/** Counter-Craft 2's emblem ("CC2" in a gold ring, as the server window draws it) for launcher icons. */
	static BufferedImage logo(int s) {
		BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		float k = s / 40f;
		g.setColor(new Color(0x2A2418));
		g.fillOval(0, 0, s, s);
		g.setStroke(new BasicStroke(2.5f * k));
		g.setColor(new Color(0xDCB45A));
		g.drawOval(Math.round(2 * k), Math.round(2 * k), Math.round(s - 4 * k), Math.round(s - 4 * k));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.round(s * 0.32f)));
		FontMetrics fm = g.getFontMetrics();
		g.drawString("CC2", (s - fm.stringWidth("CC2")) / 2f, (s + fm.getAscent() - fm.getDescent()) / 2f);
		g.dispose();
		return img;
	}
}
