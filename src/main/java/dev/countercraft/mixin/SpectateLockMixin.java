package dev.countercraft.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player waiting for Another Chance stays locked on the players alive ({@link dev.countercraft.team.AnotherChance#allowCamera}). */
@Mixin(ServerPlayer.class)
abstract class SpectateLockMixin {
	@Inject(method = "setCamera", at = @At("HEAD"), cancellable = true)
	private void countercraft$lock(final Entity entity, final CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (!dev.countercraft.team.AnotherChance.allowCamera(self, entity == null ? self : entity)) {
			ci.cancel();
		}
	}
}
