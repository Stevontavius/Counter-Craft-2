package dev.countercraft.client.death;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.agent.Ragdolls;
import dev.countercraft.client.loadout.MusicKit;
import dev.countercraft.net.DeathInfoPayload;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's death cam for the local player, timed on CS's freeze cam cvars: the view leaves the eyes and settles behind the
 * ragdoll (spec_freeze_deathanim_time 0.8 s), travels to frame the killer (spec_freeze_traveltime 0.4 s, between
 * spec_freeze_distance_min 96 and _max 200 units from them, narrowing the view towards spec_freeze_target_fov 42) and
 * holds on them (spec_freeze_time 3 s); killed by a mob, it stays on the body. Throughout, CS2's death cam colour
 * grading (death_cam_phase1.vpost: a red tint, post_effect/deathcam_*) fades in; at the end it fades to solid black
 * (death_cam_phase2 maps to black) and the death screen shows on the black, with Respawn / Main Menu. Music: the equipped kit's death
 * cam track in survival and while Another Chance keeps the run going, its round lost track when a hardcore run ends. Approximations: the cvar defaults are CS:GO's (CS2 keeps the
 * same names), the camera path between the points is a smooth blend.
 */
public final class DeathCam {
	private static final double CHASE_IN = 0.4;
	private static final double DEATHANIM = 0.8;
	private static final double TRAVEL = 0.4;
	private static final double FREEZE = 3.0;
	public static final double END = DEATHANIM + TRAVEL + FREEZE;
	/** The fade to black at the end. */
	public static final double FADE = 0.5;
	private static final double UNIT = 0.0254;
	private static final double FREEZE_MIN = 96 * UNIT;
	private static final double FREEZE_MAX = 200 * UNIT;
	/** spec_freeze_target_fov over CS's 90 degree view. */
	private static final float FREEZE_FOV = 42f / 90f;
	private static final int TINT_STEPS = 4;

	private static DeathInfoPayload info;
	private static long startNanos;
	private static Vec3 eye;
	private static float yaw;
	private static float pitch;
	private static boolean active;
	/** The player who died (a respawn makes a new one), and whether this client has seen them dead yet. */
	private static Entity died;
	private static boolean seenDead;

	/** The camera this frame: position, yaw, pitch (Minecraft degrees), FOV scale. */
	public record Pose(Vec3 pos, float yaw, float pitch, float fovScale) {
	}

	private DeathCam() {
	}

	public static boolean active() {
		return active;
	}

	public static DeathInfoPayload info() {
		return info;
	}

	public static double seconds() {
		return active ? (System.nanoTime() - startNanos) / 1e9 : 0;
	}

	/** The death sequence has played out: the death screen may show its buttons. */
	public static boolean finished() {
		return !active || seconds() >= END;
	}

	public static void onInfo(DeathInfoPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		info = p;
		startNanos = System.nanoTime();
		eye = mc.player.getEyePosition();
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		active = true;
		died = mc.player;
		seenDead = mc.player.isDeadOrDying();
		boolean hardcore = mc.level != null && mc.level.getLevelData().isHardcore();
		if (hardcore && !p.anotherChance()) {
			MusicKit.play("lostround", "snd_roundend_volume");
		} else {
			MusicKit.play("deathcam", "snd_deathcamera_volume");
		}
	}

	public static void stop() {
		if (active && !(info != null && info.anotherChance())) {
			MusicKit.stopTrack(); // (Another Chance: the death cam track plays out into spectating)
		}
		active = false;
		info = null;
		died = null;
	}

	/**
	 * Every client tick: ends once the player has respawned (a new player, or seen dead and now alive) or left the world.
	 * The death news can come a tick before this client hears its health is gone (a C4 kill at the end of the server's
	 * tick, a shot between ticks), so a player not yet dead here is still dying.
	 */
	public static void tick(Minecraft mc) {
		if (!active) {
			return;
		}
		if (mc.player != null && mc.player == died && mc.player.isDeadOrDying()) {
			seenDead = true;
		}
		if (mc.player == null || mc.player != died || seenDead && mc.player.isAlive() && !mc.player.isDeadOrDying()) {
			stop();
		}
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	private static Entity killer(Minecraft mc) {
		return info == null || info.killerId() < 0 || mc.level == null ? null : mc.level.getEntity(info.killerId());
	}

	/** Look angles (Minecraft degrees) from a to b. */
	private static float[] look(Vec3 a, Vec3 b) {
		Vec3 d = b.subtract(a);
		double h = Math.hypot(d.x, d.z);
		return new float[] {(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) Math.toDegrees(-Math.atan2(d.y, h))};
	}

	/** Back off from {@code target} towards {@code wanted} until the view isn't blocked. */
	private static Vec3 clear(Minecraft mc, Vec3 target, Vec3 wanted) {
		HitResult hit = mc.level.clip(new ClipContext(target, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() == HitResult.Type.MISS) {
			return wanted;
		}
		Vec3 d = wanted.subtract(target);
		double len = d.length();
		return len < 1e-6 ? target : target.add(d.scale(Math.max(0.1, target.distanceTo(hit.getLocation()) - 0.2) / len));
	}

	public static Pose pose(float partial) {
		Minecraft mc = Minecraft.getInstance();
		double t = seconds();
		Vec3 body = mc.player == null ? null : Ragdolls.bodyOf(mc.player.getUUID(), partial);
		if (body == null) {
			body = eye.subtract(0, 1.2, 0);
		}
		// chase: behind the body along the death view, above it, looking at it
		double yr = Math.toRadians(yaw);
		Vec3 back = new Vec3(Math.sin(yr), 0, -Math.cos(yr));
		Vec3 chase = clear(mc, body.add(0, 0.4, 0), body.add(back.scale(2.4)).add(0, 1.4, 0));
		float[] chaseLook = look(chase, body);
		// from the eyes into the chase view
		double k = smooth(t / CHASE_IN);
		Vec3 pos = eye.lerp(chase, k);
		float y = lerpAngle(yaw, chaseLook[0], (float) k);
		float p = (float) (pitch + (chaseLook[1] - pitch) * k);
		float fov = 1f;
		// CS2's freeze cam travels to the player who killed you; a mob's kill stays on your body
		Entity killer = killer(mc);
		if (killer instanceof net.minecraft.world.entity.player.Player && t > DEATHANIM) {
			Vec3 head = killer.getEyePosition(partial);
			Vec3 toBody = body.subtract(head);
			double dist = toBody.length();
			double frame = Math.max(FREEZE_MIN, Math.min(FREEZE_MAX, dist * 0.5));
			Vec3 dir = dist < 1e-6 ? back : toBody.scale(1 / dist);
			Vec3 freeze = clear(mc, head, head.add(dir.scale(frame)).add(0, 0.35, 0));
			float[] freezeLook = look(freeze, head);
			double m = smooth((t - DEATHANIM) / TRAVEL);
			pos = pos.lerp(freeze, m);
			y = lerpAngle(y, freezeLook[0], (float) m);
			p = (float) (p + (freezeLook[1] - p) * m);
			fov = (float) (1 + (FREEZE_FOV - 1) * m);
		}
		return new Pose(pos, y, p, fov);
	}

	private static float lerpAngle(float a, float b, float t) {
		float d = ((b - a) % 360f + 540f) % 360f - 180f;
		return a + d * t;
	}

	/** CS2's red death cam grading, faded in over its first 0.3 s (one of TINT_STEPS strengths), or null. */
	public static Identifier tintEffect() {
		if (!active) {
			return null;
		}
		int step = (int) Math.ceil(Math.min(1.0, seconds() / 0.3) * TINT_STEPS);
		return CounterCraft.id("deathcam_" + Math.max(1, step));
	}

	/** Phase 2: the fade to solid black over the last half second; the death screen then shows on the black. */
	public static float blackout() {
		if (!active) {
			return 0f;
		}
		double t = seconds() - (END - FADE);
		return (float) Math.max(0, Math.min(1, t / FADE));
	}

	/** Post effects to add this frame (LocalPlayerMixin). */
	public static void addEffects(List<Identifier> out) {
		Identifier tint = tintEffect();
		if (tint != null && Minecraft.getInstance().getResourceManager().getResource(CounterCraft.id("textures/effect/deathcam_phase1.png")).isPresent()) {
			out.add(tint);
		}
	}
}
