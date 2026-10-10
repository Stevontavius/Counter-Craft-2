package dev.countercraft.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The pack builder's tools: Source 2 Viewer (reads Counter-Strike's files), FFmpeg (converts its sounds) and uv (runs the
 * converter's Python). Counter-Craft 2 never hands them out: when the player asks, their own PC downloads each one from
 * its project's official release on GitHub, into the Counter-Craft 2 folder's tools/ (where the converter looks).
 */
final class Tools {
	/** A tool: what it is, its license, its official download, about how big that is, and where it goes. */
	record Tool(String name, String license, String url, long bytes, String dir) {
	}

	static final Tool VRF = new Tool("Source 2 Viewer", "MIT License",
			"https://github.com/ValveResourceFormat/ValveResourceFormat/releases/download/20.0/cli-windows-x64.zip", 52_735_867L, "tools/vrf");
	static final Tool FFMPEG = new Tool("FFmpeg", "GNU General Public License v3",
			"https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip", 200_232_791L, "tools/ffmpeg-dist");
	static final Tool UV = new Tool("uv", "Apache License 2.0 or MIT License",
			"https://github.com/astral-sh/uv/releases/download/0.13.0/uv-x86_64-pc-windows-msvc.zip", 15_722_003L, "tools/uv");
	static final List<Tool> ALL = List.of(VRF, FFMPEG, UV);

	private Tools() {
	}

	/** Is it there (in tools/; uv may also be installed on its own)? */
	static boolean present(Path repo, Tool t) {
		if (repo == null) {
			return false;
		}
		Path dir = repo.resolve(t.dir());
		if (t == VRF) {
			return Files.isRegularFile(dir.resolve("Source2Viewer-CLI.exe"));
		}
		if (t == FFMPEG) {
			try (var s = Files.list(dir)) {
				return s.anyMatch(d -> Files.isRegularFile(d.resolve("bin/ffmpeg.exe")));
			} catch (IOException e) {
				return false;
			}
		}
		return uv(repo) != null;
	}

	/** uv: the one in tools/uv, else one installed on its own (on PATH, or where its installer puts it); null: none. */
	static Path uv(Path repo) {
		List<Path> places = new java.util.ArrayList<>();
		if (repo != null) {
			places.add(repo.resolve(UV.dir()).resolve("uv.exe"));
		}
		for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
			try {
				places.add(Path.of(d.trim().replace("\"", ""), "uv.exe"));
			} catch (Exception ignored) {
				// a broken PATH entry
			}
		}
		Path home = Path.of(System.getProperty("user.home"));
		places.add(home.resolve(".local/bin/uv.exe"));
		places.add(home.resolve(".cargo/bin/uv.exe"));
		return places.stream().filter(Files::isRegularFile).findFirst().orElse(null);
	}

	/** Download {@code url} (the tool's own, or another for tests) and unpack it into the tool's folder; progress in bytes. */
	static void download(Path repo, Tool t, String url, LongConsumer progress) throws IOException {
		Path dir = repo.resolve(t.dir());
		Files.createDirectories(dir);
		Path zip = dir.resolveSibling(dir.getFileName() + ".download");
		URLConnection c = URI.create(url).toURL().openConnection();
		c.setConnectTimeout(20_000);
		c.setReadTimeout(60_000);
		try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(zip)) {
			byte[] buf = new byte[1 << 16];
			long n = 0;
			for (int r; (r = in.read(buf)) > 0;) {
				out.write(buf, 0, r);
				n += r;
				progress.accept(n);
			}
		}
		try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
			for (ZipEntry e; (e = z.getNextEntry()) != null;) {
				Path f = dir.resolve(e.getName()).normalize();
				if (!f.startsWith(dir)) {
					throw new IOException("the download has a file outside its folder: " + e.getName());
				}
				if (e.isDirectory()) {
					Files.createDirectories(f);
				} else {
					Files.createDirectories(f.getParent());
					Files.copy(z, f, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} finally {
			Files.deleteIfExists(zip);
		}
		if (!present(repo, t)) {
			throw new IOException(t.name() + " isn't where it should be after unpacking the download");
		}
	}
}
