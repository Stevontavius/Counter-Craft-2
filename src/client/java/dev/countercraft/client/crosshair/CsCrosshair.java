package dev.countercraft.client.crosshair;

import dev.countercraft.client.GunClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The in-game CS2 crosshair: spread from the held gun (interpolated within the tick, so it moves smoothly at any frame
 * rate), and with cl_crosshair_recoil on it sits where the next bullet goes: the aim punch minus the camera's share.
 */
public final class CsCrosshair {
	private CsCrosshair() {
	}

	/** Draws the CS2 crosshair in first person; false to leave it to Minecraft (third person, F3 axes). */
	public static boolean draw(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson() || mc.getDebugOverlay().showDebugScreen()) {
			return false;
		}
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		int h = mc.getWindow().getHeight();
		double vfov = Math.toRadians(mc.gameRenderer.mainCamera().getFov());
		double k = h / 2.0 / Math.tan(vfov / 2);
		float spread = (float) (Math.tan(GunClient.crosshairSpread(pt)) * k);
		float fire = (float) (Math.tan(GunClient.firePenalty()) * k);
		float dx = 0f, dy = 0f;
		if (CrosshairSettings.i("cl_crosshair_recoil") != 0) {
			float[] off = GunClient.crosshairRecoil(pt);
			if (off != null) {
				dy = (float) (-Math.tan(Math.toRadians(off[0])) * k);
				dx = (float) (Math.tan(Math.toRadians(off[1])) * k);
			}
		}
		CrosshairRenderer.draw(g, new CrosshairGeometry.Frame(spread, fire, dx, dy, h));
		return true;
	}
}
