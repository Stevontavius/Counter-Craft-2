package dev.countercraft.client;

import dev.countercraft.CounterCraft;
import dev.countercraft.VersionCheck;
import dev.countercraft.net.FirePayload;
import dev.countercraft.net.VersionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;

/**
 * The client half of {@link VersionCheck}: answers the server's version with its own, and leaves a server that runs
 * Counter-Craft 2 without saying which version (one from before the check) instead of playing on mismatched rules.
 */
public final class VersionClient {
	/** The server's version, heard while connecting (null: none yet this connection). */
	private static volatile VersionPayload server;

	private VersionClient() {
	}

	public static void init() {
		ClientConfigurationNetworking.registerGlobalReceiver(VersionPayload.TYPE, (payload, context) -> {
			server = payload;
			context.responseSender().sendPacket(new VersionPayload(CounterCraft.RELEASE, VersionCheck.VERSION));
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			VersionPayload heard = server;
			server = null;
			// a server with Counter-Craft 2 (it takes trigger pulls) that never said its version is older than this check
			if (heard == null && ClientPlayNetworking.canSend(FirePayload.TYPE)) {
				handler.getConnection().disconnect(Component.literal("This server runs an older Counter-Craft 2 (before " + CounterCraft.RELEASE
						+ "), and you have " + VersionCheck.ours() + ".\n\nAsk its owner to update the server to " + CounterCraft.RELEASE + "."));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> server = null);
	}
}
