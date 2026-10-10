package dev.countercraft.client;

import dev.countercraft.gun.DroppedWeaponEntity;
import dev.countercraft.gun.GrenadeEntity;
import dev.countercraft.net.CsDropPayload;
import dev.countercraft.net.PickupPayload;
import dev.countercraft.team.CsDrop;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's drop (G) and +use pick-up (E, the inventory key: looking at a dropped weapon within the use radius picks it up
 * instead of opening the inventory). Runs at the start of the client tick, before vanilla reads its keys.
 */
public final class DropClient {
	private DropClient() {
	}

	public static void startTick(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		while (GunKeys.DROP.consumeClick()) {
			if (mc.gui.screen() == null) {
				ClientPlayNetworking.send(CsDropPayload.INSTANCE);
			}
		}
		if (mc.gui.screen() != null) {
			return;
		}
		DroppedWeaponEntity target = target(mc);
		if (target != null && mc.options.keyInventory.consumeClick()) {
			while (mc.options.keyInventory.consumeClick()) {
				// one pick-up per press
			}
			ClientPlayNetworking.send(new PickupPayload(target.getId()));
		}
	}

	/** The dropped weapon under the crosshair within CS2's use radius, nearest first, or null. */
	public static DroppedWeaponEntity target(Minecraft mc) {
		Vec3 eye = mc.player.getEyePosition();
		double reach = CsDrop.USE_RADIUS / GrenadeEntity.UNITS + DroppedWeaponEntity.TRIGGER;
		Vec3 end = eye.add(mc.player.getViewVector(1f).scale(reach));
		DroppedWeaponEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (DroppedWeaponEntity e : mc.level.getEntitiesOfClass(DroppedWeaponEntity.class, new AABB(eye, end).inflate(1.0))) {
			// a generous box around the weapon (it's small, and CS2 uses its bloated trigger bounds)
			var hit = e.getBoundingBox().inflate(0.15).clip(eye, end);
			if (hit.isPresent()) {
				double d = hit.get().distanceToSqr(eye);
				if (d < bestD) {
					bestD = d;
					best = e;
				}
			}
		}
		return best;
	}
}
