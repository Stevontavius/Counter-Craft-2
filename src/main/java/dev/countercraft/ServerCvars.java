package dev.countercraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
 * <li>{@code sv_maxvelocity}: the speed cap of players and grenades (units/s, CS2 default 3500, up to 10000).</li>
 * <li>{@code sv_airaccelerate}: air control, CS2's 12 by default, up to 2000 (surf servers run 100 and more).</li>
 * <li>{@code sv_staminamax}, {@code sv_staminajumpcost}, {@code sv_staminalandcost}: CS2's stamina, the weaker jump
 * right after a jump or a landing (CS2 defaults 80, 0.08, 0.05; costs of 0 turn it off, as bhop servers do; see
 * {@link dev.countercraft.movement.CsMove#stamina}).</li>
 * <li>{@code mp_teamkill_ban} / {@code mp_teamdamage_ban}: Counter-Craft's permanent server ban after 5 team kills / 500
 * team damage (0: off; {@link dev.countercraft.team.FriendlyFire}).</li>
 * </ul>
 * Every cvar also takes {@code reset} (back to its CS2 default). Most take whole numbers; the stamina ones decimals too.
 */
public final class ServerCvars {
	/** One cvar: CS2 default and range; whole numbers unless {@code decimal}. */
	private record Cvar(String name, double dflt, double min, double max, boolean decimal) {
		String show(double v) {
			return decimal ? new java.math.BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString() : Long.toString(Math.round(v));
		}
	}

	private static final Map<String, Cvar> CVARS = new LinkedHashMap<>();
	private static final Map<String, Double> VALUES = new LinkedHashMap<>();

	static {
		add("sv_infinite_ammo", 0, 0, 2);
		add("sv_infinite_grenades", 0, 0, 1);
		add("mp_startmoney", 800, 0, 65535);
		add("mp_maxmoney", 16000, 0, 65535);
		add("sv_sellback_enabled", 1, 0, 1); // CS2's refunds (casual / competitive: 1)
		add("sv_maxvelocity", 3500, 0, 10000);
		add("sv_airaccelerate", 12, 0, 2000); // air control (surfing); CS2's default 12
		decimal("sv_staminamax", 80, 0, 100);
		decimal("sv_staminajumpcost", 0.08, 0, 1);
		decimal("sv_staminalandcost", 0.05, 0, 1);
		add("mp_teamkill_ban", 5, 0, 1000); // Counter-Craft's: team kills that ban a player from a server for good (0: off)
		add("mp_teamdamage_ban", 500, 0, 100000); // team damage (CS2 health) that does (0: off); see FriendlyFire
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
		CVARS.put(name, new Cvar(name, dflt, min, max, false));
	}

	private static void decimal(String name, double dflt, double min, double max) {
		CVARS.put(name, new Cvar(name, dflt, min, max, true));
	}

	/** A whole-number cvar's value. */
	public static int get(String name) {
		return (int) Math.round(value(name));
	}

	public static double value(String name) {
		Double v = VALUES.get(name);
		return v != null ? v : CVARS.get(name).dflt();
	}

	private static void set(String name, double value) {
		Cvar c = CVARS.get(name);
		double v = Math.max(c.min(), Math.min(c.max(), value));
		VALUES.put(name, c.decimal() ? v : Math.round(v));
		mirror();
	}

	private static void mirror() {
		infiniteAmmo = get("sv_infinite_ammo");
		dev.countercraft.movement.CsMove.maxVelocity = get("sv_maxvelocity");
		dev.countercraft.movement.CsMove.airAccelerate = get("sv_airaccelerate");
		dev.countercraft.movement.CsMove.stamina(value("sv_staminamax"), value("sv_staminajumpcost"), value("sv_staminalandcost"));
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
					set(c.name(), Double.parseDouble(v.trim()));
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
			p.setProperty(c.name(), c.show(value(c.name())));
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
			ServerPlayNetworking.send(player, new CvarsPayload(infiniteAmmo, get("sv_maxvelocity"), get("sv_airaccelerate"),
					(float) value("sv_staminamax"), (float) value("sv_staminajumpcost"), (float) value("sv_staminalandcost")));
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
		com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> arg = c.decimal()
				? Commands.argument("value", DoubleArgumentType.doubleArg(c.min(), c.max()))
				: Commands.argument("value", IntegerArgumentType.integer((int) c.min(), (int) c.max()));
		d.register(Commands.literal(command)
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " " + c.show(value(c.name())) + " (default " + c.show(c.dflt()) + ")"),
							false);
					return get(c.name());
				})
				.then(Commands.literal("reset").executes(ctx -> {
					VALUES.remove(c.name());
					mirror();
					save();
					broadcast(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " " + c.show(value(c.name())) + " (reset to default)"), true);
					return get(c.name());
				}))
				.then(arg.executes(ctx -> {
					set(c.name(), c.decimal() ? DoubleArgumentType.getDouble(ctx, "value") : IntegerArgumentType.getInteger(ctx, "value"));
					save();
					broadcast(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal(c.name() + " " + c.show(value(c.name()))), true);
					return get(c.name());
				})));
	}
}
