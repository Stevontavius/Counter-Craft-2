package dev.countercraft.client.mixin;

import net.minecraft.advancements.AdvancementType;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advancement toasts become CS2 MVP banners ({@link dev.countercraft.client.hud.MvpBanners}): your advancements and
 * goals as your own banner; challenges come from the server for everyone, so their toast just goes.
 */
@Mixin(ToastManager.class)
abstract class ToastManagerMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void countercraft$mvpBanner(final Toast toast, final CallbackInfo ci) {
		if (!(toast instanceof AdvancementToast at)) {
			return;
		}
		ci.cancel();
		((AdvancementToastAccessor) at).countercraft$advancement().value().display().ifPresent(d -> {
			if (d.type() != AdvancementType.CHALLENGE) {
				dev.countercraft.client.hud.MvpBanners.personal(d);
			}
		});
	}
}
