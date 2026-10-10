package dev.countercraft.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A challenge advancement completed: the server-wide MVP ({@link dev.countercraft.Mvp}). */
@Mixin(PlayerAdvancements.class)
abstract class PlayerAdvancementsMixin {
	@Shadow
	private ServerPlayer player;

	@Unique
	private boolean countercraft$wasDone;

	@Shadow
	public abstract net.minecraft.advancements.AdvancementProgress getOrStartProgress(AdvancementHolder holder);

	@Inject(method = "award", at = @At("HEAD"))
	private void countercraft$before(final AdvancementHolder holder, final String criterion, final CallbackInfoReturnable<Boolean> cir) {
		countercraft$wasDone = getOrStartProgress(holder).isDone();
	}

	@Inject(method = "award", at = @At("RETURN"))
	private void countercraft$after(final AdvancementHolder holder, final String criterion, final CallbackInfoReturnable<Boolean> cir) {
		if (countercraft$wasDone || !cir.getReturnValueZ() || !getOrStartProgress(holder).isDone()) {
			return;
		}
		holder.value().display().ifPresent(d -> {
			if (d.type() == AdvancementType.CHALLENGE) {
				dev.countercraft.Mvp.challenge(player, d);
			}
		});
	}
}
