package dev.countercraft.client.viewmodel;

import dev.countercraft.client.movement.CsPlayerMovement;
import dev.countercraft.movement.CsMove;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's viewmodel movement, measured on CS2 itself (measure/move_bob.py: the viewmodel tracked against a blacked-out
 * sky while running, walking, crouching, strafing and jumping; pixels converted to view-space metres with the glove
 * depths of the baked arms, viewmodel_fov 68):
 * <ul>
 * <li>moving eases the viewmodel back toward the camera: 6.9 cm at 250 u/s forward, 2.4 cm at 130, 1.2 cm at 85
 * (~ speed^1.6), and drops it ~5 mm; strafing shifts it ~6 mm toward the strafe; time constant ~0.15 s;</li>
 * <li>a figure-eight bob tied to distance: one sideways cycle per ~189 units (1.32 Hz at 250 u/s, 0.68 at 130),
 * vertical at twice that; 2.4 mm sideways and 1.45 mm vertical at full speed;</li>
 * <li>in the air it sits ~1.8 cm back without bobbing; standing still it sways slightly (~0.8 mm, 1.6 s).</li>
 * </ul>
 * Offsets are view-space metres (x right, y up, z toward the camera), applied with the viewmodel offsets.
 */
public final class ViewmodelBob {
	private static final double SMOOTH = 0.15;
	private static final double CYCLE_UNITS = 189;
	private static double pull;
	private static double down;
	private static double lateral;
	private static double amp;
	private static double phase;
	private static long lastNanos;

	private ViewmodelBob() {
	}

	/** {x, y, z} offset for this frame. */
	public static float[] offset(LocalPlayer p) {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
		lastNanos = now;
		Vec3 v = CsPlayerMovement.active() ? CsPlayerMovement.velocity() : p.getDeltaMovement().scale(20 * CsMove.UNITS_PER_BLOCK);
		boolean ground = CsPlayerMovement.active() ? CsPlayerMovement.onGround() : p.onGround();
		double yaw = Math.toRadians(p.getYRot());
		double vf = -Math.sin(yaw) * v.x + Math.cos(yaw) * v.z;   // forward
		double vs = -Math.cos(yaw) * v.x - Math.sin(yaw) * v.z;   // right
		double speed = Math.hypot(v.x, v.z);

		double tPull = 0.0686 * Math.pow(Math.min(1.5, Math.abs(vf) / 250), 1.6) + 0.03 * Math.pow(Math.min(1.5, Math.abs(vs) / 250), 1.6);
		double tDown = -0.005 * Math.min(1, speed / 85);
		double tLat = 0.006 * Math.max(-1.5, Math.min(1.5, vs / 250));
		double tAmp = ground ? Math.pow(Math.min(1.5, speed / 250), 1.2) : 0;
		if (!ground) {
			tPull = Math.max(tPull, 0.018);
		}
		double k = dt <= 0 ? 0 : 1 - Math.exp(-dt / SMOOTH);
		pull += (tPull - pull) * k;
		down += (tDown - down) * k;
		lateral += (tLat - lateral) * k;
		amp += (tAmp - amp) * k;
		if (ground) {
			phase += 2 * Math.PI * speed / CYCLE_UNITS * dt;
		}
		double t = now / 1e9;
		double still = 1 - Math.min(1, speed / 40);
		double x = lateral + 0.0024 * amp * Math.sin(phase) + 0.0008 * still * Math.sin(2 * Math.PI * t / 1.6);
		double y = down - 0.00145 * amp * Math.sin(2 * phase) + 0.0005 * still * Math.sin(2 * Math.PI * t / 0.8);
		return new float[] {(float) x, (float) y, (float) pull};
	}
}
