package dev.countercraft;

import dev.countercraft.net.VersionPayload;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;

/**
 * Client and server must run the same Counter-Craft 2 version. While a player connects, the server sends its version
 * and holds the connection until the client answers with its own; a different version (or a client without Counter-Craft
 * 2, or one from before this check) is turned away with both versions named and how to update. The client checks the
 * other way round too (VersionClient), so a newer client can't join an older server either.
 */
public final class VersionCheck {
	/** The mod's version (fabric.mod.json), compared exactly. */
	public static final String VERSION = FabricLoader.getInstance().getModContainer(CounterCraft.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
	private static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(CounterCraft.MOD_ID + ":version");

	private VersionCheck() {
	}

	/** "BETA 1.1 (0.1.1)". */
	public static String describe(String release, String version) {
		return release + " (" + version + ")";
	}

	public static String ours() {
		return describe(CounterCraft.RELEASE, VERSION);
	}

	public static void init() {
		PayloadTypeRegistry.clientboundConfiguration().register(VersionPayload.TYPE, VersionPayload.CODEC);
		PayloadTypeRegistry.serverboundConfiguration().register(VersionPayload.TYPE, VersionPayload.CODEC);
		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			if (!ServerConfigurationNetworking.canSend(handler, VersionPayload.TYPE)) {
				handler.disconnect(Component.literal("This server runs Counter-Craft 2 " + ours() + ".\n\nYour game doesn't have it, or has a version "
						+ "from before " + CounterCraft.RELEASE + ". Install or update it with the Counter-Craft 2 installer, then join again."));
				return;
			}
			handler.addTask(new Task());
		});
		ServerConfigurationNetworking.registerGlobalReceiver(VersionPayload.TYPE, (payload, context) -> {
			if (!VERSION.equals(payload.version())) {
				CounterCraft.LOG.info("[countercraft] turned away a client with Counter-Craft 2 {} (this server: {})",
						describe(payload.release(), payload.version()), ours());
				context.packetListener().disconnect(Component.literal("This server runs Counter-Craft 2 " + ours() + ", and you have "
						+ describe(payload.release(), payload.version()) + ".\n\nRun the Counter-Craft 2 installer for " + CounterCraft.RELEASE
						+ " and choose Update, then join again."));
				return;
			}
			context.packetListener().completeTask(TASK);
		});
	}

	private record Task() implements ConfigurationTask {
		@Override
		public void start(Consumer<Packet<?>> send) {
			send.accept(ServerConfigurationNetworking.createClientboundPacket(new VersionPayload(CounterCraft.RELEASE, VERSION)));
		}

		@Override
		public Type type() {
			return TASK;
		}
	}
}
