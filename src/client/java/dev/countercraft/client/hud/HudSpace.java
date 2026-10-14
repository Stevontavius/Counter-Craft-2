package dev.countercraft.client.hud;

/**
 * Where the corner panels were drawn this frame (GUI px, top and bottom), so the hardcore player list fits between them
 * instead of over them (TeamListHud, drawn after them). Each panel marks its box as it draws; a mark older than 0.1 s means
 * the panel isn't on screen.
 */
public final class HudSpace {
	public enum Panel {
		MONEY, KILL_FEED, PAPERDOLL, VITALS, CS_HOTBAR
	}

	private static final int N = Panel.values().length;
	private static final float[] TOP = new float[N];
	private static final float[] BOTTOM = new float[N];
	private static final long[] AT = new long[N];

	private HudSpace() {
	}

	public static void mark(Panel p, float top, float bottom) {
		TOP[p.ordinal()] = top;
		BOTTOM[p.ordinal()] = bottom;
		AT[p.ordinal()] = System.nanoTime();
	}

	private static boolean shown(Panel p) {
		return System.nanoTime() - AT[p.ordinal()] < 100_000_000L;
	}

	/** The panel's top, or {@code otherwise} when it isn't showing. */
	static float top(Panel p, float otherwise) {
		return shown(p) ? TOP[p.ordinal()] : otherwise;
	}

	/** The panel's bottom, or {@code otherwise} when it isn't showing. */
	static float bottom(Panel p, float otherwise) {
		return shown(p) ? BOTTOM[p.ordinal()] : otherwise;
	}
}
