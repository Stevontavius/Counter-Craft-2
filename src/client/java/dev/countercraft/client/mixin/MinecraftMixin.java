package dev.countercraft.client.mixin;

import dev.countercraft.gun.CsWeapon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holding a CS weapon, left click fires / slashes / primes (see GunKeys.FIRE) instead of punching or breaking blocks. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Shadow
	public LocalPlayer player;
	@org.spongepowered.asm.mixin.Unique
	private static boolean countercraft$stopping;

	private boolean countercraft$holdingGun() {
		return player != null && dev.countercraft.team.CsInventory.active(player) && player.getMainHandItem().getItem() instanceof CsWeapon;
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void countercraft$noAttack(final CallbackInfoReturnable<Boolean> cir) {
		if (countercraft$holdingGun()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void countercraft$noMining(final boolean leftClick, final CallbackInfo ci) {
		if (countercraft$holdingGun()) {
			ci.cancel();
		}
	}

	/**
	 * CS2 music kit: the main menu track replaces Minecraft's menu music; the team select screen plays its track. Music
	 * the options turn off (Minecraft's, the kit's or both) is stopped.
	 */
	@Inject(method = "getSituationalMusic", at = @At("HEAD"), cancellable = true)
	private void countercraft$musicKit(final CallbackInfoReturnable<net.minecraft.sounds.Music> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		net.minecraft.sounds.Music m = dev.countercraft.client.loadout.MusicKit.situational(mc);
		if (m == dev.countercraft.client.loadout.MusicKit.SILENCE) {
			cir.setReturnValue(null);
			// MusicManager.stopPlaying() asks for the situational music again: don't re-enter
			if (!countercraft$stopping) {
				countercraft$stopping = true;
				try {
					mc.getMusicManager().stopPlaying();
				} finally {
					countercraft$stopping = false;
				}
			}
		} else if (m != null) {
			cir.setReturnValue(m);
		}
	}

	/** Kit tracks play at CS2's music volume cvars (snd_musicvolume x snd_menumusic_volume). */
	@Inject(method = "getMusicVolume", at = @At("RETURN"), cancellable = true)
	private void countercraft$kitVolume(final CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(cir.getReturnValue() * dev.countercraft.client.loadout.MusicKit.menuGain((Minecraft) (Object) this));
	}
}
