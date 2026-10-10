// GPUs were made for this shit.
package dev.countercraft.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.countercraft.CounterCraft;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/**
 * Agents, held guns, ragdolls and dropped guns drawn on the GPU. Each mesh goes to the GPU once (vanilla's ENTITY vertex
 * layout, with the bone weights in Color and the bone indices in UV1); every frame only the pose goes up: one small
 * block per drawn thing (placement, light, overlay, up to 128 bone matrices), skinned in shaders/core/agent.vsh and
 * shaded by vanilla's own entity fragment shader, or lit the CS:GO way (agent_vm.fsh: the viewmodel, and agents and
 * their guns with Agent Shaders on). The CPU skinning and vertex streaming this replaces cost about a
 * millisecond per player per frame and several megabytes of vertex data; this costs a few microseconds and 6 KB.
 *
 * <p>Frame flow: entity submits queue draws ({@link #draw}) while the level renders; once every submit is in, vanilla
 * prepares its feature frame and the blocks are written ({@link #prepare}); vanilla's solid feature pass then draws
 * them inside its own render pass ({@link #execute}), so targets, depth and ordering are exactly vanilla's.</p>
 *
 * <p>Off (the CPU path draws instead) when the option is off, under a shader pack (Iris replaces vanilla's pipelines),
 * outside the level render (inventory and loadout previews), or for good after anything here fails.</p>
 */
public final class GpuAgents {
	private static final int MAX_BONES = 128;
	private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId GPU_TOAST =
			new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10000L);
	/** std140 AgentSkin: mat4 PoseMat, ivec4 LightOverlay, vec4 LensEye, LensCenter, LensNormal, EnvUp, vec4 Bones[MAX_BONES * 3]. */
	private static final int BLOCK_BYTES = 64 + 16 + 64 + MAX_BONES * 3 * 16;
	/** Poses per pass (agents, guns, and with GPU Environment Rendering the mobs too); past it, the CPU draws the rest. */
	private static final int MAX_BLOCKS = 512;
	/** Kept for the agents when mobs fill the pass. */
	private static final int AGENT_RESERVE = 32;
	private static final int VERTEX_BYTES = 36;

	private static RenderPipeline pipeline;
	private static CompiledRenderPipeline compiled;
	/** Your own first-person legs: the same, but nothing within {@link #LEGS_NEAR_CLIP} of the camera is drawn. */
	private static RenderPipeline legsPipeline;
	private static CompiledRenderPipeline legsCompiled;
	/** An aimed scope: the gun behind its eyepiece glass isn't drawn (CS2's lens shows the world there). */
	private static RenderPipeline lensPipeline;
	private static CompiledRenderPipeline lensCompiled;
	/** The first-person viewmodel, lit the CS:GO way (agent_vm.fsh); and the same with an aimed scope's lens window. */
	private static RenderPipeline litPipeline;
	private static CompiledRenderPipeline litCompiled;
	private static RenderPipeline litLensPipeline;
	private static CompiledRenderPipeline litLensCompiled;
	/** Your own first-person legs, lit (Agent Shaders), with the legs' near clip. */
	private static RenderPipeline litLegsPipeline;
	private static CompiledRenderPipeline litLegsCompiled;
	/** The viewmodel's maps when a material has none (the agents' gloves and sleeves): roughness 0.85, no metal, no AO; a flat normal. */
	public static final Identifier DEFAULT_SPEC = CounterCraft.id("textures/misc/vm_default_s.png");
	public static final Identifier FLAT_NORMAL = CounterCraft.id("textures/misc/vm_flat_n.png");
	/**
	 * CS2's first-person legs layer has its own near plane (r_csgo_firstpersonlegs_nearz_offset; its value isn't
	 * exposed): ours, in blocks, so the inside of the cut-off body never shows. An approximation.
	 */
	public static final float LEGS_NEAR_CLIP = 0.55f;
	private static boolean failed;
	/**
	 * Pose buffers: a pool used round-robin, one per feature pass (the level, the hand, each menu preview), so no ring
	 * rotates twice in a frame. A MappableRingBuffer's rotate waits on its oldest buffer's fence: one ring shared by the
	 * level and hand passes went round in 1.5 frames and the CPU waited on the GPU (stutter).
	 */
	private static final MappableRingBuffer[] RINGS = new MappableRingBuffer[6];
	private static int nextRing;
	private static MappableRingBuffer ring;
	private static int stride;

	/** A mesh's vertices on the GPU. */
	private record Mesh(GpuBuffer buffer) {
	}

	/** A triangle list on the GPU. */
	private record Indices(GpuBuffer buffer, int count) {
	}

	/** One pose: where to write it in this frame's block buffer. */
	public static final class Block {
		final float[] pose = new float[16];
		final float[] bones;
		final int boneCount;
		final int light;
		final int overlay;
		/** First-person legs: drawn with the near clip. */
		final boolean nearClip;
		/** A lens window (skinned space): eye xyz, centre xyz, radius, axis away from the eye xyz; or null. */
		final float[] lens;
		/** Lit the CS:GO way (the viewmodel), with the world's up in this space; or null (vanilla entity shading). */
		final float[] envUp;
		int offset = -1;

		Block(Matrix4f pose, float[] bones, int boneCount, int light, int overlay, boolean nearClip, float[] lens, float[] envUp) {
			pose.get(this.pose);
			this.bones = bones;
			this.boneCount = boneCount;
			this.light = light;
			this.overlay = overlay;
			this.nearClip = nearClip;
			this.lens = lens;
			this.envUp = envUp;
		}

		/** Which pipeline draws it: 0 normal, 1 legs (near clip), 2 lens window, 3 lit, 4 lit with a lens window, 5 lit legs. */
		int kind() {
			return envUp != null ? (lens != null ? 4 : nearClip ? 5 : 3) : nearClip ? 1 : lens != null ? 2 : 0;
		}
	}

	private record Draw(Mesh mesh, Indices indices, Identifier texture, Block block, Identifier spec, Identifier normal) {
	}

	/**
	 * Uploaded meshes by their position array, then by their bone indices: the same positions can go up skinned (a
	 * viewmodel) and rigid (the gun's third-person / dropped copy, no joints), and those are different vertex buffers.
	 */
	private static final Map<float[], Map<Object, Mesh>> MESHES = new IdentityHashMap<>();
	private static final Object RIGID = new Object();
	private static final Map<int[], Indices> INDICES = new IdentityHashMap<>();
	private static final List<Draw> QUEUE = new ArrayList<>();
	private static final List<Block> BLOCKS = new ArrayList<>();
	private static boolean prepared;
	private static GpuBufferSlice transforms;
	/** This frame's textures, loaded before the pass opens (loading one inside a render pass isn't allowed). */
	private static final Map<Identifier, AbstractTexture> TEXTURES = new java.util.HashMap<>();

	private GpuAgents() {
	}

	/**
	 * In GameRenderer.renderItemInHand (the first-person viewmodel's own pass, after the level: vanilla clears
	 * isRenderingLevel before it, but the pass prepares and runs its features the same way).
	 */
	public static boolean inHand;

	/** In a menu's agent preview (AgentPip): a picture-in-picture pass, prepared and run like the level's. */
	public static boolean inPip;

	private static boolean inPass() {
		return RenderSystem.isRenderingLevel || inHand || inPip;
	}

	/** Whether this submit should go through the GPU (else the caller draws on the CPU as before). */
	public static boolean active() {
		return dev.countercraft.client.options.CcOptions.gpuAgents() && ready(0);
	}

	/** As {@link #active}, for a mob (GPU Environment Rendering, its own option): room is kept for the agents. */
	public static boolean activeForMobs() {
		return ready(AGENT_RESERVE);
	}

	/** Whether GPU drawing broke for good this session (a shader pack, a shader that didn't compile, a failed draw). */
	public static boolean failed() {
		return failed;
	}

	private static boolean ready(int reserve) {
		if (failed || !inPass() || BLOCKS.size() >= MAX_BLOCKS - reserve) {
			return false;
		}
		if (compiled == null) {
			if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) {
				fail("a shader pack mod (Iris) is installed", null);
				return false;
			}
			try {
				compiled = RenderSystem.getCompiledPipelineNullable(pipeline(0));
				legsCompiled = RenderSystem.getCompiledPipelineNullable(pipeline(1));
				lensCompiled = RenderSystem.getCompiledPipelineNullable(pipeline(2));
				litCompiled = RenderSystem.getCompiledPipelineNullable(pipeline(3));
				litLensCompiled = RenderSystem.getCompiledPipelineNullable(pipeline(4));
				litLegsCompiled = RenderSystem.getCompiledPipelineNullable(pipeline(5));
			} catch (Throwable t) {
				fail("the agent shader didn't compile", t);
				return false;
			}
			if (compiled == null || legsCompiled == null || lensCompiled == null || litCompiled == null || litLensCompiled == null
					|| litLegsCompiled == null) {
				compiled = null;
				fail("the agent shader didn't compile", null);
				return false;
			}
		}
		return true;
	}

	private static RenderPipeline pipeline(int kind) {
		if (kind >= 3) {
			return litPipeline(kind);
		}
		boolean legs = kind == 1;
		boolean lens = kind == 2;
		if (kind == 0 ? pipeline == null : legs ? legsPipeline == null : lensPipeline == null) {
			// vanilla's ENTITY_CUTOUT (entity snippet, PER_FACE_LIGHTING, no culling, alpha cutout 0.1) with our vertex shader
			var b = RenderPipeline.builder()
					.withBindGroupLayout(BindGroupLayouts.GLOBALS)
					.withBindGroupLayout(BindGroupLayouts.PROJECTION)
					.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
					.withBindGroupLayout(BindGroupLayouts.FOG)
					.withBindGroupLayout(BindGroupLayouts.LIGHTING)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
					.withBindGroupLayout(BindGroupLayout.builder().withUniform("AgentSkin", UniformType.UNIFORM_BUFFER).build())
					.withLocation(CounterCraft.id(legs ? "pipeline/agent_legs" : lens ? "pipeline/agent_lens" : "pipeline/agent"))
					.withVertexShader(CounterCraft.id("core/agent"))
					.withFragmentShader(legs || lens ? CounterCraft.id("core/agent_legs") : Identifier.withDefaultNamespace("core/entity"))
					.withVertexBinding(0, DefaultVertexFormat.ENTITY)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withDepthStencilState(DepthStencilState.DEFAULT)
					.withShaderDefine("ALPHA_CUTOUT", 0.1f)
					.withShaderDefine("PER_FACE_LIGHTING")
					.withCull(false)
					.withColorTargetState(ColorTargetState.DEFAULT);
			if (legs) {
				legsPipeline = b.withShaderDefine("NEAR_CLIP", LEGS_NEAR_CLIP).build();
			} else if (lens) {
				lensPipeline = b.withShaderDefine("LENS").build();
			} else {
				pipeline = b.build();
			}
		}
		return kind == 0 ? pipeline : legs ? legsPipeline : lensPipeline;
	}

	private static RenderPipeline litPipeline(int kind) {
		boolean lens = kind == 4;
		boolean legs = kind == 5;
		if (lens ? litLensPipeline == null : legs ? litLegsPipeline == null : litPipeline == null) {
			var b = RenderPipeline.builder()
					.withBindGroupLayout(BindGroupLayouts.GLOBALS)
					.withBindGroupLayout(BindGroupLayouts.PROJECTION)
					.withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
					.withBindGroupLayout(BindGroupLayouts.FOG)
					.withBindGroupLayout(BindGroupLayouts.LIGHTING)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
					.withBindGroupLayout(BindGroupLayouts.SAMPLER1)
					.withBindGroupLayout(BindGroupLayout.builder().withUniform("AgentSkin", UniformType.UNIFORM_BUFFER).build())
					.withBindGroupLayout(BindGroupLayout.builder().withUniform("SpecSampler", UniformType.COMBINED_IMAGE_SAMPLER)
							.withUniform("NormalSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
					.withLocation(CounterCraft.id(lens ? "pipeline/agent_vm_lens" : legs ? "pipeline/agent_vm_legs" : "pipeline/agent_vm"))
					.withVertexShader(CounterCraft.id("core/agent"))
					.withFragmentShader(CounterCraft.id("core/agent_vm"))
					.withVertexBinding(0, DefaultVertexFormat.ENTITY)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withDepthStencilState(DepthStencilState.DEFAULT)
					.withCull(false)
					.withColorTargetState(ColorTargetState.DEFAULT);
			if (lens) {
				litLensPipeline = b.withShaderDefine("LENS").build();
			} else if (legs) {
				litLegsPipeline = b.withShaderDefine("NEAR_CLIP", LEGS_NEAR_CLIP).build();
			} else {
				litPipeline = b.build();
			}
		}
		return lens ? litLensPipeline : legs ? litLegsPipeline : litPipeline;
	}

	private static void fail(String why, Throwable t) {
		if (!failed) {
			failed = true;
			CounterCraft.LOG.warn("[countercraft] GPU agent rendering off ({}): players draw on the CPU", why, t);
			// tell the player (the Video tab's warning promises it); a toast, next frame (this may be mid render pass)
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> net.minecraft.client.gui.components.toasts.SystemToast.add(mc.gui.toastManager(), GPU_TOAST,
					net.minecraft.network.chat.Component.literal("GPU rendering unavailable"),
					net.minecraft.network.chat.Component.literal("Switched to CPU rendering: " + why)));
		}
		QUEUE.clear();
		BLOCKS.clear();
	}

	/** A pose for this frame: {@code pose} (mesh space to camera-relative world), {@code bones} 3x4 row-major each. */
	public static Block block(Matrix4f pose, float[] bones, int boneCount, int light, int overlay) {
		return block(pose, bones, boneCount, light, overlay, false);
	}

	/** As above; {@code nearClip}: your own first-person legs (see {@link #LEGS_NEAR_CLIP}). */
	public static Block block(Matrix4f pose, float[] bones, int boneCount, int light, int overlay, boolean nearClip) {
		return block(pose, bones, boneCount, light, overlay, nearClip, null);
	}

	/** As above, with a scope's lens window (see {@link Block#lens}). */
	public static Block block(Matrix4f pose, float[] bones, int boneCount, int light, int overlay, boolean nearClip, float[] lens) {
		return block(pose, bones, boneCount, light, overlay, nearClip, lens, null);
	}

	/** As above, lit the CS:GO way ({@link Block#envUp}: the world's up in the pose's output space; null: vanilla shading). */
	public static Block block(Matrix4f pose, float[] bones, int boneCount, int light, int overlay, boolean nearClip, float[] lens, float[] envUp) {
		if (boneCount > MAX_BONES) {
			return null;
		}
		Block b = new Block(pose, bones.clone(), boneCount, light, overlay, nearClip, lens == null ? null : lens.clone(), envUp);
		BLOCKS.add(b);
		return b;
	}

	/** Queue one mesh part: its vertices (by their position array), its triangles, its texture, with a pose. */
	public static void draw(float[] pos, float[] nrm, float[] uv, byte[] joints, byte[] weights, int vertexCount, int[] idx, Identifier texture,
			Block block) {
		draw(pos, nrm, uv, joints, weights, vertexCount, idx, texture, block, null, null);
	}

	/** As above, with the lit shader's roughness / metal / AO and normal maps (null: the defaults). */
	public static void draw(float[] pos, float[] nrm, float[] uv, byte[] joints, byte[] weights, int vertexCount, int[] idx, Identifier texture,
			Block block, Identifier spec, Identifier normal) {
		if (block == null || idx.length == 0 || failed) {
			return;
		}
		try {
			Mesh mesh = MESHES.computeIfAbsent(pos, k -> new IdentityHashMap<>())
					.computeIfAbsent(joints == null || weights == null ? RIGID : joints, k -> upload(pos, nrm, uv, joints, weights, vertexCount));
			Indices ind = INDICES.computeIfAbsent(idx, GpuAgents::uploadIndices);
			QUEUE.add(new Draw(mesh, ind, texture, block, spec != null ? spec : DEFAULT_SPEC, normal != null ? normal : FLAT_NORMAL));
		} catch (Throwable t) {
			fail("a mesh couldn't be uploaded", t);
		}
	}

	private static Mesh upload(float[] pos, float[] nrm, float[] uv, byte[] joints, byte[] weights, int n) {
		// a GC-managed direct buffer: the backend may copy it after this returns
		ByteBuffer b = ByteBuffer.allocateDirect(n * VERTEX_BYTES).order(ByteOrder.nativeOrder());
		{
			for (int v = 0; v < n; v++) {
				b.putFloat(pos[v * 3]).putFloat(pos[v * 3 + 1]).putFloat(pos[v * 3 + 2]);
				if (weights != null) {
					b.put(weights, v * 4, 4);
				} else {
					b.put((byte) 255).put((byte) 0).put((byte) 0).put((byte) 0); // rigid: all on bone 0
				}
				b.putFloat(uv[v * 2]).putFloat(uv[v * 2 + 1]);
				if (joints != null) {
					b.putShort((short) (Byte.toUnsignedInt(joints[v * 4]) | Byte.toUnsignedInt(joints[v * 4 + 1]) << 8));
					b.putShort((short) (Byte.toUnsignedInt(joints[v * 4 + 2]) | Byte.toUnsignedInt(joints[v * 4 + 3]) << 8));
				} else {
					b.putShort((short) 0).putShort((short) 0);
				}
				b.putShort((short) 0).putShort((short) 0);
				b.put(snorm(nrm[v * 3])).put(snorm(nrm[v * 3 + 1])).put(snorm(nrm[v * 3 + 2])).put((byte) 0);
			}
			b.flip();
			return new Mesh(RenderSystem.getDevice().createBuffer(() -> "countercraft agent mesh", GpuBuffer.USAGE_VERTEX, b));
		}
	}

	private static Indices uploadIndices(int[] idx) {
		ByteBuffer b = ByteBuffer.allocateDirect(idx.length * 4).order(ByteOrder.nativeOrder());
		b.asIntBuffer().put(idx);
		return new Indices(RenderSystem.getDevice().createBuffer(() -> "countercraft agent indices", GpuBuffer.USAGE_INDEX, b), idx.length);
	}

	private static byte snorm(float f) {
		return (byte) Math.round(Math.max(-1f, Math.min(1f, f)) * 127f);
	}

	/** Vanilla prepared its feature frame (every submit is in): write this frame's poses, before any pass opens. */
	public static void prepare() {
		prepared = false;
		if (QUEUE.isEmpty() || failed || !inPass()) {
			QUEUE.clear();
			BLOCKS.clear();
			return;
		}
		try {
			int slot = nextRing;
			nextRing = (nextRing + 1) % RINGS.length;
			if (RINGS[slot] == null) {
				int align = Math.max(16, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment());
				stride = (BLOCK_BYTES + align - 1) / align * align;
				RINGS[slot] = new MappableRingBuffer(() -> "countercraft agent poses", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, stride * MAX_BLOCKS);
			}
			ring = RINGS[slot];
			ring.rotate();
			try (GpuBufferSlice.MappedView view = ring.currentBuffer().map(false, true)) {
				ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
				for (int i = 0; i < BLOCKS.size(); i++) {
					Block b = BLOCKS.get(i);
					b.offset = i * stride;
					data.position(b.offset);
					for (float f : b.pose) {
						data.putFloat(f);
					}
					data.putInt(b.light & 0xFFFF).putInt(b.light >>> 16 & 0xFFFF).putInt(b.overlay & 0xFFFF).putInt(b.overlay >>> 16 & 0xFFFF);
					float[] l = b.lens;
					if (l != null) {
						data.putFloat(l[0]).putFloat(l[1]).putFloat(l[2]).putFloat(0f);
						data.putFloat(l[3]).putFloat(l[4]).putFloat(l[5]).putFloat(l[6]);
						data.putFloat(l[7]).putFloat(l[8]).putFloat(l[9]).putFloat(1f);
					} else {
						for (int k = 0; k < 12; k++) {
							data.putFloat(0f);
						}
					}
					float[] up = b.envUp;
					// w: the Pearlescence slider (agent_vm.fsh: Printstream's sheen, where a finish's spec map has it)
					data.putFloat(up != null ? up[0] : 0f).putFloat(up != null ? up[1] : 1f).putFloat(up != null ? up[2] : 0f)
							.putFloat(dev.countercraft.client.options.CcOptions.pearlescence());
					for (int k = 0; k < b.boneCount * 12; k++) {
						data.putFloat(b.bones[k]);
					}
				}
			}
			// textures stream in off the render thread (TextureStream): a part whose texture isn't up yet waits a few
			// frames; a missing roughness / normal map draws with the defaults meanwhile
			TEXTURES.clear();
			var tm = Minecraft.getInstance().getTextureManager();
			TEXTURES.put(DEFAULT_SPEC, tm.getTexture(DEFAULT_SPEC));
			TEXTURES.put(FLAT_NORMAL, tm.getTexture(FLAT_NORMAL));
			for (int i = 0; i < QUEUE.size(); i++) {
				Draw d = QUEUE.get(i);
				if (!TEXTURES.containsKey(d.texture())) {
					AbstractTexture t = TextureStream.get(d.texture());
					if (t != null) {
						TEXTURES.put(d.texture(), t);
					}
				}
				if (d.block().envUp != null) {
					Identifier sp = ready(d.spec()) ? d.spec() : DEFAULT_SPEC;
					Identifier nm = ready(d.normal()) ? d.normal() : FLAT_NORMAL;
					if (sp != d.spec() || nm != d.normal()) {
						QUEUE.set(i, new Draw(d.mesh(), d.indices(), d.texture(), d.block(), sp, nm));
					}
				}
			}
			QUEUE.removeIf(d -> !TEXTURES.containsKey(d.texture()));
			transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy());
			prepared = true;
		} catch (Throwable t) {
			fail("the poses couldn't be written", t);
		}
	}

	private static boolean ready(Identifier id) {
		if (TEXTURES.containsKey(id)) {
			return true;
		}
		AbstractTexture t = TextureStream.get(id);
		if (t != null) {
			TEXTURES.put(id, t);
		}
		return t != null;
	}

	/** Vanilla's solid feature pass, after its own draws: draw the queue in it. */
	public static void execute(RenderPass pass) {
		if (!prepared || QUEUE.isEmpty()) {
			return;
		}
		prepared = false;
		boolean group = false;
		try {
			Minecraft mc = Minecraft.getInstance();
			pass.pushDebugGroup(() -> "countercraft agents");
			group = true;
			GpuBuffer poses = ring.currentBuffer();
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			// everything else first, then your own legs with the near clip, then an aimed scope with its lens window, then
			// the lit ones (with and without a lens window, your own lit legs)
			var repeat = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
			for (int kind = 0; kind < 6; kind++) {
				final int k = kind;
				if (QUEUE.stream().noneMatch(d -> d.block().kind() == k)) {
					continue;
				}
				// looked up each frame (a cache hit): a resource reload compiles the shaders again and frees the old
				// pipelines, and drawing with a freed one crashed the graphics driver
				var cp = RenderSystem.getCompiledPipelineNullable(pipeline(kind));
				if (cp == null) {
					continue;
				}
				pass.setPipeline(cp);
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("DynamicTransforms", transforms);
				pass.setUniform("Sampler1", mc.gameRenderer.overlayTexture().getTextureView(), linear);
				pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), linear);
				Identifier boundTexture = null;
				Identifier boundSpec = null;
				Identifier boundNormal = null;
				Block boundBlock = null;
				for (Draw d : QUEUE) {
					if (d.block().kind() != kind) {
						continue;
					}
					if (d.texture() != boundTexture) {
						AbstractTexture tex = TEXTURES.get(d.texture());
						// lit, the albedo is sampled smoothly too (the viewmodel close up showed texels as blocks)
						pass.setUniform("Sampler0", tex.getTextureView(), kind >= 3 ? repeat : tex.getSampler());
						boundTexture = d.texture();
					}
					if (kind >= 3 && d.spec() != boundSpec) {
						pass.setUniform("SpecSampler", TEXTURES.get(d.spec()).getTextureView(), repeat);
						boundSpec = d.spec();
					}
					if (kind >= 3 && d.normal() != boundNormal) {
						pass.setUniform("NormalSampler", TEXTURES.get(d.normal()).getTextureView(), repeat);
						boundNormal = d.normal();
					}
					if (d.block() != boundBlock) {
						pass.setUniform("AgentSkin", poses.slice(d.block().offset, BLOCK_BYTES));
						boundBlock = d.block();
					}
					pass.setVertexBuffer(0, d.mesh().buffer().slice());
					pass.setIndexBuffer(d.indices().buffer(), IndexType.INT);
					pass.drawIndexed(d.indices().count(), 1, 0, 0, 0);
				}
			}
		} catch (Throwable t) {
			fail("drawing failed", t);
		} finally {
			if (group) {
				pass.popDebugGroup(); // even after a failure: the pass must close clean
			}
			QUEUE.clear();
			BLOCKS.clear();
			TEXTURES.clear();
		}
	}

	/** Resources reloaded (new meshes) or the game is closing: free the GPU copies. */
	public static void clear() {
		for (Map<Object, Mesh> byJoints : MESHES.values()) {
			for (Mesh m : byJoints.values()) {
				m.buffer().close();
			}
		}
		for (Indices i : INDICES.values()) {
			i.buffer().close();
		}
		MESHES.clear();
		INDICES.clear();
		QUEUE.clear();
		BLOCKS.clear();
	}
}
