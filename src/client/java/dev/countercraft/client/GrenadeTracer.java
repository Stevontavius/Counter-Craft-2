package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.loadout.CsUi;
import dev.countercraft.gun.GrenadeEntity;
import dev.countercraft.gun.GrenadeItem;
import dev.countercraft.gun.GrenadePhysics;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * The grenade debug (its key, unbound by default), CS2's practice tools and more:
 * <ul>
 * <li>Pin pulled: the predicted flight as a thin white line, a small magenta square at every bounce and where it stops
 * or goes off. It's run through the mod's own grenade physics ({@link GrenadePhysics}, the same throw maths as
 * {@link GrenadeItem#throwGrenade}), so it follows the strength being charged (left, right or both) and the view.</li>
 * <li>Thrown: every grenade in flight leaves the same white line behind it, every 1/64 s physics step of it as the
 * client flies it (its bounces exactly where they happened), drawn up to where the grenade is this frame; kept for
 * {@link #TRAIL_SECONDS} after it lands or goes off.</li>
 * <li>Labels on each grenade (what it is, how long it has left), and a panel ({@link DebugPanels}): the grenade in your
 * hand and its predicted throw, every live grenade around, and the last ones to go off.</li>
 * </ul>
 */
public final class GrenadeTracer {
	private static final int LINE = 0xD8FFFFFF;
	private static final int MARKER = 0xFFFF40E0;
	/** How long a thrown grenade's trail stays once it has stopped or gone. */
	private static final double TRAIL_SECONDS = 5.0;
	/** Longest predicted flight drawn, seconds. */
	private static final double MAX_FLIGHT = 8.0;
	/** Grenades listed and labelled within this distance (blocks). */
	private static final double RANGE = 96;
	/** Recent detonations kept for the panel, and for how long (seconds). */
	private static final int RECENT = 5;
	private static final double RECENT_SECONDS = 15;

	private static boolean on;
	/** Dev (-Dcountercraft.grenadeTest=true): on from the start, and a screenshot once a grenade has flown a while. */
	private static final boolean TEST = Boolean.getBoolean("countercraft.grenadeTest");
	private static boolean shot;

	/** A grenade seen: its trail (the entity's own list while it lives, kept after), when it was last seen and what it was. */
	private static final class Seen {
		List<Vec3> points = List.of();
		List<Vec3> bounces = List.of();
		long lastSeen;
		String kind;
		int state;
		Vec3 pos;
	}

	private static final Map<Integer, Seen> SEEN = new HashMap<>();
	private static final Deque<String[]> GONE = new ArrayDeque<>();
	private static final Deque<Long> GONE_AT = new ArrayDeque<>();
	/** The predicted throw this frame: flight time (s), bounces, where it ends. */
	private static double predFlight;
	private static int predBounces;
	private static Vec3 predEnd;

	private GrenadeTracer() {
	}

	public static void tick(Minecraft mc) {
		while (GunKeys.GRENADE_TRACER.consumeClick()) {
			on = !on;
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal("Grenade debug: " + (on ? "ON" : "OFF")));
			}
			SEEN.clear();
			GONE.clear();
			GONE_AT.clear();
		}
		if (TEST) {
			on = true;
		}
		GrenadeEntity.recordTrail = on;
		if (!on || mc.level == null || mc.player == null) {
			SEEN.clear();
			return;
		}
		long now = mc.level.getGameTime();
		java.util.Set<Integer> live = new java.util.HashSet<>();
		for (GrenadeEntity g : grenades(mc)) {
			Seen s = SEEN.computeIfAbsent(g.getId(), k -> new Seen());
			s.points = g.trail;
			s.bounces = g.bounceAt;
			s.lastSeen = now;
			s.kind = g.kind();
			s.state = g.state();
			s.pos = g.position();
			live.add(g.getId());
			if (TEST && !shot && g.state() == GrenadeEntity.FLYING && g.trail.size() > 40) {
				shot = true;
				net.minecraft.client.Screenshot.grab(mc.gameDirectory, "cc2-grenade-debug.png", mc.gameRenderer.mainRenderTarget(), 1,
						msg -> CounterCraft.LOG.info("[countercraft] grenade debug test: {}", msg.getString()));
			}
		}
		for (Iterator<Map.Entry<Integer, Seen>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Integer, Seen> e = it.next();
			Seen s = e.getValue();
			if (!live.contains(e.getKey()) && s.lastSeen == now - 1) {
				// just gone: keep a copy of its trail, and note what happened
				s.points = new ArrayList<>(s.points);
				s.bounces = new ArrayList<>(s.bounces);
				String what = s.state == GrenadeEntity.FLYING
						? ("hegrenade".equals(s.kind) || "flashbang".equals(s.kind) ? "went off" : "burst in the air")
						: s.state == GrenadeEntity.SMOKING ? "cleared" : s.state == GrenadeEntity.BURNING ? "burnt out" : "stopped";
				GONE.addFirst(new String[] {name(s.kind) + " " + what, String.format("%.1f", s.pos.distanceTo(mc.player.position()))});
				GONE_AT.addFirst(System.nanoTime());
				while (GONE.size() > RECENT) {
					GONE.removeLast();
					GONE_AT.removeLast();
				}
			}
			if (now - s.lastSeen > TRAIL_SECONDS * 20) {
				it.remove();
			}
		}
	}

	private static List<GrenadeEntity> grenades(Minecraft mc) {
		return mc.level.getEntitiesOfClass(GrenadeEntity.class, mc.player.getBoundingBox().inflate(RANGE),
				e -> e.getType() == CounterCraft.GRENADE_ENTITY);
	}

	private static String name(String kind) {
		return kind == null ? "?" : CsUi.name("weapon_" + kind);
	}

	public static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (!on || p == null || mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		List<Vec3[]> lines = new ArrayList<>();
		List<Vec3> markers = new ArrayList<>();
		for (Seen s : SEEN.values()) {
			List<Vec3> pts = s.points;
			for (int i = 1; i < pts.size(); i++) {
				lines.add(new Vec3[] {pts.get(i - 1), pts.get(i)});
			}
			markers.addAll(s.bounces);
		}
		// each live grenade's trail runs on to where it is this frame
		for (GrenadeEntity g : grenades(mc)) {
			if (!g.trail.isEmpty() && g.state() == GrenadeEntity.FLYING) {
				lines.add(new Vec3[] {g.trail.get(g.trail.size() - 1), g.getPosition(partial).add(0, GrenadeEntity.HALF, 0)});
			}
		}
		predEnd = null;
		float strength = GunClient.grenadeStrength();
		if (strength >= 0f && dev.countercraft.team.CsInventory.active(p) && p.getMainHandItem().getItem() instanceof GrenadeItem grenade) {
			predict(p, grenade, strength, partial, lines, markers);
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
		int fuseSteps = (int) Math.round(GrenadeEntity.fuseSeconds(kind) / GrenadePhysics.TICK);
		int maxSteps = (int) (MAX_FLIGHT / GrenadePhysics.TICK);
		Vec3 last = src;
		predBounces = 0;
		for (int i = 1; i <= maxSteps; i++) {
			GrenadePhysics.step(sim, trace);
			Vec3 now = sim.pos.scale(1.0 / units);
			if (now.distanceToSqr(last) > 1e-8) {
				lines.add(new Vec3[] {last, now});
				last = now;
			}
			predFlight = i * GrenadePhysics.TICK;
			predEnd = now;
			if (sim.hit != null) {
				markers.add(now);
				predBounces++;
				if (molotov && !sim.hit.player() && sim.hit.normal().y >= Math.cos(Math.toRadians(30))) {
					return; // bursts into flames on a floor
				}
			}
			if (timed && i >= fuseSteps || molotov && i >= fuseSteps || !timed && !molotov && sim.stopped && i >= fuseSteps) {
				markers.add(now);
				return;
			}
		}
		markers.add(last);
	}

	/** Each live grenade's label: what it is and how long it has left. */
	static void labels(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.level == null || mc.player == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (GrenadeEntity e : grenades(mc)) {
			Vec3 sp = mc.gameRenderer.projectPointToScreen(e.getPosition(partial).add(0, 0.45, 0));
			if (sp.z <= 0 || sp.z >= 1 || Math.abs(sp.x) > 1 || Math.abs(sp.y) > 1) {
				continue;
			}
			int x = (int) ((sp.x + 1) / 2 * g.guiWidth());
			int y = (int) ((1 - sp.y) / 2 * g.guiHeight());
			Component label = DebugPanels.uni(name(e.kind()) + "  " + status(e));
			g.text(mc.font, label, x - mc.font.width(label) / 2, y - 4, 0xFFFFFFFF, true);
		}
	}

	/** A grenade's state in a few words: its fuse while it flies, the time left once it has popped. */
	private static String status(GrenadeEntity e) {
		double age = e.stateAge() / 20.0;
		if (e.state() == GrenadeEntity.FLYING) {
			String k = e.kind();
			boolean waits = "smokegrenade".equals(k) || "decoy".equals(k);
			return waits ? String.format("%.1f s, pops once it stops", age) : String.format("%.1f / %.1f s", age, GrenadeEntity.fuseSeconds(k));
		}
		String what = e.state() == GrenadeEntity.SMOKING ? "smoking" : e.state() == GrenadeEntity.BURNING ? "burning" : "decoying";
		return String.format("%s, %.1f s left", what, Math.max(0, GrenadeEntity.effectSeconds(e.state()) - age));
	}

	/** The panel's lines (null when off). */
	static List<Component> panel() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (!on || p == null || mc.level == null) {
			return null;
		}
		List<Component> lines = new ArrayList<>();
		lines.add(DebugPanels.uni("GRENADE DEBUG  (" + (GunKeys.GRENADE_TRACER.isUnbound() ? "its key in Controls"
				: GunKeys.GRENADE_TRACER.getTranslatedKeyMessage().getString()) + " to close)"));
		float s = GunClient.grenadeStrength();
		if (p.getMainHandItem().getItem() instanceof GrenadeItem held) {
			String throwing = s < 0 ? "pin in" : s >= 0.99f ? "full throw (left)" : s <= 0.01f ? "lob (right)" : "medium (both)";
			String flight = s < 0 || predEnd == null ? "" : String.format(",  %.2f s, %d bounce%s, ends %.1f m away", predFlight, predBounces,
					predBounces == 1 ? "" : "s", predEnd.distanceTo(p.position()));
			lines.add(DebugPanels.uni("held: " + held.def().name() + ", " + throwing + flight).withColor(0xFFE0A0));
		}
		List<GrenadeEntity> live = grenades(mc);
		lines.add(DebugPanels.uni("live: " + live.size()));
		for (GrenadeEntity e : live) {
			double speed = e.getDeltaMovement().length() * 20 * GrenadeEntity.UNITS;
			lines.add(DebugPanels.uni(String.format(" #%d %s  %s,  %.0f u/s,  %d bounce%s,  %.1f m", e.getId(), name(e.kind()), status(e), speed,
					e.bounces(), e.bounces() == 1 ? "" : "s", e.position().distanceTo(p.position()))));
		}
		Iterator<Long> at = GONE_AT.iterator();
		boolean header = false;
		for (String[] g : GONE) {
			double ago = (System.nanoTime() - at.next()) / 1e9;
			if (ago > RECENT_SECONDS) {
				continue;
			}
			if (!header) {
				lines.add(DebugPanels.uni("recent:"));
				header = true;
			}
			lines.add(DebugPanels.uni(String.format(" %s %s m away, %.0f s ago", g[0], g[1], ago)).withColor(0xC0C0C0));
		}
		return lines;
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
