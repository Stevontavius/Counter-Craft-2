package dev.countercraft.mixin;

import dev.countercraft.movement.CsMove;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Players on CS movement make CS2's footsteps (client/movement/Footsteps), so Minecraft's own are muted for them. */
@Mixin(Entity.class)
abstract class EntityStepMixin {
	@Inject(method = "walkingStepSound", at = @At("HEAD"), cancellable = true)
	private void countercraft$csFootsteps(final BlockPos pos, final BlockState state, final CallbackInfo ci) {
		if ((Object) this instanceof Player p && CsMove.enabled(p)) {
			ci.cancel();
		}
	}
}
