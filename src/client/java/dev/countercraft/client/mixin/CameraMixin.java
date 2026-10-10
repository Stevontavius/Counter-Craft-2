package dev.countercraft.client.mixin;

import dev.countercraft.client.GunClient;
import dev.countercraft.client.movement.CsPlayerMovement;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scope zoom on the world FOV (26.3 computes the FOV in Camera), and CS2's crouch eye height and timing. */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow
	private Entity entity;
	@Shadow
	private float eyeHeight;
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(net.minecraft.world.phys.Vec3 pos);

	/** CS2's death cam takes the camera while it plays. */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void countercraft$deathCam(final float partialTick, final CallbackInfo ci) {
		if (dev.countercraft.client.death.DeathCam.active()) {
			var pose = dev.countercraft.client.death.DeathCam.pose(partialTick);
			setPosition(pose.pos());
			setRotation(pose.yaw(), pose.pitch());
			this.detached = true;
		} else if (dev.countercraft.client.death.Spectate.orbiting()) {
			// Another Chance's third person spectating: around the watched player, turned with the mouse
			var pose = dev.countercraft.client.death.Spectate.orbit(partialTick);
			setPosition(pose.pos());
			setRotation(pose.yaw(), pose.pitch());
			this.detached = true;
		}
	}

	/** No vanilla death zoom while the death cam plays (it has its own). */
	@Inject(method = "modifyFovBasedOnDeathOrFluid", at = @At("HEAD"), cancellable = true)
	private void countercraft$noDeathZoom(final float partialTick, final float fov, final CallbackInfoReturnable<Float> cir) {
		if (dev.countercraft.client.death.DeathCam.active()) {
			cir.setReturnValue(fov);
		}
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void countercraft$scopeZoom(final float partialTick, final CallbackInfoReturnable<Float> cir) {
		float fov = cir.getReturnValueF();
		if (dev.countercraft.client.death.DeathCam.active()) {
			cir.setReturnValue(fov * dev.countercraft.client.death.DeathCam.pose(partialTick).fovScale());
			return;
		}
		float zoomed = GunClient.zoomFov(fov);
		if (zoomed != fov) {
			cir.setReturnValue(zoomed);
		}
	}

	/** Eye height follows CS2's duck amount (64 -> 46 units in 0.21 s) instead of Minecraft's pose easing. */
	@Inject(method = "tick", at = @At("TAIL"))
	private void countercraft$csEyeHeight(final CallbackInfo ci) {
		if (this.entity instanceof LocalPlayer p && CsPlayerMovement.active() && CsPlayerMovement.handles(p)) {
			this.eyeHeight = CsPlayerMovement.eyeHeight();
		}
	}
}
