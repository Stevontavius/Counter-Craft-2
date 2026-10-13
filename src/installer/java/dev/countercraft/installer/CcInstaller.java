package dev.countercraft.installer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * Counter-Craft 2's installer (BETA 1.1.1): builds the asset pack from the player's own Counter-Strike 2 install (and
 * CS:GO's, for its weapon sounds) with the converter (converter/build_pack.py), or updates one by its manifest, then
 * installs the mod and the pack into one launcher's Counter-Craft 2 profile or instance (Install) and checks the result.
 * Pages: Welcome, Notices (agreed to before going on), Counter-Strike, Destination, Review, Progress, Finish; in the look
 * and font of CC2's dedicated server window (CcServerGui). Nothing is saved between runs: every start detects again.
 * Time estimates come from a measured full build (MEASURED) and adapt to the PC's speed while it builds.
 */
public final class CcInstaller extends JComponent {
	private static final int W = 1100;
	private static final int H = 700;
	private static final int HEADER = 64;
	private static final int TABS = 32;
	private static final int NAV = 48;
	private static final int FOOTER = 28;
	private static final int TOP = HEADER + TABS;
	private static final int BOTTOM = H - NAV - FOOTER;
	private static final int PAD = 20;
	private static final int BW = W - 2 * PAD;
	private static final int LH = 18;
	// CcServerGui's colours
	private static final int BG = 0xFF0E1012;
	private static final int HEADER_TOP = 0xFF1C2026;
	private static final int HEADER_BOTTOM = 0xFF121519;
	private static final int BAR = 0xFF0B0D10;
	private static final int LINE = 0xFF2A2F36;
	private static final int ACCENT = 0xFFDCB45A;
	private static final int TEXT = 0xFFE8E8E8;
	private static final int SOFT = 0xFFBFBFBF;
	private static final int DIM = 0xFF8A8A8A;
	private static final int OFF = 0xFF4A4E54;
	private static final int GOOD = 0xFF7ED37E;
	private static final int WARN = 0xFFE0C050;
	private static final int BAD = 0xFFE05050;
	private static final int HINT = 0xFF8FB8E8;
	// CcServerGui's fonts
	private static final Font FONT = pick("Segoe UI", "Dialog", 13);
	private static final Font MONO = pick("Consolas", Font.MONOSPACED, 12);
	private static final Font BODY = FONT;
	private static final Font STRONG = FONT.deriveFont(Font.BOLD, 13f);
	private static final Font SMALL = FONT.deriveFont(Font.PLAIN, 12f);
	private static final Font LABEL = FONT.deriveFont(Font.PLAIN, 11f);
	private static final Font CHIP = FONT.deriveFont(Font.BOLD, 11f);
	private static final Font SECTION = FONT.deriveFont(Font.BOLD, 12f);
	private static final Font BUTTON = FONT.deriveFont(Font.BOLD, 12f);
	private static final Font VALUE = FONT.deriveFont(Font.BOLD, 16f);
	private static final Font TITLE = FONT.deriveFont(Font.BOLD, 20f);
	private static final Font BIG = FONT.deriveFont(Font.BOLD, 24f);
	private static final Font HUGE = FONT.deriveFont(Font.BOLD, 34f);
	private static final Graphics2D MEASURE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
	// pages
	private static final String[] PAGES = {"Welcome", "Notices", "Counter-Strike", "Destination", "Review", "Progress", "Finish"};
	private static final String[] HELP = {"Counter-Craft 2 " + Install.RELEASE + ". Press Next to install, or update an install below.",
			"Read the notices, tick the box, then press I Agree & Continue.", "Where your Counter-Strike games are, the weapon sounds, and the asset pack.",
			"The one launcher Counter-Craft 2 goes into.", "Check the plan, then start it.",
			"You can keep using your PC while it works. Games may stutter while the pack builds.", "How to start playing."};
	private static final int WELCOME = 0;
	private static final int NOTICES = 1;
	private static final int SOURCE = 2;
	private static final int DEST = 3;
	private static final int REVIEW = 4;
	private static final int PROGRESS = 5;
	private static final int FINISH = 6;
	// the asset pack: build one, use one already built, or update one
	private static final int BUILD = 0;
	private static final int EXISTING = 1;
	private static final int UPDATE = 2;
	// the launcher
	private static final int OFFICIAL = 0;
	private static final int PRISM = 1;
	private static final int MULTIMC = 2;
	private static final String[] LAUNCHERS = {"the Minecraft Launcher", "Prism Launcher", "MultiMC"};
	/** Weapon sounds: 0 CS2's, 1 both, 2 CS:GO's only (Install.audio's numbers); build_pack.py's --audio. */
	private static final String[] AUDIO_ARG = {"cs2", "both", "csgo"};
	private static final int[] MEMORY_GB = {4, 6, 8, 12, 16};
	// ponytail: sizes and times measured once on this author's PC (2026-10-09, a full build from scratch: 2 h 33 min, the
	// temporary files peaking at 45 GB), not computed for the player's PC; the time left adapts while it builds
	private static final double BASE_GB = 15.8;
	private static final double CSGO_GB = 0.2;
	private static final double CS2_WEAPON_GB = 0.3;
	/** The throwaway extraction folder's largest size during a full build (it's deleted at the end). */
	private static final double EXTRACT_GB = 50;
	/** Seconds each build_pack.py step took; "Weapon finishes" (~): measured with 8 bake processes, scaled to the 4 used now. */
	private static final Map<String, Integer> MEASURED = Map.ofEntries(Map.entry("CS2 weapon data", 5), Map.entry("Cosmetics catalog", 2571),
			Map.entry("Weapons, sounds, HUD", 1823), Map.entry("Weapon table", 10), Map.entry("Agents", 3760), Map.entry("Ragdolls", 25),
			Map.entry("Agent eyes", 141), Map.entry("Death cam", 6), Map.entry("Map art", 19), Map.entry("Legacy weapon models", 133),
			Map.entry("Weapon finishes", 4500), Map.entry("Glove finishes", 180), Map.entry("Music kits", 8), Map.entry("Shading maps", 495),
			Map.entry("CS:GO weapon sounds", 42), Map.entry("manifest", 180));
	private static final String[] BUILD_STEPS = {"CS2 weapon data", "Cosmetics catalog", "Weapons, sounds, HUD", "Weapon table", "Agents",
			"Ragdolls", "Agent eyes", "Death cam", "Map art", "Legacy weapon models", "Weapon finishes", "Glove finishes", "Music kits",
			"Shading maps"};
	/** Copying the pack, GB a minute (~: not measured yet). */
	private static final double COPY_GB_PER_MIN = 6;
	private static final int UPDATE_COPY_SECONDS = 90;
	private static final int CHECK_SECONDS = 20;
	private static final Path LOG = Path.of(System.getProperty("java.io.tmpdir"), "cc2-installer.log");
	private static final BufferedImage EMBLEM = Install.logo(40);
	private static final BufferedImage EMBLEM_BIG = Install.logo(112);
	private static final Pattern DRY_STEP = Pattern.compile("^\\d+/\\d+ (.+?): ");

	private record Step(String name, int seconds) {
	}

	private record Problem(int page, String text) {
	}

	private record Chip(String text, int color) {
	}

	private record Hit(int x, int y, int w, int h, Runnable action, String hint) {
	}

	private final JFrame frame;
	private final Path repo = repo();
	private final String official = Detect.officialLauncher();
	private final long ramGb = ram();
	private final List<String> notices = resource("/legal/notices.txt");
	private List<String> licenses;
	// the choices (this run only: nothing is saved)
	private boolean agreed;
	private Path cs2;
	private String cs2Note;
	private Path csgo;
	private String csgoNote;
	private int mode;
	private Path out;
	private Path built;
	private int audio;
	private int launcher = -1;
	private Path prism;
	private Path multimc;
	private Path existing;
	private boolean updateFirst;
	private int memory;
	private boolean showLicenses;
	private boolean showDetails;
	// checked when something changes (not every paint: it reads the disk)
	private boolean dirty = true;
	private List<Problem> problems = List.of();
	private List<String> packProblems = List.of();
	private Map<String, Object> packManifest;
	private String manifestKey;
	private List<Install.Found> installs = List.of();
	private final Map<String, double[]> drives = new LinkedHashMap<>();
	private final Map<Tools.Tool, Boolean> toolHave = new LinkedHashMap<>();
	/** A tool download under way ("Downloading 42%") or its failure. */
	private final Map<Tools.Tool, String> toolState = new LinkedHashMap<>();
	// what an update would rerun (build_pack.py --update --dry-run, in the background)
	private String updKey;
	private List<String> updSteps;
	private String updWhy;
	// the screen
	private int page;
	private int furthest;
	private int scroll;
	private int contentH;
	private int mx = -1;
	private int my = -1;
	private int clipTop;
	private int clipBottom = H;
	private String status = "";
	private boolean quitting;
	private final List<Hit> hits = new ArrayList<>();
	// the work: EDT state, the worker posts to it
	private final List<Step> stages = new ArrayList<>();
	private long[] took = new long[0];
	private int buildCount;
	private int current = -1;
	private long stageStart;
	private long runStart;
	private long runEnd;
	private int failedAt = -1;
	private final ArrayDeque<String> log = new ArrayDeque<>();
	private boolean running;
	private boolean ok;
	private final List<String> result = new ArrayList<>();
	private Path opened;
	private int doneLauncher;
	private boolean doneUpdate;
	private volatile boolean existingAtStart;
	// installer updates: a newer release with an installer (checked on GitHub in the background at start), its download
	private Updates.Release newer;
	private String selfUpdate;
	// Counter-Craft 2 installs on this PC (Welcome) and their quick updates: game folder -> "Updating...", "OK", "PACK:why",
	// "Failed: why"; an update asked for before the notices were agreed to
	private List<Install.Found> onPc = List.of();
	private final Map<Path, String> quick = new LinkedHashMap<>();
	private Install.Found pendingQuick;
	private volatile boolean cancelled;
	private volatile Process proc;
	private PrintWriter logFile;
	private final Timer tick = new Timer(1000, e -> repaint());

	public static void main(String[] args) throws Exception {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); // for the folder picker
		} catch (Exception ignored) {
			// Swing's own
		}
		if (args.length == 2 && args[0].equals("--shot")) {
			shots(Path.of(args[1]));
			return;
		}
		if (args.length == 2 && args[0].equals("--replaced")) {
			Updates.cleanUp(args[1]); // started by Update Installer: the old installer jar goes
		}
		SwingUtilities.invokeLater(() -> {
			JFrame f = new JFrame("Counter-Craft 2 Installer " + Install.RELEASE);
			CcInstaller ui = new CcInstaller(f);
			f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			f.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(WindowEvent e) {
					ui.quit();
				}
			});
			f.setIconImages(List.of(Install.logo(16), Install.logo(32), Install.logo(64)));
			f.setResizable(false);
			f.add(ui);
			f.pack();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
		});
	}

	/** Dev: every page drawn to dir/page<n>.png, and the Progress page mid-build. */
	private static void shots(Path dir) throws IOException {
		CcInstaller ui = new CcInstaller(null);
		ui.agreed = true;
		ui.launcher = ui.official != null ? OFFICIAL : PRISM;
		for (int p = 0; p <= REVIEW; p++) {
			ui.go(p);
			shot(ui, dir.resolve("page" + p + ".png"));
		}
		ui.mode = BUILD;
		ui.go(SOURCE);
		shot(ui, dir.resolve("page2-build.png")); // lays the page out, so it can scroll
		ui.scroll = 360;
		shot(ui, dir.resolve("page2-build.png"));
		ui.start(true);
		long now = System.currentTimeMillis();
		ui.runStart = now - 25 * 60_000;
		for (int i = 0; i < 3; i++) {
			ui.took[i] = ui.stages.get(i).seconds() * 11L / 10;
		}
		ui.current = 3;
		ui.stageStart = now - 40_000;
		for (int i = 0; i < 30; i++) {
			ui.log.add("  exporting models/weapons/v_rif_ak47.vmdl_c ... " + i);
		}
		shot(ui, dir.resolve("page5.png"));
		ui.showDetails = true;
		shot(ui, dir.resolve("page5-details.png"));
	}

	private static void shot(CcInstaller ui, Path file) throws IOException {
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		ui.paintComponent(g);
		ui.paintComponent(g); // the second pass knows the content height
		g.dispose();
		ImageIO.write(img, "png", file.toFile());
	}

	private CcInstaller(JFrame frame) {
		this.frame = frame;
		Detect.Found f = Detect.cs2();
		cs2 = f == null ? null : f.path();
		cs2Note = f == null ? null : f.note();
		Detect.Found g = Detect.csgo();
		csgo = g == null ? null : g.path();
		csgoNote = g == null ? null : g.note();
		audio = csgo != null ? 1 : 0;
		out = repo == null ? null : repo.resolve("cs2-pack");
		Path made = out == null ? null : out.resolve(Install.PACK);
		built = made != null && Files.isDirectory(made) ? made : null;
		mode = repo == null || built != null && Install.problems(built).isEmpty() ? EXISTING : BUILD;
		launcher = official != null ? OFFICIAL : -1;
		memory = ramGb > 0 && ramGb < 10 ? 0 : 1;
		setPreferredSize(new Dimension(W, H));
		MouseAdapter m = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				if (e.getButton() != MouseEvent.BUTTON1) {
					return;
				}
				Hit h = at(e.getX(), e.getY());
				if (h != null && h.action() != null) {
					status = "";
					h.action().run();
					dirty = true;
				}
				repaint();
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				mx = e.getX();
				my = e.getY();
				Hit h = at(mx, my);
				setCursor(h != null && h.action() != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
				repaint();
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				mouseMoved(e);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				mx = -1;
				my = -1;
				repaint();
			}

			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				scroll += e.getWheelRotation() * 40;
				repaint();
			}
		};
		addMouseListener(m);
		addMouseMotionListener(m);
		addMouseWheelListener(m);
		if (frame != null) {
			Thread t = new Thread(() -> {
				try {
					Updates.Release r = Updates.check();
					if (r != null) {
						post(() -> newer = r);
					}
				} catch (Exception ignored) {
					// offline, or GitHub didn't answer: no update offered
				}
			}, "CC2 installer update check");
			t.setDaemon(true);
			t.start();
		}
	}

	/** The topmost thing at (x, y) from the last paint. */
	private Hit at(int x, int y) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (x >= h.x() && x < h.x() + h.w() && y >= h.y() && y < h.y() + h.h()) {
				return h;
			}
		}
		return null;
	}

	// ================================================================ drawing

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = gfx((Graphics2D) g0.create());
		if (dirty) {
			refresh();
		}
		hits.clear();
		clipTop = 0;
		clipBottom = H;
		fill(g, 0, 0, W, H, BG);
		header(g);
		tabs(g);
		// the page (scrolls when it's taller than the window)
		scroll = Math.max(0, Math.min(scroll, contentH - (BOTTOM - TOP)));
		clipTop = TOP;
		clipBottom = BOTTOM;
		Graphics2D b = (Graphics2D) g.create();
		b.clipRect(0, TOP, W, BOTTOM - TOP);
		int y0 = TOP + 18 - scroll;
		int end = switch (page) {
			case WELCOME -> welcome(b, y0);
			case NOTICES -> notices(b, y0);
			case SOURCE -> sources(b, y0);
			case DEST -> destination(b, y0);
			case REVIEW -> review(b, y0);
			case PROGRESS -> progress(b, y0);
			default -> finish(b, y0);
		};
		b.dispose();
		contentH = end - y0 + 30;
		clipTop = 0;
		clipBottom = H;
		int max = contentH - (BOTTOM - TOP);
		if (max > 0) {
			int track = BOTTOM - TOP - 8;
			int bar = Math.max(30, track * track / (track + max));
			int by = TOP + 4 + (track - bar) * Math.min(scroll, max) / max;
			fill(g, W - 8, TOP + 4, 4, track, 0x10FFFFFF);
			fill(g, W - 8, by, 4, bar, 0x90DCB45A);
		}
		nav(g);
		footer(g);
		g.dispose();
	}

	private void header(Graphics2D g) {
		g.setPaint(new GradientPaint(0, 0, new Color(HEADER_TOP), 0, HEADER, new Color(HEADER_BOTTOM)));
		g.fillRect(0, 0, W, HEADER);
		g.drawImage(EMBLEM, 16, 12, null);
		String title = "Counter-Craft 2 Installer";
		text(g, title, 68, 31, TEXT, TITLE);
		int cx = 68 + tw(title, TITLE) + 10;
		cx += chip(g, Install.RELEASE, cx, 16, ACCENT) + 6;
		if (running) {
			chip(g, current < buildCount ? mode == UPDATE ? "UPDATING" : "BUILDING" : "INSTALLING", cx, 16, WARN);
		} else if (!stages.isEmpty() && (ok || failedAt >= 0 || cancelled)) {
			chip(g, ok ? "DONE" : cancelled ? "CANCELLED" : "FAILED", cx, 16, ok ? GOOD : BAD);
		} else if (page >= SOURCE) {
			chip(g, problems.isEmpty() ? "READY" : "NEEDS ATTENTION", cx, 16, problems.isEmpty() ? GOOD : WARN);
		}
		String label;
		String value;
		if (running) {
			label = "TIME LEFT";
			value = dur(eta());
		} else if (ok) {
			label = "TOOK";
			value = dur((runEnd - runStart) / 1000);
		} else {
			label = "ESTIMATED TIME";
			value = mode == UPDATE && updSteps == null ? "checking..." : dur(totalSeconds());
		}
		int rw = Math.max(tw(label, LABEL), tw(value, VALUE));
		text(g, fit("A fan-made Minecraft mod. Builds the asset pack from your own Counter-Strike 2, then installs Counter-Craft 2.",
				W - 68 - rw - 48, SMALL), 68, 50, SOFT, SMALL);
		text(g, label, W - 20 - rw, 28, DIM, LABEL);
		text(g, value, W - 20 - rw, 47, TEXT, VALUE);
		hit(W - 20 - rw, 12, rw, 42, null, running ? "How long the rest should take, from how fast this PC has been so far."
				: "About how long it all takes, measured on a full build. Faster or slower PCs differ.");
	}

	private boolean open(int i) {
		if (running) {
			return i == PROGRESS;
		}
		if (i == FINISH) {
			return ok;
		}
		if (i == PROGRESS) {
			return !stages.isEmpty() && !ok;
		}
		return i <= NOTICES || agreed;
	}

	private void tabs(Graphics2D g) {
		fill(g, 0, HEADER, W, TABS, BAR);
		fill(g, 0, HEADER + TABS - 1, W, 1, LINE);
		int x = 16;
		for (int i = 0; i < PAGES.length; i++) {
			boolean sel = i == page;
			Font f = sel ? STRONG : BODY;
			int w = 36 + tw(PAGES[i], f) + 15;
			boolean open = open(i);
			boolean hover = open && over(x, HEADER, w, TABS);
			if (sel) {
				fill(g, x, HEADER, w, TABS - 1, 0x30DCB45A);
				fill(g, x, HEADER + TABS - 3, w, 2, ACCENT);
			} else if (hover) {
				fill(g, x, HEADER, w, TABS - 1, 0x18FFFFFF);
			}
			int to = i;
			boolean bad = i >= SOURCE && i <= REVIEW && problems.stream().anyMatch(p -> p.page() == to) && i <= furthest;
			boolean done = i == FINISH ? ok : i == PROGRESS ? ok : i == NOTICES ? agreed : (i < furthest || !stages.isEmpty()) && !bad;
			int dot = sel ? ACCENT : bad ? WARN : done ? GOOD : 0xFF3A3F46;
			g.setColor(new Color(dot));
			g.fillOval(x + 10, HEADER + 7, 18, 18);
			if (done && !sel) {
				check(g, x + 10, HEADER + 7, 18, 0xFF101214);
			} else {
				String n = Integer.toString(i + 1);
				text(g, n, x + 19 - tw(n, CHIP) / 2f, HEADER + 20, sel || bad ? 0xFF101214 : TEXT, CHIP);
			}
			text(g, PAGES[i], x + 36, HEADER + 21, sel ? 0xFFFFFFFF : hover ? TEXT : open ? DIM : OFF, f);
			hit(x, HEADER, w, TABS, open ? () -> go(to) : null, i > NOTICES && !agreed ? "Agree to the notices first." : HELP[i]);
			x += w + 2;
		}
	}

	private void nav(Graphics2D g) {
		int y = BOTTOM;
		fill(g, 0, y, W, NAV, BAR);
		fill(g, 0, y, W, 1, LINE);
		int by = y + 8;
		int bh = 32;
		int rx = W - 16;
		if (page == FINISH) {
			rx -= 150;
			button(g, rx, by, 150, bh, "CLOSE", GOOD, true, this::quit, "Close the installer.");
		} else {
			button(g, 16, by, 110, bh, "QUIT", DIM, true, this::quit,
					running ? "Stop and close the installer." : "Close the installer. Nothing is changed.");
			if (page == PROGRESS) {
				rx -= 170;
				if (running) {
					button(g, rx, by, 170, bh, cancelled ? "CANCELLING..." : "CANCEL", BAD, !cancelled, this::cancel,
							"Stop now. What's already installed stays; the pack builder's temporary files are deleted.");
				} else {
					button(g, rx, by, 170, bh, "TRY AGAIN", ACCENT, true, () -> go(REVIEW), "Back to Review, to try again.");
				}
			} else if (page == NOTICES) {
				rx -= 210;
				button(g, rx, by, 210, bh, "I AGREE & CONTINUE", GOOD, agreed, () -> {
					if (pendingQuick != null) {
						Install.Found f = pendingQuick;
						pendingQuick = null;
						go(WELCOME);
						quickUpdate(f); // the update they pressed before agreeing
					} else {
						go(SOURCE);
					}
				}, agreed ? "Go on to the installation." : "Tick \"I have read and understand these notices\" first.");
				rx -= 118;
				button(g, rx, by, 110, bh, "←  BACK", SOFT, true, () -> go(WELCOME), "Back to Welcome.");
			} else {
				boolean last = page == REVIEW;
				String label = !last ? "NEXT  →" : mode == BUILD ? "BUILD & INSTALL" : mode == UPDATE ? "UPDATE & INSTALL" : "INSTALL";
				int w = last ? 190 : 130;
				rx -= w;
				button(g, rx, by, w, bh, label, last ? GOOD : ACCENT, !last || problems.isEmpty(), last ? () -> start(false) : () -> go(page + 1),
						!last ? "Go on to " + PAGES[page + 1] + "." : problems.isEmpty() ? "Start now: " + dur(totalSeconds()) + "." : "Sort out the things above first.");
				if (page > WELCOME) {
					rx -= 118;
					button(g, rx, by, 110, bh, "←  BACK", SOFT, true, () -> go(page - 1), "Back to " + PAGES[page - 1] + ".");
				}
			}
		}
		if (!status.isEmpty()) {
			String s = fit(status, rx - 142 - 16, BODY);
			text(g, s, 142 + (rx - 142 - tw(s, BODY)) / 2f, by + 21, WARN, BODY);
		}
	}

	private void footer(Graphics2D g) {
		int y = H - FOOTER;
		fill(g, 0, y, W, FOOTER, BAR);
		fill(g, 0, y, W, 1, LINE);
		Hit h = mx >= 0 ? at(mx, my) : null;
		boolean hovered = h != null && h.hint() != null;
		String right = "Counter-Craft 2 " + Install.RELEASE + "   ·   Minecraft " + Install.MC + "   ·   Fabric Loader " + Install.LOADER;
		text(g, fit(hovered ? h.hint() : HELP[page], W - 48 - tw(right, SMALL), SMALL), 16, y + 18, hovered ? HINT : DIM, SMALL);
		text(g, right, W - 16 - tw(right, SMALL), y + 18, DIM, SMALL);
	}

	// ---------------------------------------------------------------- 1: Welcome

	private int welcome(Graphics2D g, int y) {
		g.drawImage(EMBLEM_BIG, PAD, y, null);
		int tx = PAD + 136;
		text(g, "Counter-Craft 2", tx, y + 44, TEXT, HUGE);
		chip(g, Install.RELEASE, tx + tw("Counter-Craft 2", HUGE) + 14, y + 22, ACCENT);
		int ly = y + 74;
		for (String l : wrap("A fan-made Minecraft mod that brings Counter-Strike-inspired gameplay, weapons, agents, cosmetics and audio "
				+ "into Minecraft survival.", BW - 136, BODY)) {
			text(g, l, tx, ly, SOFT, BODY);
			ly += LH;
		}
		y += 132;
		y = section(g, "What the installer does", y);
		y = planRow(g, y, 1, "Builds the asset pack", "Counter-Strike's models, textures and sounds, converted on this PC from your own copy of "
				+ "Counter-Strike 2.", dur(fullBuildSeconds()));
		y = planRow(g, y, 2, "Installs Counter-Craft 2", "The mod, Fabric API and the asset pack, in a Counter-Craft 2 profile or instance of "
				+ "its own in the Minecraft Launcher, Prism Launcher or MultiMC.", dur(copySeconds(BASE_GB)));
		y = planRow(g, y, 3, "Checks the installation", "Everything is in place before it says it's done.", "");
		y += 6;
		y = section(g, "On this PC", y);
		y = found(g, y, "Counter-Strike 2", cs2 != null, cs2 != null ? "FOUND" : "NOT FOUND", cs2 != null ? GOOD : WARN,
				cs2 != null ? "Needed for the asset pack." : "Needed for the asset pack: you can pick its folder in step 3.");
		y = found(g, y, "Counter-Strike: Global Offensive", csgo != null, csgo != null ? "FOUND" : "OPTIONAL", csgo != null ? GOOD : DIM,
				"Only for CS:GO's weapon sounds.");
		y = found(g, y, "Minecraft Launcher", official != null, official != null ? "FOUND: " + official.toUpperCase(Locale.ROOT) : "NOT FOUND",
				official != null ? GOOD : DIM, "Prism Launcher and MultiMC work too: you pick their folder in step 4.");
		y += 8;
		if (newer != null) {
			y = installerUpdate(g, y);
		}
		y = section(g, "Already installed?", y);
		if (onPc.isEmpty()) {
			y = line(g, "No Counter-Craft 2 install found in the Minecraft Launcher or Prism Launcher's usual folder. Installed somewhere else "
					+ "with Prism Launcher or MultiMC? Find it to update it.", y, DIM);
		}
		for (Install.Found f : onPc) {
			y = installRow(g, y, f);
		}
		button(g, PAD, y, 260, 30, "FIND PRISM / MULTIMC INSTALLS", SOFT, !running, () -> pick(prism, p -> {
			prism = p;
			dirty = true;
		}), "Pick a Prism Launcher or MultiMC folder to look for Counter-Craft 2 instances in.");
		y += 42;
		String d = "Brings an install up to date when this release changed the asset pack too (an install above says so). The pack is "
				+ "built again only if this release changed it, only files that differ are copied into the install, and its worlds and "
				+ "settings stay as they are.";
		int h = choiceH(d, BW - 230);
		card(g, PAD, y, BW, h, HINT);
		text(g, "Update the asset pack too", PAD + 16, y + 24, TEXT, STRONG);
		int dy = y + 44;
		for (String l : wrap(d, BW - 230, BODY)) {
			text(g, l, PAD + 16, dy, SOFT, BODY);
			dy += LH;
		}
		button(g, PAD + BW - 16 - 200, y + (h - 32) / 2, 200, 32, "UPDATE MOD & PACK", HINT, true, () -> {
			mode = UPDATE;
			updateFirst = true;
			go(agreed ? SOURCE : NOTICES);
		}, "Update the mod and the asset pack of a Counter-Craft 2 install made by this installer.");
		return y + h + 8;
	}

	/** A newer release's installer: what it is, and Update Installer (or its download page when it can't be checked). */
	private int installerUpdate(Graphics2D g, int y) {
		y = section(g, "Installer update", y);
		boolean can = Updates.canUpdateItself() && newer.sha256() != null;
		String d = "Counter-Craft 2 " + newer.name() + " is out; this installer is " + Install.RELEASE + ". " + (can
				? "Update Installer downloads the new installer from GitHub, checks it against GitHub's checksum, puts it here in place of this "
						+ "one and opens it. Then update your installs with it."
				: "Download it from the releases page, then update your installs with it.");
		int h = choiceH(d, BW - 230);
		card(g, PAD, y, BW, h, ACCENT);
		text(g, "Counter-Craft 2 " + newer.name(), PAD + 16, y + 24, TEXT, STRONG);
		chip(g, "NEW", PAD + 16 + tw("Counter-Craft 2 " + newer.name(), STRONG) + 10, y + 11, ACCENT);
		int dy = y + 44;
		for (String l : wrap(d, BW - 230, BODY)) {
			text(g, l, PAD + 16, dy, SOFT, BODY);
			dy += LH;
		}
		int bx = PAD + BW - 16 - 200;
		int by = y + (h - 32) / 2;
		if (selfUpdate != null && !selfUpdate.startsWith("Failed")) {
			text(g, selfUpdate, PAD + BW - 16 - tw(selfUpdate, STRONG), by + 21, ACCENT, STRONG);
		} else if (can) {
			button(g, bx, by, 200, 32, selfUpdate != null ? "TRY AGAIN" : "UPDATE INSTALLER", ACCENT, !running, this::updateInstaller,
					"Download " + newer.zip() + (newer.size() > 0 ? " (" + Math.max(1, Math.round(newer.size() / 1e6)) + " MB)" : "") + " from GitHub.");
		} else {
			button(g, bx, by, 200, 32, "OPEN RELEASES PAGE", ACCENT, true, () -> browse(newer.page()), newer.page());
		}
		y += h + 8;
		if (selfUpdate != null && selfUpdate.startsWith("Failed")) {
			y = line(g, selfUpdate, y, BAD);
		}
		return y;
	}

	/** One Counter-Craft 2 install: its release, and UPDATE when it's older than this installer (or how its update went). */
	private int installRow(Graphics2D g, int y, Install.Found f) {
		Path dir = f.gameDir();
		String rel = Install.releaseOf(dir);
		String state = quick.get(dir);
		boolean current = Install.RELEASE.equals(rel);
		boolean pack = state != null && state.startsWith("PACK:");
		int h = 58;
		card(g, PAD, y, BW, h, current ? GOOD : WARN);
		hit(PAD, y, BW, h, null, dir.toString());
		text(g, f.name(), PAD + 16, y + 24, TEXT, STRONG);
		chip(g, current ? Install.RELEASE + "  ·  UP TO DATE" : (rel == null ? "OLDER RELEASE" : rel) + "  →  " + Install.RELEASE,
				PAD + 16 + tw(f.name(), STRONG) + 10, y + 11, current ? GOOD : WARN);
		String sub;
		int color;
		if (state == null || current && "OK".equals(state)) {
			sub = current && "OK".equals(state) ? "Updated to " + Install.RELEASE + " and checked: the mod, Fabric API and loader, and the weapon table. "
					+ "The asset pack was already current." : tail(dir.toString(), BW - 280, SMALL);
			color = state != null ? GOOD : DIM;
		} else if (pack) {
			sub = "Needs the asset pack updated too: " + state.substring(5) + ".";
			color = WARN;
		} else {
			sub = state;
			color = state.startsWith("Failed") ? BAD : ACCENT;
		}
		text(g, fit(sub, BW - 260, SMALL), PAD + 16, y + 44, color, SMALL);
		boolean busy = "Updating...".equals(state);
		if (pack) {
			button(g, PAD + BW - 16 - 200, y + 13, 200, 32, "UPDATE MOD & PACK", HINT, !running, () -> fullUpdate(f),
					"Update this install's asset pack too (built again only where this release changed it), then its mod.");
		} else if (!current && !busy) {
			button(g, PAD + BW - 16 - 200, y + 13, 200, 32, "UPDATE TO " + Install.RELEASE, ACCENT, !running, () -> quickUpdate(f),
					"Update this install to " + Install.RELEASE + ". If its asset pack is already current, only the mod is replaced (a few seconds).");
		}
		return y + h + 8;
	}

	/**
	 * Update an install in place when this release didn't change its asset pack: the mod, Fabric API and loader, the weapon
	 * table, then the check. If the pack changed, the row says so and offers the full update.
	 */
	private void quickUpdate(Install.Found f) {
		if (!agreed) {
			pendingQuick = f;
			status = "Read the notices and agree to them first.";
			go(NOTICES);
			return;
		}
		Path dir = f.gameDir();
		if (repo == null) {
			quick.put(dir, "Failed: the installer's converter files are missing");
			return;
		}
		if (dir.getParent().equals(Detect.dotMinecraft()) && Detect.launcherRunning()) {
			quick.put(dir, "Failed: close the Minecraft Launcher first (it rewrites its profiles when it closes)");
			return;
		}
		quick.put(dir, "Updating...");
		Thread t = new Thread(() -> {
			String state;
			try {
				String change = Install.packChange(dir, Install.stepVersions(repo.resolve("converter")));
				if (!change.isEmpty()) {
					state = "PACK:" + change;
				} else {
					Install.modUpdate(dir, l -> { });
					List<String> bad = new ArrayList<>(Install.verify(dir));
					bad.addAll(dir.getParent().equals(Detect.dotMinecraft()) ? Install.verifyOfficial(dir) : Install.verifyInstance(dir));
					state = bad.isEmpty() ? "OK" : "Failed: updated, but " + String.join("; ", bad);
				}
			} catch (Exception e) {
				String m = String.valueOf(e.getMessage());
				state = "Failed: " + m + (m.contains("another process") ? " (close Minecraft first, then try again)" : "");
			}
			String s = state;
			post(() -> {
				quick.put(dir, s);
				dirty = true;
			});
		}, "CC2 quick update");
		t.setDaemon(true);
		t.start();
	}

	/** The full update (asset pack and mod) of this install: the Update mode with the install already picked. */
	private void fullUpdate(Install.Found f) {
		Path dir = f.gameDir();
		mode = UPDATE;
		existing = dir;
		if (dir.getParent().equals(Detect.dotMinecraft())) {
			launcher = OFFICIAL;
		} else {
			Path root = Detect.launcherRoot(dir);
			boolean multi = "MultiMC".equals(launcherOf(dir));
			launcher = multi ? MULTIMC : PRISM;
			if (multi) {
				multimc = root;
			} else {
				prism = root;
			}
		}
		setMode(UPDATE);
		go(agreed ? SOURCE : NOTICES);
	}

	private static String launcherOf(Path gameDir) {
		try {
			return (String) Json.object(Files.readString(gameDir.resolve(Install.RECORD))).get("launcher");
		} catch (Exception e) {
			return null;
		}
	}

	/** Update Installer: download, check and open the newer installer, then close this one. */
	private void updateInstaller() {
		Updates.Release r = newer;
		selfUpdate = "Starting...";
		Thread t = new Thread(() -> {
			try {
				long[] shown = {-1};
				Path jar = Updates.download(r, n -> {
					long pct = r.size() > 0 ? Math.min(99, n * 100 / r.size()) : n / 1_000_000;
					if (pct != shown[0]) {
						shown[0] = pct;
						post(() -> selfUpdate = r.size() > 0 ? "Downloading " + pct + "%" : "Downloading " + pct + " MB");
					}
				});
				post(() -> selfUpdate = "Opening the new installer...");
				Updates.launch(jar);
				post(() -> {
					if (frame != null) {
						frame.dispose();
					}
					System.exit(0);
				});
			} catch (Exception e) {
				post(() -> selfUpdate = "Failed: " + e.getMessage());
			}
		}, "CC2 installer update");
		t.setDaemon(true);
		t.start();
	}

	private void browse(String url) {
		try {
			java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
		} catch (Exception e) {
			status = "Open " + url + " in your browser.";
		}
	}

	private int found(Graphics2D g, int y, String name, boolean yes, String chip, int color, String note) {
		card(g, PAD, y, BW, 38, yes ? GOOD : 0);
		text(g, name, PAD + 16, y + 24, TEXT, STRONG);
		int cx = PAD + 16 + Math.max(tw(name, STRONG) + 12, 260);
		cx += chip(g, chip, cx, y + 11, color) + 12;
		text(g, fit(note, PAD + BW - cx - 12, SMALL), cx, y + 24, DIM, SMALL);
		return y + 44;
	}

	// ---------------------------------------------------------------- 2: Notices

	private int notices(Graphics2D g, int y) {
		y = intro(g, "Please read these notices. You need to agree to them to install Counter-Craft 2.", y);
		for (String l : notices) {
			if (l.startsWith("# ")) {
				y = section(g, l.substring(2), y + 4);
			} else if (l.startsWith("- ")) {
				g.setColor(new Color(ACCENT));
				g.fillOval(PAD + 6, y + 7, 5, 5);
				for (String w : wrap(l.substring(2), BW - 24, BODY)) {
					text(g, w, PAD + 20, y + 13, SOFT, BODY);
					y += LH;
				}
			} else if (!l.isBlank()) {
				boolean caps = l.equals(l.toUpperCase(Locale.ROOT));
				for (String w : wrap(l, BW, caps ? STRONG : BODY)) {
					text(g, w, PAD, y + 13, caps ? TEXT : SOFT, caps ? STRONG : BODY);
					y += LH;
				}
				y += 4;
			}
		}
		y += 6;
		button(g, PAD, y, 220, 30, showLicenses ? "HIDE LICENSE TEXTS" : "SHOW LICENSE TEXTS", SOFT, true, () -> {
			showLicenses = !showLicenses;
			if (licenses == null) {
				licenses = licenseTexts();
			}
		}, "The full texts of the licenses above.");
		y += 42;
		if (showLicenses && licenses != null) {
			for (String l : licenses) {
				if (l.startsWith("# ")) {
					y = section(g, l.substring(2), y + 8);
				} else {
					for (String w : l.isEmpty() ? List.of("") : wrap(l, BW - 8, MONO)) {
						text(g, w, PAD + 4, y + 12, SOFT, MONO);
						y += 16;
					}
				}
			}
			y += 10;
		}
		String agree = "I have read and understand these notices.";
		int h = 48;
		choice(g, PAD, y, BW, h, 2, agreed, true, agree, "", List.of(), () -> agreed = !agreed, "Tick this to agree and go on.");
		return y + h + 8;
	}

	/** The license texts: Counter-Craft 2's (PolyForm Noncommercial 1.0.0, its LICENSE), Brotli's (MIT) and Fabric API's (Apache 2.0, from its own jar). */
	private static List<String> licenseTexts() {
		List<String> out = new ArrayList<>();
		List<String> mit = resource("/legal/mit.txt");
		out.add("# Counter-Craft 2: PolyForm Noncommercial License 1.0.0");
		out.addAll(resource("/legal/LICENSE"));
		out.add("# Brotli decoder (org.brotli:dec): MIT License");
		mit.forEach(l -> out.add(l.replace("{COPYRIGHT}", "2009, 2010, 2013-2016 by the Brotli Authors")));
		out.add("# Fabric API: Apache License 2.0");
		String apache = Install.inBundledJar("fabric-api", "LICENSE-fabric-api");
		out.addAll(apache == null ? List.of("(its license file wasn't found in the bundled Fabric API)") : apache.lines().map(String::stripTrailing).toList());
		return out;
	}

	private static List<String> resource(String name) {
		try (InputStream in = CcInstaller.class.getResourceAsStream(name)) {
			return in == null ? List.of("(" + name + " is missing)") : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		} catch (IOException e) {
			return List.of("(" + name + " can't be read)");
		}
	}

	// ---------------------------------------------------------------- 3: Counter-Strike

	private int sources(Graphics2D g, int y) {
		y = intro(g, "The asset pack is made from your own copy of Counter-Strike 2. Pick the games, the weapon sounds, and whether to build a "
				+ "new pack, use one you built before, or update one.", y);
		y = section(g, "Counter-Strike 2", y);
		y = source(g, y, "Counter-Strike 2", cs2, Detect.isCs2(cs2), mode != EXISTING, cs2Note, "Not found in your Steam libraries: Browse to its folder.",
				() -> {
					Detect.Found f = Detect.cs2();
					if (f == null) {
						status = "Counter-Strike 2 isn't in your Steam libraries: use Browse.";
					} else {
						cs2 = f.path();
						cs2Note = f.note();
					}
				}, p -> {
					cs2 = p;
					cs2Note = null;
				}, false, "Counter-Strike 2's folder. In Steam: Counter-Strike 2 > Manage > Browse local files.");
		y = section(g, "CS:GO  (optional)", y);
		y = source(g, y, "Counter-Strike: Global Offensive", csgo, Detect.isCsgo(csgo), false, csgoNote,
				"Only needed for CS:GO's weapon sounds.", () -> {
					Detect.Found f = Detect.csgo();
					if (f == null) {
						status = "CS:GO isn't in your Steam libraries: use Browse.";
					} else {
						setCsgo(f.path());
						csgoNote = f.note();
					}
				}, p -> {
					setCsgo(p);
					csgoNote = null;
				}, true, "CS:GO's folder (Steam's Counter-Strike: Global Offensive). Only for its weapon sounds.");
		y = section(g, "Asset pack", y);
		int third = (BW - 24) / 3;
		String[] names = {"Build a new pack", "Use a pack I already built", "Update a pack I built"};
		String[] descs = {repo == null ? "Needs the converter folder next to the installer." : "Made from the games above. Takes " + dur(fullBuildSeconds()) + ".",
				"Skip building and just install it.", "Rebuilt only if this release changed it; then only changed files are copied."};
		int ah = 0;
		for (String d : descs) {
			ah = Math.max(ah, choiceH(d, third));
		}
		for (int i = 0; i < 3; i++) {
			int k = i;
			choice(g, PAD + i * (third + 12), y, third, ah, 1, mode == k, k == EXISTING || repo != null, names[i], descs[i], List.of(), () -> setMode(k),
					k == BUILD ? "Build the asset pack now from your Counter-Strike 2 install." : k == EXISTING
							? "Install a pack the converter built before (a " + Install.PACK + " folder)."
							: "Checks a pack built before against this release (its manifest, cc2-manifest.json): if anything changed, it's built "
									+ "again in full next to the old one, which keeps working until the new one is ready.");
		}
		y += ah + 12;
		if (mode == BUILD) {
			y = label(g, "BUILD IT IN", y);
			y = pathRow(g, y, out, "Pick a folder", p -> out = p, "Where the pack is built and kept. A pack already there is updated.");
			y = line(g, "The pack (about " + gb(packGb()) + ") goes in a " + Install.PACK + " folder there, with up to " + gb(EXTRACT_GB)
					+ " of temporary files while it builds. " + space(out) + ".", y, SOFT);
		} else {
			y = label(g, "THE PACK", y);
			y = pathRow(g, y, built, "Pick the " + Install.PACK + " folder", p -> {
				built = p;
				if (mode == UPDATE && Install.manifest(p) != null) {
					audioFromManifest();
				}
			}, "The " + Install.PACK + " folder the converter built.");
			if (built != null) {
				String s;
				int c;
				if (mode == EXISTING) {
					s = packProblems.isEmpty() ? "A complete pack" + (packManifest != null ? ", release " + packManifest.get("release") : "") + "."
							: "Not a complete pack: " + String.join(", ", packProblems) + ".";
					c = packProblems.isEmpty() ? GOOD : BAD;
				} else {
					s = packManifest != null ? "This pack has a manifest (release " + packManifest.get("release") + "): the update checks it against this release."
							: "This pack was built before updates were possible, so updating it means building it again in full.";
					c = packManifest != null ? GOOD : WARN;
				}
				y = line(g, s, y, c);
			}
		}
		if (mode != EXISTING && repo != null) {
			y = section(g, "Pack builder tools", y + 4);
			y = line(g, "Building the pack needs these free tools. Counter-Craft 2 doesn't include them: Download gets each one from its "
					+ "project's official release on GitHub, onto this PC.", y, SOFT);
			for (Tools.Tool t : Tools.ALL) {
				y = tool(g, y, t);
			}
			y += 4;
		}
		y = section(g, "Weapon sounds", y + 4);
		boolean csgoOk = Detect.isCsgo(csgo);
		boolean fixed = mode == EXISTING;
		int a = fixed ? built == null ? 0 : Install.audio(built) : audio;
		String[] an = {"Counter-Strike 2", "CS:GO Legacy", "Both"};
		int[] idx = {0, 2, 1};
		String[] ad = {"Counter-Strike 2's weapon sounds.", "CS:GO's weapon sounds instead of CS2's.",
				"Switch any time in game: Options > Music & Sounds > Weapon Sound Style."};
		int sh = 0;
		for (String d : ad) {
			sh = Math.max(sh, choiceH(d, third));
		}
		for (int i = 0; i < 3; i++) {
			int k = idx[i];
			boolean can = !fixed && (k == 0 || csgoOk);
			List<Chip> chips = k == 0 || fixed ? List.of() : !csgoOk ? List.of(new Chip("NEEDS CS:GO", DIM)) : List.of();
			choice(g, PAD + i * (third + 12), y, third, sh, 1, a == k, can, an[i], ad[i], chips, () -> audio = k,
					fixed ? "This pack was built with these sounds." : k == 0 ? "The weapon sounds Counter-Strike 2 has today."
							: k == 2 ? "CS:GO's weapon sounds, and CS2's left out. The in-game option stays on CS:GO."
									: "Both sets: pick one in game whenever you like.");
		}
		y += sh + 8;
		if (fixed) {
			y = line(g, "The pack you picked was built with these weapon sounds. To change them, build a new pack.", y, DIM);
		}
		return y;
	}

	/** A tool's card: found / needed, its license, and Download (or how the download is going). */
	private int tool(Graphics2D g, int y, Tools.Tool t) {
		boolean have = toolHave.getOrDefault(t, false);
		String state = toolState.get(t);
		int h = 52;
		card(g, PAD, y, BW, h, have ? GOOD : WARN);
		hit(PAD, y, BW, h, null, have ? t.name() + " is on this PC." : t.name() + " (" + t.license() + "): " + t.url()); // under the button
		text(g, t.name(), PAD + 16, y + 22, TEXT, STRONG);
		chip(g, have ? "FOUND" : "NEEDED", PAD + 16 + tw(t.name(), STRONG) + 10, y + 9, have ? GOOD : WARN);
		String host = t.url().replaceFirst("https://github.com/([^/]+/[^/]+)/.*", "github.com/$1");
		text(g, fit(t.license() + "   ·   from " + host, BW - 260, SMALL), PAD + 16, y + 41, DIM, SMALL);
		if (state != null && !state.startsWith("Failed")) {
			text(g, state, PAD + BW - 16 - tw(state, STRONG), y + 31, ACCENT, STRONG);
		} else if (!have) {
			String label = state != null ? "TRY AGAIN" : String.format("DOWNLOAD (%d MB)", Math.round(t.bytes() / 1e6));
			button(g, PAD + BW - 12 - 200, y + 11, 200, 30, label, ACCENT, true, () -> download(t),
					"Download " + t.name() + " (" + Math.round(t.bytes() / 1e6) + " MB) from " + t.url());
			if (state != null) {
				text(g, fit(state, BW - 520, SMALL), PAD + BW - 230 - tw(fit(state, BW - 520, SMALL), SMALL), y + 31, BAD, SMALL);
			}
		}
		return y + h + 8;
	}

	/** Get a tool from its official release, in the background. */
	private void download(Tools.Tool t) {
		toolState.put(t, "Starting...");
		long[] shown = {-1};
		Thread th = new Thread(() -> {
			try {
				Tools.download(repo, t, t.url(), n -> {
					long pct = Math.min(99, n * 100 / Math.max(1, t.bytes()));
					if (pct != shown[0]) {
						shown[0] = pct;
						post(() -> toolState.put(t, "Downloading " + pct + "%"));
					}
				});
				post(() -> {
					toolState.remove(t);
					dirty = true;
				});
			} catch (Exception e) {
				post(() -> {
					toolState.put(t, "Failed: " + e.getMessage());
					dirty = true;
				});
			}
		}, "CC2 tool download");
		th.setDaemon(true);
		th.start();
	}

	private void setMode(int k) {
		mode = k;
		if (k == UPDATE && built != null && Install.manifest(built) != null) {
			audioFromManifest();
		}
	}

	/** An update keeps the pack's own weapon sounds (other ones mean building it again in full). */
	private void audioFromManifest() {
		Map<String, Object> m = Install.manifest(built);
		if (m != null && m.get("options") instanceof Map<?, ?> o) {
			int i = List.of(AUDIO_ARG).indexOf(String.valueOf(o.get("audio")));
			if (i >= 0 && (i == 0 || Detect.isCsgo(csgo))) {
				audio = i;
			}
		}
	}

	private void setCsgo(Path p) {
		csgo = p;
		if (!Detect.isCsgo(p)) {
			audio = 0;
		} else if (audio == 0) {
			audio = 1;
		}
	}

	/** A game's card: its name, FOUND / REQUIRED / OPTIONAL, the folder, and Find / Browse (/ Clear). */
	private int source(Graphics2D g, int y, String name, Path p, boolean valid, boolean required, String note, String empty, Runnable find,
			Consumer<Path> set, boolean clear, String hint) {
		int h = 60;
		int color = p == null ? (required ? WARN : 0) : valid ? GOOD : BAD;
		card(g, PAD, y, BW, h, color);
		hit(PAD, y, BW, h, null, hint);
		text(g, name, PAD + 16, y + 24, TEXT, STRONG);
		chip(g, p == null ? required ? "REQUIRED" : "OPTIONAL" : valid ? "FOUND" : "WRONG FOLDER", PAD + 16 + tw(name, STRONG) + 10, y + 11,
				p == null ? required ? WARN : DIM : valid ? GOOD : BAD);
		int buttons = (clear ? 3 : 2) * 104;
		String line = p == null ? empty : valid ? p + (note != null ? "   ·   " + note : "") : p + "   ·   this isn't " + name + "'s folder";
		text(g, p == null ? fit(line, BW - 40 - buttons, SMALL) : tail(line, BW - 40 - buttons, SMALL), PAD + 16, y + 45, p == null ? DIM : valid ? SOFT : BAD, SMALL);
		int bx = PAD + BW - 12 - buttons + 8;
		button(g, bx, y + 15, 96, 30, "FIND", SOFT, true, find, "Look for it in your Steam libraries.");
		button(g, bx + 104, y + 15, 96, 30, "BROWSE", SOFT, true, () -> pick(p, set), "Pick its folder yourself.");
		if (clear) {
			button(g, bx + 208, y + 15, 96, 30, "CLEAR", SOFT, p != null, () -> set.accept(null), "Don't use it.");
		}
		return y + h + 14;
	}

	// ---------------------------------------------------------------- 4: Destination

	private int destination(Graphics2D g, int y) {
		y = intro(g, "Where do you play? Pick one launcher. Counter-Craft 2 gets its own profile or instance there, with the mod and the asset "
				+ "pack inside; nothing else in the launcher is changed.", y);
		y = section(g, "Launcher", y);
		int third = (BW - 24) / 3;
		String[] names = {"Minecraft Launcher", "Prism Launcher", "MultiMC"};
		String[] descs = {"Adds a Counter-Craft 2 installation with its own game folder and the CC2 icon.",
				"Adds a Counter-Craft 2 instance with the CC2 icon. You pick Prism's folder below.",
				"Adds a Counter-Craft 2 instance with the CC2 icon. You pick MultiMC's folder below."};
		int h = 0;
		for (String d : descs) {
			h = Math.max(h, choiceH(d, third));
		}
		for (int i = 0; i < 3; i++) {
			int k = i;
			boolean can = k != OFFICIAL || official != null;
			List<Chip> chips = k == OFFICIAL ? List.of(new Chip(official != null ? "FOUND" : "NOT FOUND", official != null ? GOOD : DIM)) : List.of();
			choice(g, PAD + i * (third + 12), y, third, h, 1, launcher == k, can, names[i], descs[i], chips, () -> {
				launcher = k;
				existing = null;
			}, !can ? "The Minecraft Launcher isn't installed on this PC." : "Install into " + LAUNCHERS[k] + ".");
		}
		y += h + 14;
		if (launcher == PRISM || launcher == MULTIMC) {
			String n = launcher == PRISM ? "Prism Launcher" : "MultiMC";
			Path root = launcher == PRISM ? prism : multimc;
			y = label(g, n.toUpperCase(Locale.ROOT) + "'S FOLDER", y);
			y = pathRow(g, y, root, "Browse to " + n + "'s folder or its instances folder, like C:\\PATH-TO-" + (launcher == PRISM ? "PRISM" : "MULTIMC")
					+ "\\instances", p -> {
						if (launcher == PRISM) {
							prism = p;
						} else {
							multimc = p;
						}
						existing = null;
					}, n + "'s folder: the one with the instances folder in it, or the instances folder itself.");
			Path r = Detect.launcherRoot(root);
			if (root != null) {
				y = line(g, r != null ? "" + n + "'s instances: " + r.resolve("instances") + "." : "This folder has no instances folder: pick " + n
						+ "'s folder (or its instances folder).", y, r != null ? GOOD : BAD);
			}
		}
		boolean ready = launcher == OFFICIAL && official != null || launcher > OFFICIAL && Detect.launcherRoot(launcher == PRISM ? prism : multimc) != null;
		if (ready) {
			y = section(g, "Counter-Craft 2 install", y + 4);
			String d = installs.isEmpty() ? "Named \"" + Install.NAME + "\" (or \"" + Install.NAME + " 2\" if that name is taken)."
					: "Named \"" + Install.NAME + "\" (or the next free name). The installs below stay as they are.";
			int nh = choiceH(d, BW);
			choice(g, PAD, y, BW, nh, 1, existing == null, true, "A new Counter-Craft 2 install", d, List.of(), () -> existing = null,
					"Make a new Counter-Craft 2 install.");
			y += nh + 8;
			for (Install.Found f : installs) {
				String u = "Brings its mod and asset pack up to date. Its worlds, settings and other files stay. " + f.gameDir();
				int uh = choiceH(u, BW);
				choice(g, PAD, y, BW, uh, 1, f.gameDir().equals(existing), true, "Update \"" + f.name() + "\"", u, List.of(new Chip("CC2 INSTALL", HINT)),
						() -> existing = f.gameDir(), "Update this Counter-Craft 2 install.");
				y += uh + 8;
			}
			if (existing == null && launcher != -1) {
				y = section(g, "Memory", y + 4);
				int x = PAD;
				for (int i = 0; i < MEMORY_GB.length; i++) {
					int k = i;
					boolean sel = i == memory;
					button(g, x, y, 84, 30, MEMORY_GB[i] + " GB", sel ? ACCENT : SOFT, true, () -> memory = k,
							"Let Minecraft use up to " + MEMORY_GB[i] + " GB of memory.");
					if (sel) {
						fill(g, x, y + 28, 84, 2, ACCENT);
					}
					x += 92;
				}
				String note = "How much memory Minecraft may use." + (ramGb > 0 ? " This PC has " + ramGb + " GB." : "");
				text(g, fit(note, PAD + BW - x - 8, BODY), x + 8, y + 20, DIM, BODY);
				y += 42;
			}
		}
		return y;
	}

	// ---------------------------------------------------------------- 5: Review

	private int review(Graphics2D g, int y) {
		y = intro(g, problems.isEmpty() ? "Here's the plan. Check it over, then start it." : "A few things need sorting out before it can start.", y);
		if (!problems.isEmpty()) {
			y = section(g, "Before you start", y);
			for (Problem p : problems) {
				List<String> lines = wrap(p.text(), BW - 190, BODY);
				int h = Math.max(46, 18 + lines.size() * LH);
				card(g, PAD, y, BW, h, BAD);
				int ly = y + (h - lines.size() * LH) / 2 + 13;
				for (String l : lines) {
					text(g, l, PAD + 16, ly, TEXT, BODY);
					ly += LH;
				}
				button(g, PAD + BW - 12 - 140, y + (h - 30) / 2, 140, 30, "FIX  →", WARN, true, () -> go(p.page()),
						"Go to " + PAGES[p.page()] + ".");
				y += h + 8;
			}
			y += 6;
		}
		y = section(g, "What will happen", y);
		int n = 1;
		if (mode == BUILD) {
			y = planRow(g, y, n++, "Build the asset pack", buildSteps().size() + " steps   ·   about " + gb(packGb()) + "   ·   "
					+ (out == null ? "" : out.resolve(Install.PACK)), dur(buildSeconds()));
		} else if (mode == UPDATE) {
			String what = updSteps == null ? "Checking what changed..." : updWhy != null ? "Built again in full: " + updWhy + "."
					: updSteps.isEmpty() ? "Nothing changed: the pack is up to date." : "Reruns: " + String.join(", ", updSteps) + ".";
			y = planRow(g, y, n++, "Update the asset pack", what + "   ·   " + (built == null ? "" : built),
					updSteps == null ? "..." : updSteps.isEmpty() && updWhy == null ? "no rebuild" : dur(buildSeconds()));
		} else {
			y = planRow(g, y, n++, "Use the asset pack you picked", built == null ? "" : built.toString(), "no build");
		}
		String where = target() == null ? "" : target().toString();
		y = planRow(g, y, n++, (existing != null ? "Update Counter-Craft 2 in " : "Install Counter-Craft 2 into ") + (launcher < 0 ? "..." : LAUNCHERS[launcher]),
				where, dur(installSeconds()));
		y = planRow(g, y, n, "Check the installation", "The mod, Fabric API, the asset pack, options.txt and the launcher's own files.", dur(CHECK_SECONDS));
		fill(g, PAD, y + 2, BW, 1, LINE);
		text(g, "Total", PAD + 16, y + 24, TEXT, STRONG);
		String total = mode == UPDATE && updSteps == null ? "checking..." : dur(totalSeconds());
		text(g, total, PAD + BW - 16 - tw(total, VALUE), y + 25, ACCENT, VALUE);
		y += 44;
		y = section(g, "Disk space", y);
		for (Map.Entry<String, double[]> d : drives.entrySet()) {
			double need = d.getValue()[0];
			double free = d.getValue()[1];
			boolean room = free < 0 || need <= free;
			card(g, PAD, y, BW, 38, room ? GOOD : BAD);
			text(g, "Drive " + d.getKey(), PAD + 16, y + 24, TEXT, STRONG);
			text(g, "needs about " + gb(need), PAD + 110, y + 24, SOFT, BODY);
			String f = free < 0 ? "free space unknown" : String.format("%.0f GB free", free);
			int bx = PAD + 290;
			int bw = BW - 290 - 32 - tw(f, STRONG);
			fill(g, bx, y + 16, bw, 5, 0x20FFFFFF);
			if (free > 0) {
				fill(g, bx, y + 16, (int) (bw * Math.min(1, need / free)), 5, room ? ACCENT : BAD);
			}
			text(g, f, PAD + BW - 16 - tw(f, STRONG), y + 24, room ? GOOD : BAD, STRONG);
			hit(PAD, y, BW, 38, null, room ? "Enough room on drive " + d.getKey() + "." : "Not enough room on drive " + d.getKey()
					+ ": free some up, or pick another place.");
			y += 44;
		}
		return line(g, "You can keep using your PC meanwhile, but games may stutter while the pack builds. You can cancel at any time.", y + 6, DIM);
	}

	private int planRow(Graphics2D g, int y, int n, String what, String detail, String time) {
		int dw = BW - 90 - tw(time, BODY) - 30;
		List<String> lines = wrap(detail, dw, SMALL).stream().map(l -> tail(l, dw, SMALL)).toList(); // a long path: its end
		int h = Math.max(52, 32 + lines.size() * 16);
		card(g, PAD, y, BW, h, 0);
		g.setColor(new Color(0x2A2418));
		g.fillOval(PAD + 14, y + (h - 26) / 2, 26, 26);
		g.setColor(new Color(ACCENT));
		g.setStroke(new BasicStroke(2f));
		g.drawOval(PAD + 15, y + (h - 26) / 2 + 1, 24, 24);
		String num = Integer.toString(n);
		text(g, num, PAD + 27 - tw(num, STRONG) / 2f, y + h / 2f + 5, ACCENT, STRONG);
		text(g, what, PAD + 54, y + 22, TEXT, STRONG);
		int ly = y + 40;
		for (String l : lines) {
			text(g, l, PAD + 54, ly, DIM, SMALL);
			ly += 16;
		}
		text(g, time, PAD + BW - 16 - tw(time, BODY), y + h / 2f + 5, SOFT, BODY);
		return y + h + 6;
	}

	// ---------------------------------------------------------------- 6: Progress

	private int progress(Graphics2D g, int y) {
		long now = System.currentTimeMillis();
		int color = running ? ACCENT : ok ? GOOD : BAD;
		String title = running ? current < 0 ? "Starting..." : current < buildCount
				? (mode == UPDATE ? "Updating the asset pack" : "Building the asset pack") : stages.get(current).name() + "..."
				: ok ? "Done" : cancelled ? "Cancelled" : "Something went wrong";
		List<String> lines = new ArrayList<>();
		for (String r : result) {
			lines.addAll(wrap(r, BW - 32, BODY));
		}
		int ch = 104 + (running || lines.isEmpty() ? 0 : lines.size() * LH + 8);
		card(g, PAD, y, BW, ch, color);
		String state = running ? current < buildCount ? mode == UPDATE ? "UPDATING" : "BUILDING" : "INSTALLING" : ok ? "DONE" : cancelled ? "CANCELLED" : "FAILED";
		text(g, fit(title, BW - 60 - chipW(state), BIG), PAD + 16, y + 36, running ? TEXT : color, BIG);
		chip(g, state, PAD + BW - 16 - chipW(state), y + 18, running ? WARN : color);
		long left = running ? eta() : 0;
		long spent = ((running ? now : runEnd) - runStart) / 1000;
		float frac = running ? spent / (float) Math.max(1, spent + left) : ok ? 1 : (float) doneSeconds() / Math.max(1, plannedSeconds());
		fill(g, PAD + 16, y + 50, BW - 32, 8, 0x20FFFFFF);
		fill(g, PAD + 16, y + 50, Math.round((BW - 32) * Math.min(1, frac)), 8, color);
		String now1 = running && current >= 0 && current < buildCount ? stages.get(current).name() + " (step " + (current + 1) + " of " + buildCount + ")" : "";
		String sub = running ? (now1.isEmpty() ? "" : now1 + "   ·   ") + clock(spent) + " so far   ·   " + dur(left) + " left"
				: "Stopped after " + dur(spent) + ".";
		text(g, fit(sub, BW - 32, BODY), PAD + 16, y + 80, SOFT, BODY);
		int ly = y + 104;
		for (String l : lines) {
			text(g, l, PAD + 16, ly, SOFT, BODY);
			ly += LH;
		}
		hit(PAD, y, BW, ch, null, running ? "The time left adapts to how fast this PC has been so far." : null);
		y += ch + 12;
		if (!running && !ok) {
			button(g, PAD, y, 160, 30, "SHOW LOG", SOFT, Files.exists(LOG), () -> open(LOG), "Open the whole log: " + LOG + ".");
			y += 42;
		}
		// the three parts, in words
		y = section(g, "Progress", y);
		y = phase(g, y, mode == BUILD ? "Build the asset pack" : mode == UPDATE ? "Update the asset pack" : "Asset pack (already built)", 0, buildCount, now);
		y = phase(g, y, stages.size() > buildCount ? stages.get(buildCount).name() : "Install", buildCount, buildCount + 1, now);
		y = phase(g, y, "Check the installation", buildCount + 1, buildCount + 2, now);
		y += 6;
		button(g, PAD, y, 230, 30, showDetails ? "HIDE TECHNICAL DETAILS" : "SHOW TECHNICAL DETAILS", SOFT, true, () -> showDetails = !showDetails,
				"Every step of the pack builder, and what it's printing.");
		y += 42;
		if (showDetails) {
			y = section(g, "Steps", y);
			int rows = (stages.size() + 1) / 2;
			int colW = (BW - 16) / 2;
			for (int i = 0; i < stages.size(); i++) {
				int x = PAD + i / rows * (colW + 16);
				int ry = y + i % rows * 24;
				boolean done = i < current || i == current && ok;
				boolean active = i == current && running;
				boolean failed = i == failedAt;
				marker(g, x + 4, ry + 3, failed ? 3 : done ? 2 : active ? 1 : 0);
				String t = done ? shortDur(took[i]) : active ? clock((now - stageStart) / 1000) : failed ? "" : "~" + shortDur(stages.get(i).seconds());
				Font f = active ? STRONG : BODY;
				text(g, fit(stages.get(i).name(), colW - 40 - tw(t, BODY) - 12, f), x + 26, ry + 16, active ? TEXT : done ? SOFT : failed ? BAD : DIM, f);
				text(g, t, x + colW - tw(t, BODY) - 4, ry + 16, active ? ACCENT : DIM, BODY);
			}
			y += rows * 24 + 12;
			y = section(g, "Log", y);
			int boxH = 10 * 17 + 16;
			fill(g, PAD, y, BW, boxH, LINE);
			fill(g, PAD + 1, y + 1, BW - 2, boxH - 2, 0xFF0A0C0E);
			List<String> all = new ArrayList<>(log);
			int ty = y + 20;
			for (String l : all.subList(Math.max(0, all.size() - 10), all.size())) {
				text(g, fit(l, BW - 28, MONO), PAD + 14, ty, l.startsWith("FAILED") ? BAD : SOFT, MONO);
				ty += 17;
			}
			hit(PAD, y, BW, boxH, null, "The latest lines. The whole log is in " + LOG + ".");
			y += boxH + 8;
		}
		return y;
	}

	/** A part of the work (stages from..to): its state and time. */
	private int phase(Graphics2D g, int y, String name, int from, int to, long now) {
		boolean none = from >= to || from >= stages.size();
		boolean done = !none && (current >= to || ok && current >= to - 1);
		boolean active = !none && running && current >= from && current < to;
		boolean failed = !none && failedAt >= from && failedAt < to;
		card(g, PAD, y, BW, 38, failed ? BAD : done ? GOOD : active ? ACCENT : 0);
		marker(g, PAD + 14, y + 11, none ? 2 : failed ? 3 : done ? 2 : active ? 1 : 0);
		text(g, name, PAD + 40, y + 24, active ? TEXT : done || none ? SOFT : DIM, active ? STRONG : BODY);
		long est = 0;
		long spent = 0;
		for (int i = from; i < Math.min(to, stages.size()); i++) {
			est += stages.get(i).seconds();
			spent += i < current || i == current && ok ? took[i] : 0;
		}
		String t = none ? "not needed" : done ? shortDur(spent) : active ? clock((now - stageStart) / 1000 + spent) + "  of ~" + shortDur(est) : failed ? "stopped" : "~" + shortDur(est);
		text(g, t, PAD + BW - 16 - tw(t, BODY), y + 24, active ? ACCENT : failed ? BAD : DIM, BODY);
		return y + 44;
	}

	/** A step's mark: 0 to do (a ring), 1 running (a dot), 2 done (a tick), 3 failed (a cross). */
	private static void marker(Graphics2D g, int x, int y, int state) {
		int c = state == 3 ? BAD : state == 2 ? GOOD : state == 1 ? ACCENT : OFF;
		g.setColor(new Color(c));
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (state) {
			case 0 -> g.drawOval(x + 2, y + 2, 12, 12);
			case 1 -> g.fillOval(x + 2, y + 2, 13, 13);
			case 2 -> check(g, x, y, 16, c);
			default -> {
				g.drawLine(x + 3, y + 3, x + 13, y + 13);
				g.drawLine(x + 13, y + 3, x + 3, y + 13);
			}
		}
	}

	/** A tick inside an s x s square. */
	private static void check(Graphics2D g, int x, int y, int s, int color) {
		g.setColor(new Color(color));
		g.setStroke(new BasicStroke(Math.max(2f, s / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		Path2D p = new Path2D.Float();
		p.moveTo(x + s * 0.25f, y + s * 0.52f);
		p.lineTo(x + s * 0.43f, y + s * 0.70f);
		p.lineTo(x + s * 0.76f, y + s * 0.32f);
		g.draw(p);
	}

	// ---------------------------------------------------------------- 7: Finish

	private int finish(Graphics2D g, int y) {
		card(g, PAD, y, BW, 96, GOOD);
		g.setColor(new Color(GOOD));
		g.fillOval(PAD + 18, y + 22, 52, 52);
		check(g, PAD + 18, y + 22, 52, 0xFF101214);
		text(g, "Counter-Craft 2 " + Install.RELEASE + (doneUpdate ? " is up to date" : " is installed"), PAD + 88, y + 44, GOOD, BIG);
		String where = opened == null ? "" : opened.toString();
		text(g, fit("In " + LAUNCHERS[doneLauncher] + ": " + where + "   ·   took " + dur((runEnd - runStart) / 1000), BW - 110, BODY), PAD + 88, y + 70, SOFT, BODY);
		y += 110;
		y = section(g, "How to play", y);
		String[] steps = doneLauncher == OFFICIAL ? new String[] {
				"Open the Minecraft Launcher and choose Minecraft: Java Edition.",
				"Next to the Play button, pick the \"" + (opened == null ? Install.NAME : opened.getFileName()) + "\" installation (it has the CC2 icon).",
				"Press Play. The first start downloads Minecraft " + Install.MC + " and Fabric Loader, so it takes a little longer."}
				: new String[] {
						"Open " + LAUNCHERS[doneLauncher] + ". If the Counter-Craft 2 instance isn't in the list yet, close the launcher and open it again.",
						"Select the \"" + (opened == null ? Install.NAME : opened.getParent().getFileName()) + "\" instance (it has the CC2 icon) and press Launch.",
						"Minecraft " + Install.MC + " needs Java 25: if the launcher asks, let it download Java 25 or pick it."};
		for (int i = 0; i < steps.length; i++) {
			y = planRow(g, y, i + 1, steps[i], "", "");
		}
		y = section(g, "Good to know", y + 4);
		for (String l : new String[] {"The asset pack is already turned on. In game, Options > Video > Texture Quality chooses the size textures are shown at.",
				"To bring the asset pack up to date later, run the installer again and press Update Resource Pack.",
				"The asset pack contains Valve's work: it's for your own use, so please don't share, upload or sell it."}) {
			g.setColor(new Color(ACCENT));
			g.fillOval(PAD + 6, y + 7, 5, 5);
			for (String w : wrap(l, BW - 24, BODY)) {
				text(g, w, PAD + 20, y + 13, SOFT, BODY);
				y += LH;
			}
			y += 4;
		}
		y += 8;
		button(g, PAD, y, 200, 32, "OPEN GAME FOLDER", ACCENT, opened != null, () -> open(opened), "Open Counter-Craft 2's game folder.");
		button(g, PAD + 208, y, 160, 32, "SHOW LOG", SOFT, Files.exists(LOG), () -> open(LOG), "Open the log of this run: " + LOG + ".");
		return y + 40;
	}

	// ================================================================ pieces (CcServerGui's)

	private static Font pick(String name, String fallback, int size) {
		Font f = new Font(name, Font.PLAIN, size);
		return f.getFamily().equalsIgnoreCase(name) ? f : new Font(fallback, Font.PLAIN, size);
	}

	private static Graphics2D gfx(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

	/** Text with its baseline at y. */
	private static void text(Graphics2D g, String s, float x, float y, int color, Font f) {
		g.setFont(f);
		g.setColor(new Color(color, true));
		g.drawString(s, x, y);
	}

	private static int tw(String s, Font f) {
		return MEASURE.getFontMetrics(f).stringWidth(s);
	}

	private static void fill(Graphics2D g, int x, int y, int w, int h, int argb) {
		g.setColor(new Color(argb, true));
		g.fillRect(x, y, w, h);
	}

	private boolean over(int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= Math.max(y, clipTop) && my < Math.min(y + h, clipBottom);
	}

	/** Something clickable ({@code action}) or explained in the footer ({@code hint}), kept inside the page's scroll area. */
	private void hit(int x, int y, int w, int h, Runnable action, String hint) {
		int t = Math.max(y, clipTop);
		int b = Math.min(y + h, clipBottom);
		if (b > t) {
			hits.add(new Hit(x, t, w, b - t, action, hint));
		}
	}

	/** The card: a soft top-lit fill, a light top edge, a dark bottom edge, an accent bar down the left. */
	private static void card(Graphics2D g, int x, int y, int w, int h, int accent) {
		g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 0x22), 0, y + h, new Color(255, 255, 255, 0x0A)));
		g.fillRect(x, y, w, h);
		fill(g, x, y, w, 1, 0x24FFFFFF);
		fill(g, x, y + h - 1, w, 1, 0x50000000);
		if (accent != 0) {
			fill(g, x, y, 3, h, accent);
		}
	}

	/** The chip: a tinted tag with a solid bar on its left. */
	private static int chip(Graphics2D g, String t, int x, int y, int color) {
		int w = chipW(t);
		fill(g, x, y, w, 17, 0x45000000 | color & 0xFFFFFF);
		fill(g, x, y, 2, 17, color);
		text(g, t, x + 7, y + 13, TEXT, CHIP);
		return w;
	}

	private static int chipW(String t) {
		return tw(t, CHIP) + 12;
	}

	/** The button: tinted fill (stronger on hover), a line round it, the label in the middle. */
	private void button(Graphics2D g, int x, int y, int w, int h, String label, int color, boolean enabled, Runnable action, String hint) {
		boolean hover = enabled && over(x, y, w, h);
		int c = enabled ? color : OFF;
		fill(g, x, y, w, h, (enabled ? hover ? 0x60 : 0x28 : 0x10) << 24 | c & 0xFFFFFF);
		g.setColor(new Color(c));
		g.setStroke(new BasicStroke(1f));
		g.drawRect(x, y, w - 1, h - 1);
		String s = fit(label, w - 12, BUTTON);
		text(g, s, x + (w - tw(s, BUTTON)) / 2f, y + h / 2f + 4.5f, enabled ? TEXT : 0xFF6A6E74, BUTTON);
		hit(x, y, w, h, enabled ? action : null, hint);
	}

	private static int intro(Graphics2D g, String t, int y) {
		for (String l : wrap(t, BW, BODY)) {
			text(g, l, PAD, y + 13, SOFT, BODY);
			y += LH;
		}
		return y + 12;
	}

	/** A section title in the accent colour with a faint rule after it. */
	private static int section(Graphics2D g, String t, int y) {
		String s = t.toUpperCase(Locale.ROOT);
		text(g, s, PAD, y + 12, ACCENT, SECTION);
		int w = tw(s, SECTION);
		fill(g, PAD + w + 8, y + 7, BW - w - 8, 1, 0x30DCB45A);
		return y + 24;
	}

	private static int label(Graphics2D g, String t, int y) {
		text(g, t, PAD, y + 11, DIM, LABEL);
		return y + 18;
	}

	private static int line(Graphics2D g, String t, int y, int color) {
		for (String l : wrap(t, BW, BODY)) {
			text(g, l, PAD, y + 13, color, BODY);
			y += LH;
		}
		return y + 8;
	}

	private static int choiceH(String desc, int w) {
		return desc.isEmpty() ? 48 : 36 + wrap(desc, w - 56, BODY).size() * LH + 8;
	}

	/**
	 * A card to choose: {@code kind} 0 information, 1 one of several (a round mark), 2 on / off (a square mark). The whole
	 * card is the button; a card that can't be changed still shows what's chosen.
	 */
	private void choice(Graphics2D g, int x, int y, int w, int h, int kind, boolean on, boolean enabled, String title, String desc,
			List<Chip> chips, Runnable click, String hint) {
		boolean hover = enabled && click != null && over(x, y, w, h);
		card(g, x, y, w, h, on ? ACCENT : 0);
		if (on && kind > 0) {
			fill(g, x, y, w, h, 0x14DCB45A);
			g.setColor(new Color(0x70DCB45A, true));
			g.setStroke(new BasicStroke(1f));
			g.drawRect(x, y, w - 1, h - 1);
		}
		if (hover) {
			fill(g, x, y, w, h, 0x10FFFFFF);
		}
		int mark = on ? ACCENT : enabled ? DIM : OFF;
		g.setColor(new Color(mark));
		g.setStroke(new BasicStroke(1.6f));
		int my0 = desc.isEmpty() ? y + (h - 15) / 2 : y + 11;
		if (kind == 1) {
			g.drawOval(x + 16, my0, 15, 15);
			if (on) {
				g.fillOval(x + 20, my0 + 4, 8, 8);
			}
		} else if (kind == 2) {
			g.drawRect(x + 16, my0, 15, 15);
			if (on) {
				check(g, x + 15, my0 - 1, 18, ACCENT);
			}
		} else {
			g.fillOval(x + 18, my0 + 2, 11, 11);
		}
		int cx = x + w - 12;
		for (int i = chips.size() - 1; i >= 0; i--) {
			cx -= chipW(chips.get(i).text());
			chip(g, chips.get(i).text(), cx, y + 10, chips.get(i).color());
			cx -= 6;
		}
		text(g, fit(title, cx - x - 48, STRONG), x + 42, desc.isEmpty() ? y + h / 2f + 5 : y + 24, enabled || on ? TEXT : DIM, STRONG);
		int ly = y + 44;
		for (String l : desc.isEmpty() ? List.<String>of() : wrap(desc, w - 56, BODY)) {
			text(g, tail(l, w - 56, BODY), x + 42, ly, enabled || on ? SOFT : 0xFF6A6E74, BODY);
			ly += LH;
		}
		hit(x, y, w, h, enabled ? click : null, hint);
	}

	/** A folder: the box with its path (click: pick another; the server window's command line), and Browse. */
	private int pathRow(Graphics2D g, int y, Path p, String empty, Consumer<Path> set, String hint) {
		int bw = 110;
		int boxW = BW - bw - 8;
		boolean hover = over(PAD, y, boxW, 30);
		fill(g, PAD, y, boxW, 30, 0xFF15181C);
		g.setColor(new Color(hover ? 0xFF4A5058 : LINE));
		g.setStroke(new BasicStroke(1f));
		g.drawRect(PAD, y, boxW - 1, 29);
		text(g, p == null ? fit(empty, boxW - 20, BODY) : tail(p.toString(), boxW - 20, MONO), PAD + 10, y + 20, p == null ? DIM : TEXT, p == null ? BODY : MONO);
		hit(PAD, y, boxW, 30, () -> pick(p, set), hint);
		button(g, PAD + boxW + 8, y, bw, 30, "BROWSE", SOFT, true, () -> pick(p, set), hint);
		return y + 38;
	}

	private static String fit(String s, int w, Font f) {
		if (tw(s, f) <= w) {
			return s;
		}
		while (!s.isEmpty() && tw(s + "…", f) > w) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	private static String tail(String s, int w, Font f) {
		if (tw(s, f) <= w) {
			return s;
		}
		while (!s.isEmpty() && tw("…" + s, f) > w) {
			s = s.substring(1);
		}
		return "…" + s;
	}

	private static List<String> wrap(String s, int w, Font f) {
		List<String> lines = new ArrayList<>();
		String line = "";
		for (String word : s.split(" ")) {
			String t = line.isEmpty() ? word : line + " " + word;
			if (tw(t, f) > w && !line.isEmpty()) {
				lines.add(line);
				line = word;
			} else {
				line = t;
			}
		}
		if (!line.isEmpty()) {
			lines.add(line);
		}
		return lines;
	}

	private static String gb(double v) {
		return String.format("%.1f GB", v);
	}

	/** "about 25 min", "about 1 h 15 min". */
	private static String dur(long s) {
		if (s < 45) {
			return "under a minute";
		}
		if (s < 90) {
			return "about a minute";
		}
		long m = Math.round(s / 60.0);
		if (m < 60) {
			return "about " + m + " min";
		}
		long r = Math.round(s / 300.0) * 5;
		return "about " + r / 60 + " h" + (r % 60 > 0 ? " " + r % 60 + " min" : "");
	}

	/** "40 s", "12 min", "1 h 05". */
	private static String shortDur(long s) {
		if (s < 60) {
			return s + " s";
		}
		long m = Math.round(s / 60.0);
		return m < 60 ? m + " min" : m / 60 + " h " + String.format("%02d", m % 60);
	}

	private static String clock(long s) {
		return s >= 3600 ? s / 3600 + ":" + String.format("%02d:%02d", s / 60 % 60, s % 60) : s / 60 + ":" + String.format("%02d", s % 60);
	}

	private static long ram() {
		try {
			return Math.round(((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize() / (double) (1L << 30));
		} catch (Throwable t) {
			return 0;
		}
	}

	// ================================================================ sizes, times, problems

	private double packGb() {
		if (mode != BUILD && packManifest != null && packManifest.get("files") instanceof Map<?, ?> files) { // a built pack: its real size
			return files.values().stream().mapToDouble(e -> e instanceof Map<?, ?> m && m.get("size") instanceof Double d ? d : 0).sum() / 1e9;
		}
		int a = mode == EXISTING && built != null ? Install.audio(built) : audio;
		return BASE_GB + (a == 1 ? CSGO_GB : a == 2 ? CSGO_GB - CS2_WEAPON_GB : 0);
	}

	/** build_pack.py's full build for these choices, with the measured times (its plan(), mirrored). */
	private List<Step> buildSteps() {
		List<Step> s = new ArrayList<>();
		for (String n : BUILD_STEPS) {
			s.add(step(n));
		}
		if (audio > 0) {
			s.add(step("CS:GO weapon sounds"));
		}
		return s;
	}

	private static Step step(String name) {
		return new Step(name, MEASURED.getOrDefault(name, 60));
	}

	/** The pack's part: a full build, an update's steps, or nothing. */
	private List<Step> packSteps() {
		if (mode == EXISTING) {
			return List.of();
		}
		if (mode == UPDATE && updSteps != null && updWhy == null) {
			return updSteps.stream().map(CcInstaller::step).toList();
		}
		return buildSteps();
	}

	private int fullBuildSeconds() {
		return buildSteps().stream().mapToInt(Step::seconds).sum() + MEASURED.get("manifest");
	}

	private int buildSeconds() {
		List<Step> s = packSteps();
		return s.stream().mapToInt(Step::seconds).sum() + (s.isEmpty() ? 0 : MEASURED.get("manifest"));
	}

	private int copySeconds(double gb) {
		return (int) (gb * 60 / COPY_GB_PER_MIN) + 10;
	}

	private int installSeconds() {
		return existing != null ? UPDATE_COPY_SECONDS : copySeconds(packGb());
	}

	private long totalSeconds() {
		return buildSeconds() + installSeconds() + CHECK_SECONDS;
	}

	/** Seconds left: the steps still to come, scaled by how fast this PC has been against the measured times. */
	private long eta() {
		if (current < 0) {
			return plannedSeconds();
		}
		double est = 0;
		double act = 0;
		for (int i = 0; i < current; i++) {
			est += stages.get(i).seconds();
			act += took[i];
		}
		double speed = est > 120 ? Math.max(0.4, Math.min(3, act / est)) : 1;
		double cur = stages.get(current).seconds() * speed;
		double left = Math.max(cur * 0.1, cur - (System.currentTimeMillis() - stageStart) / 1000.0);
		for (int i = current + 1; i < stages.size(); i++) {
			left += stages.get(i).seconds() * speed;
		}
		return Math.round(left);
	}

	private long plannedSeconds() {
		return stages.stream().mapToLong(Step::seconds).sum();
	}

	private long doneSeconds() {
		long d = 0;
		for (int i = 0; i < Math.max(0, current); i++) {
			d += stages.get(i).seconds();
		}
		return d;
	}

	/** Free space on {@code p}'s drive: "Drive E: has 1552 GB free". */
	private static String space(Path p) {
		double f = freeGb(p);
		return p == null ? "" : "Drive " + drive(p) + (f < 0 ? ": free space unknown" : String.format(" has %.0f GB free", f));
	}

	private static String drive(Path p) {
		Path r = p.toAbsolutePath().getRoot();
		return r == null ? "?" : r.toString().replace("\\", "");
	}

	/** Free space where {@code p} is (or would be), in GB; -1: unknown. */
	private static double freeGb(Path p) {
		for (Path q = p; q != null; q = q.getParent()) {
			try {
				if (Files.exists(q)) {
					return Files.getFileStore(q).getUsableSpace() / 1e9;
				}
			} catch (IOException ignored) {
				return -1;
			}
		}
		return -1;
	}

	/** Where Counter-Craft 2 goes: the install being updated, or (roughly) where the new one will be. */
	private Path target() {
		if (existing != null) {
			return existing;
		}
		if (launcher == OFFICIAL) {
			return Detect.dotMinecraft().resolve(Install.free(Detect.dotMinecraft(), Install.NAME));
		}
		Path root = Detect.launcherRoot(launcher == PRISM ? prism : launcher == MULTIMC ? multimc : null);
		return root == null ? null : root.resolve("instances").resolve(Install.free(root.resolve("instances"), Install.NAME));
	}

	/** What's wrong with the choices (each fixed on a page), the picked pack's state, CC2 installs found, and the room needed. */
	private void refresh() {
		dirty = false;
		List<Problem> p = new ArrayList<>();
		String mk = built == null ? "" : built + "@" + modified(built.resolve(Install.MANIFEST));
		if (!mk.equals(manifestKey)) { // a real pack's manifest is several MB: read it again only when it changes
			manifestKey = mk;
			packManifest = built == null ? null : Install.manifest(built);
		}
		if (mode != EXISTING) {
			if (!Detect.isCs2(cs2)) {
				p.add(new Problem(SOURCE, cs2 == null ? "Counter-Strike 2 wasn't found. Use Find or Browse on the Counter-Strike page."
						: "The Counter-Strike 2 folder on the Counter-Strike page isn't Counter-Strike 2's."));
			}
			if (audio > 0 && !Detect.isCsgo(csgo)) {
				p.add(new Problem(SOURCE, "CS:GO weapon sounds need CS:GO: pick its folder, or choose Counter-Strike 2's sounds."));
			}
		}
		for (Tools.Tool t : Tools.ALL) {
			toolHave.put(t, Tools.present(repo, t));
			if (mode != EXISTING && !toolHave.get(t)) {
				p.add(new Problem(SOURCE, t.name() + " is needed to build the pack: press Download next to it on the Counter-Strike page."));
			}
		}
		if (mode == BUILD && out == null) {
			p.add(new Problem(SOURCE, "Choose where to build the asset pack."));
		}
		if (mode == EXISTING) {
			packProblems = Install.problems(built);
			if (!packProblems.isEmpty()) {
				p.add(new Problem(SOURCE, built == null ? "Pick the asset pack you built before." : "The asset pack you picked isn't complete: build a new one, or update it."));
			}
		}
		if (mode == UPDATE) {
			if (built == null || !Files.isDirectory(built) || !built.getFileName().toString().equals(Install.PACK)) {
				p.add(new Problem(SOURCE, "Pick the " + Install.PACK + " folder of the pack to update."));
			} else {
				checkUpdate();
			}
		}
		if (launcher < 0) {
			p.add(new Problem(DEST, "Pick the launcher to install Counter-Craft 2 into."));
		} else if (launcher == OFFICIAL) {
			if (official == null) {
				p.add(new Problem(DEST, "The Minecraft Launcher wasn't found: pick Prism Launcher or MultiMC."));
			} else if (Detect.launcherProfiles().isEmpty()) {
				p.add(new Problem(DEST, "The Minecraft Launcher hasn't been started on this PC yet: start it once, close it, then try again."));
			} else if (Detect.launcherRunning()) {
				p.add(new Problem(DEST, "Close the Minecraft Launcher first: it rewrites its profiles when it closes."));
			}
		} else if (Detect.launcherRoot(launcher == PRISM ? prism : multimc) == null) {
			p.add(new Problem(DEST, "Pick " + (launcher == PRISM ? "Prism Launcher" : "MultiMC") + "'s folder, the one with the instances folder in it."));
		}
		installs = launcher == OFFICIAL ? Install.officialInstalls() : launcher > OFFICIAL ? Install.instanceInstalls(Detect.launcherRoot(launcher == PRISM ? prism : multimc)) : List.of();
		List<Install.Found> all = new ArrayList<>(Install.officialInstalls());
		for (Path root : new Path[] {Detect.defaultPrism(), Detect.launcherRoot(prism), Detect.launcherRoot(multimc)}) {
			for (Install.Found f : Install.instanceInstalls(root)) {
				if (all.stream().noneMatch(x -> x.gameDir().equals(f.gameDir()))) {
					all.add(f);
				}
			}
		}
		onPc = all;
		if (existing != null && installs.stream().noneMatch(f -> f.gameDir().equals(existing))) {
			existing = null;
		}
		if (updateFirst && !installs.isEmpty()) {
			existing = installs.getFirst().gameDir();
			updateFirst = false;
		}
		drives.clear();
		Map<String, Boolean> dest = new LinkedHashMap<>();
		if (mode == BUILD && out != null) {
			need(out, EXTRACT_GB + (Files.isDirectory(out.resolve(Install.PACK)) ? 0 : packGb()));
		} else if (mode == UPDATE && built != null && !(updSteps != null && updSteps.isEmpty() && updWhy == null)) {
			need(built, EXTRACT_GB);
		}
		Path t = target();
		if (t != null) {
			need(t, existing != null ? 0.5 : packGb());
			dest.put(drive(t), true);
		}
		for (Map.Entry<String, double[]> d : drives.entrySet()) {
			if (d.getValue()[1] >= 0 && d.getValue()[0] > d.getValue()[1]) {
				p.add(new Problem(dest.containsKey(d.getKey()) ? DEST : SOURCE, String.format("Not enough room on drive %s — it needs about %s and %.0f GB is free. "
						+ "Free some up, or pick another place.", d.getKey(), gb(d.getValue()[0]), d.getValue()[1])));
			}
		}
		problems = p;
	}

	private static long modified(Path f) {
		try {
			return Files.getLastModifiedTime(f).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private void need(Path where, double gb) {
		double[] d = drives.computeIfAbsent(drive(where), k -> new double[] {0, freeGb(where)});
		d[0] += gb;
	}

	/** What an update would rerun: build_pack.py --update --dry-run, in the background (it starts Python). */
	private void checkUpdate() {
		String key = built + "|" + audio + "|" + csgo + "|" + cs2;
		if (key.equals(updKey) || repo == null || !Detect.isCs2(cs2)) {
			return;
		}
		updKey = key;
		updSteps = null;
		updWhy = null;
		List<String> cmd = builderCommand(true);
		cmd.add("--dry-run");
		Thread t = new Thread(() -> {
			List<String> steps = new ArrayList<>();
			String why = null;
			try {
				Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
				try (BufferedReader in = p.inputReader(StandardCharsets.UTF_8)) {
					for (String l; (l = in.readLine()) != null;) {
						Matcher m = DRY_STEP.matcher(l);
						if (l.startsWith("update: full build (")) {
							why = l.substring("update: full build (".length(), l.length() - 1);
						} else if (m.find()) {
							steps.add(m.group(1));
						}
					}
				}
				p.waitFor();
			} catch (Exception e) {
				why = "the check didn't work (" + e.getMessage() + ")";
			}
			String w = why;
			post(() -> {
				if (key.equals(updKey)) {
					updSteps = steps;
					updWhy = w;
					dirty = true; // the room needed depends on it
				}
			});
		}, "CC2 update check");
		t.setDaemon(true);
		t.start();
	}

	// ================================================================ actions

	private void go(int p) {
		page = p;
		furthest = Math.max(furthest, Math.min(p, REVIEW));
		scroll = 0;
		dirty = true;
	}

	private void pick(Path start, Consumer<Path> set) {
		JFileChooser c = new JFileChooser();
		c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		c.setDialogTitle("Counter-Craft 2 Installer");
		if (start != null) {
			c.setCurrentDirectory(start.getParent() != null ? start.getParent().toFile() : start.toFile());
			c.setSelectedFile(start.toFile());
		}
		if (c.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION && c.getSelectedFile() != null) {
			set.accept(c.getSelectedFile().toPath());
			dirty = true;
		}
	}

	private static void open(Path p) {
		try {
			Desktop.getDesktop().open(p.toFile());
		} catch (Exception ignored) {
			// nothing to open with
		}
	}

	private void quit() {
		if (running && !quitting) {
			quitting = true;
			status = "Still working: press Quit again to stop it and close.";
			repaint();
			return;
		}
		cancel();
		System.exit(0);
	}

	private void cancel() {
		cancelled = true;
		Process p = proc;
		if (p != null) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
		}
	}

	/** Start building / installing ({@code dry}: set up the Progress page only, for the screenshots). */
	private void start(boolean dry) {
		refresh();
		if (!problems.isEmpty() && !dry) {
			status = "Sort out the things listed on Review first.";
			go(REVIEW);
			return;
		}
		int how = mode;
		int to = launcher;
		Path root = Detect.launcherRoot(launcher == PRISM ? prism : multimc);
		Path into = existing;
		existingAtStart = into != null;
		int mb = MEMORY_GB[memory] * 1024;
		stages.clear();
		stages.addAll(packSteps());
		buildCount = stages.size();
		stages.add(new Step(installStage(into, to), installSeconds()));
		stages.add(new Step("Check the installation", CHECK_SECONDS));
		took = new long[stages.size()];
		current = -1;
		failedAt = -1;
		log.clear();
		result.clear();
		ok = false;
		cancelled = false;
		quitting = false;
		opened = null;
		running = true;
		runStart = System.currentTimeMillis();
		go(PROGRESS);
		if (dry) {
			return;
		}
		tick.start();
		try {
			logFile = new PrintWriter(Files.newBufferedWriter(LOG), true);
		} catch (IOException e) {
			logFile = null;
		}
		log("Counter-Craft 2 Installer " + Install.RELEASE + "   " + java.time.LocalDateTime.now());
		Thread t = new Thread(() -> work(how, to, root, into, mb), "CC2 installer");
		t.setDaemon(true);
		t.start();
	}

	private static String installStage(Path into, int to) {
		return (into != null ? "Update Counter-Craft 2 in " : "Install into ") + (to < 0 ? "?" : LAUNCHERS[to]);
	}

	/** The worker is on build step {@code at} (build_pack.py's name for it), or on the stage named {@code name} ({@code at} -1). */
	private void begin(int at, String name) {
		post(() -> {
			long now = System.currentTimeMillis();
			int i;
			if (at < 0) {
				i = 0;
				while (i < stages.size() && !stages.get(i).name().equals(name)) {
					i++;
				}
			} else if (at < buildCount) {
				i = at;
				if (!stages.get(i).name().equals(name)) {
					stages.set(i, new Step(name, MEASURED.getOrDefault(name, stages.get(i).seconds())));
				}
			} else { // a build step this installer didn't expect: show it, before the install
				i = buildCount++;
				stages.add(i, step(name));
				long[] t = new long[stages.size()];
				System.arraycopy(took, 0, t, 0, i);
				System.arraycopy(took, i, t, i + 1, took.length - i);
				took = t;
			}
			if (current >= 0 && current < took.length && current != i) {
				took[current] = (now - stageStart) / 1000;
			}
			current = i;
			stageStart = now;
		});
	}

	private void work(int how, int to, Path root, Path into, int mb) {
		String stage = "the asset pack";
		try {
			Path pack = built;
			if (how != EXISTING) {
				build(how == UPDATE);
				pack = how == UPDATE ? built : out.resolve(Install.PACK);
			}
			List<String> bad = Install.problems(pack);
			if (!bad.isEmpty()) {
				throw new IOException("The asset pack isn't complete: " + String.join(", ", bad) + ".");
			}
			stage = "the install";
			begin(-1, installStage(into, to));
			log("Installing into " + LAUNCHERS[to] + ":");
			Path game = to == OFFICIAL ? Install.officialLauncher(mb, pack, into, this::log) : Install.instance(root, to == PRISM, mb, pack, into, this::log);
			stage = "the check";
			begin(-1, "Check the installation");
			log("Checking " + game + ":");
			List<String> problems = new ArrayList<>(Install.verify(game));
			problems.addAll(to == OFFICIAL ? Install.verifyOfficial(game) : Install.verifyInstance(game));
			for (String p : problems) {
				log("  ! " + p);
			}
			if (!problems.isEmpty()) {
				throw new IOException("The installation isn't complete: " + String.join("; ", problems) + ".");
			}
			log("  everything is in place");
			finish(true, List.of(), game, to);
		} catch (CancellationException e) {
			finish(false, List.of("You stopped it. Whatever was already installed stays where it is: run the installer again to finish."), null, -1);
		} catch (Exception e) {
			if (!cancelled) {
				log("FAILED: " + e);
			}
			finish(false, cancelled ? List.of("You stopped it. Whatever was already installed stays where it is: run the installer again to finish.")
					: friendly(e, stage), null, -1);
		} finally {
			if (cancelled && how != EXISTING) {
				deleteTree((how == UPDATE ? built.getParent() : out).resolve(".cc2-extract")); // the build's throwaway files
			}
		}
	}

	/** What went wrong and what to do, in words. */
	private static List<String> friendly(Exception e, String stage) {
		String m = String.valueOf(e.getMessage());
		String what;
		String fix;
		if (m.contains("uv isn't installed")) {
			what = "The pack builder needs uv, and it isn't installed.";
			fix = "Install uv (docs.astral.sh/uv), then press Try Again.";
		} else if (m.toLowerCase(Locale.ROOT).contains("not enough space") || m.toLowerCase(Locale.ROOT).contains("disk full")) {
			what = "The disk ran out of space while working on " + stage + ".";
			fix = "Free up some space (Review shows how much is needed), then press Try Again.";
		} else if (m.startsWith("The pack couldn't be built") || m.startsWith("The pack builder stopped")) {
			what = m;
			fix = "Press Try Again: the next try starts that step again. If it stops at the same place, the log (Show Log) says why.";
		} else if (m.startsWith("The installation isn't complete") || m.startsWith("The asset pack isn't complete")) {
			what = m;
			fix = "Press Try Again. Nothing outside Counter-Craft 2's own folder was changed.";
		} else if (e instanceof java.nio.file.AccessDeniedException || e instanceof java.nio.file.FileSystemException) {
			what = "A file couldn't be written while working on " + stage + ": " + m;
			fix = "Close Minecraft and the launcher if they're open, then press Try Again.";
		} else {
			what = "Something went wrong while working on " + stage + ": " + m;
			fix = "Press Try Again. The log (Show Log) has the details.";
		}
		return List.of(what, fix);
	}

	/** build_pack.py's command line for these choices ({@code update}: --update on the picked pack). */
	private List<String> builderCommand(boolean update) {
		List<String> cmd = new ArrayList<>();
		try {
			cmd.add(uv().toString());
		} catch (IOException e) {
			cmd.add("uv");
		}
		cmd.addAll(List.of("run", repo.resolve("converter/build_pack.py").toString(), "--cs2", String.valueOf(cs2),
				"--out", String.valueOf(update ? built.getParent() : out), "--audio", AUDIO_ARG[audio]));
		if (audio > 0) {
			cmd.addAll(List.of("--csgo", String.valueOf(csgo)));
		}
		if (update) {
			cmd.add("--update");
		}
		return cmd;
	}

	/** Runs the pack builder. */
	private void build(boolean update) throws IOException, InterruptedException {
		uv(); // a clear message if it's missing
		List<String> cmd = builderCommand(update);
		log("> " + String.join(" ", cmd));
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true);
		pb.environment().put("PYTHONUNBUFFERED", "1");
		pb.environment().put("PYTHONIOENCODING", "utf-8");
		proc = pb.start();
		String fail = null;
		try (BufferedReader in = proc.inputReader(StandardCharsets.UTF_8)) {
			for (String line; (line = in.readLine()) != null;) {
				if (line.startsWith("@@STEP ")) {
					String[] p = line.substring(7).split(" ", 2);
					begin(Integer.parseInt(p[0].split("/")[0]) - 1, p.length > 1 ? p[1] : "");
				} else if (line.startsWith("@@FAIL")) {
					fail = line.substring(6).trim();
				}
				log(line.startsWith("@@") ? line.substring(2) : line);
			}
		}
		int code = proc.waitFor();
		proc = null;
		if (cancelled) {
			throw new CancellationException();
		}
		if (code != 0) {
			throw new IOException(fail != null ? "The pack couldn't be built: " + fail + "." : "The pack builder stopped (exit " + code + ").");
		}
	}

	/** uv (it runs the converter with its Python packages). */
	private Path uv() throws IOException {
		Path p = Tools.uv(repo);
		if (p == null) {
			throw new IOException("uv isn't installed, and the pack builder runs with it: download it on the Counter-Strike page.");
		}
		return p;
	}

	/**
	 * The folder with the converter: the Counter-Craft 2 folder the installer is in (when run from the source), else the
	 * converter bundled in the installer, unpacked to %LOCALAPPDATA%\Counter-Craft 2 (its tools/ are downloaded there too).
	 */
	private static Path repo() {
		for (Path p = Install.self(); p != null; p = p.getParent()) {
			if (Files.isRegularFile(p.resolve("converter/build_pack.py"))) {
				return p;
			}
		}
		Path home = Path.of(Detect.env("LOCALAPPDATA", System.getProperty("user.home")), Install.NAME);
		try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(Install.self().toFile())) {
			boolean any = false;
			for (java.util.zip.ZipEntry e : java.util.Collections.list(z.entries())) {
				if (e.getName().startsWith("bundle/converter/") && !e.isDirectory()) {
					Path f = home.resolve(e.getName().substring("bundle/".length())).normalize();
					Files.createDirectories(f.getParent());
					try (InputStream in = z.getInputStream(e)) {
						Files.copy(in, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
					any = true;
				}
			}
			return any ? home : null;
		} catch (IOException e) {
			return null;
		}
	}

	private static void deleteTree(Path dir) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (var s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException ignored) {
			// left for the next build
		}
	}

	private void log(String line) {
		if (cancelled && proc == null) {
			throw new CancellationException();
		}
		PrintWriter f = logFile;
		if (f != null) {
			f.println(line);
		}
		post(() -> {
			log.addLast(line);
			while (log.size() > 300) {
				log.removeFirst();
			}
		});
	}

	private void post(Runnable r) {
		SwingUtilities.invokeLater(() -> {
			r.run();
			repaint();
		});
	}

	private void finish(boolean success, List<String> lines, Path at, int to) {
		boolean updating = existingAtStart;
		post(() -> {
			if (success) {
				doneLauncher = to;
				doneUpdate = updating;
			}
			long now = System.currentTimeMillis();
			if (current >= 0 && current < took.length) {
				took[current] = (now - stageStart) / 1000;
			}
			if (!success && !cancelled) {
				failedAt = Math.max(current, 0);
			}
			running = false;
			ok = success;
			runEnd = now;
			result.clear();
			result.addAll(lines);
			opened = at;
			tick.stop();
			if (success) {
				go(FINISH);
			}
			if (logFile != null) {
				logFile.close();
				logFile = null;
			}
		});
	}
}
