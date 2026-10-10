package dev.countercraft.client.mixin;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Weapon Sound Style: a weapon sound plays its CS:GO version when that style is chosen ({@link dev.countercraft.client.CsgoSounds}). */
@Mixin(SoundManager.class)
abstract class SoundManagerMixin {
	@ModifyVariable(method = "getSoundEvent", at = @At("HEAD"), argsOnly = true)
	private Identifier countercraft$weaponSoundStyle(final Identifier id) {
		return dev.countercraft.client.CsgoSounds.redirect(id);
	}
}
