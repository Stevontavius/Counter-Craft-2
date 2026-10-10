package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GrenadeEntity;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GrenadePhysics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Grenade tracer (debug key, unbound by default), as CS2's practice tools draw it:
 * <ul>
 * <li>Pin pulled: the predicted flight as a thin white line, with a small magenta square at every bounce and where it
 * stops or goes off. It's run through the mod's own grenade physics ({@link GrenadePhysics}, the same throw maths as
 * {@link GrenadeItem#throwGrenade}), so it follows the strength being charged (left, right or both) and the view.</li>
 * <li>Thrown: every grenade in flight leaves the same white line behind it, kept for {@link #TRAIL_SECONDS} after it
 * lands or goes off.</li>
 * </ul>
 */
public final class GrenadeTracer {
	private static final int LINE = 0xD8FFFFFF;
	private static final int MARKER = 0xFFFF40E0;
	/** How long a thrown grenade's trail stays once it has stopped or gone. */
	private static final double TRAIL_SECONDS = 5.0;
	/** Longest predicted flight drawn, seconds. */
	private static final double MAX_FLIGHT = 8.0;

	private static boolean on;

	private static final class Trail {
		final List<Vec3> points = new ArrayList<>();
		long lastMove;
	}

	private static final Map<Integer, Trail> TRAILS = new HashMap<>();

	private GrenadeTracer() {
	}

	public static void tick(Minecraft mc) {
		while (GunKeys.GRENADE_TRACER.consumeClick()) {
			on = !on;
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal("Grenade tracer: " + (on ? "ON" : "OFF")));
			}
			TRAILS.clear();
		}
		if (!on || mc.level == null) {
			TRAILS.clear();
			return;
		}
		long now = mc.level.getGameTime();
		for (GrenadeEntity g : mc.level.getEntitiesOfClass(GrenadeEntity.class, mc.player.getBoundingBox().inflate(256),
				e -> e.getType() == CounterCraft.GRENADE_ENTITY)) {
			Trail t = TRAILS.computeIfAbsent(g.getId(), k -> new Trail());
			Vec3 p = g.position().add(0, GrenadeEntity.HALF, 0);
			if (t.points.isEmpty() || t.points.get(t.points.size() - 1).distanceToSqr(p) > 1e-4) {
				t.points.add(p);
				t.lastMove = now;
			}
		}
		for (Iterator<Trail> it = TRAILS.values().iterator(); it.hasNext(); ) {
			if (now - it.next().lastMove > TRAIL_SECONDS * 20) {
				it.remove();
			}
		}
	}

	public static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (!on || p == null || mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		List<Vec3[]> lines = new ArrayList<>();
		List<Vec3> markers = new ArrayList<>();
		for (Trail t : TRAILS.values()) {
			for (int i = 1; i < t.points.size(); i++) {
				lines.add(new Vec3[] {t.points.get(i - 1), t.points.get(i)});
			}
		}
		float strength = GunClient.grenadeStrength();
		if (strength >= 0f && dev.countercraft.team.CsInventory.active(p) && p.getMainHandItem().getItem() instanceof GrenadeItem grenade) {
			predict(p, grenade, strength, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), lines, markers);
		}
		if (lines.isEmpty() && markers.isEmpty()) {
			return;
		}
		PoseStack ps = ctx.poseStack();
		Vec3 right = Vec3.directionFromRotation(0, mc.gameRenderer.mainCamera().yRot() + 90f);
		Vec3 up = Vec3.directionFromRotation(mc.gameRenderer.mainCamera().xRot() - 90f, mc.gameRenderer.mainCamera().yRot());
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.linesTranslucent(), (pose, vc) -> {
			for (Vec3[] l : lines) {
				line(pose, vc, l[0].subtract(cam), l[1].subtract(cam), LINE, 1.5f);
			}
			for (Vec3 m : markers) {
				// a small square facing the camera, the same size on screen at any distance (CS2's practice markers)
				Vec3 c = m.subtract(cam);
				double s = Math.max(0.02, c.length() * 0.009);
				square(pose, vc, c, right.scale(s), up.scale(s), MARKER);
				square(pose, vc, c, right.scale(s * 0.35), up.scale(s * 0.35), MARKER);
			}
		});
	}

	/** The throw the server would make now (GrenadeItem.throwGrenade), flown until it stops or goes off. */
	private static void predict(LocalPlayer p, GrenadeItem grenade, float s, float partial, List<Vec3[]> lines, List<Vec3> markers) {
		double units = GrenadeEntity.UNITS;
		float yaw = p.getViewYRot(partial);
		float pitch = p.getViewXRot(partial);
		Vec3 dir = GrenadePhysics.direction(GrenadePhysics.throwPitch(Math.max(-90f, Math.min(90f, pitch))), yaw);
		Vec3 eye = p.getEyePosition(partial).add(0, (12.0 * s - 12.0) / units, 0);
		Vec3 src = GrenadeEntity.hullTrace(p.level(), p, eye, eye.add(dir.scale(22.0 / units))).end();
		Vec3 own = GunClient.throwVelocity(p);
		GrenadePhysics.State sim = new GrenadePhysics.State(src.scale(units),
				GrenadePhysics.throwVelocity(yaw, pitch, s, grenade.def().throwVelocity(), own));
		GrenadePhysics.Trace trace = (a, b) -> {
			GrenadeEntity.TraceHit h = GrenadeEntity.hullTrace(p.level(), p, a.scale(1.0 / units), b.scale(1.0 / units));
			return h.normal() == null ? null : new GrenadePhysics.Hit(h.end().scale(units), h.normal(), h.fraction(), h.entity() != null);
		};
		String kind = grenade.def().id();
		boolean molotov = kind.equals("molotov") || kind.equals("incgrenade");
		boolean timed = kind.equals("hegrenade") || kind.equals("flashbang");
		int fuseSteps = (int) Math.round(1.5 / GrenadePhysics.TICK); // HE / flash: 1.5 s (GrenadeEntity.FUSE)
		int molotovSteps = (int) Math.round(2.0 / GrenadePhysics.TICK); // molotov_throw_detonate_time
		int maxSteps = (int) (MAX_FLIGHT / GrenadePhysics.TICK);
		Vec3 last = src;
		for (int i = 1; i <= maxSteps; i++) {
			GrenadePhysics.step(sim, trace);
			Vec3 now = sim.pos.scale(1.0 / units);
			if (now.distanceToSqr(last) > 1e-8) {
				lines.add(new Vec3[] {last, now});
				last = now;
			}
			if (sim.hit != null) {
				markers.add(now);
				if (molotov && !sim.hit.player() && sim.hit.normal().y >= Math.cos(Math.toRadians(30))) {
					return; // bursts into flames on a floor
				}
			}
			if (timed && i >= fuseSteps || molotov && i >= molotovSteps || !timed && !molotov && sim.stopped && i >= fuseSteps) {
				markers.add(now);
				return;
			}
		}
		markers.add(last);
	}

	private static void square(PoseStack.Pose pose, VertexConsumer vc, Vec3 c, Vec3 r, Vec3 u, int color) {
		Vec3 a = c.add(r).add(u);
		Vec3 b = c.subtract(r).add(u);
		Vec3 d = c.subtract(r).subtract(u);
		Vec3 e = c.add(r).subtract(u);
		line(pose, vc, a, b, color, 1.5f);
		line(pose, vc, b, d, color, 1.5f);
		line(pose, vc, d, e, color, 1.5f);
		line(pose, vc, e, a, color, 1.5f);
	}

	private static void line(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, int color, float width) {
		Vec3 n = b.subtract(a);
		n = n.lengthSqr() < 1e-12 ? new Vec3(0, 1, 0) : n.normalize();
		vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(width);
		vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(width);
	}
}
