package dev.countercraft.client.crosshair;

import dev.countercraft.CounterCraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * CS2's crosshair editor (settings_crosshair.xml): a live preview on CS2's own editor backgrounds (arrows switch map,
 * "Dynamic Preview" sweeps the spread like CS2's), the style list in CS2's order, and only the options CS2 shows for the
 * chosen style (settingsmenu_crosshair.js). Colours use an HSV picker with alpha and a hex box. Open with /cs_crosshair
 * or the key in Controls.
 */
public final class CrosshairScreen extends Screen {
	private static final int[] STYLE_ORDER = {4, 3, 8, 6, 9, 0, 1, 2, 5, 7};
	private static final String[] STYLE_NAMES = {"Dynamic Cross", "Dynamic Circle", "Dynamic Cross (Classic)", "Static Circle", "Static Cross",
			"Dynamic Cross (Legacy/Shot Feedback)", "Dot Only", "Dynamic Quadrant", "Static Square", "Static Quadrant"};
	private static final String[] BACKGROUNDS = {"dust2", "mirage", "inferno", "nuke", "ancient", "anubis", "cache"};
	private static boolean openNext;

	private final Screen parent;
	private final Map<String, Float> undo;
	private int bg;
	private boolean dynamicPreview = true;
	private ColorPicker picker;
	/** The "Import from CS2" share code box, while open. */
	private EditBox codeBox;
	private boolean importing;
	private String pickerPrefix;
	private Component status = Component.empty();
	private long opened = System.nanoTime();

	public CrosshairScreen(Screen parent) {
		super(Component.literal("Crosshair"));
		this.parent = parent;
		this.undo = CrosshairSettings.snapshot();
	}

	/** Open on the next client tick (from a chat command, whose screen closes first). */
	public static void openLater() {
		openNext = true;
	}

	public static void tick(Minecraft mc) {
		if (openNext) {
			openNext = false;
			mc.gui.setScreen(new CrosshairScreen(null));
		}
	}

	private int previewW() {
		return Math.min(width / 2, 360);
	}

	@Override
	protected void init() {
		int style = CrosshairSettings.i("cl_crosshairstyle");
		int pw = previewW();
		int x = pw + 20;
		int w = Math.min(220, width - x - 10);
		int y = 24;
		int row = 22;
		// preview background arrows and dynamic preview
		addRenderableWidget(Button.builder(Component.literal("<"), b -> bg = (bg + BACKGROUNDS.length - 1) % BACKGROUNDS.length)
				.bounds(10, 24 + previewH() / 2 - 10, 16, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> bg = (bg + 1) % BACKGROUNDS.length)
				.bounds(10 + pw - 16, 24 + previewH() / 2 - 10, 16, 20).build());
		addRenderableWidget(Button.builder(Component.literal(dynamicPreview ? "Dynamic Preview: On" : "Dynamic Preview: Off"), b -> {
			dynamicPreview = !dynamicPreview;
			rebuildWidgets();
		}).bounds(10, 24 + previewH() + 4, 130, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Style: " + STYLE_NAMES[style]), b -> {
			int k = 0;
			for (int i = 0; i < STYLE_ORDER.length; i++) {
				if (STYLE_ORDER[i] == style) {
					k = i;
				}
			}
			CrosshairSettings.set("cl_crosshairstyle", STYLE_ORDER[(k + 1) % STYLE_ORDER.length]);
			rebuildWidgets();
		}).bounds(x, y, w, 20).build());
		y += row;
		addRenderableWidget(Button.builder(Component.literal("Color"), b -> openPicker("cl_crosshaircolor")).bounds(x, y, w, 20).build());
		y += row;
		int outline = CrosshairSettings.i("cl_crosshair_drawoutline");
		addRenderableWidget(Button.builder(Component.literal("Outline: " + (outline == 1 ? "Full Outline" : outline == 2 ? "Half Outline" : "No Outline")),
				b -> {
					int o = CrosshairSettings.i("cl_crosshair_drawoutline");
					CrosshairSettings.set("cl_crosshair_drawoutline", o == 1 ? 2 : o == 2 ? 0 : 1);
					rebuildWidgets();
				}).bounds(x, y, w, 20).build());
		y += row;
		if (outline != 0) {
			addRenderableWidget(Button.builder(Component.literal("Outline Color and Transparency"), b -> openPicker("cl_crosshairoutline"))
					.bounds(x, y, w, 20).build());
			y += row;
		}
		y = slider(x, y, w, "Thickness", "cl_crosshair_thickness", 0, 32, 0);
		// per-style options (CS2: SettingsMenuCrosshairSettings.OnCrosshairStyleChange)
		boolean dot = style != 6;
		boolean gap = style == 0 || style == 3 || style == 4 || style == 5 || style == 7 || style == 8 || style == 9;
		boolean classicGap = style == 2;
		boolean length = style == 0 || style == 2 || style == 4 || style == 5 || style == 7;
		boolean tStyle = length;
		boolean spreadLimit = style == 0 || style == 1 || style == 7;
		boolean split = style == 2;
		boolean quad = style == 9;
		if (dot) {
			y = toggle(x, y, w, "Center Dot", "cl_crosshairdot");
		}
		if (length) {
			y = slider(x, y, w, "Length", "cl_crosshair_length", 0, 255, 0);
		}
		if (gap) {
			y = slider(x, y, w, "Gap", "cl_crosshair_gap", 0, 128, 0);
		}
		if (classicGap) {
			y = slider(x, y, w, "Gap", "cl_crosshair_gap", -10, 128, 0);
		}
		if (spreadLimit) {
			y = slider(x, y, w, "Dynamic Spread Limit", "cl_crosshair_dynamic_spread_limit", 0, 255, 0);
		}
		if (split) {
			y = slider(x, y, w, "Split Distance", "cl_crosshair_dynamic_splitdist", 0, 127, 0);
			y = slider(x, y, w, "Inner Split Alpha", "cl_crosshair_dynamic_splitalpha_innermod", 0, 1, 2);
			y = slider(x, y, w, "Outer Split Alpha", "cl_crosshair_dynamic_splitalpha_outermod", 0.3f, 1, 2);
			y = slider(x, y, w, "Split Size Ratio", "cl_crosshair_dynamic_maxdist_splitratio", 0, 1, 2);
		}
		if (quad) {
			y = slider(x, y, w, "Quadrant Size", "cl_crosshair_dynamic_maxdist_splitratio", 0, 1, 2);
		}
		if (tStyle) {
			y = toggle(x, y, w, "T Style", "cl_crosshair_t");
		}
		y = toggle(x, y, w, "Follow Recoil", "cl_crosshair_recoil");
		// actions
		int by = height - 26;
		addRenderableWidget(Button.builder(Component.literal("Import from CS2"), b -> {
			importing = true;
			rebuildWidgets();
		}).bounds(10, by, 110, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Undo Changes"), b -> {
			CrosshairSettings.restore(undo);
			rebuildWidgets();
		}).bounds(124, by, 90, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			CrosshairSettings.resetAll();
			rebuildWidgets();
		}).bounds(218, by, 60, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - 90, by, 80, 20).build());
		if (picker != null) {
			picker = new ColorPicker(pickerPrefix, width / 2 - 110, height / 2 - 80);
			for (AbstractWidget wdg : picker.widgets()) {
				addRenderableWidget(wdg);
			}
		}
		if (importing) {
			// CS2's "Import crosshair code" box: paste a share code (CS2: Settings > Game > Crosshair > Share or Import)
			int px = width / 2 - 150;
			int py = height / 2 - 30;
			String old = codeBox == null ? clipboardCode() : codeBox.getValue();
			codeBox = new EditBox(font, px + 8, py + 22, 284, 20, Component.literal("Crosshair code"));
			codeBox.setMaxLength(64);
			codeBox.setHint(Component.literal("CS... or CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx").withColor(0xFF7A7A7A));
			codeBox.setValue(old);
			addRenderableWidget(codeBox);
			setInitialFocus(codeBox);
			addRenderableWidget(Button.builder(Component.literal("Import"), b -> importCode()).bounds(px + 8, py + 48, 138, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
				importing = false;
				codeBox = null;
				rebuildWidgets();
			}).bounds(px + 154, py + 48, 138, 20).build());
		}
	}

	/** The clipboard, if it holds a crosshair code (so it's ready to import). */
	private String clipboardCode() {
		String c = minecraft.keyboardHandler.getClipboard().trim();
		return c.startsWith("CS") && c.length() < 64 ? c : "";
	}

	private void importCode() {
		try {
			CrosshairShareCode.Result r = CrosshairShareCode.decode(codeBox.getValue());
			r.cvars().forEach(CrosshairSettings::set);
			CrosshairSettings.save();
			status = Component.literal(r.approximate() ? "Imported an old CS:GO crosshair code (sizes converted approximately)" : "Crosshair imported from CS2 code");
			importing = false;
			codeBox = null;
		} catch (CrosshairShareCode.InvalidCode e) {
			status = Component.literal(e.getMessage()).withColor(0xFFFF5050);
		}
		rebuildWidgets();
	}

	private int previewH() {
		return previewW() * 9 / 16;
	}

	private int slider(int x, int y, int w, String label, String cvar, float min, float max, int decimals) {
		addRenderableWidget(new CvarSlider(x, y, w, label, cvar, min, max, decimals));
		return y + 22;
	}

	private int toggle(int x, int y, int w, String label, String cvar) {
		addRenderableWidget(Button.builder(Component.literal(label + ": " + (CrosshairSettings.i(cvar) != 0 ? "Yes" : "No")), b -> {
			CrosshairSettings.set(cvar, CrosshairSettings.i(cvar) != 0 ? 0 : 1);
			rebuildWidgets();
		}).bounds(x, y, w, 20).build());
		return y + 22;
	}

	private void openPicker(String prefix) {
		pickerPrefix = prefix;
		picker = new ColorPicker(prefix, width / 2 - 110, height / 2 - 80);
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		int pw = previewW();
		int ph = previewH();
		Identifier tex = CounterCraft.id("textures/hud/crosshair_bg/" + BACKGROUNDS[bg] + ".png");
		g.fill(9, 23, 11 + pw, 25 + ph, 0xFF000000);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, 10, 24, 0f, 0f, pw, ph, pw, ph);
		// the crosshair at its real in-game size, centred on the preview
		double gs = minecraft.getWindow().getGuiScale();
		float cx = (float) ((10 + pw / 2.0) * gs);
		float cy = (float) ((24 + ph / 2.0) * gs);
		int h = minecraft.getWindow().getHeight();
		float spread = 0f;
		float fire = 0f;
		if (dynamicPreview) {
			// CS2's dynamic preview: the spread swells and settles, like a short burst
			double t = ((System.nanoTime() - opened) / 1e9) % 2.0;
			double s = t < 0.6 ? t / 0.6 : Math.exp(-(t - 0.6) * 3);
			double vfov = Math.toRadians(minecraft.options.fov().get());
			double k = h / 2.0 / Math.tan(vfov / 2);
			spread = (float) (Math.tan(0.0070 + 0.03 * s) * k);
			fire = (float) (Math.tan(0.03 * s) * k);
		}
		CrosshairRenderer.draw(g, new CrosshairGeometry.Frame(spread, fire, 0, 0, h), cx, cy);
		g.text(font, title, 10, 8, 0xFFFFFFFF, true);
		g.text(font, status, 10, height - 40, 0xFFBFBFBF, true);
		if (picker != null) {
			picker.draw(g, font);
		}
		if (importing) {
			int px = width / 2 - 150;
			int py = height / 2 - 30;
			g.fill(0, 0, width, height, 0x90000000);
			g.fill(px, py, px + 300, py + 76, 0xF0101418);
			g.outline(px, py, 300, 76, 0xFF3A4048);
			g.text(font, "Import Crosshair Code", px + 8, py + 8, 0xFFFFFFFF, true);
			// the box and buttons draw again on top of the dimming
			for (var child : children()) {
				if (child instanceof AbstractWidget wdg && (wdg == codeBox || wdg.getY() == py + 48)) {
					wdg.extractRenderState(g, mouseX, mouseY, delta);
				}
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		if (importing) {
			for (var child : children()) {
				if (child instanceof AbstractWidget wdg && (wdg == codeBox || wdg.getY() == height / 2 - 30 + 48) && wdg.isMouseOver(e.x(), e.y())) {
					return super.mouseClicked(e, doubleClick);
				}
			}
			return true; // modal: nothing behind it
		}
		if (picker != null) {
			if (picker.click(e.x(), e.y())) {
				return true;
			}
			if (!picker.contains(e.x(), e.y())) {
				picker = null;
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(e, doubleClick);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent e) {
		if (importing && (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER)) {
			importCode();
			return true;
		}
		return super.keyPressed(e);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		if (picker != null && picker.drag(e.x(), e.y())) {
			return true;
		}
		return super.mouseDragged(e, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		if (picker != null) {
			picker.release();
		}
		return super.mouseReleased(e);
	}

	@Override
	public void onClose() {
		CrosshairSettings.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** A slider bound to a cvar, showing CS2's value. */
	private static final class CvarSlider extends AbstractSliderButton {
		private final String label;
		private final String cvar;
		private final float min;
		private final float max;
		private final int decimals;

		CvarSlider(int x, int y, int w, String label, String cvar, float min, float max, int decimals) {
			super(x, y, w, 20, Component.empty(), (CrosshairSettings.get(cvar) - min) / (max - min));
			this.label = label;
			this.cvar = cvar;
			this.min = min;
			this.max = max;
			this.decimals = decimals;
			updateMessage();
		}

		private float current() {
			float v = (float) (min + value * (max - min));
			if (decimals == 0) {
				return Math.round(v);
			}
			float p = (float) Math.pow(10, decimals);
			return Math.round(v * p) / p;
		}

		@Override
		protected void updateMessage() {
			float v = current();
			setMessage(Component.literal(label + ": " + (decimals == 0 ? Integer.toString(Math.round(v)) : String.format(java.util.Locale.ROOT, "%." + decimals + "f", v))));
		}

		@Override
		protected void applyValue() {
			CrosshairSettings.set(cvar, current());
		}
	}

	/** HSV colour picker with an alpha bar, hex box and the result swatch (CS2's context_menu_color_picker). */
	private final class ColorPicker {
		private static final int SV = 120;
		private final String prefix;
		private final int x;
		private final int y;
		private float hue;
		private float sat;
		private float val;
		private int dragging;
		private final EditBox hex;
		private final List<AbstractWidget> widgets = new ArrayList<>();

		ColorPicker(String prefix, int x, int y) {
			this.prefix = prefix;
			this.x = x;
			this.y = y;
			int r = CrosshairSettings.i(prefix + "_r");
			int g = CrosshairSettings.i(prefix + "_g");
			int b = CrosshairSettings.i(prefix + "_b");
			float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
			hue = hsv[0];
			sat = hsv[1];
			val = hsv[2];
			hex = new EditBox(font, x + 8, y + SV + 18, 70, 16, Component.literal("hex"));
			hex.setMaxLength(9);
			hex.setValue(hexValue());
			hex.setResponder(s -> {
				String t = s.startsWith("#") ? s.substring(1) : s;
				if (t.length() == 6 || t.length() == 8) {
					try {
						long v = Long.parseLong(t, 16);
						int a = t.length() == 8 ? (int) (v & 255) : CrosshairSettings.i(prefix + "_a");
						int rgb = (int) (t.length() == 8 ? v >> 8 : v);
						apply(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, a, false);
					} catch (NumberFormatException ignored) {
						// keep typing
					}
				}
			});
			widgets.add(hex);
			widgets.add(Button.builder(Component.literal("OK"), btn -> {
				picker = null;
				rebuildWidgets();
			}).bounds(x + 180, y + SV + 16, 40, 20).build());
		}

		List<AbstractWidget> widgets() {
			return widgets;
		}

		boolean contains(double mx, double my) {
			return mx >= x - 4 && mx <= x + 224 && my >= y - 16 && my <= y + SV + 40;
		}

		private String hexValue() {
			return String.format("%02X%02X%02X%02X", CrosshairSettings.i(prefix + "_r"), CrosshairSettings.i(prefix + "_g"), CrosshairSettings.i(prefix + "_b"),
					CrosshairSettings.i(prefix + "_a"));
		}

		private void apply(int r, int g, int b, int a, boolean fromHsv) {
			CrosshairSettings.set(prefix + "_r", r);
			CrosshairSettings.set(prefix + "_g", g);
			CrosshairSettings.set(prefix + "_b", b);
			CrosshairSettings.set(prefix + "_a", a);
			if (!fromHsv) {
				float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
				hue = hsv[0];
				sat = hsv[1];
				val = hsv[2];
			}
		}

		private void fromHsv() {
			int rgb = java.awt.Color.HSBtoRGB(hue, sat, val);
			apply(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, CrosshairSettings.i(prefix + "_a"), true);
			hex.setValue(hexValue());
		}

		boolean click(double mx, double my) {
			dragging = 0;
			if (mx >= x && mx < x + SV && my >= y && my < y + SV) {
				dragging = 1;
			} else if (mx >= x + SV + 8 && mx < x + SV + 22 && my >= y && my < y + SV) {
				dragging = 2;
			} else if (mx >= x + SV + 28 && mx < x + SV + 42 && my >= y && my < y + SV) {
				dragging = 3;
			}
			return dragging != 0 && drag(mx, my);
		}

		boolean drag(double mx, double my) {
			float fy = (float) Math.max(0, Math.min(1, (my - y) / SV));
			switch (dragging) {
				case 1 -> {
					sat = (float) Math.max(0, Math.min(1, (mx - x) / SV));
					val = 1 - fy;
					fromHsv();
				}
				case 2 -> {
					hue = fy;
					fromHsv();
				}
				case 3 -> {
					CrosshairSettings.set(prefix + "_a", Math.round((1 - fy) * 255));
					hex.setValue(hexValue());
				}
				default -> {
					return false;
				}
			}
			return true;
		}

		void release() {
			dragging = 0;
		}

		void draw(GuiGraphicsExtractor g, net.minecraft.client.gui.Font f) {
			g.fill(x - 6, y - 18, x + 228, y + SV + 42, 0xF0101418);
			g.text(f, prefix.endsWith("outline") ? "Outline Color and Transparency" : "Crosshair Color", x, y - 13, 0xFFFFFFFF, true);
			int cells = 30;
			float cs = SV / (float) cells;
			for (int i = 0; i < cells; i++) {
				for (int j = 0; j < cells; j++) {
					int rgb = java.awt.Color.HSBtoRGB(hue, (i + 0.5f) / cells, 1 - (j + 0.5f) / cells);
					g.fill(x + Math.round(i * cs), y + Math.round(j * cs), x + Math.round((i + 1) * cs), y + Math.round((j + 1) * cs), 0xFF000000 | rgb);
				}
			}
			for (int j = 0; j < SV; j++) {
				g.fill(x + SV + 8, y + j, x + SV + 22, y + j + 1, 0xFF000000 | java.awt.Color.HSBtoRGB(j / (float) SV, 1, 1));
				int a = Math.round((1 - j / (float) SV) * 255);
				int base = ((j / 6) % 2 == 0) ? 0xFF888888 : 0xFFCCCCCC;
				g.fill(x + SV + 28, y + j, x + SV + 42, y + j + 1, base);
				g.fill(x + SV + 28, y + j, x + SV + 42, y + j + 1, a << 24 | (java.awt.Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF));
			}
			// markers and swatch
			int mx = x + Math.round(sat * SV);
			int my = y + Math.round((1 - val) * SV);
			g.fill(mx - 2, my - 2, mx + 3, my + 3, 0xFFFFFFFF);
			int hy = y + Math.round(hue * SV);
			g.fill(x + SV + 6, hy - 1, x + SV + 24, hy + 1, 0xFFFFFFFF);
			int ay = y + Math.round((1 - CrosshairSettings.i(prefix + "_a") / 255f) * SV);
			g.fill(x + SV + 26, ay - 1, x + SV + 44, ay + 1, 0xFFFFFFFF);
			int col = CrosshairSettings.i(prefix + "_a") << 24 | CrosshairSettings.i(prefix + "_r") << 16 | CrosshairSettings.i(prefix + "_g") << 8
					| CrosshairSettings.i(prefix + "_b");
			g.fill(x + SV + 50, y, x + 222, y + 40, 0xFFFFFFFF);
			g.fill(x + SV + 51, y + 1, x + 221, y + 39, col);
			g.text(f, "Hex RGBA", x + 84, y + SV + 22, 0xFFBFBFBF, true);
		}
	}
}
