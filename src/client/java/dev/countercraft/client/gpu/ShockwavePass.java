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
import dev.countercraft.client.fx.ShockwaveFx;
import dev.countercraft.gun.PlantedC4;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * A C4's shockwave on screen, two full-screen passes on Minecraft's own render API (like the anti-aliasing):
 * <ul>
 * <li>the wave in the world (shaders/core/cc_shockwave_world.fsh), over the world before the viewmodel, while a wave
 * is spreading and its dust settling ({@link ShockwaveFx#waves}): it needs the world's depth, from before the viewmodel
 * draws;</li>
 * <li>being struck (shaders/core/cc_shockwave_hit.fsh), the screen's shake and blur, over the finished 3D frame
 * before the HUD.</li>
 * </ul>
 * Each draws the frame into a texture through its shader and copies it back. Any failure turns them off for the session.
 */
public final class ShockwavePass {
	private static final int WAVE_BYTES = 64 + 64;
	private static final int HIT_BYTES = 16;
	/** The wave's band where it meets the world (blocks). */
	private static final float BAND = 1.6f;
	/**
	 * The grey dust it leaves where it went (per block of the line of sight through it): about 40 % over 30 blocks, 80 %
	 * over 100. It clears as the black smoke does ({@link dev.countercraft.client.fx.C4Explosion#smokeLeft}).
	 */
	private static final float DUST = 0.018f;
	private static RenderPipeline world;
	private static RenderPipeline hit;
	private static RenderPipeline blit;
	private static GpuBuffer waveInfo;
	private static GpuBuffer hitInfo;
	private static final ByteBuffer WAVE = ByteBuffer.allocateDirect(WAVE_BYTES).order(ByteOrder.nativeOrder());
	private static final ByteBuffer HIT = ByteBuffer.allocateDirect(HIT_BYTES).order(ByteOrder.nativeOrder());
	private static GpuTexture temp;
	private static GpuTextureView tempView;
	private static int width;
	private static int height;
	private static boolean failed;
	/** This frame's world projection (as it went up, before any anti-aliasing jitter). */
	private static final Matrix4f PROJECTION = new Matrix4f();

	private ShockwavePass() {
	}

	/** It turned itself off this session (a failure, logged). */
	public static boolean failed() {
		return failed;
	}

	/** renderLevel: the world's projection, each frame. */
	public static void projection(Matrix4f projection) {
		PROJECTION.set(projection);
	}

	/** renderLevel, the world drawn and the viewmodel not yet: the wave spreading through it. */
	public static void afterLevel(CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		if (failed || mc.level == null || ShockwaveFx.waves().isEmpty()) {
			return;
		}
		try {
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			if (!ensure(main.width, main.height)) {
				return;
			}
			// the nearest wave (two at once is rare)
			Vec3 cam = camera.pos;
			ShockwaveFx.Wave w = null;
			for (ShockwaveFx.Wave x : ShockwaveFx.waves()) {
				w = w == null || x.centre().distanceToSqr(cam) < w.centre().distanceToSqr(cam) ? x : w;
			}
			double front = w.front();
			float strength = (float) Math.pow(Math.max(0, 1 - front / PlantedC4.WAVE_HEARD), 0.5); // (clearly on past the blast)
			Vec3 c = w.centre().subtract(cam);
			Matrix4f viewProj = new Matrix4f(PROJECTION).mul(camera.viewRotationMatrix);
			Matrix4f inv = new Matrix4f(viewProj).invert();
			// the bomb on screen (the middle when it's behind)
			Vector4f s = viewProj.transform(new Vector4f((float) c.x, (float) c.y, (float) c.z, 1f));
			float sx = 0.5f;
			float sy = 0.5f;
			if (s.w > 0.01f) {
				sx = (s.x / s.w) * 0.5f + 0.5f;
				sy = (s.y / s.w) * 0.5f + 0.5f;
			}
			boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
			WAVE.clear();
			inv.get(0, WAVE);
			WAVE.putFloat(64, (float) c.x).putFloat(68, (float) c.y).putFloat(72, (float) c.z).putFloat(76, (float) front);
			WAVE.putFloat(80, strength).putFloat(84, zeroToOne ? 1f : 0f).putFloat(88, (float) ((System.nanoTime() - w.start()) / 1e9)).putFloat(92, BAND);
			WAVE.putFloat(96, sx).putFloat(100, sy).putFloat(104, (float) main.width / main.height).putFloat(108, 0f);
			// the dust: in the sphere the front has swept, as bright as the light where you are (grey by day, dim at night)
			double left = dev.countercraft.client.fx.C4Explosion.smokeLeft(w.age());
			net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(cam);
			float day = 1f - mc.level.getSkyDarken() / 11f;
			float sky = mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at) / 15f;
			float lamp = mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at) / 15f;
			float light = 0.12f + 0.88f * Math.max(sky * day, lamp * 0.7f);
			WAVE.putFloat(112, (float) (DUST * left)).putFloat(116, (float) Math.min(front, PlantedC4.WAVE_HEARD)).putFloat(120, light)
					.putFloat(124, 0f);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToBuffer(waveInfo.slice(), WAVE.rewind());
			var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			try (RenderPass pass = encoder.createRenderPass(() -> "countercraft shockwave", tempView, Optional.empty())) {
				pass.setPipeline(compiled(world));
				pass.setUniform("InSampler", main.getColorTextureView(), linear);
				pass.setUniform("DepthSampler", main.getDepthTextureView(), nearest);
				pass.setUniform("WaveInfo", waveInfo);
				pass.draw(3, 1, 0, 0);
			}
			copyBack(encoder, main);
		} catch (Throwable e) {
			fail(e);
		}
	}

	/** After vanilla's post effects (and the anti-aliasing), before the HUD: struck, the shake and its blur. */
	public static void afterPost() {
		double t = ShockwaveFx.hitTime();
		Minecraft mc = Minecraft.getInstance();
		if (t < 0 || failed || mc.level == null) {
			return;
		}
		try {
			RenderTarget main = mc.gameRenderer.mainRenderTarget();
			if (!ensure(main.width, main.height)) {
				return;
			}
			HIT.clear();
			HIT.putFloat(0, (float) t).putFloat(4, ShockwaveFx.strength()).putFloat(8, ShockwaveFx.seed()).putFloat(12, (float) main.width / main.height);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.writeToBuffer(hitInfo.slice(), HIT.rewind());
			try (RenderPass pass = encoder.createRenderPass(() -> "countercraft shockwave hit", tempView, Optional.empty())) {
				pass.setPipeline(compiled(hit));
				pass.setUniform("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("HitInfo", hitInfo);
				pass.draw(3, 1, 0, 0);
			}
			copyBack(encoder, main);
		} catch (Throwable e) {
			fail(e);
		}
	}

	private static void copyBack(CommandEncoder encoder, RenderTarget main) {
		try (RenderPass pass = encoder.createRenderPass(() -> "countercraft shockwave copy", main.getColorTextureView(), Optional.empty())) {
			pass.setPipeline(compiled(blit));
			pass.setUniform("InSampler", tempView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void fail(Throwable e) {
		failed = true;
		CounterCraft.LOG.warn("[countercraft] shockwave shaders off (they failed)", e);
	}

	private static boolean ensure(int w, int h) {
		if (blit == null) {
			world = compile("shockwave_world", CounterCraft.id("core/cc_shockwave_world"), "WaveInfo", "InSampler", "DepthSampler");
			hit = compile("shockwave_hit", CounterCraft.id("core/cc_shockwave_hit"), "HitInfo", "InSampler");
			blit = compile("shockwave_copy", Identifier.withDefaultNamespace("core/blit_screen"), null, "InSampler");
			if (world == null || hit == null || blit == null) {
				failed = true;
				blit = null;
				CounterCraft.LOG.warn("[countercraft] shockwave shaders off (they didn't compile)");
				return false;
			}
			waveInfo = RenderSystem.getDevice().createBuffer(() -> "countercraft shockwave wave", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, WAVE_BYTES);
			hitInfo = RenderSystem.getDevice().createBuffer(() -> "countercraft shockwave hit", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, HIT_BYTES);
		}
		if (compiled(world) == null || compiled(hit) == null || compiled(blit) == null) {
			return false; // mid resource reload
		}
		if (w != width || h != height || temp == null) {
			if (tempView != null) {
				tempView.close();
				temp.close();
			}
			width = w;
			height = h;
			temp = RenderSystem.getDevice().createTexture(() -> "countercraft shockwave", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
					GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			tempView = RenderSystem.getDevice().createTextureView(temp);
		}
		return true;
	}

	private static CompiledRenderPipeline compiled(RenderPipeline p) {
		return RenderSystem.getCompiledPipelineNullable(p);
	}

	private static RenderPipeline compile(String name, Identifier fragment, String ubo, String... samplers) {
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
		if (ubo != null) {
			b.withBindGroupLayout(BindGroupLayout.builder().withUniform(ubo, UniformType.UNIFORM_BUFFER).build());
		}
		RenderPipeline p = b.build();
		return compiled(p) != null ? p : null;
	}
}
