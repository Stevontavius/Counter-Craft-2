package dev.countercraft.client.mixin;

import dev.countercraft.client.movement.CsPlayerMovement;
import dev.countercraft.client.movement.MovementKeys;
import dev.countercraft.client.movement.MovementSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CS2 keys: crouch comes from Counter-Craft's Crouch key (Ctrl) instead of vanilla Sneak, and there's no sprint on
 * land. (Walk is read directly by the movement.) Swimming and flying keep vanilla sprint.
 */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void countercraft$csKeys(final CallbackInfo ci) {
		if (!MovementSettings.enabled) {
			return;
		}
		ClientInput self = (ClientInput) (Object) this;
		Input k = self.keyPresses;
		var player = Minecraft.getInstance().player;
		boolean land = player != null && CsPlayerMovement.handles(player);
		self.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), MovementKeys.CROUCH.isDown(), !land && k.sprint());
	}
}
