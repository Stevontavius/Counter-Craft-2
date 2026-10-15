package dev.countercraft.client.movement;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.countercraft.CounterCraft;
import dev.countercraft.movement.CsMove;
import dev.countercraft.net.MovementModePayload;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Movement options as CS2-style console variables (client commands, saved to config/countercraft-movement.properties):
 * {@code cs_movement} (CS2 movement on/off, default 1). The bunny hop cvars are the server's (ServerCvars), as in CS2.
 */
public final class MovementSettings {
	public static boolean enabled = true;
	/** Which CS2 movement keys stop you at a block edge, as sneaking does in Minecraft. */
	public static EdgeGuard edgeGuard = EdgeGuard.BOTH;

	public enum EdgeGuard {
		CROUCH("Crouch"), WALK("Walk"), BOTH("Both"), NONE("None");

		public final String label;

		EdgeGuard(String label) {
			this.label = label;
		}

		public boolean holds(boolean crouching, boolean walking) {
			return (crouching && (this == CROUCH || this == BOTH)) || (walking && (this == WALK || this == BOTH));
		}

		public EdgeGuard next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	private MovementSettings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft-movement.properties");
	}

	public static void load() {
		Path f = file();
		if (Files.exists(f)) {
			Properties p = new Properties();
			try (Reader r = Files.newBufferedReader(f)) {
				p.load(r);
				enabled = !"0".equals(p.getProperty("cs_movement", "1"));
				try {
					edgeGuard = EdgeGuard.valueOf(p.getProperty("edge_guard", "BOTH"));
				} catch (IllegalArgumentException e) {
					edgeGuard = EdgeGuard.BOTH;
				}
			} catch (IOException e) {
				CounterCraft.LOG.warn("[countercraft] couldn't read {}: {}", f, e.toString());
			}
		}
		CsMove.clientEnabled = enabled;
	}

	public static void save() {
		Path f = file();
		Properties p = new Properties();
		p.setProperty("cs_movement", enabled ? "1" : "0");
		p.setProperty("edge_guard", edgeGuard.name());
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) {
				p.store(w, "Counter-Craft 2 movement (CS2 cvars, 0/1)");
			}
		} catch (IOException e) {
			CounterCraft.LOG.warn("[countercraft] couldn't save {}: {}", f, e.toString());
		}
	}

	/** Tell the server which movement this client runs (speed units, fall damage, crouched eye height). */
	public static void sync() {
		CsMove.clientEnabled = enabled;
		if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(MovementModePayload.TYPE)) {
			ClientPlayNetworking.send(new MovementModePayload(enabled));
		}
	}

	/** The toggle key: asks the server (operators only, as in CS2). */
	public static void toggleAutoBhop() {
		var connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendCommand("sv_autobunnyhopping " + (CsMove.autoBhop ? 0 : 1));
		}
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		cvar(d, "cs_movement", () -> enabled, v -> {
			enabled = v;
			sync();
		});
		cvar(d, "cs_movement_debug", () -> CsPlayerMovement.debug, v -> CsPlayerMovement.debug = v);
	}

	private static void cvar(CommandDispatcher<FabricClientCommandSource> d, String name, Supplier<Boolean> get, Consumer<Boolean> set) {
		d.register(ClientCommands.literal(name)
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal(name + " " + (get.get() ? 1 : 0)));
					return 1;
				})
				.then(ClientCommands.argument("value", IntegerArgumentType.integer(0, 1)).executes(c -> {
					set.accept(IntegerArgumentType.getInteger(c, "value") == 1);
					save();
					c.getSource().sendFeedback(Component.literal(name + " " + (get.get() ? 1 : 0)));
					return 1;
				})));
	}
}
