package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.countercraft.gun.Hitboxes;
import dev.countercraft.gun.MobHeads;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Hitbox debug view (key: Counter-Craft 2 > Debug Hitboxes, unbound by default): draws what bullets test, for living things within 48
 * blocks. Red = head (model head cubes, CS2 head capsule, or the eye-height fallback plane), yellow = CS2 body capsules
 * (players), blue = the leg line (body hits below it are leg hits), grey = the Minecraft box (body), purple = the knife's
 * backstab plane on mobs (the zone is behind it, where the short arrow points).
 */
public final class HitboxDebug {
	private static final int HEAD = 0xFFFF3030;
	private static final int BODY = 0xFFFFD040;
	private static final int LEGS = 0xFF40A0FF;
	private static final int BOX = 0xFFB0B0B0;
	private static final int FALLBACK = 0xFFFF9020;
	private static final int BACKSTAB = 0xFFC060FF;
	private static boolean on;

	private HitboxDebug() {
	}

	public static void tick(Minecraft mc) {
		while (GunKeys.DEBUG_HITBOXES.consumeClick()) {
			on = !on;
			if (mc.player != null) {
				mc.player.sendOverlayMessage(Component.literal("Hitbox debug: " + (on ? "ON" : "OFF")));
			}
		}
	}

	private record Line(Vec3 a, Vec3 b, int color) {
	}

	public static void submit(LevelRenderContext ctx) {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.level == null || mc.player == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		List<Line> lines = new ArrayList<>();
		for (Entity e : mc.level.getEntities(mc.player, mc.player.getBoundingBox().inflate(48), x -> x instanceof LivingEntity && x.isAlive())) {
			// draw at the interpolated position (the boxes are computed at the tick position)
			Vec3 shift = e.getPosition(partial).subtract(e.position()).subtract(cam);
			if (Hitboxes.uses(e)) {
				for (Hitboxes.Capsule k : Hitboxes.worldCapsules(e)) {
					capsule(lines, new Vec3(k.ax(), k.ay(), k.az()).add(shift), new Vec3(k.bx(), k.by(), k.bz()).add(shift), k.r(),
							k.group() == Hitboxes.HEAD ? HEAD : BODY);
				}
				continue;
			}
			AABB box = e.getBoundingBox().inflate(e.getPickRadius()).move(shift);
			box(lines, box, BOX);
			if (e instanceof LivingEntity le) {
				// the knife's backstab zone: everything behind this plane (along the body's facing)
				Vec3 body = Vec3.directionFromRotation(0f, le.yBodyRot);
				Vec3 side = new Vec3(-body.z, 0, body.x).scale(box.getXsize() * 0.75);
				Vec3 mid = new Vec3(le.getX(), 0, le.getZ()).add(shift.x, 0, shift.z).add(body.scale(dev.countercraft.gun.KnifeItem.backZoneDepth(le)));
				Vec3[] q = {mid.add(side).add(0, box.minY, 0), mid.subtract(side).add(0, box.minY, 0), mid.subtract(side).add(0, box.maxY, 0),
						mid.add(side).add(0, box.maxY, 0)};
				for (int i = 0; i < 4; i++) {
					lines.add(new Line(q[i], q[(i + 1) % 4], BACKSTAB));
				}
				lines.add(new Line(q[0], q[2], BACKSTAB));
				// an arrow pointing behind: where the back zone is
				Vec3 c = mid.add(0, (box.minY + box.maxY) / 2, 0);
				lines.add(new Line(c, c.subtract(body.scale(0.5)), BACKSTAB));
			}
			if (MobHeads.has(e)) {
				for (Vec3[] c : MobHeads.worldBoxes(e)) {
					Vec3[] s = new Vec3[8];
					for (int i = 0; i < 8; i++) {
						s[i] = c[i].add(shift);
					}
					cuboid(lines, s, HEAD);
				}
				double leg = MobHeads.legHeight(e);
				if (leg > 0) {
					rect(lines, box, e.getY() + leg + shift.y, LEGS);
				}
			} else {
				// no model data: head = above eye height - margin (Ballistics.headshot)
				rect(lines, box, e.getEyeY() - dev.countercraft.gun.Ballistics.HEAD_MARGIN + shift.y, FALLBACK);
			}
		}
		if (lines.isEmpty()) {
			return;
		}
		PoseStack ps = ctx.poseStack();
		ctx.submitNodeCollector().submitCustomGeometry(ps, RenderTypes.lines(), (pose, vc) -> {
			for (Line l : lines) {
				line(pose, vc, l.a(), l.b(), l.color());
			}
		});
	}

	private static void line(PoseStack.Pose pose, VertexConsumer vc, Vec3 a, Vec3 b, int color) {
		Vec3 n = b.subtract(a).normalize();
		vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2f);
		vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(color).setNormal(pose, (float) n.x, (float) n.y, (float) n.z).setLineWidth(2f);
	}

	/** 8 corners (bit 0 x, bit 1 y, bit 2 z): the 12 edges. */
	private static void cuboid(List<Line> out, Vec3[] c, int color) {
		for (int i = 0; i < 8; i++) {
			for (int bit = 1; bit < 8; bit <<= 1) {
				if ((i & bit) == 0) {
					out.add(new Line(c[i], c[i | bit], color));
				}
			}
		}
	}

	private static void box(List<Line> out, AABB b, int color) {
		Vec3[] c = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			c[i] = new Vec3((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
		}
		cuboid(out, c, color);
	}

	/** A horizontal rectangle across the box at height y. */
	private static void rect(List<Line> out, AABB b, double y, int color) {
		Vec3 a = new Vec3(b.minX, y, b.minZ), bb = new Vec3(b.maxX, y, b.minZ), c = new Vec3(b.maxX, y, b.maxZ), d = new Vec3(b.minX, y, b.maxZ);
		out.add(new Line(a, bb, color));
		out.add(new Line(bb, c, color));
		out.add(new Line(c, d, color));
		out.add(new Line(d, a, color));
	}

	/** A capsule as its axis, rings at both ends and four side lines. */
	private static void capsule(List<Line> out, Vec3 a, Vec3 b, double r, int color) {
		Vec3 axis = b.subtract(a);
		Vec3 dir = axis.lengthSqr() < 1e-8 ? new Vec3(0, 1, 0) : axis.normalize();
		Vec3 u = Math.abs(dir.y) < 0.9 ? dir.cross(new Vec3(0, 1, 0)).normalize() : dir.cross(new Vec3(1, 0, 0)).normalize();
		Vec3 v = dir.cross(u);
		int n = 16;
		for (Vec3 end : new Vec3[] {a, b}) {
			for (int i = 0; i < n; i++) {
				double t0 = i * Math.PI * 2 / n, t1 = (i + 1) * Math.PI * 2 / n;
				out.add(new Line(end.add(u.scale(Math.cos(t0) * r)).add(v.scale(Math.sin(t0) * r)),
						end.add(u.scale(Math.cos(t1) * r)).add(v.scale(Math.sin(t1) * r)), color));
			}
		}
		for (int i = 0; i < 4; i++) {
			double t = i * Math.PI / 2;
			Vec3 off = u.scale(Math.cos(t) * r).add(v.scale(Math.sin(t) * r));
			out.add(new Line(a.add(off), b.add(off), color));
		}
		// the caps' tips
		out.add(new Line(a.subtract(dir.scale(r)), b.add(dir.scale(r)), color));
	}
}
