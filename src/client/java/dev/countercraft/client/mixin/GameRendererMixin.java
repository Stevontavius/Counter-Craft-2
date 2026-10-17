package dev.countercraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.countercraft.client.GunClient;
import dev.countercraft.client.movement.CsPlayerMovement;
import dev.countercraft.client.movement.MovementSettings;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Camera effects, where vanilla applies its hurt shake: recoil view punch and CS2's landing roll. CS2's camera
 * doesn't bob when you move, so vanilla view bobbing (world and hand) is off under CS movement.
 *
 * <p>Vanilla calls bobHurt for the world and again for the hand. Rotating the hand like the world kept the gun still
 * in the world while the view kicked up, so it sank on screen. In the hand pass the gun instead kicks up and along
 * with the punch (CS2's viewmodel recoil; the share, 1 x the camera punch, is an approximation).</p>
 *
 * <p>Also Video > Anti-Aliasing ({@link dev.countercraft.client.gpu.AntiAliasingPass}): TAA jitters the world's
 * projection and resolves before the viewmodel draws; FXAA runs after vanilla's post effects, before the HUD.</p>
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Unique
	private static boolean countercraft$hand;

	/** Watching someone in first person (Another Chance) shows what they hold: vanilla draws no hands in spectator mode. */
	@Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
	private net.minecraft.world.level.GameType countercraft$spectatedHands(final net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode) {
		return dev.countercraft.client.death.SpectateViewmodel.target() != null ? net.minecraft.world.level.GameType.SURVIVAL : gameMode.getPlayerMode();
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void countercraft$handStart(final CallbackInfo ci) {
		countercraft$hand = true;
		dev.countercraft.client.gpu.GpuAgents.inHand = true; // the viewmodel draws on the GPU in the hand pass too
	}

	@Inject(method = "renderItemInHand", at = @At("RETURN"))
	private void countercraft$handEnd(final CallbackInfo ci) {
		countercraft$hand = false;
		dev.countercraft.client.gpu.GpuAgents.inHand = false;
	}

	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void countercraft$viewPunch(final CameraRenderState camera, final PoseStack poseStack, final CallbackInfo ci) {
		float[] punch = GunClient.viewPunch(camera.cameraEntityPartialTicks);
		if (punch != null) {
			float sign = countercraft$hand ? -1f : 1f;
			poseStack.rotateDegrees(Axis.XP, -punch[0] * sign);
			poseStack.rotateDegrees(Axis.YP, punch[1] * sign);
		}
		float roll = CsPlayerMovement.landPunch(CsPlayerMovement.now());
		if (roll != 0f) {
			poseStack.rotateDegrees(Axis.ZP, roll);
		}
	}

	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void countercraft$noViewBob(final CameraRenderState camera, final PoseStack poseStack, final CallbackInfo ci) {
		if (MovementSettings.enabled) {
			ci.cancel();
		}
	}

	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f countercraft$taaJitter(final Matrix4f projection) {
		dev.countercraft.client.gpu.ShockwavePass.projection(projection);
		return dev.countercraft.client.gpu.AntiAliasingPass.jitter(projection);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud("
			+ "Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
			+ "Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void countercraft$taaResolve(final CallbackInfo ci) {
		dev.countercraft.client.gpu.AntiAliasingPass.afterLevel(gameRenderState.levelRenderState.cameraRenderState);
		dev.countercraft.client.gpu.ShockwavePass.afterLevel(gameRenderState.levelRenderState.cameraRenderState);
	}

	/** CC2's Aspect Ratio (Video): the hand and viewmodel stretched or squeezed with the world (CameraMixin). */
	@ModifyArg(method = "render3dHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"), index = 3)
	private float countercraft$aspectHand(final float width) {
		return width * dev.countercraft.client.options.CcOptions.aspectFactor();
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void countercraft$fxaa(final CallbackInfo ci) {
		dev.countercraft.client.gpu.AntiAliasingPass.afterPost();
		dev.countercraft.client.gpu.ShockwavePass.afterPost();
	}
}
