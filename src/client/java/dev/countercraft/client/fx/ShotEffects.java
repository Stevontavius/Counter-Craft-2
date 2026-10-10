package dev.countercraft.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.gun.Ballistics;
import dev.countercraft.gun.GunSpec;
import dev.countercraft.gun.Guns;
import dev.countercraft.net.ShotFxPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * CS2 muzzle flashes and bullet tracers, drawn with CS2's own textures (converted into the pack) and additive blending.
 *
 * <p>Tracers follow CS2's tracer particles: a streak leaving the muzzle at the tracer's speed (20500 u/s for rifles),
 * growing to its full length over the fade-in time (1200 u over 0.08 s), its width held between the min / max screen
 * size; SMG / MG / AUG tracers are a short streak travelling along the shot. Every Nth bullet carries one (vdata
 * m_nTracerFrequency), decided by the server.</p>
 *
 * <p>Muzzle flashes rebuild the main fire layer of the gun's CS2 flash system: N fire sprites spread along the barrel,
 * random roll, colour and alpha from the particle file, lasting its lifetime (0.05 s), plus sparks. The overall size is
 * set by a control point CS2's game code fills in, which isn't in the files, so {@link #FLASH_SIZE} is judged by eye.</p>
 */
public final class ShotEffects {
	private static final double UNITS = 39.37;
	/** Scale of the flash sprites (CS2's control point 5, not in the particle files): approximated by eye. */
	private static final float FLASH_SIZE = 6f;
	/** How far along the barrel the sprites spread, relative to the particle file's range: approximated by eye. */
	private static final float FLASH_REACH = 0.45f;
	private static final float INCH = 0.0254f;
	private static final Identifier SPARK = CounterCraft.id("textures/fx/spark.png");
	private static final Identifier ROPE = CounterCraft.id("textures/fx/tracer_rope.png");
	private static final Identifier[] FIRE = {CounterCraft.id("textures/fx/flash_fire_0.png"), CounterCraft.id("textures/fx/flash_fire_1.png"),
			CounterCraft.id("textures/fx/flash_fire_2.png"), CounterCraft.id("textures/fx/flash_fire_3.png")};

	private record Tracer(Vec3 start, Vec3 dir, double dist, long born, Guns.Tracer t, float r, float g, float b) {
	}

	private record Flash(Vec3 pos, Vec3 dir, long born, Guns.Flash f, long seed) {
	}

	private static final List<Tracer> TRACERS = new ArrayList<>();
	private static final List<Flash> WORLD_FLASHES = new ArrayList<>();
	private static final Random RANDOM = new Random();
	/** Brightness of tracers (CS2's trail overbright isn't extracted; set by eye) and their minimum half width (pixels). */
	private static final float TRACER_OVERBRIGHT = 2f;
	private static final double MIN_HALF_PIXELS = 0.75;
	private static boolean loggedTracer;

	/** First-person flash on the viewmodel. */
	private static Guns.Flash vmFlash;
	private static long vmFlashAt;
	private static long vmSeed;
	private static boolean vmAlt;
	/**
	 * Where the viewmodel's muzzle is relative to the camera (last frame: forward, up, right in blocks), so the local
	 * player's tracers start at the muzzle even though the shot's packet arrives a tick later and the player has moved.
	 */
	private static double muzzleFwd;
	private static double muzzleUp;
	private static double muzzleRight;
	private static long muzzleAt;

	private ShotEffects() {
	}

	private static Guns.Flash flashOf(GunSpec spec, boolean silenced) {
		String fam = silenced && spec.fx().flashSilenced() != null ? spec.fx().flashSilenced() : spec.fx().flash();
		return fam == null ? null : Guns.FLASHES.get(fam);
	}

	/** The local player fired (predicted): flash on the viewmodel. {@code alt}: Dual Berettas' left gun / silencer end. */
	public static void localShot(GunSpec spec, boolean silenced, boolean alt) {
		vmFlash = flashOf(spec, silenced);
		vmFlashAt = System.nanoTime();
		vmSeed = RANDOM.nextLong();
		vmAlt = alt;
		if (vmFlash != null && !vmFlash.silenced()) {
			Vec3 m = localMuzzle();
			MuzzleLight.flash(m != null ? m : Minecraft.getInstance().player.getEyePosition());
		}
	}

	/** The viewmodel muzzle in the world now (camera + the last frame's camera-relative offset), or null. */
	private static Vec3 localMuzzle() {
		if (System.nanoTime() - muzzleAt > 200_000_000L) {
			return null;
		}
		Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
		Vector3fc f = cam.forwardVector();
		Vector3fc u = cam.upVector();
		Vector3fc l = cam.leftVector();
		return cam.position().add(f.x() * muzzleFwd, f.y() * muzzleFwd, f.z() * muzzleFwd)
				.add(u.x() * muzzleUp, u.y() * muzzleUp, u.z() * muzzleUp)
				.add(-l.x() * muzzleRight, -l.y() * muzzleRight, -l.z() * muzzleRight);
	}

	/** A shot from the server: tracers (and, for other players, the muzzle flash). */
	public static void onShot(ShotFxPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		GunSpec spec = Guns.get(p.gun());
		Entity e = mc.level.getEntity(p.shooter());
		if (spec == null || !(e instanceof LivingEntity shooter)) {
			return;
		}
		boolean local = shooter == mc.player;
		boolean firstPerson = local && mc.options.getCameraType().isFirstPerson();
		long now = System.nanoTime();
		Vec3 start = firstPerson ? localMuzzle() : null;
		if (start == null) {
			// the agent model's own gun muzzle as last drawn; CS2's fixed muzzle point (along the view) if not drawn
			start = shooter instanceof net.minecraft.world.entity.player.Player && !p.altMuzzle()
					? dev.countercraft.client.agent.AgentClient.muzzle(shooter.getId()) : null;
			if (start == null) {
				start = thirdPersonMuzzle(shooter, spec, p.altMuzzle());
			}
			Guns.Flash f = flashOf(spec, p.silenced());
			if (!firstPerson && f != null && !f.silenced()) {
				WORLD_FLASHES.add(new Flash(start, Ballistics.direction(shooter.getXRot(), shooter.getYRot()), now, f, RANDOM.nextLong()));
				MuzzleLight.flash(start);
			}
		}
		if (spec.taser()) {
			// the Zeus: two crackling lightning arcs from its prongs to where it hit, and the creature hit wrapped in it
			for (Vec3 end : p.ends()) {
				MiniLightning.shot(shooter, start, end);
			}
			return;
		}
		Guns.Tracer t = spec.fx().tracer() == null ? null : Guns.TRACERS.get(spec.fx().tracer());
		if (t == null) {
			return;
		}
		for (int i = 0; i < p.ends().size(); i++) {
			if ((p.tracers() & (1 << i)) == 0) {
				continue;
			}
			Vec3 d = p.ends().get(i).subtract(start);
			double dist = d.length();
			if (dist < 0.5) {
				continue;
			}
			float k = RANDOM.nextFloat();
			float a = t.alpha()[0] + (t.alpha()[1] - t.alpha()[0]) * RANDOM.nextFloat();
			int[] c0 = t.color()[0];
			int[] c1 = t.color()[1];
			float glow = TRACER_OVERBRIGHT * a;
			TRACERS.add(new Tracer(start, d.scale(1 / dist), dist, now, t, glow * (c0[0] + (c1[0] - c0[0]) * k) / 255f,
					glow * (c0[1] + (c1[1] - c0[1]) * k) / 255f, glow * (c0[2] + (c1[2] - c0[2]) * k) / 255f));
			if (!loggedTracer) {
				loggedTracer = true;
				CounterCraft.LOG.debug("[countercraft] tracer {} from {} over {} blocks", spec.fx().tracer(), start, String.format("%.1f", dist));
			}
		}
	}

	/** CS2's third-person muzzle point: vdata m_vecMuzzlePos (units forward, left, up of the eye, along the view). */
	private static Vec3 thirdPersonMuzzle(LivingEntity shooter, GunSpec spec, boolean alt) {
		float[] m = spec.fx().muzzle()[alt ? 1 : 0];
		Vec3 fwd = Ballistics.direction(shooter.getXRot(), shooter.getYRot());
		Vec3 right = fwd.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1e-8 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 up = right.cross(fwd).normalize();
		return shooter.getEyePosition().add(fwd.scale(m[0] / UNITS)).add(right.scale(-m[1] / UNITS)).add(up.scale(m[2] / UNITS));
	}

	// ------------------------------------------------------------------ first person (inside the viewmodel transform)

	/**
	 * Called while the viewmodel is drawn, with its transform on {@code ps}: remembers where the muzzle is in the world
	 * and draws the flash at the muzzle attachment ({@code att_muzzle_flash}, baked by the converter).
	 */
	public static void submitViewmodel(PoseStack ps, SubmitNodeCollector collector, ViewmodelAsset asset, float[] pose) {
		Integer att = asset.bones.get(vmAlt && asset.bones.containsKey("att_muzzle_flash2") ? "att_muzzle_flash2" : "att_muzzle_flash");
		if (att == null) {
			return;
		}
		int o = att * 12;
		Vector3f pos = new Vector3f(pose[o + 3], pose[o + 7], pose[o + 11]);
		Vector3f fwd = new Vector3f(pose[o], pose[o + 4], pose[o + 8]).normalize();
		rememberMuzzle(ps.last().pose().transformPosition(new Vector3f(pos)));
		Guns.Flash f = vmFlash;
		if (f == null || f.silenced()) {
			return;
		}
		float age = (System.nanoTime() - vmFlashAt) / 1e9f;
		float life = Math.max(f.life(), 0.03f);
		if (age > life + 0.1f) {
			vmFlash = null;
			return;
		}
		Vector3f up = new Vector3f(0, 1, 0);
		Vector3f right = new Vector3f(1, 0, 0);
		Random r = new Random(vmSeed);
		if (age <= life) {
			drawFire(ps, collector, f, r, pos.x, pos.y, pos.z, fwd.x, fwd.y, fwd.z, right, up, INCH, age, life);
		}
		drawSparks(ps, collector, f, r, pos, fwd, right, up, INCH, age);
	}

	/** Viewmodel point (hand view space) to the world point that appears at the same place on screen. */
	private static void rememberMuzzle(Vector3f v) {
		Minecraft mc = Minecraft.getInstance();
		Camera cam = mc.gameRenderer.mainCamera();
		// the hand pass starts from the inverse view rotation (the model-view matrix re-applies it), so the pose gives
		// a camera-relative point in world axes: rotate it into view space first
		v = cam.getViewRotationMatrix(new org.joml.Matrix4f()).transformPosition(new Vector3f(v));
		if (v.z >= -1e-3) {
			return;
		}
		double tanHand = Math.tan(Math.toRadians(70.0 / 2));
		double tanWorld = Math.tan(Math.toRadians(cam.getFov() / 2));
		float s = (float) (tanWorld / tanHand);
		// v is where the muzzle shows on screen (hand view space). The world is drawn through the camera's recoil punch
		// (GameRendererMixin: Rx(-up) then Ry(right)), so the world point at that spot is the punch undone.
		Vector3f c = new Vector3f(v.x * s, v.y * s, v.z);
		float[] punch = dev.countercraft.client.GunClient.viewPunch(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
		if (punch != null) {
			c.rotateX((float) Math.toRadians(punch[0])).rotateY((float) Math.toRadians(-punch[1]));
		}
		muzzleFwd = -c.z;
		muzzleUp = c.y;
		muzzleRight = c.x;
		muzzleAt = System.nanoTime();
	}

	// ------------------------------------------------------------------ world

	/** Tracers and other players' muzzle flashes (camera-relative, with the level's pose stack). */
	public static void submitWorld(LevelRenderContext ctx) {
		MuzzleLight.frame();
		if (TRACERS.isEmpty() && WORLD_FLASHES.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Camera cam = mc.gameRenderer.mainCamera();
		Vec3 eye = cam.position();
		long now = System.nanoTime();
		PoseStack ps = ctx.poseStack();
		SubmitNodeCollector collector = ctx.submitNodeCollector();
		double tanV = Math.tan(Math.toRadians(cam.getFov() / 2));
		Vector3fc lv = cam.leftVector();
		Vector3fc uv = cam.upVector();
		Vector3f right = new Vector3f(-lv.x(), -lv.y(), -lv.z());
		Vector3f up = new Vector3f(uv.x(), uv.y(), uv.z());

		List<float[]> spark = new ArrayList<>();
		List<float[]> rope = new ArrayList<>();
		for (Iterator<Tracer> it = TRACERS.iterator(); it.hasNext(); ) {
			Tracer tr = it.next();
			double age = (now - tr.born()) / 1e9;
			Guns.Tracer t = tr.t();
			double head = t.speed() * age / UNITS;
			double len = t.maxLength() / UNITS * (t.fadeIn() > 0 ? Math.min(1.0, age / t.fadeIn()) : 1.0);
			double tail = Math.max(0, head - len);
			if (tail >= tr.dist() || age > 3) {
				it.remove();
				continue;
			}
			head = Math.min(head, tr.dist());
			Vec3 a = tr.start().add(tr.dir().scale(tail)).subtract(eye);
			Vec3 b = tr.start().add(tr.dir().scale(head)).subtract(eye);
			float wa = width(t, a.length(), tanV);
			float wb = width(t, b.length(), tanV);
			(t.rope() ? rope : spark).add(new float[] {(float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z, wa, wb,
					tr.r(), tr.g(), tr.b()});
		}
		if (!spark.isEmpty()) {
			collector.submitCustomGeometry(ps, additive(SPARK), (p, vc) -> spark.forEach(s -> beam(p, vc, s)));
		}
		if (!rope.isEmpty()) {
			collector.submitCustomGeometry(ps, additive(ROPE), (p, vc) -> rope.forEach(s -> beam(p, vc, s)));
		}

		for (Iterator<Flash> it = WORLD_FLASHES.iterator(); it.hasNext(); ) {
			Flash fl = it.next();
			float age = (now - fl.born()) / 1e9f;
			float life = Math.max(fl.f().life(), 0.03f);
			if (age > life + 0.1f) {
				it.remove();
				continue;
			}
			Vec3 c = fl.pos().subtract(eye);
			Random r = new Random(fl.seed());
			if (age <= life) {
				drawFire(ps, collector, fl.f(), r, (float) c.x, (float) c.y, (float) c.z, (float) fl.dir().x, (float) fl.dir().y, (float) fl.dir().z,
						right, up, (float) (1 / UNITS), age, life);
			}
			drawSparks(ps, collector, fl.f(), r, new Vector3f((float) c.x, (float) c.y, (float) c.z),
					new Vector3f((float) fl.dir().x, (float) fl.dir().y, (float) fl.dir().z), right, up, (float) (1 / UNITS), age);
		}
	}

	/** Half width (blocks) of a tracer at a distance: its radius, kept between CS2's min / max screen sizes. */
	private static float width(Guns.Tracer t, double dist, double tanV) {
		double w = t.radius() / UNITS;
		double frac = w / Math.max(1e-3, dist * tanV);
		double lo = t.minSize();
		double hi = t.maxSize() > 0 ? t.maxSize() : 1;
		frac = Math.max(lo, Math.min(hi, frac));
		frac = Math.max(frac, MIN_HALF_PIXELS / Math.max(1, Minecraft.getInstance().getWindow().getHeight() / 2.0));
		return (float) (frac * dist * tanV);
	}

	/** A camera-facing quad from a (tail) to b (head), widths wa / wb; texture v runs tail 0 -> head 1. */
	private static void beam(PoseStack.Pose p, VertexConsumer vc, float[] s) {
		Vector3f a = new Vector3f(s[0], s[1], s[2]);
		Vector3f b = new Vector3f(s[3], s[4], s[5]);
		Vector3f dir = new Vector3f(b).sub(a);
		if (dir.lengthSquared() < 1e-10) {
			return;
		}
		Vector3f mid = new Vector3f(a).add(b).mul(0.5f);
		Vector3f side = dir.cross(mid, new Vector3f());
		if (side.lengthSquared() < 1e-12) {
			return;
		}
		side.normalize();
		int col = color(s[8], s[9], s[10]);
		quad(p, vc, a.x - side.x * s[6], a.y - side.y * s[6], a.z - side.z * s[6], 0, 0,
				a.x + side.x * s[6], a.y + side.y * s[6], a.z + side.z * s[6], 1, 0,
				b.x + side.x * s[7], b.y + side.y * s[7], b.z + side.z * s[7], 1, 1,
				b.x - side.x * s[7], b.y - side.y * s[7], b.z - side.z * s[7], 0, 1, col);
	}

	/**
	 * The flash's fire sprites: {@code count} sprites along the barrel ({@code along} units, nearest first), growing
	 * outward, random roll / colour / alpha, overbright, fading out over the last 0.015 s. {@code unit} = metres or
	 * blocks per unit in this space.
	 */
	private static void drawFire(PoseStack ps, SubmitNodeCollector collector, Guns.Flash f, Random r, float x, float y, float z,
			float fx, float fy, float fz, Vector3f right, Vector3f up, float unit, float age, float life) {
		int n = Math.max(1, f.count());
		float fade = Math.min(1f, (life - age) / 0.015f);
		List<float[]> sprites = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			float frac = n == 1 ? 0.5f : i / (float) (n - 1);
			float along = (f.along()[0] + (f.along()[1] - f.along()[0]) * frac) * FLASH_REACH * unit;
			float radius = (f.radius()[0] + (f.radius()[1] - f.radius()[0]) * r.nextFloat()) * (0.2f + 0.6f * frac)
					* f.radiusScale() * FLASH_SIZE * unit;
			float roll = (float) Math.toRadians(f.roll()[0] + (f.roll()[1] - f.roll()[0]) * r.nextFloat()) * (r.nextBoolean() ? 1 : -1);
			float k = r.nextFloat();
			float a = (f.alpha()[0] + (f.alpha()[1] - f.alpha()[0]) * r.nextFloat()) * fade * Math.min(2f, f.overbright() * 0.5f);
			int[] c0 = f.color()[0];
			int[] c1 = f.color()[1];
			sprites.add(new float[] {x + fx * along, y + fy * along, z + fz * along, radius, roll,
					a * (c0[0] + (c1[0] - c0[0]) * k) / 255f, a * (c0[1] + (c1[1] - c0[1]) * k) / 255f, a * (c0[2] + (c1[2] - c0[2]) * k) / 255f,
					r.nextInt(FIRE.length)});
		}
		for (int seq = 0; seq < FIRE.length; seq++) {
			int s = seq;
			List<float[]> mine = sprites.stream().filter(q -> (int) q[8] == s).toList();
			if (!mine.isEmpty()) {
				collector.submitCustomGeometry(ps, additive(FIRE[s]), (p, vc) -> mine.forEach(q -> sprite(p, vc, q, right, up)));
			}
		}
	}

	/** Sparks thrown forward from the muzzle (CS2's spark child: ~200-400 u/s, short streaks), 0.1 s. */
	private static void drawSparks(PoseStack ps, SubmitNodeCollector collector, Guns.Flash f, Random r, Vector3f at, Vector3f fwd,
			Vector3f right, Vector3f up, float unit, float age) {
		int n = Math.min(6, f.sparks() / 2);
		float life = 0.1f;
		if (n <= 0 || age > life) {
			return;
		}
		Vector3f side = new Vector3f(fwd).cross(up);
		if (side.lengthSquared() < 1e-6) {
			side.set(right);
		}
		side.normalize();
		Vector3f lift = new Vector3f(side).cross(fwd).normalize();
		float fade = 1f - age / life;
		List<float[]> streaks = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			float speed = (200f + 200f * r.nextFloat()) * unit;
			float sx = (r.nextFloat() - 0.5f) * 0.6f;
			float sy = (r.nextFloat() - 0.5f) * 0.6f;
			Vector3f dir = new Vector3f(fwd).add(new Vector3f(side).mul(sx)).add(new Vector3f(lift).mul(sy)).normalize();
			Vector3f head = new Vector3f(dir).mul(speed * age).add(at);
			Vector3f tail = new Vector3f(dir).mul(Math.max(0, speed * age - 4f * unit)).add(at);
			float w = 0.25f * unit;
			streaks.add(new float[] {tail.x, tail.y, tail.z, head.x, head.y, head.z, w, w, fade, fade * 0.7f, fade * 0.35f});
		}
		collector.submitCustomGeometry(ps, additive(SPARK), (p, vc) -> streaks.forEach(s -> beam(p, vc, s)));
	}

	private static void sprite(PoseStack.Pose p, VertexConsumer vc, float[] q, Vector3f right, Vector3f up) {
		float c = (float) Math.cos(q[4]) * q[3];
		float s = (float) Math.sin(q[4]) * q[3];
		// rotated half-axes in the camera plane
		float ax = right.x * c + up.x * s, ay = right.y * c + up.y * s, az = right.z * c + up.z * s;
		float bx = -right.x * s + up.x * c, by = -right.y * s + up.y * c, bz = -right.z * s + up.z * c;
		int col = color(q[5], q[6], q[7]);
		quad(p, vc, q[0] - ax - bx, q[1] - ay - by, q[2] - az - bz, 0, 1,
				q[0] + ax - bx, q[1] + ay - by, q[2] + az - bz, 1, 1,
				q[0] + ax + bx, q[1] + ay + by, q[2] + az + bz, 1, 0,
				q[0] - ax + bx, q[1] - ay + by, q[2] - az + bz, 0, 0, col);
	}

	/** Both windings, so it shows whichever way round the camera sees it. */
	private static void quad(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1,
			float u1, float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3, int col) {
		vertex(p, vc, x0, y0, z0, u0, v0, col);
		vertex(p, vc, x1, y1, z1, u1, v1, col);
		vertex(p, vc, x2, y2, z2, u2, v2, col);
		vertex(p, vc, x3, y3, z3, u3, v3, col);
		vertex(p, vc, x3, y3, z3, u3, v3, col);
		vertex(p, vc, x2, y2, z2, u2, v2, col);
		vertex(p, vc, x1, y1, z1, u1, v1, col);
		vertex(p, vc, x0, y0, z0, u0, v0, col);
	}

	private static void vertex(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float u, float v, int col) {
		vc.addVertex(p, x, y, z).setColor(col).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(p, 0f, 1f, 0f);
	}

	/** Additive blending carries brightness in the colour, so alpha stays opaque. */
	private static int color(float r, float g, float b) {
		int ri = (int) (Math.max(0f, Math.min(1f, r)) * 255);
		int gi = (int) (Math.max(0f, Math.min(1f, g)) * 255);
		int bi = (int) (Math.max(0f, Math.min(1f, b)) * 255);
		return 0xFF000000 | ri << 16 | gi << 8 | bi;
	}

	private static RenderType additive(Identifier tex) {
		return RenderTypes.energySwirl(tex, 0f, 0f); // the additive pipeline in 26.3 (eyes is no longer additive)
	}

	public static void clear() {
		TRACERS.clear();
		WORLD_FLASHES.clear();
		vmFlash = null;
	}
}
