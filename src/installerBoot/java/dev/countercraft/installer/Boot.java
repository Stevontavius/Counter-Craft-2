package dev.countercraft.installer;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

/**
 * The installer jar's entry point, built for Java 8 so any Java that opens the jar runs it (the installer needs Java 25).
 * On Java 25 or newer it starts the installer. On an older one (double-clicking a jar opens it with whatever Java the
 * system has for .jar files, often an old Java 8) it looks for a Java 25 on this computer, Minecraft's own first (the
 * Minecraft Launcher and Prism keep one for Minecraft 26), and opens the installer with that; with none, it says so and
 * offers the download page.
 */
public final class Boot {
	/** The Java the installer needs (-Dcc2.needJava=N: pretend it needs N, testing the message). */
	private static final int NEED = Integer.getInteger("cc2.needJava", 25);
	private static final String PAGE = "https://github.com/Stevontavius/Counter-Craft-2/releases";
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

	private Boot() {
	}

	public static void main(String[] args) throws Exception {
		// -Dcc2.javaFeature=8: act as if running on that Java (testing the search)
		int running = Integer.getInteger("cc2.javaFeature", major(System.getProperty("java.specification.version", "0")));
		if (running >= NEED) {
			try {
				Class.forName("dev.countercraft.installer.CcInstaller").getMethod("main", String[].class).invoke(null, (Object) args);
			} catch (java.lang.reflect.InvocationTargetException e) {
				throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
			}
			return;
		}
		File jar = new File(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path java = findJava();
		if (java != null) {
			List<String> cmd = new ArrayList<>();
			cmd.add(java.toString());
			cmd.add("-jar");
			cmd.add(jar.getAbsolutePath());
			Collections.addAll(cmd, args);
			new ProcessBuilder(cmd).directory(jar.getAbsoluteFile().getParentFile()).inheritIO().start();
			return;
		}
		tell(running);
	}

	/** "1.8" -> 8, "25" -> 25, "25.0.4" -> 25, "1.8.0_421" -> 8. */
	static int major(String v) {
		String[] p = v.trim().replace("\"", "").split("[._+-]");
		try {
			int first = Integer.parseInt(p[0]);
			return first == 1 && p.length > 1 ? Integer.parseInt(p[1]) : first;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** The newest Java 25+ on this computer (its javaw / java), or null. */
	static Path findJava() {
		List<Path> roots = new ArrayList<>();
		String home = System.getProperty("user.home", "");
		String local = System.getenv("LOCALAPPDATA");
		String roaming = System.getenv("APPDATA");
		String pf = System.getenv("ProgramFiles");
		String pf86 = System.getenv("ProgramFiles(x86)");
		addRoot(roots, System.getenv("JAVA_HOME"));
		// Minecraft's own Java: the Minecraft Launcher (Microsoft Store and installer builds) and Prism Launcher
		addRoot(roots, local, "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe", "LocalCache", "Local", "runtime");
		addRoot(roots, pf86, "Minecraft Launcher", "runtime");
		addRoot(roots, pf, "Minecraft Launcher", "runtime");
		addRoot(roots, roaming, ".minecraft", "runtime");
		addRoot(roots, roaming, "PrismLauncher", "java");
		addRoot(roots, home, ".minecraft", "runtime");
		addRoot(roots, home, ".local", "share", "PrismLauncher", "java");
		addRoot(roots, home, "Library", "Application Support", "minecraft", "runtime");
		// installed JDKs / JREs
		for (String vendor : new String[] {"Java", "Eclipse Adoptium", "Zulu", "Microsoft", "BellSoft", "Amazon Corretto", "Semeru"}) {
			addRoot(roots, pf, vendor);
		}
		addRoot(roots, "/usr/lib/jvm");
		addRoot(roots, "/Library/Java/JavaVirtualMachines");
		Path best = null;
		int bestMajor = NEED - 1;
		for (Path root : roots) {
			for (Path javaHome : homesUnder(root)) {
				int m = releaseMajor(javaHome);
				Path exe = launcher(javaHome);
				if (m > bestMajor && exe != null) {
					best = exe;
					bestMajor = m;
				}
			}
		}
		return best;
	}

	private static void addRoot(List<Path> roots, String first, String... more) {
		if (first != null && !first.isEmpty()) {
			Path p = Paths.get(first, more);
			if (Files.isDirectory(p)) {
				roots.add(p);
			}
		}
	}

	/** Java homes (folders with a release file and a bin folder) under {@code root}, a few folders deep. */
	private static List<Path> homesUnder(Path root) {
		final List<Path> out = new ArrayList<>();
		try {
			Files.walkFileTree(root, EnumSet.noneOf(java.nio.file.FileVisitOption.class), 7, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
					if (f.getFileName().toString().equals("release") && Files.isDirectory(f.resolveSibling("bin"))) {
						out.add(f.getParent());
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path f, IOException e) {
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// unreadable: nothing there
		}
		return out;
	}

	/** JAVA_VERSION from a Java home's release file, as a major version (0 if unreadable). */
	private static int releaseMajor(Path javaHome) {
		try {
			for (String line : Files.readAllLines(javaHome.resolve("release"), StandardCharsets.UTF_8)) {
				if (line.startsWith("JAVA_VERSION=")) {
					return major(line.substring("JAVA_VERSION=".length()));
				}
			}
		} catch (IOException ignored) {
			// unreadable
		}
		return 0;
	}

	/** The launcher to start the installer with: javaw on Windows (no console window), else java. */
	private static Path launcher(Path javaHome) {
		for (String name : WINDOWS ? new String[] {"javaw.exe", "java.exe"} : new String[] {"java"}) {
			Path p = javaHome.resolve("bin").resolve(name);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	/** No Java 25 here: say what to do, and offer the download page. */
	private static void tell(int running) {
		String msg = "Counter-Craft 2's installer needs Java " + NEED + " or newer. This computer opened it with Java " + running
				+ ", and there's no Java " + NEED + " on it.\n\n"
				+ (WINDOWS ? "Download the installer for Windows instead (the file ending in -Windows.zip): it brings its own Java."
						: "Install Java " + NEED + " (for example from adoptium.net), then open the installer again.");
		System.err.println(msg);
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception ignored) {
			// the default look
		}
		try {
			Object[] options = {"Open the download page", "Close"};
			int pick = JOptionPane.showOptionDialog(null, msg, "Counter-Craft 2 Installer", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
					options, options[0]);
			if (pick == 0 && Desktop.isDesktopSupported()) {
				Desktop.getDesktop().browse(URI.create(PAGE));
			}
		} catch (Exception e) {
			// no screen (headless): the message above is all there is
		}
	}
}
