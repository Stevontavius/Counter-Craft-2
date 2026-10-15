package dev.countercraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.countercraft.client.viewmodel.ViewmodelAsset;
import dev.countercraft.client.viewmodel.ViewmodelRenderer;
import dev.countercraft.gun.PlantedC4;
import java.util.Optional;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * A planted C4: CS2's C4 model (converted as viewmodels/c4_thrown.cvm, no arms) on the ground, turned the way its planter
 * faced. Without the asset pack, the C4 item lying flat.
 */
public final class PlantedC4Renderer extends ThrownItemRenderer<PlantedC4> {
	private static final ViewmodelRenderer RENDERER = new ViewmodelRenderer();

	static final class State extends ThrownItemRenderState {
		float yaw;
	}

	public PlantedC4Renderer(EntityRendererProvider.Context context) {
		super(context, 1f, false);
	}

	@Override
	public ThrownItemRenderState createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(PlantedC4 entity, ThrownItemRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		((State) state).yaw = entity.getYRot();
	}

	@Override
	public void submit(ThrownItemRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		float yaw = ((State) state).yaw;
		Optional<ViewmodelAsset> asset = GunClient.thrownAssetIfReady("c4");
		if (asset == null) {
			return; // loading in the background
		}
		ViewmodelAsset.ClipData pose = asset.map(a -> a.clips.get("idle")).orElse(null);
		poseStack.pushPose();
		poseStack.rotate(Axis.YP.rotationDegrees(180f - yaw));
		if (pose != null) {
			poseStack.translate(0f, C4_LIFT, 0f); // the model is centred on its box: half its height up
			poseStack.rotate(Axis.XP.rotationDegrees(C4_TILT));
			RENDERER.submitWorld(asset.get(), pose.mats(), poseStack, collector, state.lightCoords);
		} else {
			poseStack.translate(0f, 0.03f, 0f);
			poseStack.rotate(Axis.XP.rotationDegrees(90f));
			poseStack.scale(1.4f, 1.4f, 1.4f);
			state.item.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		}
		poseStack.popPose();
	}

	/** Placement of the converted model: lifted by half its smallest extent, tilted to lie keypad up (checked in game). */
	private static final float C4_LIFT = 0.042f;
	private static final float C4_TILT = 0f;
}
