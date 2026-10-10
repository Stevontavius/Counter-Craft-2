package dev.countercraft.movement;

import dev.countercraft.net.StepFxPayload;
import dev.countercraft.net.StepPayload;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server: passes a CS player's footstep / jump / landing on to the players who can hear it (CS2's footstep curves
 * reach 1100 units, 28 blocks). Footsteps come about every 0.3 s at a run, so much faster ones are dropped.
 */
public final class StepRelay {
	private static final double RANGE = 1100 / 39.37;
	private static final Map<UUID, Long> LAST_STEP = new ConcurrentHashMap<>();

	private StepRelay() {
	}

	public static void relay(ServerPlayer from, StepPayload step) {
		if (!CsMove.enabled(from) || from.isSpectator()) {
			return;
		}
		if (step.kind() == StepPayload.STEP) {
			long now = from.level().getGameTime();
			Long last = LAST_STEP.put(from.getUUID(), now);
			if (last != null && now - last < 5) {
				return;
			}
		}
		StepFxPayload fx = new StepFxPayload(from.getId(), step.kind(), step.surface(), (float) from.getX(), (float) from.getY(), (float) from.getZ());
		for (ServerPlayer p : PlayerLookup.tracking(from)) {
			if (p != from && p.distanceToSqr(from) <= RANGE * RANGE && ServerPlayNetworking.canSend(p, StepFxPayload.TYPE)) {
				ServerPlayNetworking.send(p, fx);
			}
		}
	}

	public static void forget(UUID player) {
		LAST_STEP.remove(player);
	}
}
