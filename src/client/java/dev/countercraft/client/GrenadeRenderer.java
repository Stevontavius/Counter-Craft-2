package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.client.viewmodel.ViewmodelRenderer;
import dev.countercraft.gun.GrenadeEntity;
import java.util.Optional;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Thrown grenades as their CS2 models (converted as viewmodels/&lt;id&gt;_thrown.cvm, pin and spoon removed): tumbling
 * end over end in flight, lying on their side once they stop. Falls back to the flat item sprite without the pack.
 */
public final class GrenadeRenderer extends ThrownItemRenderer<GrenadeEntity> {
	private static final ViewmodelRenderer RENDERER = new ViewmodelRenderer();

	static final class State extends ThrownItemRenderState {
		String kind = "";
		int effect;
		float spin;
		float yaw;
		boolean resting;
	}

	public GrenadeRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public ThrownItemRenderState createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(GrenadeEntity entity, ThrownItemRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		State s = (State) state;
		s.kind = entity.kind();
		s.effect = entity.state();
		s.spin = entity.spin(partialTick);
		s.yaw = entity.travelYaw();
		s.resting = entity.resting();
	}

	@Override
	public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		State s = (State) state;
		Optional<ViewmodelAsset> asset = GunClient.thrownAssetIfReady(s.kind);
		if (asset == null) {
			return; // loading in the background: it appears in a moment instead of stalling this frame
		}
		ViewmodelAsset.ClipData pose = asset.map(a -> a.clips.get("idle")).orElse(null);
		if (pose == null) {
			super.submit(state, poseStack, collector, camera);
			return;
		}
		if (s.effect == GrenadeEntity.BURNING) {
			return; // the bottle broke on impact
		}
		poseStack.pushPose();
		// the entity stands on the bottom of its box; the model is centred: lift it to sit on the ground when lying down
		poseStack.translate(0f, s.resting ? 0.045f : 0.125f, 0f);
		poseStack.rotate(Axis.YP.rotationDegrees(s.yaw));
		poseStack.rotate(Axis.XP.rotationDegrees(s.spin));
		RENDERER.submitWorld(asset.get(), pose.mats(), poseStack, collector, state.lightCoords);
		poseStack.popPose();
	}
}
