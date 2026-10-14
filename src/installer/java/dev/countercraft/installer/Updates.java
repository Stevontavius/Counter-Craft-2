package dev.countercraft.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The installer keeping itself up to date. When it starts it reads Counter-Craft 2's public release list on GitHub
 * (nothing about the player is sent) and, if a newer release has an installer, offers it. Update Installer downloads that
 * release's installer jar (or, from a release without one, its installer zip, taking the jar out of it wherever it is: the
 * Windows app keeps it under app\), checks it against the SHA-256 GitHub lists for it, puts it next to this one, starts
 * the new one and closes this one; the new one then deletes the old jar. The Windows app keeps its exe and Java and only
 * swaps the jar. A release has both: the zip (the Windows app) and the jar (any system with Java 25).
 */
final class Updates {
	static final String REPO = "Stevontavius/Counter-Craft-2";
	static final String PAGE = "https://github.com/" + REPO + "/releases";
	/** {@code -Dcc2.releases=<url>}: read another release list (testing an update without publishing one). */
	private static final String API = System.getProperty("cc2.releases", "https://api.github.com/repos/" + REPO + "/releases?per_page=20");
	private static final Pattern NUMBERS = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
	/** A release's installers: CC2-Installer-Beta-1.2-Universal.jar and -Windows.zip (BETA 1.0 - 1.1.1: Counter-Craft-2-Installer-BETA-x.zip). */
	private static final Pattern INSTALLER_ASSET = Pattern.compile("(Counter-Craft-2-Installer|CC2-Installer)-[A-Za-z0-9.-]+\\.(jar|zip)");
	/** The jars an installer is named (both schemes, and the BETA 1.0 test build): the only files an update deletes. */
	private static final Pattern INSTALLER_JAR = Pattern.compile("(Counter-Craft-2-Installer|CC2-Installer)-[A-Za-z0-9.-]+\\.jar");
	static final String BAT = "Run Counter-Craft 2 Installer.bat";

	/** A release with an installer: its name, version numbers, the installer jar or zip (url, size, SHA-256) and its page. */
	record Release(String name, int[] version, String asset, String url, long size, String sha256, String page) {
	}

	private Updates() {
	}

	/** "BETA 1.1" / "beta-1.1" -> {1, 1, 0}; null without numbers. */
	static int[] version(String s) {
		Matcher m = s == null ? null : NUMBERS.matcher(s);
		if (m == null || !m.find()) {
			return null;
		}
		return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3))};
	}

	static boolean newer(int[] a, int[] b) {
		for (int i = 0; i < 3; i++) {
			if (a[i] != b[i]) {
				return a[i] > b[i];
			}
		}
		return false;
	}

	/** The newest published release with an installer, if newer than this installer; null if this one is the newest. */
	static Release check() throws IOException {
		URLConnection c = URI.create(API).toURL().openConnection();
		c.setConnectTimeout(10_000);
		c.setReadTimeout(20_000);
		c.setRequestProperty("Accept", "application/vnd.github+json");
		c.setRequestProperty("User-Agent", "Counter-Craft-2-Installer");
		String body;
		try (InputStream in = c.getInputStream()) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		return pick(Json.parse(body), version(Install.RELEASE));
	}

	/**
	 * From GitHub's release list: the newest release (not a draft) with an installer jar or zip (the jar if it has both), if
	 * newer than {@code ours}.
	 */
	static Release pick(Object list, int[] ours) {
		Release best = null;
		if (!(list instanceof List<?> releases)) {
			return null;
		}
		for (Object o : releases) {
			if (!(o instanceof Map<?, ?> r) || Boolean.TRUE.equals(r.get("draft"))) {
				continue;
			}
			int[] v = version(String.valueOf(r.get("tag_name")));
			if (v == null || ours != null && !newer(v, ours) || best != null && !newer(v, best.version())) {
				continue;
			}
			if (!(r.get("assets") instanceof List<?> assets)) {
				continue;
			}
			Map<?, ?> asset = null;
			for (Object a : assets) {
				if (a instanceof Map<?, ?> m && m.get("name") instanceof String name && INSTALLER_ASSET.matcher(name).matches()
						&& (asset == null || name.endsWith(".jar"))) {
					asset = m; // the jar is all an update swaps: no need for the zip's 30 MB of Java
				}
			}
			if (asset != null) {
				String digest = asset.get("digest") instanceof String d && d.startsWith("sha256:") ? d.substring(7) : null;
				String title = r.get("name") instanceof String n && !n.isBlank() ? n : String.valueOf(r.get("tag_name"));
				best = new Release(title, v, String.valueOf(asset.get("name")), String.valueOf(asset.get("browser_download_url")),
						asset.get("size") instanceof Double d ? d.longValue() : -1, digest, String.valueOf(r.get("html_url")));
			}
		}
		return best;
	}

	/** This installer runs from a jar it can be swapped for (not from the build's classes). */
	static boolean canUpdateItself() {
		Path self = Install.self();
		return Files.isRegularFile(self) && INSTALLER_JAR.matcher(self.getFileName().toString()).matches();
	}

	/**
	 * Download {@code r}'s installer, check it, and put its jar (and a zip's Run bat) next to this installer. Returns the
	 * new jar. Nothing is replaced unless the download matches GitHub's SHA-256.
	 */
	static Path download(Release r, LongConsumer progress) throws IOException {
		if (r.sha256() == null) {
			throw new IOException("GitHub lists no checksum for " + r.asset() + ", so it can't be checked: download it from " + r.page());
		}
		Path dir = Install.self().getParent();
		Path dl = dir.resolve(".cc2-installer-update");
		MessageDigest sha;
		try {
			sha = MessageDigest.getInstance("SHA-256");
		} catch (Exception e) {
			throw new IOException(e);
		}
		URLConnection c = URI.create(r.url()).toURL().openConnection();
		c.setConnectTimeout(20_000);
		c.setReadTimeout(60_000);
		c.setRequestProperty("User-Agent", "Counter-Craft-2-Installer");
		try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(dl)) {
			byte[] buf = new byte[1 << 16];
			long n = 0;
			for (int k; (k = in.read(buf)) > 0;) {
				out.write(buf, 0, k);
				sha.update(buf, 0, k);
				n += k;
				progress.accept(n);
			}
		}
		try {
			String got = HexFormat.of().formatHex(sha.digest());
			if (!got.equalsIgnoreCase(r.sha256())) {
				throw new IOException("the download doesn't match GitHub's checksum (got " + got.substring(0, 12) + "..., expected "
						+ r.sha256().substring(0, 12) + "...): nothing was changed");
			}
			if (r.asset().endsWith(".jar")) {
				Path jar = dir.resolve(r.asset());
				if (jar.equals(Install.self())) {
					throw new IOException("the new installer has the same name as this one (" + r.asset() + ")");
				}
				Files.move(dl, jar, StandardCopyOption.REPLACE_EXISTING);
				return jar;
			}
			Path jar = null;
			List<Path> written = new ArrayList<>();
			try (ZipInputStream z = new ZipInputStream(Files.newInputStream(dl))) {
				for (ZipEntry e; (e = z.getNextEntry()) != null;) {
					String name = Path.of(e.getName()).getFileName().toString();
					boolean isJar = INSTALLER_JAR.matcher(name).matches();
					if (e.isDirectory() || !isJar && !name.equals(BAT)) {
						continue; // only the installer jar and its Run bat are taken
					}
					Path t = dir.resolve(name);
					if (t.equals(Install.self())) {
						throw new IOException("the new installer has the same name as this one (" + name + ")");
					}
					Path tmp = dir.resolve(name + ".cc2tmp");
					Files.copy(z, tmp, StandardCopyOption.REPLACE_EXISTING);
					written.add(tmp);
					if (isJar) {
						jar = t;
					}
				}
			}
			if (jar == null) {
				throw new IOException(r.asset() + " has no installer jar in it");
			}
			for (Path tmp : written) {
				String n = tmp.getFileName().toString();
				Files.move(tmp, tmp.resolveSibling(n.substring(0, n.length() - ".cc2tmp".length())), StandardCopyOption.REPLACE_EXISTING);
			}
			return jar;
		} finally {
			Files.deleteIfExists(dl);
		}
	}

	/**
	 * Start the new installer, telling it which jar it replaces. As the Windows app (Counter-Craft 2 Installer.exe, its own
	 * Java: jpackage sets jpackage.app-path), the app's settings file (app/&lt;name&gt;.cfg) is pointed at the new jar and the
	 * exe started again; as a plain jar, the new jar is started with the same Java as this one.
	 */
	static void launch(Path jar) throws IOException {
		String exe = System.getProperty("jpackage.app-path");
		Path old = Install.self();
		if (exe != null) {
			Path cfg = appConfig(old.getParent());
			if (cfg == null) {
				throw new IOException("the installer app's settings file (app\\*.cfg) wasn't found");
			}
			String text = Files.readString(cfg);
			Files.writeString(cfg, text.replace(old.getFileName().toString(), jar.getFileName().toString()));
			new ProcessBuilder(exe, "--replaced", old.toString()).directory(Path.of(exe).getParent().toFile()).start();
			return;
		}
		String java = ProcessHandle.current().info().command().orElse("javaw");
		new ProcessBuilder(java, "-jar", jar.toString(), "--replaced", old.toString()).directory(jar.getParent().toFile()).start();
	}

	/** The app image's launcher settings (the .cfg naming the jar it runs) in {@code appDir}, or null. */
	static Path appConfig(Path appDir) throws IOException {
		try (var s = Files.list(appDir)) {
			return s.filter(p -> p.getFileName().toString().endsWith(".cfg")).filter(p -> {
				try {
					return Files.readString(p).contains("app.classpath");
				} catch (IOException e) {
					return false;
				}
			}).findFirst().orElse(null);
		}
	}

	/** Started by an update: delete the installer jar it replaced (once that one has closed), if it's an installer next to this one. */
	static void cleanUp(String replaced) {
		Path old = Path.of(replaced);
		Path self = Install.self();
		if (!INSTALLER_JAR.matcher(old.getFileName().toString()).matches() || self.getParent() == null || !self.getParent().equals(old.getParent())
				|| old.equals(self)) {
			return;
		}
		// an update from the jar-and-bat installers (BETA 1.0 - 1.1.1): the release zip has no Run bat any more, so the old
		// one next to us still names the old jar; point it at this one
		Path bat = self.getParent().resolve(BAT);
		try {
			if (Files.isRegularFile(bat)) {
				String text = Files.readString(bat);
				if (text.contains(old.getFileName().toString())) {
					Files.writeString(bat, text.replace(old.getFileName().toString(), self.getFileName().toString()));
				}
			}
		} catch (IOException ignored) {
			// it only starts the installer; the jar works on its own
		}
		Thread t = new Thread(() -> {
			for (int i = 0; i < 40; i++) {
				try {
					Files.deleteIfExists(old);
					return;
				} catch (IOException e) {
					try {
						Thread.sleep(250); // the old installer is still closing
					} catch (InterruptedException x) {
						return;
					}
				}
			}
		}, "CC2 installer clean-up");
		t.setDaemon(true);
		t.start();
	}
}
