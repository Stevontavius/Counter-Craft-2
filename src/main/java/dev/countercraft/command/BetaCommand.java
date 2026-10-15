package dev.countercraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.countercraft.gun.C4Item;
import dev.countercraft.gun.PlantedC4;
import dev.countercraft.team.CsInventory;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Tests of things not released yet (operators). {@code /beta test_c4 1 | 0}: the C4 test, for the whole world (saved with
 * it, {@link CsInventory#C4_TEST}). On: the C4 may exist ({@code /cc give beta c4} gives one, into the CS hotbar's C4 slot,
 * key 5), CTs can buy the defuse kit, and everyone sees the blast debug view (each planted C4's blast bands in colour
 * with their health percentages, and its panel). Off (the default): planted C4s and everyone's C4s are gone, the kit is
 * off the menu and {@code /cc give beta c4} is refused.
 */
public final class BetaCommand {
	private BetaCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("beta")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("test_c4")
						.executes(ctx -> {
							boolean on = CsInventory.c4Test(ctx.getSource().getServer().overworld());
							ctx.getSource().sendSuccess(() -> Component.literal("test_c4 " + (on ? 1 : 0)), false);
							return on ? 1 : 0;
						})
						.then(Commands.argument("on", IntegerArgumentType.integer(0, 1)).executes(ctx -> {
							boolean on = IntegerArgumentType.getInteger(ctx, "on") == 1;
							setC4Test(ctx.getSource().getServer(), on);
							ctx.getSource().sendSuccess(() -> Component.literal(on
									? "test_c4 1: /cc give beta c4, the CT defuse kit and the blast debug view"
									: "test_c4 0: every C4 removed, the defuse kit and the blast debug view gone"), true);
							return 1;
						}))));
	}

	/** A player joining a world with the test off loses any C4 they still carry. */
	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!CsInventory.c4Test(handler.getPlayer())) {
				takeC4(handler.getPlayer());
			}
		});
	}

	/** The C4 test on or off in every level; off, every C4 goes: planted, dropped and carried. */
	public static void setC4Test(MinecraftServer server, boolean on) {
		for (ServerLevel level : server.getAllLevels()) {
			level.setAttached(CsInventory.C4_TEST, on);
			if (!on) {
				List<Entity> gone = new ArrayList<>();
				for (Entity e : level.getAllEntities()) {
					if (e instanceof PlantedC4 || e instanceof ItemEntity item && item.getItem().getItem() instanceof C4Item) {
						gone.add(e);
					}
				}
				gone.forEach(Entity::discard);
			}
		}
		if (!on) {
			server.getPlayerList().getPlayers().forEach(BetaCommand::takeC4);
		}
	}

	/** The C4 out of a player's CS slot (the Minecraft inventory's go by themselves: C4Item.inventoryTick). */
	private static void takeC4(ServerPlayer p) {
		CsInventory.Data d = CsInventory.of(p);
		if (d.get(CsInventory.C4).getItem() instanceof C4Item) {
			d.items.set(CsInventory.C4, ItemStack.EMPTY);
			CsInventory.markDirty(p);
			if (CsInventory.active(p) && p.getInventory().getSelectedSlot() == CsInventory.C4) {
				CsInventory.selectBest(p);
			}
		}
	}
}
