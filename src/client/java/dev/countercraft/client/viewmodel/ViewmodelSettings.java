package dev.countercraft.client.viewmodel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import dev.countercraft.CounterCraft;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

/**
 * CS2's viewmodel console variables, as client commands with CS2's defaults and limits:
 * {@code viewmodel_offset_x} (right), {@code viewmodel_offset_y} (forward), {@code viewmodel_offset_z} (up),
 * in inches, and {@code viewmodel_fov}; {@code cl_righthand} (CS:GO's; 0 = the viewmodel mirrored into the left hand, toggled
 * with the Switch Hands key, H). Changes apply live and are saved to config/countercraft-viewmodel.properties.
 */
public final class ViewmodelSettings {
	public static float offsetX = 1f;
	public static float offsetY = 1f;
	public static float offsetZ = -1f;
	public static float fov = 68f;
	/** cl_righthand: false = left-handed viewmodel (mirrored, like CS:GO's cl_righthand 0). */
	public static boolean rightHand = true;

	private ViewmodelSettings() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("countercraft-viewmodel.properties");
	}

	public static void load() {
		Path f = file();
		if (!Files.exists(f)) {
			return;
		}
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(f)) {
			p.load(r);
			offsetX = Float.parseFloat(p.getProperty("viewmodel_offset_x", "1"));
			offsetY = Float.parseFloat(p.getProperty("viewmodel_offset_y", "1"));
			offsetZ = Float.parseFloat(p.getProperty("viewmodel_offset_z", "-1"));
			fov = Float.parseFloat(p.getProperty("viewmodel_fov", "68"));
			rightHand = !"0".equals(p.getProperty("cl_righthand", "1").trim());
		} catch (IOException | NumberFormatException e) {
			CounterCraft.LOG.warn("[countercraft] couldn't read {}: {}", f, e.toString());
		}
	}

	public static void save() {
		Path f = file();
		Properties p = new Properties();
		p.setProperty("viewmodel_offset_x", Float.toString(offsetX));
		p.setProperty("viewmodel_offset_y", Float.toString(offsetY));
		p.setProperty("viewmodel_offset_z", Float.toString(offsetZ));
		p.setProperty("viewmodel_fov", Float.toString(fov));
		p.setProperty("cl_righthand", rightHand ? "1" : "0");
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) {
				p.store(w, "Counter-Craft 2 viewmodel (CS2 units: inches, fov = viewmodel_fov)");
			}
		} catch (IOException e) {
			CounterCraft.LOG.warn("[countercraft] couldn't save {}: {}", f, e.toString());
		}
	}

	/** CS2's switchhands: the viewmodel to the other hand. */
	public static void switchHands() {
		rightHand = !rightHand;
		save();
	}

	private static String describe() {
		return String.format(Locale.ROOT, "viewmodel_offset_x %.2f  viewmodel_offset_y %.2f  viewmodel_offset_z %.2f  viewmodel_fov %.0f",
				offsetX, offsetY, offsetZ, fov);
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		// CS2 limits: x -2.5..2.5, y -2..2, z -2..2, fov 54..68
		cvar(d, "viewmodel_offset_x", -2.5f, 2.5f, v -> offsetX = v);
		cvar(d, "viewmodel_offset_y", -2f, 2f, v -> offsetY = v);
		cvar(d, "viewmodel_offset_z", -2f, 2f, v -> offsetZ = v);
		cvar(d, "viewmodel_fov", 54f, 68f, v -> fov = v);
		// tuning the aim-down-sights camera (not saved)
		debug(d, "cc_ads_forward", -1f, 0.8f, () -> ViewmodelRenderer.ADS_FORWARD, v -> ViewmodelRenderer.ADS_FORWARD = v);
		debug(d, "cc_ads_fov", -1f, 110f, () -> ViewmodelRenderer.ADS_VIEWMODEL_FOV, v -> ViewmodelRenderer.ADS_VIEWMODEL_FOV = v);
		debug(d, "cc_ads_depth", 1f, 20f, () -> ViewmodelRenderer.ADS_DEPTH_SCALE, v -> ViewmodelRenderer.ADS_DEPTH_SCALE = v);
		debug(d, "cc_ads_lens", -0.1f, 0.2f, () -> ViewmodelRenderer.LENS_PLANE_BACK, v -> ViewmodelRenderer.LENS_PLANE_BACK = v);
		d.register(ClientCommands.literal("cc_ads_debug").executes(c -> {
			c.getSource().sendFeedback(Component.literal(dev.countercraft.client.GunClient.lensDebug()));
			return 1;
		}));
		debug(d, "cc_ads_lens_r", 0.005f, 0.1f, () -> ViewmodelRenderer.LENS_RADIUS, v -> ViewmodelRenderer.LENS_RADIUS = v);
		d.register(ClientCommands.literal("cl_righthand")
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal("cl_righthand " + (rightHand ? 1 : 0)));
					return rightHand ? 1 : 0;
				})
				.then(ClientCommands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 1)).executes(c -> {
					rightHand = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "value") == 1;
					save();
					c.getSource().sendFeedback(Component.literal("cl_righthand " + (rightHand ? 1 : 0)));
					return 1;
				})));
		d.register(ClientCommands.literal("switchhands").executes(c -> {
			switchHands();
			c.getSource().sendFeedback(Component.literal("cl_righthand " + (rightHand ? 1 : 0)));
			return 1;
		}));
		d.register(ClientCommands.literal("viewmodel").executes(c -> {
			c.getSource().sendFeedback(Component.literal(describe()));
			return 1;
		}).then(ClientCommands.literal("reset").executes(c -> {
			offsetX = 1f;
			offsetY = 1f;
			offsetZ = -1f;
			fov = 68f;
			save();
			c.getSource().sendFeedback(Component.literal("CS2 defaults: " + describe()));
			return 1;
		})));
	}

	private static void debug(CommandDispatcher<FabricClientCommandSource> d, String name, float min, float max, java.util.function.Supplier<Float> get,
			Consumer<Float> set) {
		d.register(ClientCommands.literal(name)
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal(name + " " + get.get()));
					return 1;
				})
				.then(ClientCommands.argument("value", FloatArgumentType.floatArg(min, max)).executes(c -> {
					set.accept(FloatArgumentType.getFloat(c, "value"));
					c.getSource().sendFeedback(Component.literal(name + " " + get.get()));
					return 1;
				})));
	}

	private static void cvar(CommandDispatcher<FabricClientCommandSource> d, String name, float min, float max, Consumer<Float> set) {
		d.register(ClientCommands.literal(name)
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal(describe()));
					return 1;
				})
				.then(ClientCommands.argument("value", FloatArgumentType.floatArg(min, max)).executes(c -> {
					set.accept(FloatArgumentType.getFloat(c, "value"));
					save();
					c.getSource().sendFeedback(Component.literal(describe()));
					return 1;
				})));
	}
}
