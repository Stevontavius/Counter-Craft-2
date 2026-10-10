package dev.countercraft.mixin;

import dev.countercraft.steam.GenuineSkins;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Genuine Skins: "PlayerName ✓" wherever the name shows (name tag, chat, ...) for players with a linked Steam account
 * who play with their own inventory ({@link GenuineSkins}). Hovering it says exactly what it means. Nobody else is marked.
 */
@Mixin(Player.class)
abstract class GenuineNameMixin {
	private static final Component TICK_HOVER = Component.literal("Genuine Skins\n")
			.append(Component.literal("The CS2 cosmetics you see on this player are in their own linked Steam inventory.\n").withColor(0xBFBFBF))
			.append(Component.literal("Not a rank, not a rating: just provenance.").withColor(0x8A8A8A));

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void countercraft$genuineTick(final CallbackInfoReturnable<Component> cir) {
		if (GenuineSkins.genuine((Player) (Object) this)) {
			cir.setReturnValue(cir.getReturnValue().copy().append(Component.literal(" ✓")
					.withStyle(Style.EMPTY.withColor(0x4FC3F7).withHoverEvent(new HoverEvent.ShowText(TICK_HOVER)))));
		}
	}
}
