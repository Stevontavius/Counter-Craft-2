package dev.countercraft.client.radio;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.CounterCraft;
import dev.countercraft.net.PingPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * CS2's player ping (mouse 3, {@code player_ping}): marks where you look for everyone, in your player colour, with CS2's
 * ping arrow (drawn through walls) and ground rings (playerping textures) and the UI.PlayerPing sound. Pinging again
 * within half a second makes it urgent (red, UI.PlayerPingUrgent). One ping per player; a new one replaces it.
 * Approximated (flagged): how long a ping stays (5 s) and the player colours (CS2's five teammate colours, by eye).
 */
public final class Pings {
	private static final double RANGE = 256;
	private static final double LIFE = 5.0;
	private static final double DOUBLE_TAP = 0.5;
	private static final int[] COLORS = {0xFFF2C63D, 0xFFA05CF2, 0xFF3FC15A, 0xFF4BA3F2, 0xFFF28B2E};
	private static final int URGENT = 0xFFF23D3D;
	private static final Identifier ARROW = CounterCraft.id("textures/fx/ping_arrow.png");
	private static final Identifier ARROW_OUTLINE = CounterCraft.id("textures/fx/ping_arrow_outline.png");
	private static final Identifier RING = CounterCraft.id("textures/fx/ping_ring.png");

	private record Ping(Vec3 pos, int color, long born) {
	}

	private static final Map<Integer, Ping> PINGS = new HashMap<>();
	private static long lastPing;

	private Pings() {
	}

	/** Start of client tick: the ping key (it shares mouse 3 with pick block, which is skipped when it pings). */
	public static void startTick(Minecraft mc) {
		boolean pressed = false;
		while (RadioWheel.PING.consumeClick()) {
			pressed = true;
		}
		if (pressed && mc.player != null && mc.gui.screen() == null) {
			while (mc.options.keyPickItem.consumeClick()) {
				// the ping takes mouse 3
			}
			long now = System.nanoTime();
			boolean urgent = (now - lastPing) / 1e9 < DOUBLE_TAP;
			lastPing = now;
			pingHere(mc, urgent);
		}
	}

	/** Ping where the crosshair is (blocks or a creature, up to 256 blocks). */
	static void pingHere(Minecraft mc, boolean urgent) {
		if (mc.player == null || !ClientPlayNetworking.canSend(PingPayload.TYPE)) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 end = eye.add(mc.player.getViewVector(1f).scale(RANGE));
		BlockHitResult block = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		Vec3 at = block.getType() == HitResult.Type.MISS ? null : block.getLocation();
		Vec3 reach = at != null ? at : end;
		EntityHitResult ent = ProjectileUtil.getEntityHitResult(mc.player, eye, reach, new AABB(eye, reach).inflate(1),
				e -> e != mc.player && e.isPickable() && !e.isSpectator(), eye.distanceToSqr(reach));
		if (ent != null) {
			at = ent.getEntity().position();
		}
		if (at == null) {
			CounterCraft.LOG.info("[countercraft] ping: nothing under the crosshair within {} blocks", RANGE);
			return;
		}
		ClientPlayNetworking.send(new PingPayload(0, 0, at.x, at.y, at.z, urgent));
	}

	public static void onPing(PingPayload p) {
		CounterCraft.LOG.debug("[countercraft] ping from {} at {} {} {}", p.player(), p.x(), p.y(), p.z());
		int color = p.urgent() ? URGENT : COLORS[Math.floorMod(p.color(), COLORS.length)];
		PINGS.put(p.player(), new Ping(new Vec3(p.x(), p.y(), p.z()), color, System.nanoTime()));
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(CounterCraft.id(p.urgent() ? "ui.playerpingurgent" : "ui.playerping"),
				SoundSource.UI, 1f, 1f, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
	}

	public static void submit(LevelRenderContext ctx) {
		if (PINGS.isEmpty() || !dev.countercraft.client.GunHud.drawHud) {
			return; // cl_drawhud 0 hides pings with the rest of the HUD
		}
		Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 eye = cam.position();
		Vector3fc lv = cam.leftVector();
		Vector3fc uv = cam.upVector();
		Vector3f right = new Vector3f(-lv.x(), -lv.y(), -lv.z());
		Vector3f up = new Vector3f(uv.x(), uv.y(), uv.z());
		long now = System.nanoTime();
		PINGS.values().removeIf(p -> (now - p.born()) / 1e9 > LIFE);
		PoseStack ps = ctx.poseStack();
		for (Ping p : PINGS.values()) {
			double age = (now - p.born()) / 1e9;
			float fade = (float) Math.min(1.0, (LIFE - age) / 0.5);
			Vec3 rel = p.pos().subtract(eye);
			float dist = (float) rel.length();
			// the arrow (CS2's playerping_arrow: a tall pointer) with its tip on the spot, in the player's colour with
			// the white outline layer, about the same size on screen at any distance, visible through walls
			float s = Math.max(0.6f, dist * 0.06f);
			float ax = (float) rel.x;
			float ay = (float) rel.y + s;
			float az = (float) rel.z;
			int col = (Math.round(255 * fade) << 24) | (p.color() & 0xFFFFFF);
			int white = (Math.round(255 * fade) << 24) | 0xFFFFFF;
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.textSeeThrough(ARROW), (pose, vc) -> {
				quad(pose, vc, ax, ay, az, right, up, s, col);
			});
			ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.textSeeThrough(ARROW_OUTLINE), (pose, vc) -> {
				quad(pose, vc, ax, ay, az, right, up, s, white);
			});
			// ground rings: grow 0 -> full over 0.9 s, fading, once a second (CS2's player_ping_ground_rings)
			float t = (float) (age % 1.0) / 0.9f;
			if (t <= 1f) {
				float rr = 0.15f + 0.6f * t;
				int ringCol = (Math.round(255 * fade * (1f - t)) << 24) | (p.color() & 0xFFFFFF);
				float gx = (float) rel.x;
				float gy = (float) rel.y + 0.02f;
				float gz = (float) rel.z;
				ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.textSeeThrough(RING), (pose, vc) -> {
					flat(pose, vc, gx, gy, gz, rr, ringCol);
				});
			}
		}
	}

	private static void quad(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, Vector3f right, Vector3f up, float h, int col) {
		v(p, vc, x - right.x * h - up.x * h, y - right.y * h - up.y * h, z - right.z * h - up.z * h, 0, 1, col);
		v(p, vc, x + right.x * h - up.x * h, y + right.y * h - up.y * h, z + right.z * h - up.z * h, 1, 1, col);
		v(p, vc, x + right.x * h + up.x * h, y + right.y * h + up.y * h, z + right.z * h + up.z * h, 1, 0, col);
		v(p, vc, x - right.x * h + up.x * h, y - right.y * h + up.y * h, z - right.z * h + up.z * h, 0, 0, col);
		// and the other winding (the see-through text pipeline culls back faces)
		v(p, vc, x - right.x * h + up.x * h, y - right.y * h + up.y * h, z - right.z * h + up.z * h, 0, 0, col);
		v(p, vc, x + right.x * h + up.x * h, y + right.y * h + up.y * h, z + right.z * h + up.z * h, 1, 0, col);
		v(p, vc, x + right.x * h - up.x * h, y + right.y * h - up.y * h, z + right.z * h - up.z * h, 1, 1, col);
		v(p, vc, x - right.x * h - up.x * h, y - right.y * h - up.y * h, z - right.z * h - up.z * h, 0, 1, col);
	}

	private static void flat(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float r, int col) {
		v(p, vc, x - r, y, z - r, 0, 0, col);
		v(p, vc, x - r, y, z + r, 0, 1, col);
		v(p, vc, x + r, y, z + r, 1, 1, col);
		v(p, vc, x + r, y, z - r, 1, 0, col);
		v(p, vc, x + r, y, z - r, 1, 0, col);
		v(p, vc, x + r, y, z + r, 1, 1, col);
		v(p, vc, x - r, y, z + r, 0, 1, col);
		v(p, vc, x - r, y, z - r, 0, 0, col);
	}

	private static void v(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float u, float vv, int col) {
		vc.addVertex(p, x, y, z).setColor(col).setUv(u, vv).setLight(LightCoordsUtil.FULL_BRIGHT);
	}

	public static void clear() {
		PINGS.clear();
	}
}
