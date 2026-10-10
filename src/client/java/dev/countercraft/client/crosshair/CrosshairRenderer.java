package dev.countercraft.client.crosshair;

import com.mojang.blaze3d.platform.NativeImage;
import dev.countercraft.CounterCraft;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws CS2's crosshair. Measured in CS2 (measure/xhair_*.py, 2560x1440): every size is setting x (screen height /
 * cl_crosshair_screen_height) physical pixels, drawn anti-aliased at fractional positions around the exact screen
 * centre. Styles (CS2 names):
 * <ul>
 * <li>4 Static Cross: arms (thickness, length) from the gap; 0 Dynamic Cross: arms from the spread radius (the gap does
 * not apply); 5 Legacy / Shot Feedback: static cross pushed out by the firing part of the spread only; 2 Dynamic Cross
 * (Classic): arms at gap + spread, split into an inner / outer part past the split distance (inner part at
 * gap + spread x (1 - ratio), outer at gap + spread + inner length, alphas inner / outer mod; fitted on one sample).</li>
 * <li>1 Dynamic Circle: a ring of the thickness at the spread radius; 3 Static Circle: ring from the gap; 8 Static
 * Square: square ring from the gap; 9 Static Quadrant: the ring's four diagonal arcs, each the quadrant size x 90 deg;
 * 7 Dynamic Quadrant: a static cross plus a ring at the spread radius; 6 Dot Only.</li>
 * </ul>
 * The spread radius is the true spread cone on screen: tan(inaccuracy + spread) / tan(vertical fov / 2) x half the
 * screen height (CS2's dynamic circle sits on its own weapon_debug_spread_show circle), capped by the spread limit.
 * Outline: full = every part grown by one unit; half = a copy shifted one unit up-left (as measured). T style: no top
 * arm. Dot: a square of the thickness.
 *
 * <p>Each part gets its own small texture, rasterised in screen pixels around its own bounds (arms are a few hundred
 * pixels however wide the spread) and only when it changed; rings, squares and arcs are rasterised as one quadrant
 * and mirrored. All outlines are drawn before all fills, so overlapping arms (negative gap) never get an outline across
 * them. (The old single full-size texture was re-rasterised and re-uploaded every frame: up to a megabyte per frame
 * while the spread moved, and clipped beyond 256 pixels.)</p>
 */
public final class CrosshairRenderer {
	private static final List<Layer> OUTLINES = new ArrayList<>();
	private static final List<Layer> FILLS = new ArrayList<>();

	private CrosshairRenderer() {
	}

	/** Draw centred on the screen (plus the recoil offset). */
	public static void draw(GuiGraphicsExtractor g, CrosshairGeometry.Frame f) {
		Minecraft mc = Minecraft.getInstance();
		draw(g, f, mc.getWindow().getWidth() / 2f, mc.getWindow().getHeight() / 2f);
	}

	/** Draw with its centre at a physical pixel position (the settings preview), plus the frame's offset. */
	public static void draw(GuiGraphicsExtractor g, CrosshairGeometry.Frame f, float centerX, float centerY) {
		Minecraft mc = Minecraft.getInstance();
		int screenH = mc.getWindow().getHeight();
		List<CrosshairGeometry.Part> parts = CrosshairGeometry.parts(f, CrosshairSettings::get);
		if (parts.isEmpty()) {
			return;
		}
		float u = screenH / Math.max(240f, CrosshairSettings.get("cl_crosshair_screen_height"));
		int outline = CrosshairSettings.i("cl_crosshair_drawoutline");
		float o = outline == 1 ? 1.4f * u : outline == 2 ? u : 0; // measured: full outline ~1.4 units, half 1 unit
		int col = argb("cl_crosshaircolor");
		int ocol = argb("cl_crosshairoutline");
		float cx = centerX + f.offX();
		float cy = centerY + f.offY();
		double gs = mc.getWindow().getGuiScale();
		var pose = g.pose();
		pose.pushMatrix();
		pose.scale((float) (1 / gs), (float) (1 / gs)); // physical pixels
		if (outline != 0 && (ocol >>> 24) != 0) {
			for (int i = 0; i < parts.size(); i++) {
				layer(OUTLINES, "o", i).draw(g, parts.get(i), outline, o, ocol, cx, cy);
			}
		}
		for (int i = 0; i < parts.size(); i++) {
			layer(FILLS, "f", i).draw(g, parts.get(i), 0, 0f, col, cx, cy);
		}
		pose.popMatrix();
	}

	private static Layer layer(List<Layer> list, String kind, int i) {
		while (list.size() <= i) {
			list.add(new Layer(CounterCraft.id("dynamic/crosshair_" + kind + list.size())));
		}
		return list.get(i);
	}

	private static int argb(String prefix) {
		return CrosshairSettings.i(prefix + "_a") << 24 | CrosshairSettings.i(prefix + "_r") << 16 | CrosshairSettings.i(prefix + "_g") << 8
				| CrosshairSettings.i(prefix + "_b");
	}

	/** What a layer's texture currently holds; re-rasterised only when this changes. */
	private record Key(CrosshairGeometry.Part part, int mode, float o, int argb, float fx, float fy, int bw, int bh) {
	}

	/** One part (fill or outline) in its own texture. */
	private static final class Layer {
		private final Identifier id;
		private DynamicTexture texture;
		private NativeImage image;
		private int w;
		private int h;
		private Key key;

		Layer(Identifier id) {
			this.id = id;
		}

		void draw(GuiGraphicsExtractor g, CrosshairGeometry.Part p, int mode, float o, int argb, float cx, float cy) {
			if (p.shape().symmetric()) {
				drawQuadrants(g, p, mode, o, argb, cx, cy);
				return;
			}
			float[] b = p.shape().box();
			float pad = o + 1.5f;
			int x0 = (int) Math.floor(cx + b[0] - pad);
			int y0 = (int) Math.floor(cy + b[1] - pad);
			int bw = (int) Math.ceil(cx + b[2] + pad) - x0;
			int bh = (int) Math.ceil(cy + b[3] + pad) - y0;
			if (bw <= 0 || bh <= 0) {
				return;
			}
			// the part's position within its pixel box (fractional): the anti-aliasing depends on it
			float fx = cx - x0;
			float fy = cy - y0;
			Key k = new Key(p, mode, o, argb, fx, fy, bw, bh);
			if (!k.equals(key)) {
				ensure(bw, bh);
				int ca = argb >>> 24;
				int rgb = argb & 0xFFFFFF;
				for (int y = 0; y < bh; y++) {
					float py = y + 0.5f - fy;
					for (int x = 0; x < bw; x++) {
						float c = CrosshairGeometry.coverage(p, mode, o, x + 0.5f - fx, py);
						image.setPixel(x, y, Math.round(c * ca) << 24 | rgb);
					}
				}
				texture.upload();
				key = k;
			}
			g.blit(id, x0, y0, x0 + bw, y0 + bh, 0f, bw / (float) w, 0f, bh / (float) h);
		}

		/**
		 * Rings, squares, arcs: the lower-right quadrant, mirrored into the other three (centre snapped to a pixel corner).
		 * The half outline is the fill shape shifted up-left, drawn with the shift rounded to whole pixels.
		 */
		private void drawQuadrants(GuiGraphicsExtractor g, CrosshairGeometry.Part p, int mode, float o, int argb, float cx, float cy) {
			int qx = Math.round(cx);
			int qy = Math.round(cy);
			float grow = mode == 1 ? o : 0f;
			int r = (int) Math.ceil(p.shape().extent() + grow + 1.5f);
			int quadMode = mode == 2 ? 0 : mode;
			Key k = new Key(p, quadMode, grow, argb, 0f, 0f, r, r);
			if (!k.equals(key)) {
				ensure(r, r);
				int ca = argb >>> 24;
				int rgb = argb & 0xFFFFFF;
				float inner = Math.max(0f, p.shape().innerRadius() - grow - 1.5f);
				for (int y = 0; y < r; y++) {
					float py = y + 0.5f;
					// pixels well inside the inner radius are empty: skip the distance maths there
					int skip = inner > py ? Math.max(0, (int) Math.floor(Math.sqrt(inner * inner - py * py)) - 1) : 0;
					for (int x = 0; x < r; x++) {
						int a = 0;
						if (x >= skip) {
							a = Math.round(CrosshairGeometry.coverage(p, quadMode, grow, x + 0.5f, py) * ca);
						}
						image.setPixel(x, y, a << 24 | rgb);
					}
				}
				texture.upload();
				key = k;
			}
			int s = mode == 2 ? -Math.round(o) : 0;
			qx += s;
			qy += s;
			float uu = r / (float) w;
			float vv = r / (float) h;
			g.blit(id, qx, qy, qx + r, qy + r, 0f, uu, 0f, vv);
			g.blit(id, qx - r, qy, qx, qy + r, uu, 0f, 0f, vv);
			g.blit(id, qx, qy - r, qx + r, qy, 0f, uu, vv, 0f);
			g.blit(id, qx - r, qy - r, qx, qy, uu, 0f, vv, 0f);
		}

		/** Grow-only power-of-two texture, so a moving spread doesn't reallocate it every few frames. */
		private void ensure(int needW, int needH) {
			if (texture != null && needW <= w && needH <= h) {
				return;
			}
			int nw = pow2(Math.max(needW, w));
			int nh = pow2(Math.max(needH, h));
			if (texture != null) {
				texture.close();
			}
			w = nw;
			h = nh;
			image = new NativeImage(nw, nh, true);
			texture = new DynamicTexture(() -> "countercraft crosshair", image);
			Minecraft.getInstance().getTextureManager().register(id, texture);
			key = null;
		}

		private static int pow2(int v) {
			int s = 16;
			while (s < v) {
				s *= 2;
			}
			return s;
		}
	}
}
