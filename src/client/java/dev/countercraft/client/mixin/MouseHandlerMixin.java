package dev.countercraft.client.mixin;

import dev.countercraft.client.GunClient;
import dev.countercraft.client.radio.RadioWheel;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scoped mouse look is scaled by the zoom (CS2 zoom_sensitivity_ratio 1). */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void countercraft$scopedTurn(final LocalPlayer player, final double yaw, final double pitch) {
		if (RadioWheel.isOpen()) {
			RadioWheel.mouse(yaw, pitch); // the chat wheel takes the mouse while it's open
			return;
		}
		double k = GunClient.zoomSensitivity();
		player.turn(yaw * k, pitch * k);
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void countercraft$wheelTabs(final long window, final double dx, final double dy, final CallbackInfo ci) {
		if (RadioWheel.isOpen() && dy != 0) {
			RadioWheel.scroll(dy);
			ci.cancel();
			return;
		}
		if (dev.countercraft.client.loadout.CsHotbar.scroll(dy)) {
			ci.cancel(); // CS mode: the wheel cycles the CS slots
		}
	}
}
