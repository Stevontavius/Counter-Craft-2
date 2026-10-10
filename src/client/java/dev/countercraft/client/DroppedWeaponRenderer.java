package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.countercraft.client.viewmodel.FastMesh;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.client.viewmodel.ViewmodelRenderer;
import dev.countercraft.gun.DroppedWeaponEntity;
import dev.countercraft.gun.GrenadeItem;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Vector3f;

/**
 * Dropped CS2 weapons as their CS2 models: the gun's mesh from its converted viewmodel (the arms left out), rigid,
 * tumbling while it flies and lying on its side once it rests (thinnest axis up, barrel level); grenades as their
 * thrown models. Falls back to the item sprite without the CS2 pack.
 */
public final class DroppedWeaponRenderer extends ThrownItemRenderer<DroppedWeaponEntity> {
	private static final ViewmodelRenderer RENDERER = new ViewmodelRenderer();
	/** Per gun: the model-space placement that lays it on its side, centred, its lowest point at y = 0. */
	private static final Map<ViewmodelAsset, float[]> LAY = new HashMap<>();
	private static final Vector3f TUMBLE_AXIS = new Vector3f(1, 1, 1).normalize();
	/** {@link #LAY}'s 3x4 placement alone, per gun (no copy per dropped gun per frame). */
	private static final Map<ViewmodelAsset, float[]> PLACE = new HashMap<>();

	static final class State extends ThrownItemRenderState {
		String weapon = "";
		dev.countercraft.client.skins.Skins.Skin skin;
		boolean grenade;
		boolean resting;
		float spin;
		float yaw;
	}

	public DroppedWeaponRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public ThrownItemRenderState createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(DroppedWeaponEntity entity, ThrownItemRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		State s = (State) state;
		s.weapon = entity.weaponId();
		s.skin = dev.countercraft.client.skins.Skins.of(entity.getItem());
		s.grenade = entity.getItem().getItem() instanceof GrenadeItem;
		s.resting = entity.resting();
		s.spin = entity.spin(partialTick);
		s.yaw = entity.restYaw();
	}

	@Override
	public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		State s = (State) state;
		if (s.grenade) {
			Optional<ViewmodelAsset> asset = GunClient.thrownAssetIfReady(s.weapon);
			if (asset == null) {
				return; // loading in the background: it appears in a moment instead of stalling this frame
			}
			ViewmodelAsset.ClipData pose = asset.map(a -> a.clips.get("idle")).orElse(null);
			if (pose == null) {
				super.submit(state, poseStack, collector, camera);
				return;
			}
			poseStack.pushPose();
			poseStack.translate(0f, s.resting ? 0.045f : 0.125f, 0f);
			poseStack.rotate(Axis.YP.rotationDegrees(s.yaw));
			poseStack.rotate(Axis.XP.rotationDegrees(s.resting ? 90f : s.spin));
			RENDERER.submitWorld(asset.get(), pose.mats(), poseStack, collector, state.lightCoords);
			poseStack.popPose();
			return;
		}
		Optional<ViewmodelAsset> va = GunClient.viewmodelIfReady(s.weapon);
		if (va == null) {
			return; // loading in the background: it appears in a moment instead of stalling this frame
		}
		if (va.isEmpty()) {
			super.submit(state, poseStack, collector, camera);
			return;
		}
		ViewmodelAsset asset = va.get();
		float[] lay = LAY.computeIfAbsent(asset, DroppedWeaponRenderer::lay);
		poseStack.pushPose();
		poseStack.rotate(Axis.YP.rotationDegrees(s.yaw));
		if (!s.resting) {
			// tumbling (Source's drop impulse spins it about all three axes) around its middle, half a gun height up
			poseStack.translate(0f, lay[12], 0f);
			poseStack.rotate(new org.joml.Quaternionf().fromAxisAngleDeg(TUMBLE_AXIS, s.spin));
			poseStack.translate(0f, -lay[12], 0f);
		}
		float[] place = PLACE.computeIfAbsent(asset, a -> java.util.Arrays.copyOf(lay, 12));
		int light = state.lightCoords;
		// on the GPU when it can (the mesh is uploaded once; only its placement goes up each frame)
		dev.countercraft.client.gpu.GpuAgents.Block block = dev.countercraft.client.gpu.GpuAgents.active()
				? dev.countercraft.client.gpu.GpuAgents.block(poseStack.last().pose(), place, 1, light, OverlayTexture.NO_OVERLAY) : null;
		for (ViewmodelAsset.Part part : dev.countercraft.client.skins.Skins.gunParts(asset, s.weapon, s.skin)) {
			if (block != null) {
				dev.countercraft.client.gpu.GpuAgents.draw(part.pos(), part.nrm(), part.uv(), null, null, part.vertexCount(), part.indices(), part.texture(),
						block);
				continue;
			}
			if (part.vertexCount() == 0) {
				continue;
			}
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(part.texture()),
					(p, vc) -> FastMesh.emit(part.indices(), part.uv(), part.pos(), part.nrm(), part.vertexCount(), place, p, vc, light,
							OverlayTexture.NO_OVERLAY, false));
		}
		poseStack.popPose();
	}

	/**
	 * Rigid placement (3x4, row-major) of the gun's rest mesh lying on its side: its longest extent (the barrel) along x,
	 * its thinnest (the width) up, centred on the entity with its lowest point on the ground. [12] = half its lying height.
	 */
	private static float[] lay(ViewmodelAsset asset) {
		float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
		float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (ViewmodelAsset.Part part : asset.parts) {
			if (ViewmodelAsset.isArms(part)) {
				continue;
			}
			float[] pos = part.pos();
			for (int v = 0; v < part.vertexCount(); v++) {
				for (int a = 0; a < 3; a++) {
					min[a] = Math.min(min[a], pos[v * 3 + a]);
					max[a] = Math.max(max[a], pos[v * 3 + a]);
				}
			}
		}
		if (min[0] > max[0]) {
			return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0};
		}
		Integer[] axes = {0, 1, 2};
		java.util.Arrays.sort(axes, (a, b) -> Float.compare(max[b] - min[b], max[a] - min[a])); // longest first
		int along = axes[0];
		int side = axes[1];
		int up = axes[2];
		float[] r = new float[9];
		r[along] = 1f;        // world x <- the barrel
		r[3 + up] = 1f;       // world y <- the width
		r[6 + side] = 1f;     // world z <- the height
		// keep it a rotation (no mirroring): det must be +1
		float det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6]);
		if (det < 0) {
			r[6 + side] = -1f;
		}
		float[] c = {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
		float halfUp = (max[up] - min[up]) / 2;
		float[] out = new float[13];
		for (int row = 0; row < 3; row++) {
			out[row * 4] = r[row * 3];
			out[row * 4 + 1] = r[row * 3 + 1];
			out[row * 4 + 2] = r[row * 3 + 2];
			out[row * 4 + 3] = -(r[row * 3] * c[0] + r[row * 3 + 1] * c[1] + r[row * 3 + 2] * c[2]) + (row == 1 ? halfUp : 0f);
		}
		out[12] = halfUp;
		return out;
	}
}
