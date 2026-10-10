package dev.countercraft.client.crosshair;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** CS2 crosshair geometry (see {@link CrosshairRenderer}): parts as signed-distance shapes in physical pixels, and their
 * anti-aliased coverage. No Minecraft dependencies (measure/xhair_check renders it offline against CS2's screenshots). */
public final class CrosshairGeometry {
	private CrosshairGeometry() {
	}

	/** One signed-distance shape (pixels, centre at 0,0, y down). */
	public interface Shape {
		float sd(float x, float y);

		float extent();

		/** Bounding box {minX, minY, maxX, maxY} around the centre. */
		default float[] box() {
			float e = extent();
			return new float[] {-e, -e, e, e};
		}

		/** Mirror-symmetric in x and y about the centre (drawn as one quadrant, mirrored four times). */
		default boolean symmetric() {
			return false;
		}

		/** Nothing is drawn closer to the centre than this (lets rings skip their empty middle). */
		default float innerRadius() {
			return 0f;
		}
	}

	private record Rect(float cx, float cy, float hx, float hy) implements Shape {
		public float sd(float x, float y) {
			float qx = Math.abs(x - cx) - hx;
			float qy = Math.abs(y - cy) - hy;
			float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
			return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0);
		}

		public float extent() {
			return Math.max(Math.abs(cx) + hx, Math.abs(cy) + hy);
		}

		public float[] box() {
			return new float[] {cx - hx, cy - hy, cx + hx, cy + hy};
		}
	}

	private record Ring(float rin, float rout) implements Shape {
		public float sd(float x, float y) {
			float r = (float) Math.sqrt(x * x + y * y);
			return Math.max(rin - r, r - rout);
		}

		public float extent() {
			return rout;
		}

		public boolean symmetric() {
			return true;
		}

		public float innerRadius() {
			return rin;
		}
	}

	private record SquareRing(float hin, float hout) implements Shape {
		public float sd(float x, float y) {
			float outer = box(x, y, hout);
			float inner = box(x, y, hin);
			return Math.max(outer, -inner);
		}

		private static float box(float x, float y, float h) {
			float qx = Math.abs(x) - h, qy = Math.abs(y) - h;
			float ox = Math.max(qx, 0), oy = Math.max(qy, 0);
			return (float) Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0);
		}

		public float extent() {
			return hout * 1.42f;
		}

		public boolean symmetric() {
			return true;
		}

		public float innerRadius() {
			return hin;
		}
	}

	/** Arcs of a ring centred on the diagonals, each spanning {@code halfAngle} either side. */
	private record Arcs(float rin, float rout, float halfAngle) implements Shape {
		public float sd(float x, float y) {
			float r = (float) Math.sqrt(x * x + y * y);
			float ring = Math.max(rin - r, r - rout);
			double a = Math.atan2(Math.abs(y), Math.abs(x)); // 0..90 deg in the quadrant
			double off = Math.abs(a - Math.PI / 4) - halfAngle; // angle outside the arc (negative inside)
			return Math.max(ring, (float) (off * r));
		}

		public float extent() {
			return rout;
		}

		public boolean symmetric() {
			return true;
		}

		public float innerRadius() {
			return rin;
		}
	}

	public record Part(Shape shape, float alpha) {
	}

	/** Inputs that change per frame. */
	public record Frame(float spreadPx, float firePx, float offX, float offY, int screenH) {
	}

	/** The parts for the current settings, sizes in physical pixels. */
	public static List<Part> parts(Frame f, ToDoubleFunction<String> cvar) {
		float u = f.screenH() / Math.max(240f, (float) cvar.applyAsDouble("cl_crosshair_screen_height"));
		int style = (int) Math.round(cvar.applyAsDouble("cl_crosshairstyle"));
		float t = (float) cvar.applyAsDouble("cl_crosshair_thickness") * u;
		float len = (float) cvar.applyAsDouble("cl_crosshair_length") * u;
		float gap = (float) cvar.applyAsDouble("cl_crosshair_gap") * u;
		boolean dot = (int) Math.round(cvar.applyAsDouble("cl_crosshairdot")) != 0;
		boolean tStyle = (int) Math.round(cvar.applyAsDouble("cl_crosshair_t")) != 0;
		float limit = (float) cvar.applyAsDouble("cl_crosshair_dynamic_spread_limit") * u;
		float spread = Math.min(f.spreadPx(), limit);
		float fire = Math.min(f.firePx(), limit);
		List<Part> out = new ArrayList<>();
		switch (style) {
			case 0 -> cross(out, spread, len, t, tStyle, 1f);
			case 5 -> cross(out, gap + fire, len, t, tStyle, 1f);
			case 2 -> {
				float split = (float) cvar.applyAsDouble("cl_crosshair_dynamic_splitdist") * u;
				float ratio = (float) cvar.applyAsDouble("cl_crosshair_dynamic_maxdist_splitratio");
				if (spread > split && ratio < 1f) {
					float innerLen = len * (1f - ratio);
					cross(out, gap + spread * (1f - ratio), innerLen, t, tStyle, (float) cvar.applyAsDouble("cl_crosshair_dynamic_splitalpha_innermod"));
					cross(out, gap + spread + innerLen, len * ratio, t, tStyle, (float) cvar.applyAsDouble("cl_crosshair_dynamic_splitalpha_outermod"));
				} else {
					cross(out, gap + spread, len, t, tStyle, 1f);
				}
			}
			case 1 -> out.add(new Part(ring(spread + t / 2 + 0.5f, t), 1f));
			case 3 -> out.add(new Part(ring(gap + t / 2 + 1.4f, t), 1f));
			case 8 -> out.add(new Part(new SquareRing(gap, gap + t), 1f));
			case 9 -> {
				float q = (float) cvar.applyAsDouble("cl_crosshair_dynamic_maxdist_splitratio");
				out.add(new Part(new Arcs(gap, gap + t, (float) (q * Math.PI / 4)), 1f));
			}
			case 7 -> {
				cross(out, gap, len, t, tStyle, 1f);
				out.add(new Part(ring(spread + t / 2 + 0.5f, t), 1f));
			}
			case 6 -> dot = true;
			default -> cross(out, gap, len, t, tStyle, 1f);
		}
		if (dot && t > 0) {
			out.add(new Part(new Rect(0, 0, t / 2, t / 2), 1f));
		}
		return out;
	}

	/**
	 * CS2's circles, measured: the stroke is (thickness px - 1.1) wide, centred half a thickness out from the radius
	 * plus 1.4 px (static, from the gap) or 0.5 px (dynamic, from the spread).
	 */
	private static Ring ring(float centre, float t) {
		float w = Math.max(0.6f, t - 1.1f);
		return new Ring(Math.max(0, centre - w / 2), centre + w / 2);
	}

	private static void cross(List<Part> out, float rin, float len, float t, boolean tStyle, float alpha) {
		if (len <= 0 || t <= 0 || alpha <= 0) {
			return;
		}
		float c = rin + len / 2;
		out.add(new Part(new Rect(c, 0, len / 2, t / 2), alpha));
		out.add(new Part(new Rect(-c, 0, len / 2, t / 2), alpha));
		out.add(new Part(new Rect(0, c, t / 2, len / 2), alpha));
		if (!tStyle) {
			out.add(new Part(new Rect(0, -c, t / 2, len / 2), alpha));
		}
	}


	/**
	 * Coverage of the crosshair ({@code cover}) and of its outline ({@code outline}) on an n x n pixel grid centred on the
	 * crosshair centre (between pixels). Outline: 1 full (grown by o px), 2 half (shifted o px up-left).
	 */
	public static void raster(List<Part> parts, int outlineMode, float o, int n, float[] cover, float[] outline) {
		float c = n / 2f;
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				float px = x + 0.5f - c;
				float py = y + 0.5f - c;
				float cv = 0f;
				float ov = 0f;
				for (Part p : parts) {
					float d = p.shape().sd(px, py);
					cv = Math.max(cv, clamp01(0.5f - d) * p.alpha());
					if (outlineMode == 1) {
						ov = Math.max(ov, clamp01(0.5f - (d - o)) * p.alpha());
					} else if (outlineMode == 2) {
						ov = Math.max(ov, clamp01(0.5f - p.shape().sd(px + o, py + o)) * p.alpha());
					}
				}
				cover[y * n + x] = cv;
				outline[y * n + x] = ov;
			}
		}
	}

	/** One part's coverage at (x, y) for the fill (mode 0), full outline (1, grown by o) or half outline (2, a copy
	 * shifted o up-left). */
	public static float coverage(Part p, int mode, float o, float x, float y) {
		float d = mode == 2 ? p.shape().sd(x + o, y + o) : p.shape().sd(x, y) - (mode == 1 ? o : 0f);
		return clamp01(0.5f - d) * p.alpha();
	}

	static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}
}
