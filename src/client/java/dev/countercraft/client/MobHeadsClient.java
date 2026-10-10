package dev.countercraft.client;

import dev.countercraft.CounterCraft;
import dev.countercraft.client.mixin.EntityRenderDispatcherAccessor;
import dev.countercraft.client.mixin.ModelPartAccessor;
import dev.countercraft.gun.MobHeads;
import dev.countercraft.net.MobHeadsPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Reads every mob's head hitboxes from its Minecraft model ({@link MobHeads}): walks each living renderer's model in its
 * rest pose, the way LivingEntityRenderer places it (turned upside down by scale(-1, -1, 1), raised 1.501), and keeps the
 * cubes under any part named "head". Done once the renderers exist after joining (and after a resource reload), then
 * handed to the server.
 */
public final class MobHeadsClient {
	private static boolean dirty = true;

	private MobHeadsClient() {
	}

	public static void markDirty() {
		dirty = true;
	}

	public static void tick(Minecraft mc) {
		if (!dirty || mc.level == null || mc.getConnection() == null) {
			return;
		}
		dirty = false;
		Map<String, MobHeads.Shape> table;
		try {
			table = compute(mc);
		} catch (RuntimeException e) {
			CounterCraft.LOG.warn("[countercraft] mob head hitboxes not read: {}", e.toString());
			return;
		}
		if (table.isEmpty()) {
			return;
		}
		MobHeads.set(table); // singleplayer: the integrated server uses it directly
		if (ClientPlayNetworking.canSend(MobHeadsPayload.TYPE)) {
			ClientPlayNetworking.send(new MobHeadsPayload(table));
		}
		CounterCraft.LOG.info("[countercraft] head hitboxes for {} mob types", table.values().stream().filter(s -> !s.parts().isEmpty()).count());
	}

	/** Collected while walking one model. */
	private static final class Walk {
		float height;
		float legTop;
		final List<MobHeads.Part> parts = new ArrayList<>();
	}

	/** One head part being collected: its pivot and boxes. */
	private static final class Head {
		final Vector3f pivot;
		final boolean follows;
		final List<Float> boxes = new ArrayList<>();

		Head(Vector3f pivot, boolean follows) {
			this.pivot = pivot;
			this.follows = follows;
		}
	}

	private static Map<String, MobHeads.Shape> compute(Minecraft mc) {
		Map<EntityType<?>, EntityRenderer<?, ?>> renderers = ((EntityRenderDispatcherAccessor) mc.getEntityRenderDispatcher()).countercraft$renderers();
		Map<String, MobHeads.Shape> out = new HashMap<>();
		if (renderers == null) {
			return out;
		}
		for (Map.Entry<EntityType<?>, EntityRenderer<?, ?>> e : renderers.entrySet()) {
			if (!(e.getValue() instanceof LivingEntityRenderer<?, ?, ?> lr) || lr.getModel() == null) {
				continue;
			}
			Walk w = new Walk();
			// LivingEntityRenderer: scale(-1, -1, 1) then translate(0, -1.501, 0); model units are 1/16 block
			Matrix4f base = new Matrix4f().scale(-1f, -1f, 1f).translate(0f, -1.501f, 0f);
			walk(lr.getModel().root(), "", base, null, w);
			w.legTop = Math.min(w.legTop, w.height * 0.5f); // splayed legs (spiders) reach above the body
			if (w.height > 0.05f) {
				out.put(BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()).toString(),
						new MobHeads.Shape(scaleHeight(lr, e.getKey(), w.height), w.legTop, w.parts));
			}
		}
		return out;
	}

	/**
	 * The height MobHeads scales the model by (scale at runtime = the mob's box height over this): the type's box height
	 * over the size its renderer draws the model at, so the model comes out as drawn. Sizing the model's own height to
	 * the box shrank mobs whose head sticks up above their box (a horse's head hitbox sat down by its chest); the
	 * model's height is the fallback when the renderer's size can't be read (slimes: it depends on the size).
	 */
	private static float scaleHeight(LivingEntityRenderer<?, ?, ?> lr, EntityType<?> type, float modelHeight) {
		try {
			var state = (net.minecraft.client.renderer.entity.state.LivingEntityRenderState) lr.createRenderState();
			com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
			((dev.countercraft.client.mixin.LivingEntityRendererInvoker) lr).countercraft$scale(state, ps);
			float r = Math.abs(ps.last().pose().m11());
			float box = type.getHeight();
			if (r > 0.01f && r < 100f && box > 0.05f) {
				return box / r;
			}
		} catch (RuntimeException e) {
			// a renderer whose scale needs a filled-in state: the model's height
		}
		return modelHeight;
	}

	private static void walk(ModelPart part, String name, Matrix4f parent, Head head, Walk w) {
		PartPose pose = part.getInitialPose();
		Matrix4f m = new Matrix4f(parent).translate(pose.x() / 16f, pose.y() / 16f, pose.z() / 16f)
				.rotateZYX(pose.zRot(), pose.yRot(), pose.xRot()).scale(pose.xScale(), pose.yScale(), pose.zScale());
		Head h = head;
		if (h == null && name.contains("head")) {
			Vector3f pivot = parent.transformPosition(new Vector3f(pose.x() / 16f, pose.y() / 16f, pose.z() / 16f));
			h = new Head(pivot, name.equals("head") || name.equals("head_parts")); // horses' head_parts turns with the head
		}
		boolean leg = name.contains("leg");
		for (ModelPart.Cube c : ((ModelPartAccessor) (Object) part).countercraft$cubes()) {
			float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
			for (int k = 0; k < 8; k++) {
				Vector3f v = m.transformPosition(new Vector3f(((k & 1) == 0 ? c.minX : c.maxX) / 16f, ((k & 2) == 0 ? c.minY : c.maxY) / 16f,
						((k & 4) == 0 ? c.minZ : c.maxZ) / 16f));
				x0 = Math.min(x0, v.x);
				y0 = Math.min(y0, v.y);
				z0 = Math.min(z0, v.z);
				x1 = Math.max(x1, v.x);
				y1 = Math.max(y1, v.y);
				z1 = Math.max(z1, v.z);
			}
			w.height = Math.max(w.height, y1);
			if (leg) {
				w.legTop = Math.max(w.legTop, y1);
			}
			if (h != null) {
				h.boxes.add(x0 - h.pivot.x);
				h.boxes.add(y0 - h.pivot.y);
				h.boxes.add(z0 - h.pivot.z);
				h.boxes.add(x1 - h.pivot.x);
				h.boxes.add(y1 - h.pivot.y);
				h.boxes.add(z1 - h.pivot.z);
			}
		}
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).countercraft$children().entrySet()) {
			walk(child.getValue(), child.getKey(), m, h, w);
		}
		if (h != null && head == null && !h.boxes.isEmpty()) {
			float[] b = new float[h.boxes.size()];
			for (int i = 0; i < b.length; i++) {
				b[i] = h.boxes.get(i);
			}
			w.parts.add(new MobHeads.Part(h.pivot.x, h.pivot.y, h.pivot.z, b, h.follows));
		}
	}
}
