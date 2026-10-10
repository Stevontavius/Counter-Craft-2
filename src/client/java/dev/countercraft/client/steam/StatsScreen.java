package dev.countercraft.client.steam;

import dev.countercraft.client.loadout.CsUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The look shared by CS2 Stats and MC Stats: a header band (the subclass draws who it is), a row of page tabs, a
 * scrolling page, and a footer whose left side explains whatever the mouse is over (so every number can say what it
 * means) and whose right side has Done. Pages are drawn by the subclass; its clickable rows report themselves with
 * {@link #clickable} while drawing and get {@link #clicked} back.
 */
abstract class StatsScreen extends Screen {
	static final int ACCENT = 0xFFDCB45A;
	static final int TEXT = 0xFFE8E8E8;
	static final int SOFT = 0xFFBFBFBF;
	static final int DIM = 0xFF8A8A8A;
	static final int GOOD = 0xFF7ED37E;
	static final int BAD = 0xFFE05050;
	static final int HEADER = 60;
	static final int TABS_H = 18;
	static final int PAD = 16;

	protected final Screen parent;
	/** The page shown, per screen class (kept while the game runs). */
	private static final java.util.Map<Class<?>, Integer> PAGE = new java.util.HashMap<>();
	private double scroll;
	private int contentHeight;
	private String hint = "";
	private final java.util.List<Object[]> clickables = new java.util.ArrayList<>();

	StatsScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	// ---------------------------------------------------------------- for subclasses

	abstract String[] tabs();

	/** One-line help per tab, shown in the footer while the mouse is on the tab. */
	abstract String[] tabHelp();

	/** Draw the header's content (avatar, name, ...) in 0..{@link #HEADER}. */
	abstract void header(GuiGraphicsExtractor g, int mouseX, int mouseY);

	/** Draw the page from y (already scrolled); return the y it ended at. */
	abstract int page(GuiGraphicsExtractor g, int page, int x, int y, int w, int mouseX, int mouseY);

	/** The footer's text when nothing is hovered. */
	abstract String source(int page);

	/** Widgets of the page (they don't scroll: put them in the header, or where the page keeps fixed). */
	void pageWidgets(int page) {
	}

	/** A clickable row was clicked (the object given to {@link #clickable}); right: the x was in its last 20 px. */
	boolean clicked(Object what, boolean right) {
		return false;
	}

	int page() {
		return Math.max(0, Math.min(tabs().length - 1, PAGE.getOrDefault(getClass(), 0)));
	}

	void setPage(int p) {
		PAGE.put(getClass(), p);
		scroll = 0;
		rebuildWidgets();
	}

	int contentTop() {
		return HEADER + TABS_H + 8;
	}

	int contentBottom() {
		return height - 30;
	}

	/** Explain this in the footer (call while drawing, when the mouse is over it). */
	void hint(String text) {
		hint = text;
	}

	/** Hovered (in screen coordinates, the page already scrolled) and inside the page's view. */
	boolean over(int mouseX, int mouseY, int x, int y, int w, int h) {
		return mouseY >= contentTop() && mouseY < contentBottom() && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	/** Mark a rectangle (screen coordinates) as clickable for {@code what}. */
	void clickable(int x, int y, int w, int h, Object what) {
		clickables.add(new Object[] {x, y, w, h, what});
	}

	// ---------------------------------------------------------------- shared drawing

	void section(GuiGraphicsExtractor g, String title, int x, int y, int w) {
		g.text(font, title, x, y, ACCENT, true);
		g.fill(x + font.width(title) + 6, y + 4, x + w, y + 5, 0x30DCB45A);
	}

	/** A stat card: a small grey label, a big value, an optional line under it; explains itself on hover. */
	void stat(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String value, int color, String sub, String help, int mouseX, int mouseY) {
		boolean on = over(mouseX, mouseY, x, y, w, h);
		CsUi.card(g, x, y, w, h, CsUi.hover("stat" + label + x + y, on), color, false);
		g.text(font, label, x + 7, y + 6, DIM, false);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + 7, y + 18);
		float s = font.width(value) * 1.5f <= w - 14 ? 1.5f : 1f;
		pose.scale(s, s);
		g.text(font, font.plainSubstrByWidth(value, (int) ((w - 14) / s)), 0, 0, color, true);
		pose.popMatrix();
		if (sub != null && !sub.isEmpty()) {
			g.text(font, font.plainSubstrByWidth(sub, w - 14), x + 7, y + h - 12, SOFT, false);
		}
		if (on && help != null) {
			hint(help);
		}
	}

	/** A bar from 0 to 1 in {@code color} on a dark track. */
	void meter(GuiGraphicsExtractor g, int x, int y, int w, int h, double frac, int color) {
		g.fill(x, y, x + w, y + h, 0x40000000);
		g.fill(x, y, x + w, y + 1, 0x18FFFFFF);
		int fw = (int) Math.round(w * Math.max(0, Math.min(1, frac)));
		if (fw > 0) {
			g.fillGradient(x, y, x + fw, y + h, color, (color & 0x00FFFFFF) | 0xA0000000);
		}
	}

	/** A small coloured tag; returns its width. */
	int chip(GuiGraphicsExtractor g, String text, int x, int y, int color) {
		int w = font.width(text) + 8;
		g.fill(x, y, x + w, y + 11, (color & 0x00FFFFFF) | 0x50000000);
		g.fill(x, y, x + 1, y + 11, color);
		g.text(font, text, x + 4, y + 2, 0xFFFFFFFF, false);
		return w;
	}

	/** Text twice the size. */
	void big(GuiGraphicsExtractor g, String text, int x, int y, int color, float scale) {
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		g.text(font, text, 0, 0, color, true);
		pose.popMatrix();
	}

	// ---------------------------------------------------------------- the frame

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - PAD - 80, height - 24, 80, 18).build());
		pageWidgets(page());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		g.fill(0, 0, width, height, 0xF20E1012);
		g.fillGradient(0, 0, width, HEADER, 0xFF1C2026, 0xFF121519);
		g.fill(0, HEADER, width, HEADER + TABS_H, 0xFF0B0D10);
		g.fill(0, HEADER + TABS_H, width, HEADER + TABS_H + 1, 0xFF2A2F36);
		g.fill(0, height - 30, width, height, 0xFF0B0D10);
		g.fill(0, height - 30, width, height - 29, 0xFF2A2F36);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		hint = "";
		clickables.clear();
		header(g, mouseX, mouseY);
		// tabs
		String[] tabs = tabs();
		int tx = PAD;
		for (int i = 0; i < tabs.length; i++) {
			int w = font.width(tabs[i]) + 20;
			boolean on = mouseX >= tx && mouseX < tx + w && mouseY >= HEADER && mouseY < HEADER + TABS_H;
			float hv = CsUi.hover("tab" + getClass().getSimpleName() + i, on);
			boolean sel = i == page();
			if (sel) {
				g.fill(tx, HEADER, tx + w, HEADER + TABS_H, 0x30DCB45A);
				g.fill(tx, HEADER + TABS_H - 2, tx + w, HEADER + TABS_H, ACCENT);
			} else if (hv > 0) {
				g.fill(tx, HEADER, tx + w, HEADER + TABS_H, Math.round(0x20 * hv) << 24 | 0xFFFFFF);
			}
			g.text(font, tabs[i], tx + 10, HEADER + 5, sel ? 0xFFFFFFFF : on ? TEXT : DIM, sel);
			if (on) {
				hint(tabHelp()[i]);
			}
			tx += w + 2;
		}
		// page (scrolled, clipped)
		int top = contentTop();
		int bottom = contentBottom();
		g.enableScissor(0, top - 4, width, bottom);
		int end = page(g, page(), PAD, top - (int) scroll, width - 2 * PAD, mouseX, mouseY);
		g.disableScissor();
		contentHeight = end + (int) scroll - top + 8;
		int max = Math.max(0, contentHeight - (bottom - top));
		scroll = Math.max(0, Math.min(max, scroll));
		if (max > 0) {
			int track = bottom - top;
			int bar = Math.max(16, track * track / (track + max));
			int by = top + (int) ((track - bar) * (scroll / max));
			g.fill(width - 5, top, width - 2, bottom, 0x20FFFFFF);
			g.fill(width - 5, by, width - 2, by + bar, 0x90DCB45A);
		}
		// footer: what the mouse is over, else where the numbers come from
		String foot = hint.isEmpty() ? source(page()) : hint;
		g.text(font, font.plainSubstrByWidth(foot, width - 2 * PAD - 96), PAD, height - 19, hint.isEmpty() ? 0xFF6E6E6E : 0xFF8FC8FF, false);
		super.extractRenderState(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		if (super.mouseScrolled(mx, my, dx, dy)) {
			return true;
		}
		scroll = Math.max(0, scroll - dy * 24);
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (super.mouseClicked(e, doubleClick)) {
			return true; // a button or text box
		}
		if (e.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			String[] tabs = tabs();
			int tx = PAD;
			for (int i = 0; i < tabs.length; i++) {
				int w = font.width(tabs[i]) + 20;
				if (e.x() >= tx && e.x() < tx + w && e.y() >= HEADER && e.y() < HEADER + TABS_H) {
					click();
					setPage(i);
					return true;
				}
				tx += w + 2;
			}
			if (e.y() >= contentTop() && e.y() < contentBottom()) {
				for (Object[] c : clickables) {
					int x = (int) c[0], y = (int) c[1], w = (int) c[2], h = (int) c[3];
					if (e.x() >= x && e.x() < x + w && e.y() >= y && e.y() < y + h) {
						if (clicked(c[4], e.x() >= x + w - 20)) {
							click();
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	void click() {
		minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1f));
	}
}
