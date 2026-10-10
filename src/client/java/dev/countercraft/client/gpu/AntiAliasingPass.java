package dev.countercraft.client.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.options.CcOptions;
import dev.countercraft.client.options.CcOptions.AntiAliasing;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Video > Anti-Aliasing, as full-screen passes on Minecraft's own render API (running on Vulkan, which they require).
 * FXAA smooths the finished 3D frame (world and viewmodel) before the HUD. TAA works on the world before the viewmodel
 * draws: the world's projection is jittered by a sub-pixel Halton offset each frame, and each frame is blended into a
 * history reprojected through the depth buffer (camera motion; things that move on their own rely on the
 * neighbourhood clamp in cc_taa.fsh). Available only with GPU Player and GPU Environment Rendering on (as the game
 * started) and the Vulkan graphics API selected in Minecraft's Video Settings; anything else, or a failure, means off.
 */
public final class AntiAliasingPass {
	/** This frame's share in the TAA blend. */
	private static final float TAA_WEIGHT = 0.1f;
	private static final int UBO_BYTES = 64 + 64 + 16 + 16;

	/** The pipelines (their compiled form is looked up per use: a resource reload frees the old ones). */
	private static RenderPipeline fxaa;
	private static RenderPipeline taa;
	private static RenderPipeline blit;
	private static GpuBuffer taaInfo;
	private static final ByteBuffer UBO = ByteBuffer.allocateDirect(UBO_BYTES).order(ByteOrder.nativeOrder());
	/** temp: FXAA's output; history[0 / 1]: TAA's, read one frame and written the next. */
	private static GpuTexture temp;
	private static GpuTextureView tempView;
	private static final GpuTexture[] HISTORY = new GpuTexture[2];
	private static final GpuTextureView[] HISTORY_VIEW = new GpuTextureView[2];
	private static int width;
	private static int height;
	private static int latest;
	private static boolean historyValid;
	private static boolean failed;

	/** TAA: this frame's jitter (pixels), its unjittered projection, last frame's view-projection and camera. */
	private static boolean jittered;
	private static float jx;
	private static float jy;
	private static int frame;
	private static final Matrix4f PROJECTION = new Matrix4f();
	private static final Matrix4f PREV_VIEW_PROJ = new Matrix4f();
	private static Vec3 prevCamera;

	private AntiAliasingPass() {
	}

	/** Why anti-aliasing can't run, or null when it can. */
	public static String unavailableReason() {
		if (!CcOptions.gpuAgents() || !CcOptions.gpuEnvironmentActive()) {
			return "Needs GPU Player Rendering and GPU Environment Rendering on (GPU Environment Rendering applies after a restart)";
		}
		if (GpuAgents.failed() || failed) {
			return "GPU rendering failed this session (see the log)";
		}
		if (!vulkan()) {
			return "Needs the Vulkan graphics API: Minecraft's Options > Video Settings > Graphics API, then restart";
		}
		return null;
	}

	public static boolean available() {
		return unavailableReason() == null;
	}

	/** The anti-aliasing running now: the option where it's available, else off. */
	public static AntiAliasing mode() {
		return available() ? CcOptions.antiAliasing() : AntiAliasing.OFF;
	}

	private static boolean vulkan() {
		try {
			return RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan");
		} catch (RuntimeException e) {
			return false; // no device yet (early startup)
		}
	}

	// ---------------------------------------------------------------- TAA

	/** renderLevel: the world's projection as it goes up: jittered for TAA (the unjittered one kept for next frame). */
	public static Matrix4f jitter(Matrix4f projection) {
		jittered = false;
		if (mode() != AntiAliasing.TAA || Minecraft.getInstance().level == null) {
			historyValid = false;
			return projection;
		}
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		frame = (frame + 1) % 8;
		jx = halton(frame + 1, 2) - 0.5f;
		jy = halton(frame + 1, 3) - 0.5f;
		PROJECTION.set(projection);
		jittered = true;
		return new Matrix4f().translation(2f * jx / main.width, 2f * jy / main.height, 0f).mul(projection);
	}

	/** renderLevel, the world drawn and the viewmodel not yet: blend it with the history. */
	public static void afterLevel(CameraRenderState camera) {
		if (!jittered) {
			return;
		}
		jittered = false;
		try {
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			if (!ensure(main.width, main.height)) {
				return;
			}
			Matrix4f view = camera.viewRotationMatrix;
			Matrix4f invCurrent = new Matrix4f().translation(2f * jx / width, 2f * jy / height, 0f).mul(PROJECTION).mul(view).invert();
			Vec3 pos = camera.pos;
			boolean reset = !historyValid || prevCamera == null || pos.distanceToSqr(prevCamera) > 16 * 16;
			Vec3 delta = reset ? Vec3.ZERO : pos.subtract(prevCamera);
			UBO.clear();
			invCurrent.get(0, UBO);
			PREV_VIEW_PROJ.get(64, UBO);
			UBO.putFloat(128, (float) delta.x).putFloat(132, (float) delta.y).putFloat(136, (float) delta.z).putFloat(140, reset ? 1f : 0f);
			boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
			UBO.putFloat(144, zeroToOne ? 1f : 0f).putFloat(148, 0f).putFloat(152, 0f).putFloat(156, TAA_WEIGHT);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToBuffer(taaInfo.slice(), UBO.rewind());
			int out = 1 - latest;
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (RenderPass pass = encoder.createRenderPass(() -> "countercraft taa", HISTORY_VIEW[out], Optional.empty())) {
				pass.setPipeline(compiled(taa));
				pass.setUniform("CurrentSampler", main.getColorTextureView(), nearest);
				pass.setUniform("HistorySampler", HISTORY_VIEW[latest], linear);
				pass.setUniform("DepthSampler", main.getDepthTextureView(), nearest);
				pass.setUniform("TaaInfo", taaInfo);
				pass.draw(3, 1, 0, 0);
			}
			copy(encoder, HISTORY_VIEW[out], main.getColorTextureView());
			latest = out;
			historyValid = true;
			PREV_VIEW_PROJ.set(PROJECTION).mul(view);
			prevCamera = pos;
		} catch (Throwable t) {
			fail("TAA", t);
		}
	}

	// ---------------------------------------------------------------- FXAA

	/** After vanilla's post effects, before the HUD: FXAA over the 3D frame. */
	public static void afterPost() {
		if (mode() != AntiAliasing.FXAA || Minecraft.getInstance().level == null) {
			return;
		}
		try {
			RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
			if (!ensure(main.width, main.height)) {
				return;
			}
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			try (RenderPass pass = encoder.createRenderPass(() -> "countercraft fxaa", tempView, Optional.empty())) {
				pass.setPipeline(compiled(fxaa));
				pass.setUniform("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
			copy(encoder, tempView, main.getColorTextureView());
		} catch (Throwable t) {
			fail("FXAA", t);
		}
	}

	// ---------------------------------------------------------------- plumbing

	/** A full-screen draw of {@code from} into {@code to}. */
	private static void copy(CommandEncoder encoder, GpuTextureView from, GpuTextureView to) {
		try (RenderPass pass = encoder.createRenderPass(() -> "countercraft aa copy", to, Optional.empty())) {
			pass.setPipeline(compiled(blit));
			pass.setUniform("InSampler", from, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	/** Pipelines (once) and targets the size of the frame (again on a resize, which also drops the TAA history). */
	private static boolean ensure(int w, int h) {
		if (failed) {
			return false;
		}
		if (blit == null) {
			fxaa = compile("aa_fxaa", CounterCraft.id("core/cc_fxaa"), false, "InSampler");
			taa = compile("aa_taa", CounterCraft.id("core/cc_taa"), true, "CurrentSampler", "HistorySampler", "DepthSampler");
			blit = compile("aa_copy", Identifier.withDefaultNamespace("core/blit_screen"), false, "InSampler");
			if (fxaa == null || taa == null || blit == null) {
				blit = null;
				fail("the shaders didn't compile", null);
				return false;
			}
			if (taaInfo == null) {
				taaInfo = RenderSystem.getDevice().createBuffer(() -> "countercraft taa info", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UBO_BYTES);
			}
		}
		if (compiled(fxaa) == null || compiled(taa) == null || compiled(blit) == null) {
			return false; // mid resource reload: no anti-aliasing this frame
		}
		if (w != width || h != height || temp == null) {
			release();
			width = w;
			height = h;
			temp = target("aa temp");
			tempView = RenderSystem.getDevice().createTextureView(temp);
			for (int i = 0; i < 2; i++) {
				HISTORY[i] = target("taa history " + i);
				HISTORY_VIEW[i] = RenderSystem.getDevice().createTextureView(HISTORY[i]);
			}
			historyValid = false;
		}
		return true;
	}

	private static GpuTexture target(String name) {
		return RenderSystem.getDevice().createTexture(() -> "countercraft " + name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
				GpuFormat.RGBA8_UNORM, width, height, 1, 1);
	}

	private static void release() {
		if (tempView != null) {
			tempView.close();
			temp.close();
			tempView = null;
			temp = null;
		}
		for (int i = 0; i < 2; i++) {
			if (HISTORY_VIEW[i] != null) {
				HISTORY_VIEW[i].close();
				HISTORY[i].close();
				HISTORY_VIEW[i] = null;
				HISTORY[i] = null;
			}
		}
	}

	/** A compiled pipeline, looked up at each use (a cache hit; null if it didn't compile). */
	private static CompiledRenderPipeline compiled(RenderPipeline p) {
		return RenderSystem.getCompiledPipelineNullable(p);
	}

	private static RenderPipeline compile(String name, Identifier fragment, boolean ubo, String... samplers) {
		BindGroupLayout.Builder layout = BindGroupLayout.builder();
		for (String s : samplers) {
			layout.withUniform(s, UniformType.COMBINED_IMAGE_SAMPLER);
		}
		RenderPipeline.Builder b = RenderPipeline.builder()
				.withLocation(CounterCraft.id("pipeline/" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(fragment)
				.withBindGroupLayout(layout.build())
				.withColorTargetState(ColorTargetState.DEFAULT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
		if (ubo) {
			b.withBindGroupLayout(BindGroupLayout.builder().withUniform("TaaInfo", UniformType.UNIFORM_BUFFER).build());
		}
		RenderPipeline p = b.build();
		return compiled(p) != null ? p : null;
	}

	private static void fail(String what, Throwable t) {
		if (!failed) {
			failed = true;
			CounterCraft.LOG.warn("[countercraft] anti-aliasing off ({} failed)", what, t);
		}
	}

	/** Radical inverse of i in base b: the Halton sequence, in (0, 1). */
	private static float halton(int i, int b) {
		float f = 1f;
		float r = 0f;
		while (i > 0) {
			f /= b;
			r += f * (i % b);
			i /= b;
		}
		return r;
	}
}
