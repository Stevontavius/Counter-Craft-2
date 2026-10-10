package dev.countercraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.countercraft.net.CvarsPayload;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Server-side CS2 cvars, per world: set with commands of the same name (operators) and saved in the world folder
 * (countercraft-cvars.properties), so every world / server keeps its own and a new world starts at CS2's defaults.
 * <ul>
 * <li>{@code sv_infinite_ammo} (alias {@code sv_infiniteammo}): 0 normal (CS2 default), 1 the magazine never empties,
 * 2 infinite reserve ammo (still reload).</li>
 * <li>{@code sv_infinite_grenades}: 1 = thrown grenades stay in the inventory.</li>
 * <li>{@code mp_startmoney} / {@code mp_maxmoney}: CS2's economy (800 / 16000), survival and adventure only; creative
 * buys for free.</li>
 * <li>{@code sv_maxvelocity}: the speed cap of players and grenades (units/s, CS2 default 3500, up to 9999).</li>
 * </ul>
 * Every cvar also takes {@code reset} (back to its CS2 default).
 */
public final class ServerCvars {
	/** One integer cvar: CS2 default and range. */
	private record Cvar(String name, int dflt, int min, int max) {
	}

	private static final Map<String, Cvar> CVARS = new LinkedHashMap<>();
	private static final Map<String, Integer> VALUES = new LinkedHashMap<>();

	static {
		add("sv_infinite_ammo", 0, 0, 2);
		add("sv_infinite_grenades", 0, 0, 1);
		add("mp_startmoney", 800, 0, 65535);
		add("mp_maxmoney", 16000, 0, 65535);
		add("sv_sellback_enabled", 1, 0, 1); // CS2's refunds (casual / competitive: 1)
		add("sv_maxvelocity", 3500, 0, 9999);
	}

	/** Mirrors of the values the hot paths read. */
	public static volatile int infiniteAmmo = 0;
	private static Path file;

	private ServerCvars() {
	}

	/** cl_immune_to_flashbangs 1 (testing): these players don't go white. Per player, not saved. */
	private static final java.util.Set<java.util.UUID> FLASH_IMMUNE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public static boolean immuneToFlash(ServerPlayer p) {
		return FLASH_IMMUNE.contains(p.getUUID());
	}

	private static void add(String name, int dflt, int min, int max) {
		CVARS.put(name, new Cvar(name, dflt, min, max));
	}

	public static int get(String name) {
		Integer v = VALUES.get(name);
		return v != null ? v : CVARS.get(name).dflt();
	}

	private static void set(String name, int value) {
		Cvar c = CVARS.get(name);
		VALUES.put(name, Math.max(c.min(), Math.min(c.max(), value)));
		infiniteAmmo = get("sv_infinite_ammo");
		dev.countercraft.movement.CsMove.maxVelocity = get("sv_maxvelocity");
	}

	private static void mirror() {
		infiniteAmmo = get("sv_infinite_ammo");
		dev.countercraft.movement.CsMove.maxVelocity = get("sv_maxvelocity");
	}

	/** World loaded: its cvars (CS2 defaults for a new world). */
	public static void load(MinecraftServer server) {
		VALUES.clear();
		mirror();
		file = server.getWorldPath(LevelResource.ROOT).resolve("countercraft-cvars.properties");
		if (!Files.exists(file)) {
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(file)) {
			p.load(r);
			for (Cvar c : CVARS.values()) {
				String v = p.getProperty(c.name());
				if (v != null) {
					set(c.name(), Integer.parseInt(v.trim()));
				}
			}
		} catch (IOException | NumberFormatException e) {
			CounterCraft.LOG.warn("[countercraft] couldn't read {}: {}", file, e.toString());
		}
	}

	/** World closed: back to defaults (the next world loads its own). */
	public static void unload() {
		VALUES.clear();
		mirror();
		file = null;
	}

	private static void save() {
		if (file == null) {
			return;
		}
		Properties p = new Properties();
		for (Cvar c : CVARS.values()) {
			p.setProperty(c.name(), Integer.toString(get(c.name())));
		}
		try (Writer w = Files.newBufferedWriter(file)) {
			p.store(w, "Counter-Craft 2 cvars for this world");
		} catch (IOException e) {
			CounterCraft.LOG.warn("[countercraft] couldn't save {}: {}", file, e.toString());
		}
	}

	/** True when a thrown grenade is kept (sv_infinite_grenades 1). */
	public static boolean keepGrenades() {
		return get("sv_infinite_grenades") != 0;
	}

	public static void send(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, CvarsPayload.TYPE)) {
			ServerPlayNetworking.send(player, new CvarsPayload(infiniteAmmo, get("sv_maxvelocity")));
		}
	}

	private static void broadcast(MinecraftServer server) {
		server.getPlayerList().getPlayers().forEach(ServerCvars::send);
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		for (Cvar c : CVARS.values()) {
			literal(d, c.name(), c);
		}
		literal(d, "sv_infiniteammo", CVARS.get("sv_infinite_ammo")); // the spelling without the underscore
		d.register(Commands.literal("cl_immune_to_flashbangs")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					ctx.getSource().sendSuccess(() -> Component.literal("cl_immune_to_flashbangs " + (immuneToFlash(p) ? 1 : 0)), false);
					return immuneToFlash(p) ? 1 : 0;
				})
				.then(Commands.argument("value", IntegerArgumentType.integer(0, 1)).executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					if (IntegerArgumentType.getInteger(ctx, "value") == 1) {
						FLASH_IMMUNE.add(p.getUUID());
					} else {
						FLASH_IMMUNE.remove(p.getUUID());
					}
					ctx.getSource().sendSuccess(() -> Component.literal("cl_immune_to_flashbangs " + (immuneToFlash(p) ? 1 : 0)), false);
					return 1;
				})));
	}

	private static void literal(CommandDispatcher<CommandSourceStack> d, String command, Cvar c) {
		d.register(Commands.literal(command)
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " " + get(c.name()) + " (default " + c.dflt() + ")"), false);
					return get(c.name());
				})
				.then(Commands.literal("reset").executes(ctx -> {
					VALUES.remove(c.name());
					mirror();
					save();
					broadcast(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " " + get(c.name()) + " (reset to default)"), true);
					return get(c.name());
				}))
				.then(Commands.argument("value", IntegerArgumentType.integer(c.min(), c.max())).executes(ctx -> {
					set(c.name(), IntegerArgumentType.getInteger(ctx, "value"));
					save();
					broadcast(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " " + get(c.name())), true);
					return get(c.name());
				})));
	}
}
