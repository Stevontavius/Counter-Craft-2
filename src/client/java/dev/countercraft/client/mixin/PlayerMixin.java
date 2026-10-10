package dev.countercraft.client.mixin;

import dev.countercraft.client.movement.CsPlayerMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS2 lets you crouch-walk off ledges: no Minecraft sneak edge guard under CS movement. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
	private void countercraft$noEdgeGuard(final Vec3 movement, final MoverType type, final CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this instanceof LocalPlayer p && CsPlayerMovement.handles(p)) {
			cir.setReturnValue(movement);
		}
	}
}
