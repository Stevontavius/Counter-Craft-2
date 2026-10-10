package dev.countercraft.client.mixin;

import dev.countercraft.client.fx.MuzzleLight;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block meshes get their light through these lookups (vanilla's model lighter and Fabric's Indigo renderer alike): add
 * the muzzle flash's light.
 */
@Mixin(LightCoordsUtil.class)
abstract class BlockLightMixin {
	@Inject(method = "getLightCoords(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"),
			cancellable = true)
	private static void countercraft$muzzleLight(final BlockAndLightGetter level, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
		int lit = MuzzleLight.apply(cir.getReturnValueI(), pos);
		if (lit != cir.getReturnValueI()) {
			cir.setReturnValue(lit);
		}
	}

	@Inject(method = "getLightCoords(Lnet/minecraft/util/LightCoordsUtil$BrightnessGetter;Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
			at = @At("RETURN"), cancellable = true)
	private static void countercraft$muzzleLightState(final LightCoordsUtil.BrightnessGetter getter, final BlockAndLightGetter level,
			final BlockState state, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
		int lit = MuzzleLight.apply(cir.getReturnValueI(), pos);
		if (lit != cir.getReturnValueI()) {
			cir.setReturnValue(lit);
		}
	}
}
