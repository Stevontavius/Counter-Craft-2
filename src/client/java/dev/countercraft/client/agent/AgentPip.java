package dev.countercraft.client.agent;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.countercraft.client.mixin.GuiGraphicsAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/**
 * A CS2 agent posed in a menu, seen through one of CS2's own UI cameras (maps/ui/buy_menu.vpk entities: the buy menu
 * and loadout cameras and the vanity_character spot), drawn with a real perspective projection at the camera's vertical
 * FOV into a picture-in-picture texture covering the panel. The pose is one of CS2's buy menu clips
 * (animation/anims/ui_anims/buy_menu/&lt;team&gt;/&lt;team&gt;_buymenu_&lt;weapon&gt;), holding that weapon.
 */
public final class AgentPip {
	/** A UI camera / character placement from the buy_menu map (Source units and yaw degrees). */
	public record Scene(float camX, float camY, float camZ, float camYaw, float vfov, float charX, float charY, float charZ, float charYaw) {
	}

	/** cam_buymenu and the vanity character. */
	public static final Scene BUY_MENU = new Scene(-60f, -136f, 28f, 90f, 30f, -27.141846f, 16.045654f, -22.297701f, 255.000046f);
	/** cam_loadoutmenu (T side). */
	public static final Scene LOADOUT_T = new Scene(-20f, -158f, 18f, 90f, 30f, -27.141846f, 16.045654f, -22.297701f, 255.000046f);
	/** cam_loadoutmenu_ct. */
	public static final Scene LOADOUT_CT = new Scene(-64f, -158f, 18f, 78.083054f, 30f, -27.141846f, 16.045654f, -22.297701f, 255.000046f);

	private static final float UNIT = 0.0254f;

	/**
	 * One agent preview to draw; {@code item}: a Minecraft item in the hand instead of a weapon, held like the default
	 * knife {@code itemKnife} (null: a block, upright), as agents hold them in the world.
	 */
	public record State(String agent, String gun, dev.countercraft.client.skins.Skins.Skin finish, String clip, float clipTime, Scene scene, int x0, int y0,
			int x1, int y1, ScreenRectangle scissorArea, ScreenRectangle bounds, net.minecraft.client.renderer.item.ItemStackRenderState item,
			String itemKnife, dev.countercraft.client.skins.Skins.Glove glove) implements PictureInPictureRenderState {
		@Override
		public float scale() {
			return 1f;
		}
	}

	private AgentPip() {
	}

	/**
	 * Draw an agent ({@code agent} model name) holding {@code gun} in the pose of {@code clip} (from {@link #clipFor}) inside
	 * the rectangle, through {@code scene}'s camera.
	 */
	public static void draw(GuiGraphicsExtractor g, String agent, String gun, String clip, float clipTime, Scene scene, int x0, int y0, int x1, int y1) {
		draw(g, agent, gun, null, clip, clipTime, scene, x0, y0, x1, y1);
	}

	/** As above, the weapon wearing {@code finish} (a paint kit of {@code gun}, or null for the plain weapon). */
	public static void draw(GuiGraphicsExtractor g, String agent, String gun, dev.countercraft.client.skins.Skins.Skin finish, String clip, float clipTime,
			Scene scene, int x0, int y0, int x1, int y1) {
		draw(g, agent, gun, finish, clip, clipTime, scene, x0, y0, x1, y1, null, null, null);
	}

	/** As above, holding a Minecraft item (see {@link State#item}) and wearing {@code glove} (null: the agent's own). */
	public static void draw(GuiGraphicsExtractor g, String agent, String gun, dev.countercraft.client.skins.Skins.Skin finish, String clip, float clipTime,
			Scene scene, int x0, int y0, int x1, int y1, net.minecraft.client.renderer.item.ItemStackRenderState item, String itemKnife,
			dev.countercraft.client.skins.Skins.Glove glove) {
		if (AgentClient.data() == null) {
			return;
		}
		ScreenRectangle r = new ScreenRectangle(x0, y0, x1 - x0, y1 - y0);
		((GuiGraphicsAccessor) g).countercraft$guiRenderState().addPicturesInPictureState(
				new State(agent, gun, finish, clip, clipTime, scene, x0, y0, x1, y1, null, r, item, itemKnife, glove));
	}

	/** The CS2 buy menu clip for a weapon (mod id) and team, or null: t_buymenu_ak_03, ct_buymenu_m4a1 (= M4A1-S)... */
	public static String clipFor(String team, String gun) {
		AgentData data = AgentClient.data();
		if (data == null || gun == null) {
			return null;
		}
		// asked several times a frame (buy menu, paperdoll): the scan over every clip name ran each time
		if (data != clipsFor) {
			CLIPS.clear();
			clipsFor = data;
		}
		return CLIPS.computeIfAbsent(team + "|" + gun, k -> Optional.ofNullable(findClip(data, team, gun))).orElse(null);
	}

	private static final Map<String, Optional<String>> CLIPS = new HashMap<>();
	private static AgentData clipsFor;

	private static String findClip(AgentData data, String team, String gun) {
		String key = switch (gun) {
			case "ak47" -> "ak";
			case "cz75a" -> "cz75";
			case "glock" -> "glock18";
			case "m4a1" -> "m4a4";
			case "m4a1_silencer" -> "m4a1";
			default -> gun;
		};
		String best = latest(data, "buymenu/" + team + "/" + team + "_buymenu_" + key);
		if (best == null) {
			// knives and grenades: CS2's one pose for both teams (buy_menu/shared)
			String shared = dev.countercraft.CounterCraft.KNIVES.containsKey(gun) ? "knife" : switch (gun) {
				case "flashbang" -> "flash";
				case "smokegrenade" -> "smoke";
				case "incgrenade" -> "incendiary";
				default -> gun;
			};
			best = latest(data, "buymenu/shared/sh_buymenu_" + shared);
		}
		return best;
	}

	/** The clip named {@code prefix}, or its latest numbered version ({@code prefix_01}, {@code _02}...). */
	private static String latest(AgentData data, String prefix) {
		String best = null;
		for (String name : data.clipNames()) {
			if ((name.equals(prefix) || name.startsWith(prefix + "_0")) && (best == null || name.compareTo(best) > 0)) {
				best = name;
			}
		}
		return best;
	}

	/** Renders {@link State}s: registered with Fabric's picture-in-picture registry. */
	public static final class Renderer extends PictureInPictureRenderer<State> {
		private final Projection projection = new Projection();
		private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer("countercraft agent preview");
		private final AgentRenderer agentRenderer = new AgentRenderer();
		private final Map<String, AgentAnimator> animators = new HashMap<>();

		@Override
		public Class<State> getRenderStateClass() {
			return State.class;
		}

		/** The preview's agent and weapon draw on the GPU too (uploaded once), not rebuilt on the CPU every frame. */
		@Override
		public void prepare(State s, net.minecraft.client.renderer.state.gui.GuiRenderState gui,
				net.minecraft.client.renderer.feature.FeatureRenderDispatcher features, int i) {
			dev.countercraft.client.gpu.GpuAgents.inPip = true;
			try {
				super.prepare(s, gui, features, i);
			} finally {
				dev.countercraft.client.gpu.GpuAgents.inPip = false;
			}
		}

		@Override
		protected String getTextureLabel() {
			return "countercraft agent preview";
		}

		@Override
		protected void renderToTexture(State s, PoseStack ignored, SubmitNodeCollector collector) {
			AgentData data = AgentClient.data();
			AgentModel model = data == null ? null : data.model(s.agent());
			if (model == null) {
				return;
			}
			AgentAnimator anim = animators.computeIfAbsent(s.agent(), k -> new AgentAnimator(data));
			if (s.clip() == null || !anim.showClip(s.clip(), s.clipTime())) {
				return;
			}
			// the panel's own perspective: CS2's vertical FOV over the panel height
			double gs = Minecraft.getInstance().getWindow().getGuiScale();
			float w = (float) ((s.x1() - s.x0()) * gs);
			float h = (float) ((s.y1() - s.y0()) * gs);
			projection.setupPerspective(0.05f, 100f, s.scene().vfov(), w, h);
			RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE);
			Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
			// character relative to the camera, in view space (x right, y up, -z forward), metres
			Scene sc = s.scene();
			double cy = Math.toRadians(sc.camYaw());
			double dx = sc.charX() - sc.camX(), dy = sc.charY() - sc.camY(), dz = sc.charZ() - sc.camZ();
			double fwd = dx * Math.cos(cy) + dy * Math.sin(cy);
			double right = dx * Math.sin(cy) - dy * Math.cos(cy);
			PoseStack ps = new PoseStack();
			ps.translate((float) (right * UNIT), (float) (dz * UNIT), (float) (-fwd * UNIT));
			// the agent faces model +Z; CS2's character yaw relative to the camera, turned to view space
			ps.rotate(Axis.YP.rotationDegrees(sc.charYaw() - sc.camYaw() + 180f));
			agentRenderer.finish = s.finish();
			agentRenderer.glove = s.glove();
		agentRenderer.submit(data, model, anim, s.gun(), ps, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, false);
			if (s.item() != null) {
				agentRenderer.submitItem(data, anim, s.item(), ps, collector, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0, s.itemKnife());
			}
		}

		@Override
		public void close() {
			super.close();
			projectionBuffer.close();
		}
	}
}
