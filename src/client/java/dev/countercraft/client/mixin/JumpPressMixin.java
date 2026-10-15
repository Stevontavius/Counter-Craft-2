package dev.countercraft.client.mixin;

import dev.countercraft.client.movement.CsPlayerMovement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** When the jump key goes down, to the nanosecond: CS2 times a jump inside its tick (sub-tick), Minecraft only per tick. */
@Mixin(KeyMapping.class)
abstract class JumpPressMixin {
	@Inject(method = "setDown", at = @At("HEAD"))
	private void countercraft$jumpPressTime(boolean down, CallbackInfo ci) {
		KeyMapping self = (KeyMapping) (Object) this;
		var options = Minecraft.getInstance().options;
		if (down && !self.isDown() && options != null && self == options.keyJump) {
			CsPlayerMovement.jumpKeyDown(System.nanoTime());
		}
	}
}
