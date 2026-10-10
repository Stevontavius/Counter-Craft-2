package dev.countercraft.mixin;

import dev.countercraft.movement.CsMove;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** CS2's crouched eye height (46 units, 1.17 blocks) for players on CS movement; the hitbox stays Minecraft's. */
@Mixin(Avatar.class)
abstract class AvatarMixin {
	@Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
	private void countercraft$csCrouchEye(final Pose pose, final CallbackInfoReturnable<EntityDimensions> cir) {
		if (pose == Pose.CROUCHING && (Object) this instanceof Player player && CsMove.enabled(player)) {
			cir.setReturnValue(cir.getReturnValue().withEyeHeight(CsMove.CROUCH_EYE));
		}
	}
}
