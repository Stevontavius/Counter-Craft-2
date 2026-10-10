package dev.countercraft.client.steam;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.countercraft.CounterCraft;
import dev.countercraft.client.mixin.GuiGraphicsAccessor;
import dev.countercraft.client.mixin.PipTextureAccessor;
import dev.countercraft.net.DeathInfoPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The deep dive's picture of what killed you: the killer, front-facing, holding what it killed you with. It's rendered
 * once, the moment the death is known (an invisible picture-in-picture whose texture is read back instead of drawn), and
 * saved next to the run history (config/countercraft_runs/&lt;run&gt;.png), so it outlives the world. Players aren't
 * pictured (they wear CS2 agents, drawn their own way).
 */
public final class KillerPip {
	/** A picture to render; {@code saveTo} non-null: read it back into that file instead of drawing it. */
	public record State(EntityRenderState renderState, Vector3f translation, Quaternionf rotation, int x0, int y0, int x1, int y1, float scale, Path saveTo,
			ScreenRectangle scissorArea, ScreenRectangle bounds) implements PictureInPictureRenderState {
	}

	/** The picture's size in GUI pixels (times the GUI scale in the file). */
	private static final int W = 72;
	private static final int H = 96;

	private static Entity pending;
	private static Path pendingPath;
	private static int pendingFrames;

	private KillerPip() {
	}

	public static Path dir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("countercraft_runs");
	}

	public static Path file(RunHistory.Run run) {
		return dir().resolve(run.started + ".png");
	}

	/** The death is known: picture the killer (the one in the world, or a stand-in of its type holding the weapon). */
	public static void request(RunHistory.Run run, DeathInfoPayload info) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || info.killerPlayer() || info.killerType().isEmpty()) {
			return;
		}
		Entity killer = info.killerId() >= 0 ? mc.level.getEntity(info.killerId()) : null;
		if (!(killer instanceof LivingEntity) || killer instanceof Player) {
			killer = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(info.killerType()))
					.map(t -> (Entity) t.create(mc.level, EntitySpawnReason.LOAD)).orElse(null);
			if (killer == null || killer instanceof Player) {
				return;
			}
			killer.setId(Integer.MIN_VALUE + 11); // never in the world, but rendering needs an id
			if (killer instanceof LivingEntity le && !info.weapon().isEmpty()) {
				le.setItemSlot(EquipmentSlot.MAINHAND, info.weapon().copy());
			}
		}
		pending = killer;
		pendingPath = file(run);
		pendingFrames = 0;
	}

	/** HUD layer: while a picture is pending, add it (invisibly) to this frame. */
	public static void hud(GuiGraphicsExtractor g) {
		if (pending == null) {
			return;
		}
		if (++pendingFrames > 10) {
			pending = null; // give up rather than retry forever
			return;
		}
		EntityRenderState state = renderState(pending);
		if (state == null) {
			pending = null;
			return;
		}
		submit(g, state, 0, 0, W, H, pendingPath);
	}

	/** A front-facing, fully lit render state of the entity (or null if it can't be pictured). */
	static EntityRenderState renderState(Entity e) {
		try {
			var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
			EntityRenderState s = renderer.createRenderState(e, 1f);
			s.shadowPieces.clear();
			s.outlineColor = 0;
			s.lightCoords = LightCoordsUtil.FULL_BRIGHT;
			s.nameTag = null;
			if (s instanceof LivingEntityRenderState l) {
				l.bodyRot = 180f;
				l.yRot = 0f;
				l.xRot = 0f;
				l.hasRedOverlay = false;
				l.deathTime = 0f;
				l.boundingBoxWidth /= l.scale;
				l.boundingBoxHeight /= l.scale;
				l.scale = 1f;
			}
			return s;
		} catch (RuntimeException ex) {
			CounterCraft.LOG.debug("[countercraft] killer picture skipped: {}", ex.toString());
			return null;
		}
	}

	/** Add the picture of {@code state} fitted into the rectangle (drawn, or with {@code saveTo}, saved). */
	static void submit(GuiGraphicsExtractor g, EntityRenderState state, int x0, int y0, int x1, int y1, Path saveTo) {
		float bw = Math.max(0.3f, state.boundingBoxWidth);
		float bh = Math.max(0.3f, state.boundingBoxHeight);
		float scale = Math.min((y1 - y0) * 0.86f / bh, (x1 - x0) * 0.86f / bw);
		Vector3f translation = new Vector3f(0f, bh / 2f, 0f);
		Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).mul(new Quaternionf().rotateX((float) Math.toRadians(-8)));
		ScreenRectangle r = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
		((GuiGraphicsAccessor) g).countercraft$guiRenderState().addPicturesInPictureState(
				new State(state, translation, rotation, x0, y0, x1, y1, scale, saveTo, null, r));
	}

	/** Renders {@link State}s (registered with Fabric's picture-in-picture registry). */
	public static final class Renderer extends PictureInPictureRenderer<State> {
		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		@Override
		protected String getTextureLabel() {
			return "countercraft killer picture";
		}

		@Override
		protected float getTranslateY(int height, int guiScale) {
			return height / 2f;
		}

		@Override
		protected void renderToTexture(State s, PoseStack pose, SubmitNodeCollector collector) {
			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			pose.translate(s.translation().x(), s.translation().y(), s.translation().z());
			pose.rotate(s.rotation());
			Minecraft.getInstance().getEntityRenderDispatcher().submit(s.renderState(), new CameraRenderState(), 0, 0, 0, pose, collector);
		}

		@Override
		protected void blitTexture(State s, GuiRenderState gui) {
			if (s.saveTo() == null) {
				super.blitTexture(s, gui);
				return;
			}
			if (pendingPath == null || !pendingPath.equals(s.saveTo())) {
				return;
			}
			pending = null;
			pendingPath = null;
			save(((PipTextureAccessor) (Object) this).countercraft$texture(), s.saveTo());
		}

		/** Copy the texture back and write it as a PNG (Minecraft's own screenshot code, keeping transparency). */
		private static void save(GpuTexture tex, Path out) {
			if (tex == null) {
				return;
			}
			int w = tex.getWidth(0);
			int h = tex.getHeight(0);
			int block = tex.getFormat().blockSize();
			var device = RenderSystem.getDevice();
			GpuBuffer buffer = device.createBuffer(() -> "countercraft killer picture", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) w * h * block);
			device.createCommandEncoder().copyTextureToBuffer(tex, buffer, 0L, () -> {
				try (var view = buffer.map(true, false); NativeImage img = new NativeImage(w, h, false)) {
					for (int y = 0; y < h; y++) {
						for (int x = 0; x < w; x++) {
							img.setPixelABGR(x, h - y - 1, view.data().getInt((x + y * w) * block));
						}
					}
					Files.createDirectories(out.getParent());
					img.writeToFile(out);
					RunHistory.pictureSaved(out);
				} catch (Exception e) {
					CounterCraft.LOG.warn("[countercraft] killer picture not saved: {}", e.toString());
				} finally {
					buffer.close();
				}
			}, 0);
		}
	}
}
