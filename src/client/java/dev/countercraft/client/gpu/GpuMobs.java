package dev.countercraft.client.gpu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.countercraft.client.mixin.ModelPartAccessor;
import dev.countercraft.client.mixin.RenderSetupAccessor;
import dev.countercraft.client.mixin.RenderTypeAccessor;
import dev.countercraft.client.mixin.TextureBindingAccessor;
import dev.countercraft.client.options.CcOptions;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Mobs and animals on the GPU (Video > GPU Environment Rendering, read at startup). Each vanilla model's cubes go up
 * once as one mesh, one bone per model part; every frame only the parts' poses go up, and {@link GpuAgents} draws them
 * like the agents. Only plain layers: Minecraft's entity solid / cutout pipelines, untinted, not outlined (glowing),
 * one whole texture. Anything else (translucent slime bodies, tinted wool, glowing mobs, players) draws as before.
 */
public final class GpuMobs {
	/** A model's mesh: vertices (part space, one bone each), triangles, and which part each bone is. */
	private record Baked(Map<ModelPart, Integer> bones, int boneCount, float[] pos, float[] nrm, float[] uv, byte[] joints, byte[] weights,
			int vertexCount, int[] idx) {
	}

	/**
	 * Models baked so far (one per renderer, shared by every mob of that kind; weak: a resource reload makes new models,
	 * and Model doesn't override equals, so this is by identity); {@link #NONE}: can't go up.
	 */
	private static final Map<Model<?>, Baked> BAKED = new WeakHashMap<>();
	private static final Baked NONE = new Baked(Map.of(), 0, null, null, null, null, null, 0, null);

	private GpuMobs() {
	}

	/** A model submit: drawn on the GPU (true), or left to vanilla. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean submit(Model model, Object state, PoseStack ps, RenderType type, int light, int overlay, int color, UvMapping uv,
			int outline) {
		if (!CcOptions.gpuEnvironmentActive() || uv != null || color != -1 || outline != 0 || !(state instanceof LivingEntityRenderState)
				|| state instanceof AvatarRenderState) {
			return false;
		}
		RenderPipeline p = type.pipeline();
		if (p != RenderPipelines.ENTITY_CUTOUT && p != RenderPipelines.ENTITY_CUTOUT_CULL && p != RenderPipelines.ENTITY_SOLID) {
			return false;
		}
		Identifier texture = texture(type);
		if (texture == null || !GpuAgents.activeForMobs()) {
			return false;
		}
		Baked b = BAKED.computeIfAbsent(model, m -> bake((Model<?>) m));
		if (b == NONE) {
			return false;
		}
		// vanilla poses the model right before drawing it: the same, here, and the parts' poses read straight away
		model.setupAnim(state);
		float[] bones = new float[b.boneCount() * 12];
		pose(model.root(), new PoseStack(), b, bones, true);
		GpuAgents.Block block = GpuAgents.block(ps.last().pose(), bones, b.boneCount(), light, overlay);
		if (block == null) {
			return false;
		}
		GpuAgents.draw(b.pos(), b.nrm(), b.uv(), b.joints(), b.weights(), b.vertexCount(), b.idx(), texture, block);
		return true;
	}

	/** The render type's own texture (Sampler0), or null. */
	private static Identifier texture(RenderType type) {
		Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) type).countercraft$state()).countercraft$textures().get("Sampler0");
		return binding == null ? null : ((TextureBindingAccessor) binding).countercraft$location();
	}

	/**
	 * Each part with cubes gets its pose as ModelPart.render would apply it (3x4 row-major, from the model's root);
	 * hidden parts (and everything under them) and skipDraw parts' own cubes collapse to a point (zero matrix).
	 */
	private static void pose(ModelPart part, PoseStack ps, Baked b, float[] out, boolean shown) {
		shown &= part.visible;
		ps.pushPose();
		part.translateAndRotate(ps);
		Integer bone = b.bones().get(part);
		if (bone != null && shown && !part.skipDraw) {
			Matrix4f m = ps.last().pose();
			int o = bone * 12;
			for (int r = 0; r < 3; r++) {
				out[o + r * 4] = m.get(0, r);
				out[o + r * 4 + 1] = m.get(1, r);
				out[o + r * 4 + 2] = m.get(2, r);
				out[o + r * 4 + 3] = m.get(3, r);
			}
		}
		for (ModelPart child : ((ModelPartAccessor) (Object) part).countercraft$children().values()) {
			pose(child, ps, b, out, shown);
		}
		ps.popPose();
	}

	/** Every cube face of every part as two triangles, in its part's space (blocks), on that part's bone. */
	private static Baked bake(Model<?> model) {
		List<ModelPart> parts = new ArrayList<>();
		collect(model.root(), parts);
		if (parts.isEmpty() || parts.size() > 128) {
			return NONE;
		}
		int quads = 0;
		for (ModelPart part : parts) {
			for (ModelPart.Cube c : ((ModelPartAccessor) (Object) part).countercraft$cubes()) {
				quads += c.polygons.length;
			}
		}
		int n = quads * 4;
		float[] pos = new float[n * 3];
		float[] nrm = new float[n * 3];
		float[] uv = new float[n * 2];
		byte[] joints = new byte[n * 4];
		byte[] weights = new byte[n * 4];
		int[] idx = new int[quads * 6];
		Map<ModelPart, Integer> bones = new IdentityHashMap<>();
		int v = 0;
		int t = 0;
		for (int bone = 0; bone < parts.size(); bone++) {
			ModelPart part = parts.get(bone);
			bones.put(part, bone);
			for (ModelPart.Cube c : ((ModelPartAccessor) (Object) part).countercraft$cubes()) {
				for (ModelPart.Polygon poly : c.polygons) {
					int first = v;
					for (ModelPart.Vertex vx : poly.vertices()) {
						pos[v * 3] = vx.worldX();
						pos[v * 3 + 1] = vx.worldY();
						pos[v * 3 + 2] = vx.worldZ();
						nrm[v * 3] = poly.normal().x();
						nrm[v * 3 + 1] = poly.normal().y();
						nrm[v * 3 + 2] = poly.normal().z();
						uv[v * 2] = vx.u();
						uv[v * 2 + 1] = vx.v();
						joints[v * 4] = (byte) bone;
						weights[v * 4] = (byte) 255;
						v++;
					}
					idx[t++] = first;
					idx[t++] = first + 1;
					idx[t++] = first + 2;
					idx[t++] = first;
					idx[t++] = first + 2;
					idx[t++] = first + 3;
				}
			}
		}
		return new Baked(bones, parts.size(), pos, nrm, uv, joints, weights, n, idx);
	}

	/** The parts that have cubes, depth first. */
	private static void collect(ModelPart part, List<ModelPart> out) {
		if (!((ModelPartAccessor) (Object) part).countercraft$cubes().isEmpty()) {
			out.add(part);
		}
		for (ModelPart child : ((ModelPartAccessor) (Object) part).countercraft$children().values()) {
			collect(child, out);
		}
	}
}
