package dev.countercraft.server;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * Counter-Craft 2's dedicated server window, in place of Minecraft's (DedicatedServerGuiMixin), in the look of CC2's
 * menus (the stats screens' dark header, tab bar and footer, gold accent, CS2 cards, team colours) and with more to see:
 * <ul>
 * <li>Overview: players, ticks per second and a tick time graph, memory, uptime, mods, and Counter-Craft's round (the
 * day, its phase and countdown, the players in the round and those waiting for Another Chance).</li>
 * <li>Players: everyone online with team, state, health, money, kills, deaths, days survived, ping and position.</li>
 * <li>Console: the server log, coloured by level.</li>
 * <li>Mods: every Fabric mod loaded.</li>
 * <li>Bans: banned players and addresses, each with an Unban button (Minecraft's pardon / pardon-ip: at once, no
 * restart).</li>
 * </ul>
 * A command line under every page (Up / Down for history). Closing the window stops the server, as Minecraft's does.
 * Text uses the system font: Minecraft's font isn't in the server jar and can't be shipped with the mod. Only Swing and
 * {@link ServerStatus}: no game types, so it can't touch the game from Swing's thread.
 */
public final class CcServerGui {
	static final Color BG = new Color(0x0E1012);
	static final Color HEADER_TOP = new Color(0x1C2026);
	static final Color HEADER_BOTTOM = new Color(0x121519);
	static final Color BAR = new Color(0x0B0D10);
	static final Color LINE = new Color(0x2A2F36);
	static final Color ACCENT = new Color(0xDCB45A);
	static final Color TEXT = new Color(0xE8E8E8);
	static final Color SOFT = new Color(0xBFBFBF);
	static final Color DIM = new Color(0x8A8A8A);
	static final Color GOOD = new Color(0x7ED37E);
	static final Color WARN = new Color(0xE0C050);
	static final Color BAD = new Color(0xE05050);
	static final Color T = new Color(0xEABE54);
	static final Color CT = new Color(0x6F9CE6);
	static final String[] TABS = {"Overview", "Players", "Console", "Mods", "Bans"};
	static final String[] TAB_HELP = {"The server at a glance: players, performance, Counter-Craft's round.",
			"Everyone online: team, state, health, money, score and where they are.", "The server log, coloured by level.",
			"The Fabric mods this server runs.", "Who's banned. Unban takes effect at once (no restart)."};

	private static volatile boolean open;

	private final Supplier<ServerStatus> status;
	private final Consumer<String> command;
	private final Runnable stop;
	private final JFrame frame;
	private final CardLayout cards = new CardLayout();
	private final JPanel pages = new JPanel(cards);
	private final JTextPane console = new JTextPane();
	private final JTextField input = new JTextField();
	private final List<String> history = new ArrayList<>();
	private int historyAt;
	private int tab;
	private int hoverTab = -1;
	private boolean hoverStop;
	private volatile boolean stopping;
	private final Font font;
	private final Font mono;
	private final Header header = new Header();
	private final TabBar tabBar = new TabBar();
	private final Footer footer = new Footer();
	/** Redraws twice a second; stopped on close (a running Swing timer keeps the JVM from exiting). */
	private final Timer refresh;

	private CcServerGui(String title, Supplier<ServerStatus> status, Consumer<String> command, Runnable stop) {
		this.status = status;
		this.command = command;
		this.stop = stop;
		this.font = pick("Segoe UI", "Dialog", Font.PLAIN, 13);
		this.mono = pick("Consolas", Font.MONOSPACED, Font.PLAIN, 12);
		frame = new JFrame(title);
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				requestStop();
			}
		});
		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(BG);
		JPanel top = new JPanel(new BorderLayout());
		top.add(header, BorderLayout.NORTH);
		top.add(tabBar, BorderLayout.SOUTH);
		root.add(top, BorderLayout.NORTH);
		pages.setBackground(BG);
		pages.add(scroll(new Overview()), TABS[0]);
		pages.add(scroll(new Players()), TABS[1]);
		pages.add(consolePage(), TABS[2]);
		pages.add(scroll(new Mods()), TABS[3]);
		pages.add(scroll(new Bans()), TABS[4]);
		root.add(pages, BorderLayout.CENTER);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(commandBar(), BorderLayout.NORTH);
		bottom.add(footer, BorderLayout.SOUTH);
		root.add(bottom, BorderLayout.SOUTH);
		frame.setContentPane(root);
		frame.setMinimumSize(new Dimension(900, 600));
		frame.setSize(1180, 760);
		frame.setLocationRelativeTo(null);
		refresh = new Timer(500, e -> frame.repaint());
		refresh.start();
	}

	/** Open the window (on Swing's thread); {@code stop} is run on its own thread when it's closed. */
	public static CcServerGui open(String title, Supplier<ServerStatus> status, Consumer<String> command, Runnable stop) {
		CcServerGui[] gui = new CcServerGui[1];
		try {
			SwingUtilities.invokeAndWait(() -> {
				gui[0] = new CcServerGui(title, status, command, stop);
				gui[0].frame.setVisible(true);
				gui[0].input.requestFocusInWindow(); // ready to type a command
			});
		} catch (Exception e) {
			throw new IllegalStateException("server window", e);
		}
		open = true;
		return gui[0];
	}

	public static boolean isOpen() {
		return open;
	}

	/** The frame, for a screenshot (GuiPreview). */
	JFrame frame() {
		return frame;
	}

	void showTab(int i) {
		tab = i;
		cards.show(pages, TABS[i]);
		frame.repaint();
		if (i == 2) {
			input.requestFocusInWindow();
		}
	}

	private void requestStop() {
		if (stopping) {
			return;
		}
		stopping();
		Thread t = new Thread(stop, "CC2 server window stop");
		t.setDaemon(true);
		t.start();
	}

	/** The server is going down (the window stays up, saying so, until {@link #close}). */
	public void stopping() {
		stopping = true;
		SwingUtilities.invokeLater(() -> {
			frame.setTitle(frame.getTitle() + " - stopping");
			frame.repaint();
		});
	}

	public void close() {
		open = false;
		SwingUtilities.invokeLater(() -> {
			refresh.stop();
			frame.dispose();
		});
	}

	/** A line of the server log (any thread). */
	public void log(String line) {
		SwingUtilities.invokeLater(() -> append(line));
	}

	private void append(String line) {
		StyledDocument doc = console.getStyledDocument();
		SimpleAttributeSet a = new SimpleAttributeSet();
		Color c = line.contains("ERROR]") || line.contains("FATAL]") ? BAD : line.contains("WARN]") ? WARN : SOFT;
		StyleConstants.setForeground(a, c);
		JScrollBar bar = ((JScrollPane) console.getParent().getParent()).getVerticalScrollBar();
		boolean atEnd = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 8;
		try {
			doc.insertString(doc.getLength(), line.endsWith("\n") ? line : line + "\n", a);
			if (doc.getLength() > 600_000) {
				doc.remove(0, doc.getLength() - 450_000); // keep the latest lines
			}
		} catch (BadLocationException ignored) {
			// nothing to do
		}
		if (atEnd) {
			console.setCaretPosition(doc.getLength());
		}
	}

	private void send() {
		String s = input.getText().trim();
		if (s.isEmpty()) {
			return;
		}
		if (s.startsWith("/")) {
			s = s.substring(1);
		}
		history.add(s);
		historyAt = history.size();
		input.setText("");
		append("> " + s);
		command.accept(s);
	}

	// ---- layout pieces ----

	private JScrollPane scroll(JComponent page) {
		JScrollPane sp = new JScrollPane(page, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getViewport().setBackground(BG);
		sp.getVerticalScrollBar().setUnitIncrement(24);
		sp.getVerticalScrollBar().setUI(new ThinBar());
		sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));
		return sp;
	}

	private JComponent consolePage() {
		console.setEditable(false);
		console.setBackground(new Color(0x0A0C0E));
		console.setForeground(SOFT);
		console.setFont(mono);
		console.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
		console.setCaretColor(TEXT);
		JScrollPane sp = scroll(console);
		sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		sp.getHorizontalScrollBar().setUI(new ThinBar());
		sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));
		sp.getHorizontalScrollBar().setUnitIncrement(24);
		sp.setCorner(JScrollPane.LOWER_RIGHT_CORNER, new JPanel() {
			{
				setBackground(new Color(0x0A0C0E));
			}
		});
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(BG);
		p.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
		JPanel framed = new JPanel(new BorderLayout());
		framed.setBorder(BorderFactory.createLineBorder(LINE));
		framed.add(sp);
		p.add(framed);
		return p;
	}

	private JComponent commandBar() {
		JPanel bar = new JPanel(new BorderLayout(8, 0)) {
			@Override
			protected void paintComponent(Graphics g) {
				g.setColor(BAR);
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(LINE);
				g.fillRect(0, 0, getWidth(), 1);
			}
		};
		bar.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
		JComponent prompt = new JComponent() {
			@Override
			protected void paintComponent(Graphics g0) {
				Graphics2D g = gfx(g0);
				g.setFont(font.deriveFont(Font.BOLD, 13f));
				g.setColor(ACCENT);
				g.drawString("COMMAND", 0, getHeight() / 2 + 5);
			}
		};
		prompt.setPreferredSize(new Dimension(76, 28));
		input.setFont(mono.deriveFont(13f));
		input.setBackground(new Color(0x15181C));
		input.setForeground(TEXT);
		input.setCaretColor(TEXT);
		input.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
		input.addActionListener(e -> send());
		input.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_UP && historyAt > 0) {
					input.setText(history.get(--historyAt));
				} else if (e.getKeyCode() == KeyEvent.VK_DOWN && historyAt < history.size()) {
					historyAt++;
					input.setText(historyAt < history.size() ? history.get(historyAt) : "");
				}
			}
		});
		bar.add(prompt, BorderLayout.WEST);
		bar.add(input, BorderLayout.CENTER);
		return bar;
	}

	// ---- drawing helpers ----

	private static Font pick(String name, String fallback, int style, int size) {
		Font f = new Font(name, style, size);
		return f.getFamily().equalsIgnoreCase(name) ? f : new Font(fallback, style, size);
	}

	static Graphics2D gfx(Graphics g0) {
		Graphics2D g = (Graphics2D) g0;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		return g;
	}

	static Color alpha(Color c, int a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	/** CS2's card (CsUi.card): a soft top-lit fill, a light top edge, a dark bottom edge; {@code accent}: a bar down the left. */
	static void card(Graphics2D g, int x, int y, int w, int h, Color accent) {
		g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 0x22), 0, y + h, new Color(255, 255, 255, 0x0A)));
		g.fillRect(x, y, w, h);
		g.setColor(new Color(255, 255, 255, 0x24));
		g.fillRect(x, y, w, 1);
		g.setColor(new Color(0, 0, 0, 0x50));
		g.fillRect(x, y + h - 1, w, 1);
		if (accent != null) {
			g.setColor(accent);
			g.fillRect(x, y, 3, h);
		}
	}

	/** A section title in the accent colour with a faint rule after it (the stats screens'). */
	void section(Graphics2D g, String title, int x, int y, int w) {
		g.setFont(font.deriveFont(Font.BOLD, 12f));
		g.setColor(ACCENT);
		String s = title.toUpperCase(Locale.ROOT);
		g.drawString(s, x, y + 12);
		int tw = g.getFontMetrics().stringWidth(s);
		g.setColor(alpha(ACCENT, 0x30));
		g.fillRect(x + tw + 8, y + 7, Math.max(0, w - tw - 8), 1);
	}

	/** A small coloured tag: tinted fill, a solid bar on the left. */
	int chip(Graphics2D g, String text, int x, int y, Color c) {
		g.setFont(font.deriveFont(Font.BOLD, 11f));
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text) + 12;
		g.setColor(alpha(c, 0x45));
		g.fillRect(x, y, w, 17);
		g.setColor(c);
		g.fillRect(x, y, 2, 17);
		g.setColor(TEXT);
		g.drawString(text, x + 7, y + 13);
		return w;
	}

	/** A stat card: the label, a big value in {@code color}, a line under it. */
	void stat(Graphics2D g, int x, int y, int w, int h, String label, String value, Color color, String sub) {
		card(g, x, y, w, h, null);
		g.setFont(font.deriveFont(Font.PLAIN, 11f));
		g.setColor(DIM);
		g.drawString(label.toUpperCase(Locale.ROOT), x + 12, y + 18);
		g.setFont(font.deriveFont(Font.BOLD, 24f));
		g.setColor(color);
		g.drawString(value, x + 12, y + 46);
		if (sub != null) {
			g.setFont(font.deriveFont(Font.PLAIN, 11f));
			g.setColor(SOFT);
			g.drawString(fit(g, sub, w - 24), x + 12, y + 64);
		}
	}

	static String fit(Graphics2D g, String s, int w) {
		FontMetrics fm = g.getFontMetrics();
		if (fm.stringWidth(s) <= w) {
			return s;
		}
		while (!s.isEmpty() && fm.stringWidth(s + "...") > w) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "...";
	}

	static Color team(String team) {
		return "ct".equals(team) ? CT : "t".equals(team) ? T : DIM;
	}

	static String teamName(String team) {
		return "ct".equals(team) ? "CT" : "t".equals(team) ? "T" : "-";
	}

	static Color stateColor(String state) {
		return switch (state) {
			case "Alive", "Creative" -> GOOD;
			case "Down", "Respawning" -> WARN;
			case "Out", "Dead" -> BAD;
			default -> DIM;
		};
	}

	static Color tpsColor(double tps, double max) {
		return tps >= max * 0.95 ? GOOD : tps >= max * 0.75 ? WARN : BAD;
	}

	static String uptime(long ms) {
		long s = ms / 1000;
		long h = s / 3600;
		long m = s / 60 % 60;
		return h > 0 ? h + "h " + String.format("%02dm", m) : m + "m " + String.format("%02ds", s % 60);
	}

	static String mb(long bytes) {
		return (bytes >> 20) + " MB";
	}

	static String clock(int seconds) {
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	// ---- components ----

	private final class Header extends JComponent {
		Header() {
			setPreferredSize(new Dimension(0, 64));
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (stopBounds()[0] <= e.getX() && e.getX() < stopBounds()[0] + stopBounds()[1] && e.getY() > 16 && e.getY() < 48) {
						requestStop();
					}
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hoverStop = false;
					repaint();
				}
			});
			addMouseMotionListener(new MouseAdapter() {
				@Override
				public void mouseMoved(MouseEvent e) {
					boolean on = stopBounds()[0] <= e.getX() && e.getX() < stopBounds()[0] + stopBounds()[1] && e.getY() > 16 && e.getY() < 48;
					if (on != hoverStop) {
						hoverStop = on;
						setCursor(on ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
						repaint();
					}
				}
			});
		}

		/** The Stop server button: x, width. */
		int[] stopBounds() {
			return new int[] {getWidth() - 16 - 120, 120};
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = gfx(g0);
			ServerStatus s = status.get();
			g.setPaint(new GradientPaint(0, 0, HEADER_TOP, 0, getHeight(), HEADER_BOTTOM));
			g.fillRect(0, 0, getWidth(), getHeight());
			emblem(g, 16, 12, 40);
			g.setFont(font.deriveFont(Font.BOLD, 20f));
			g.setColor(TEXT);
			String name = s.world().isEmpty() ? "Counter-Craft 2 Server" : s.world();
			g.drawString(name, 68, 31);
			int nx = 68 + g.getFontMetrics().stringWidth(name) + 10;
			chip(g, stopping ? "STOPPING" : s.world().isEmpty() ? "STARTING" : "RUNNING", nx, 16, stopping ? BAD : s.world().isEmpty() ? WARN : GOOD);
			g.setFont(font.deriveFont(Font.PLAIN, 12f));
			g.setColor(SOFT);
			String line = s.world().isEmpty() ? "Starting the server..." : s.mode() + "   ·   " + s.version();
			g.drawString(fit(g, line, getWidth() - 68 - 300), 68, 50);
			// right: uptime and the stop button
			int[] b = stopBounds();
			g.setFont(font.deriveFont(Font.PLAIN, 11f));
			g.setColor(DIM);
			String up = "UPTIME";
			g.drawString(up, b[0] - 110, 28);
			g.setFont(font.deriveFont(Font.BOLD, 16f));
			g.setColor(TEXT);
			g.drawString(uptime(s.uptimeMillis()), b[0] - 110, 47);
			g.setColor(hoverStop && !stopping ? alpha(BAD, 0x60) : alpha(BAD, 0x28));
			g.fillRect(b[0], 18, b[1], 28);
			g.setColor(BAD);
			g.drawRect(b[0], 18, b[1] - 1, 27);
			g.setFont(font.deriveFont(Font.BOLD, 12f));
			g.setColor(stopping ? DIM : TEXT);
			String st = stopping ? "STOPPING..." : "STOP SERVER";
			g.drawString(st, b[0] + (b[1] - g.getFontMetrics().stringWidth(st)) / 2, 37);
		}
	}

	/** Counter-Craft 2's mark: "CC2" in a gold ring (drawn: the server has no textures). */
	private static void emblem(Graphics2D g, int x, int y, int s) {
		g.setColor(new Color(0x2A2418));
		g.fillOval(x, y, s, s);
		g.setStroke(new BasicStroke(2.5f));
		g.setColor(ACCENT);
		g.drawOval(x + 2, y + 2, s - 4, s - 4);
		g.setStroke(new BasicStroke(1f));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) (s * 0.32f)));
		FontMetrics fm = g.getFontMetrics();
		String t = "CC2";
		g.drawString(t, x + (s - fm.stringWidth(t)) / 2f, y + (s + fm.getAscent() - fm.getDescent()) / 2f);
	}

	private final class TabBar extends JComponent {
		TabBar() {
			setPreferredSize(new Dimension(0, 32));
			MouseAdapter m = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					int i = at(e.getX());
					if (i >= 0) {
						showTab(i);
					}
				}

				@Override
				public void mouseMoved(MouseEvent e) {
					int i = at(e.getX());
					if (i != hoverTab) {
						hoverTab = i;
						setCursor(i >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
						repaint();
						footer.repaint();
					}
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hoverTab = -1;
					repaint();
					footer.repaint();
				}
			};
			addMouseListener(m);
			addMouseMotionListener(m);
		}

		/** The tab's name, with a count where it has one. */
		private String label(int i) {
			return switch (i) {
				case 1 -> TABS[i] + "  " + status.get().online();
				case 3 -> TABS[i] + "  " + status.get().mods().size();
				case 4 -> TABS[i] + "  " + status.get().bans().size();
				default -> TABS[i];
			};
		}

		private int width(Graphics2D g, int i) {
			return g.getFontMetrics(font.deriveFont(Font.BOLD, 13f)).stringWidth(label(i)) + 30;
		}

		int at(int mx) {
			Graphics2D g = (Graphics2D) getGraphics();
			if (g == null) {
				return -1;
			}
			int x = 16;
			for (int i = 0; i < TABS.length; i++) {
				int w = width(g, i);
				if (mx >= x && mx < x + w) {
					return i;
				}
				x += w + 2;
			}
			return -1;
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = gfx(g0);
			g.setColor(BAR);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(LINE);
			g.fillRect(0, getHeight() - 1, getWidth(), 1);
			int x = 16;
			for (int i = 0; i < TABS.length; i++) {
				int w = width(g, i);
				boolean sel = i == tab;
				if (sel) {
					g.setColor(alpha(ACCENT, 0x30));
					g.fillRect(x, 0, w, getHeight() - 1);
					g.setColor(ACCENT);
					g.fillRect(x, getHeight() - 3, w, 2);
				} else if (i == hoverTab) {
					g.setColor(new Color(255, 255, 255, 0x18));
					g.fillRect(x, 0, w, getHeight() - 1);
				}
				g.setFont(font.deriveFont(sel ? Font.BOLD : Font.PLAIN, 13f));
				g.setColor(sel ? Color.WHITE : i == hoverTab ? TEXT : DIM);
				g.drawString(label(i), x + 15, 21);
				x += w + 2;
			}
		}
	}

	private final class Footer extends JComponent {
		Footer() {
			setPreferredSize(new Dimension(0, 28));
		}

		@Override
		protected void paintComponent(Graphics g0) {
			Graphics2D g = gfx(g0);
			g.setColor(BAR);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(LINE);
			g.fillRect(0, 0, getWidth(), 1);
			ServerStatus s = status.get();
			g.setFont(font.deriveFont(Font.PLAIN, 12f));
			g.setColor(hoverTab >= 0 ? new Color(0x8FB8E8) : DIM);
			String hint = hoverTab >= 0 ? TAB_HELP[hoverTab] : "Closing this window stops the server. Commands go in without the slash.";
			g.drawString(hint, 16, 18);
			String right = s.port() > 0 ? "Port " + s.port() + "   ·   " + s.online() + " / " + s.maxPlayers() + " players" : "";
			g.setColor(DIM);
			g.drawString(right, getWidth() - 16 - g.getFontMetrics().stringWidth(right), 18);
		}
	}

	/** A page that sizes its height to what it draws (it scrolls when the window is short), with clickable areas. */
	private abstract class Page extends JComponent {
		private int contentHeight = 400;
		/** This paint's buttons: where, and what a press there does. */
		final List<Object[]> hits = new ArrayList<>();
		int mouseX = -1;
		int mouseY = -1;

		Page() {
			MouseAdapter m = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					for (Object[] h : List.copyOf(hits)) {
						if (((java.awt.Rectangle) h[0]).contains(e.getPoint())) {
							((Runnable) h[1]).run();
							repaint();
							return;
						}
					}
				}

				@Override
				public void mouseMoved(MouseEvent e) {
					mouseX = e.getX();
					mouseY = e.getY();
					boolean on = hits.stream().anyMatch(h -> ((java.awt.Rectangle) h[0]).contains(e.getPoint()));
					setCursor(on ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					mouseX = -1;
					repaint();
				}
			};
			addMouseListener(m);
			addMouseMotionListener(m);
		}

		/** A button drawn this paint; true while the mouse is on it. */
		boolean button(java.awt.Rectangle r, Runnable action) {
			hits.add(new Object[] {r, action});
			return r.contains(mouseX, mouseY);
		}

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(0, contentHeight);
		}

		@Override
		protected final void paintComponent(Graphics g0) {
			Graphics2D g = gfx(g0);
			g.setColor(BG);
			g.fillRect(0, 0, getWidth(), getHeight());
			hits.clear();
			int h = draw(g, status.get(), 16, 16, getWidth() - 32) + 16;
			if (h != contentHeight) {
				contentHeight = h;
				revalidate();
			}
		}

		/** Draw from {@code y}; returns where it ended. */
		abstract int draw(Graphics2D g, ServerStatus s, int x, int y, int w);
	}

	private final class Overview extends Page {
		@Override
		int draw(Graphics2D g, ServerStatus s, int x, int y, int w) {
			// the stat cards
			int n = 6;
			int gap = 10;
			int cw = (w - gap * (n - 1)) / n;
			int ch = 76;
			double maxTps = 20;
			stat(g, x, y, cw, ch, "Players", s.online() + " / " + s.maxPlayers(), TEXT,
					s.players().stream().filter(ServerStatus.Player::bot).count() + " bots");
			stat(g, x + (cw + gap), y, cw, ch, "Ticks per second", String.format(Locale.ROOT, "%.1f", s.tps()), tpsColor(s.tps(), maxTps),
					String.format(Locale.ROOT, "%.1f ms per tick", s.mspt()));
			double mem = s.memMax() > 0 ? (double) s.memUsed() / s.memMax() : 0;
			stat(g, x + 2 * (cw + gap), y, cw, ch, "Memory", (int) Math.round(mem * 100) + "%", mem < 0.75 ? TEXT : mem < 0.9 ? WARN : BAD,
					mb(s.memUsed()) + " of " + mb(s.memMax()));
			g.setColor(new Color(255, 255, 255, 0x20));
			g.fillRect(x + 2 * (cw + gap) + 12, y + ch - 8, cw - 24, 3);
			g.setColor(mem < 0.75 ? ACCENT : mem < 0.9 ? WARN : BAD);
			g.fillRect(x + 2 * (cw + gap) + 12, y + ch - 8, (int) ((cw - 24) * Math.min(1, mem)), 3);
			stat(g, x + 3 * (cw + gap), y, cw, ch, "Uptime", uptime(s.uptimeMillis()), TEXT, "since the window opened");
			ServerStatus.Round r = s.round();
			stat(g, x + 4 * (cw + gap), y, cw, ch, "Day", r == null ? "-" : Integer.toString(r.day()), r == null ? DIM : ACCENT,
					r == null ? "not a Counter-Craft world" : "days survived");
			stat(g, x + 5 * (cw + gap), y, cw, ch, "Mods", Integer.toString(s.mods().size()), TEXT, "+" + s.hiddenMods() + " bundled modules");
			y += ch + 22;

			// tick time graph (left) and the round (right)
			int lw = (int) (w * 0.62);
			int rw = w - lw - 16;
			section(g, "Tick time", x, y, lw);
			section(g, r != null ? "Counter-Craft round" : "World", x + lw + 16, y, rw);
			y += 22;
			int gh = 170;
			graph(g, s, x, y, lw, gh);
			if (r != null) {
				round(g, r, x + lw + 16, y, rw, gh);
			} else {
				world(g, s, x + lw + 16, y, rw, gh);
			}
			y += gh + 22;

			// who's on
			section(g, "Players online", x, y, w);
			y += 22;
			if (s.players().isEmpty()) {
				g.setFont(font.deriveFont(Font.PLAIN, 13f));
				g.setColor(DIM);
				g.drawString("Nobody's online.", x, y + 14);
				return y + 24;
			}
			int cols = Math.max(1, Math.min(3, w / 300));
			int pw = (w - (cols - 1) * 10) / cols;
			for (int i = 0; i < s.players().size(); i++) {
				ServerStatus.Player p = s.players().get(i);
				int px = x + (i % cols) * (pw + 10);
				int py = y + (i / cols) * 46;
				card(g, px, py, pw, 40, team(p.team()));
				g.setFont(font.deriveFont(Font.BOLD, 13f));
				g.setColor(team(p.team()).equals(DIM) ? TEXT : team(p.team()));
				g.drawString(fit(g, p.name(), pw - 140), px + 14, py + 18);
				g.setFont(font.deriveFont(Font.PLAIN, 11f));
				g.setColor(stateColor(p.state()));
				g.drawString(p.state() + (p.bot() ? "  ·  bot" : ""), px + 14, py + 33);
				healthBar(g, px + pw - 118, py + 14, 70, p.health());
				g.setFont(font.deriveFont(Font.PLAIN, 11f));
				g.setColor(SOFT);
				String ping = p.ping() + " ms";
				g.drawString(ping, px + pw - 12 - g.getFontMetrics().stringWidth(ping), py + 24);
			}
			return y + ((s.players().size() + cols - 1) / cols) * 46;
		}

		private void graph(Graphics2D g, ServerStatus s, int x, int y, int w, int h) {
			card(g, x, y, w, h, null);
			double[] v = s.msptHistory();
			double top = 60;
			for (double d : v) {
				top = Math.max(top, d * 1.15);
			}
			int gx = x + 44;
			int gw = w - 56;
			int gy = y + 12;
			int gh = h - 34;
			g.setFont(font.deriveFont(Font.PLAIN, 10f));
			for (int ms : new int[] {0, 25, 50}) {
				int ly = gy + gh - (int) (gh * ms / top);
				g.setColor(ms == 50 ? alpha(BAD, 0x70) : new Color(255, 255, 255, 0x14));
				g.fillRect(gx, ly, gw, 1);
				g.setColor(DIM);
				g.drawString(ms + " ms", x + 8, ly + 4);
			}
			if (v.length > 1) {
				Path2D line = new Path2D.Float();
				Path2D area = new Path2D.Float();
				for (int i = 0; i < v.length; i++) {
					float px = gx + gw * (float) (i + 120 - v.length) / 119f;
					float py = gy + gh - (float) (gh * Math.min(v[i], top) / top);
					if (i == 0) {
						line.moveTo(px, py);
						area.moveTo(px, gy + gh);
					}
					line.lineTo(px, py);
					area.lineTo(px, py);
				}
				area.lineTo(gx + gw, gy + gh);
				area.closePath();
				g.setPaint(new GradientPaint(0, gy, alpha(ACCENT, 0x50), 0, gy + gh, alpha(ACCENT, 0x05)));
				g.fill(area);
				g.setColor(ACCENT);
				g.setStroke(new BasicStroke(1.6f));
				g.draw(line);
				g.setStroke(new BasicStroke(1f));
			}
			g.setColor(DIM);
			g.drawString("last 60 s   ·   50 ms = the whole tick budget (below it, 20 ticks per second)", gx, y + h - 8);
		}

		private void round(Graphics2D g, ServerStatus.Round r, int x, int y, int w, int h) {
			Color phase = switch (r.phase()) {
				case "BUY PHASE" -> GOOD;
				case "BOMB" -> BAD;
				case "NIGHT" -> new Color(0x8FA0E8);
				default -> ACCENT;
			};
			card(g, x, y, w, h, phase);
			g.setFont(font.deriveFont(Font.PLAIN, 11f));
			g.setColor(DIM);
			g.drawString("DAY " + r.day(), x + 16, y + 22);
			g.setFont(font.deriveFont(Font.BOLD, 26f));
			g.setColor(phase);
			g.drawString(r.over() ? "GAME OVER" : r.phase(), x + 16, y + 54);
			g.setFont(font.deriveFont(Font.PLAIN, 13f));
			g.setColor(SOFT);
			String next = switch (r.phase()) {
				case "BUY PHASE" -> "buying closes in ";
				case "DAY" -> "night falls in ";
				case "NIGHT" -> "the bomb in ";
				default -> "dawn in ";
			};
			if (!r.over()) {
				g.drawString(next + clock(r.secondsLeft()), x + 16, y + 76);
			}
			int cy = y + h - 34;
			int cx = x + 16;
			cx += chip(g, r.alive() + " ALIVE", cx, cy, GOOD) + 6;
			if (r.down() > 0) {
				chip(g, r.down() + " WAITING FOR ANOTHER CHANCE", cx, cy, WARN);
			}
		}

		private void world(Graphics2D g, ServerStatus s, int x, int y, int w, int h) {
			card(g, x, y, w, h, ACCENT);
			int ly = y + 26;
			for (String[] row : new String[][] {{"Mode", s.mode()}, {"Message", s.motd()}, {"Port", Integer.toString(s.port())}}) {
				g.setFont(font.deriveFont(Font.PLAIN, 11f));
				g.setColor(DIM);
				g.drawString(row[0].toUpperCase(Locale.ROOT), x + 16, ly);
				g.setFont(font.deriveFont(Font.PLAIN, 13f));
				g.setColor(TEXT);
				g.drawString(fit(g, row[1], w - 32), x + 16, ly + 17);
				ly += 44;
			}
		}
	}

	/** Health: a bar in the colour of how much is left, and the percentage. */
	void healthBar(Graphics2D g, int x, int y, int w, float f) {
		Color c = f > 0.5f ? GOOD : f > 0.25f ? WARN : BAD;
		g.setColor(new Color(255, 255, 255, 0x20));
		g.fillRect(x, y, w, 5);
		g.setColor(c);
		g.fillRect(x, y, (int) (w * Math.max(0, Math.min(1, f))), 5);
		g.setFont(font.deriveFont(Font.PLAIN, 10f));
		g.setColor(SOFT);
		g.drawString(Math.round(f * 100) + "%", x, y + 17);
	}

	private final class Players extends Page {
		private static final String[] COLS = {"PLAYER", "TEAM", "STATE", "HEALTH", "MONEY", "K", "D", "STREAK", "PING", "WHERE"};
		private static final float[] AT = {0f, 0.22f, 0.28f, 0.39f, 0.5f, 0.58f, 0.62f, 0.66f, 0.72f, 0.78f};

		@Override
		int draw(Graphics2D g, ServerStatus s, int x, int y, int w) {
			section(g, s.online() + " of " + s.maxPlayers() + " players online", x, y, w);
			y += 26;
			g.setFont(font.deriveFont(Font.BOLD, 11f));
			g.setColor(DIM);
			for (int i = 0; i < COLS.length; i++) {
				g.drawString(COLS[i], x + 14 + (int) (AT[i] * (w - 28)), y + 12);
			}
			y += 20;
			for (ServerStatus.Player p : s.players()) {
				Color tc = team(p.team());
				card(g, x, y, w, 36, tc);
				int cy = y + 23;
				g.setFont(font.deriveFont(Font.BOLD, 13f));
				g.setColor(tc.equals(DIM) ? TEXT : tc);
				String name = p.name();
				g.drawString(fit(g, name, (int) (0.2f * (w - 28)) - 30), x + 14, cy);
				if (p.bot()) {
					int nx = x + 14 + g.getFontMetrics().stringWidth(fit(g, name, (int) (0.2f * (w - 28)) - 30)) + 6;
					chip(g, "BOT", nx, y + 10, DIM);
				}
				chip(g, teamName(p.team()), x + 14 + (int) (AT[1] * (w - 28)), y + 10, tc);
				g.setFont(font.deriveFont(Font.PLAIN, 12f));
				g.setColor(stateColor(p.state()));
				g.drawString(p.state(), x + 14 + (int) (AT[2] * (w - 28)), cy);
				healthBar(g, x + 14 + (int) (AT[3] * (w - 28)), y + 8, (int) (0.08f * (w - 28)), p.health());
				g.setFont(font.deriveFont(Font.PLAIN, 12f));
				g.setColor(p.money() < 0 ? DIM : GOOD);
				g.drawString(p.money() < 0 ? "-" : String.format(Locale.ROOT, "$%,d", p.money()), x + 14 + (int) (AT[4] * (w - 28)), cy);
				g.setColor(TEXT);
				g.drawString(Integer.toString(p.kills()), x + 14 + (int) (AT[5] * (w - 28)), cy);
				g.drawString(Integer.toString(p.deaths()), x + 14 + (int) (AT[6] * (w - 28)), cy);
				g.drawString(p.streak() + (p.streak() == 1 ? " day" : " days"), x + 14 + (int) (AT[7] * (w - 28)), cy);
				g.setColor(p.ping() < 80 ? GOOD : p.ping() < 160 ? WARN : BAD);
				g.drawString(p.ping() + " ms", x + 14 + (int) (AT[8] * (w - 28)), cy);
				g.setColor(DIM);
				int wx = x + 14 + (int) (AT[9] * (w - 28));
				g.drawString(fit(g, p.where(), x + w - 14 - wx), wx, cy);
				y += 40;
			}
			if (s.players().isEmpty()) {
				g.setFont(font.deriveFont(Font.PLAIN, 13f));
				g.setColor(DIM);
				g.drawString("Nobody's online.", x, y + 14);
				y += 24;
			}
			return y;
		}
	}

	private final class Mods extends Page {
		@Override
		int draw(Graphics2D g, ServerStatus s, int x, int y, int w) {
			section(g, s.mods().size() + " mods loaded  (+" + s.hiddenMods() + " modules bundled inside them)", x, y, w);
			y += 26;
			int cols = Math.max(1, Math.min(3, w / 340));
			int cw = (w - (cols - 1) * 10) / cols;
			for (int i = 0; i < s.mods().size(); i++) {
				ServerStatus.Mod m = s.mods().get(i);
				int mx = x + (i % cols) * (cw + 10);
				int my = y + (i / cols) * 62;
				boolean cc = m.id().equals("countercraft");
				card(g, mx, my, cw, 56, cc ? ACCENT : m.id().startsWith("fabric") ? CT : null);
				g.setFont(font.deriveFont(Font.BOLD, 13f));
				g.setColor(cc ? ACCENT : TEXT);
				g.drawString(fit(g, m.name(), cw - 120), mx + 14, my + 21);
				g.setFont(font.deriveFont(Font.PLAIN, 11f));
				g.setColor(SOFT);
				String v = m.version();
				g.drawString(fit(g, v, 100), mx + cw - 12 - Math.min(100, g.getFontMetrics().stringWidth(v)), my + 21);
				g.setColor(DIM);
				g.drawString(fit(g, m.id() + (m.authors().isEmpty() ? "" : "   ·   " + m.authors()), cw - 28), mx + 14, my + 41);
			}
			return y + ((s.mods().size() + cols - 1) / cols) * 62;
		}
	}

	private final class Bans extends Page {
		private static final String[] COLS = {"BANNED", "REASON", "BY", "WHEN", "UNTIL"};
		private static final float[] AT = {0f, 0.24f, 0.56f, 0.68f, 0.8f};
		/** Unbans sent, shown as such until the list no longer has them. */
		private final java.util.Set<String> pending = new java.util.HashSet<>();

		@Override
		int draw(Graphics2D g, ServerStatus s, int x, int y, int w) {
			long players = s.bans().stream().filter(b -> !b.ip()).count();
			section(g, players + " banned " + (players == 1 ? "player" : "players") + "  ·  " + (s.bans().size() - players) + " banned "
					+ (s.bans().size() - players == 1 ? "address" : "addresses"), x, y, w);
			y += 26;
			pending.removeIf(k -> s.bans().stream().noneMatch(b -> key(b).equals(k)));
			if (s.bans().isEmpty()) {
				g.setFont(font.deriveFont(Font.PLAIN, 13f));
				g.setColor(DIM);
				g.drawString("Nobody's banned. Ban from the command line below: ban <player> [reason], or ban-ip <address> [reason].", x, y + 14);
				return y + 24;
			}
			int tw = w - 28 - 110;
			g.setFont(font.deriveFont(Font.BOLD, 11f));
			g.setColor(DIM);
			for (int i = 0; i < COLS.length; i++) {
				g.drawString(COLS[i], x + 14 + (int) (AT[i] * tw), y + 12);
			}
			y += 20;
			java.text.DateFormat when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
			for (ServerStatus.Ban b : s.bans()) {
				card(g, x, y, w, 40, b.ip() ? WARN : BAD);
				int cy = y + 25;
				g.setFont(font.deriveFont(Font.BOLD, 13f));
				g.setColor(TEXT);
				String name = fit(g, b.name(), (int) (0.22f * tw) - (b.ip() ? 34 : 0));
				g.drawString(name, x + 14, cy);
				if (b.ip()) {
					chip(g, "IP", x + 14 + g.getFontMetrics().stringWidth(name) + 6, y + 12, WARN);
				}
				g.setFont(font.deriveFont(Font.PLAIN, 12f));
				g.setColor(SOFT);
				g.drawString(fit(g, b.reason() == null ? "" : b.reason(), (int) (0.3f * tw)), x + 14 + (int) (AT[1] * tw), cy);
				g.drawString(fit(g, b.source() == null ? "" : b.source(), (int) (0.11f * tw)), x + 14 + (int) (AT[2] * tw), cy);
				g.setColor(DIM);
				g.drawString(b.created() > 0 ? when.format(new java.util.Date(b.created())) : "-", x + 14 + (int) (AT[3] * tw), cy);
				g.setColor(b.expires() > 0 ? WARN : SOFT);
				g.drawString(b.expires() > 0 ? when.format(new java.util.Date(b.expires())) : "Forever", x + 14 + (int) (AT[4] * tw), cy);
				// the Unban button
				java.awt.Rectangle r = new java.awt.Rectangle(x + w - 12 - 96, y + 7, 96, 26);
				boolean waiting = pending.contains(key(b));
				boolean hover = !waiting && button(r, () -> {
					pending.add(key(b));
					command.accept((b.ip() ? "pardon-ip " : "pardon ") + b.name());
				});
				g.setColor(waiting ? new Color(255, 255, 255, 0x10) : hover ? alpha(GOOD, 0x60) : alpha(GOOD, 0x26));
				g.fillRect(r.x, r.y, r.width, r.height);
				g.setColor(waiting ? DIM : GOOD);
				g.drawRect(r.x, r.y, r.width - 1, r.height - 1);
				g.setFont(font.deriveFont(Font.BOLD, 12f));
				g.setColor(waiting ? DIM : TEXT);
				String label = waiting ? "UNBANNING..." : "UNBAN";
				g.drawString(label, r.x + (r.width - g.getFontMetrics().stringWidth(label)) / 2, r.y + 17);
				y += 44;
			}
			return y;
		}

		private static String key(ServerStatus.Ban b) {
			return (b.ip() ? "ip:" : "player:") + b.name();
		}
	}

	/** A thin dark scroll bar with a gold thumb (the stats screens'). */
	private static final class ThinBar extends BasicScrollBarUI {
		@Override
		protected void configureScrollBarColors() {
			thumbColor = alpha(ACCENT, 0x90);
			trackColor = BG;
		}

		@Override
		protected javax.swing.JButton createDecreaseButton(int orientation) {
			return zero();
		}

		@Override
		protected javax.swing.JButton createIncreaseButton(int orientation) {
			return zero();
		}

		private static javax.swing.JButton zero() {
			javax.swing.JButton b = new javax.swing.JButton();
			b.setPreferredSize(new Dimension(0, 0));
			return b;
		}

		@Override
		protected void paintThumb(Graphics g, JComponent c, java.awt.Rectangle r) {
			g.setColor(thumbColor);
			if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
				g.fillRect(r.x + 2, r.y, r.width - 4, r.height);
			} else {
				g.fillRect(r.x, r.y + 2, r.width, r.height - 4);
			}
		}

		@Override
		protected void paintTrack(Graphics g, JComponent c, java.awt.Rectangle r) {
			g.setColor(new Color(0x0A0C0E));
			g.fillRect(r.x, r.y, r.width, r.height);
			g.setColor(new Color(255, 255, 255, 0x10));
			if (scrollbar.getOrientation() == JScrollBar.VERTICAL) {
				g.fillRect(r.x + 3, r.y, r.width - 6, r.height);
			} else {
				g.fillRect(r.x, r.y + 3, r.width, r.height - 6);
			}
		}
	}
}
