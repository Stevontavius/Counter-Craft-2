package dev.countercraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.Ballistics;
import dev.countercraft.gun.GunItem;
import dev.countercraft.gun.GunSpec;
import dev.countercraft.gun.Guns;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /cc give <weapon|all>} ({@code /cc give beta c4}: the C4 test's), {@code /cc money <amount>} (your CS2 cash) and {@code /cc test <gun>}. The test is the oracle for the weapon core: it spawns a
 * still zombie 10 blocks ahead, fires one body shot and one head shot, and logs the damage each one dealt.
 */
public final class CcCommand {
	private static final double TEST_DISTANCE = 10.0;

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cc")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("give")
						.then(Commands.literal("all").executes(CcCommand::giveAll))
						.then(Commands.literal("beta").then(Commands.literal("c4").executes(CcCommand::giveC4)))
						.then(Commands.argument("weapon", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(weapons().keySet(), b))
								.executes(ctx -> give(ctx, 0))
								// a finish (paint kit id, e.g. 962 = Desert Eagle | Printstream)
								.then(Commands.argument("finish", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
										.executes(ctx -> give(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "finish"))))))
				.then(Commands.literal("money")
						.then(Commands.argument("amount", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 65535)).executes(ctx -> {
							ServerPlayer p = ctx.getSource().getPlayerOrException();
							dev.countercraft.team.Economy.set(p, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "amount"));
							int m = dev.countercraft.team.Economy.money(p);
							ctx.getSource().sendSuccess(() -> Component.literal("Money: $" + m), false);
							return m;
						})))
				.then(Commands.literal("test")
						.then(Commands.argument("gun", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(Guns.ALL.keySet(), b))
								.executes(CcCommand::test))));
	}

	private static GunItem gun(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "gun");
		GunItem item = CounterCraft.GUNS.get(id);
		if (item == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown gun: " + id + " (have " + Guns.ALL.keySet() + ")"));
		}
		return item;
	}

	/** Every CS item by weapon id: guns, knives and grenades (the C4: {@code /cc give beta c4}). */
	private static Map<String, Item> weapons() {
		Map<String, Item> all = new LinkedHashMap<>(CounterCraft.GUNS);
		all.putAll(CounterCraft.KNIVES);
		all.putAll(CounterCraft.GRENADES);
		return all;
	}

	/** {@code /cc give beta c4}: a C4 into its CS slot, with the C4 test on only ({@code /beta test_c4 1}). */
	private static int giveC4(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		if (!dev.countercraft.team.CsInventory.c4Test(player)) {
			ctx.getSource().sendFailure(Component.literal("The C4 test is off (/beta test_c4 1)"));
			return 0;
		}
		ItemStack stack = new ItemStack(CounterCraft.C4);
		if (!dev.countercraft.team.CsInventory.give(player, stack)) {
			player.getInventory().add(stack);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Gave c4"), false);
		return 1;
	}

	private static int give(CommandContext<CommandSourceStack> ctx, int finish) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "weapon");
		Item item = weapons().get(id);
		if (item == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown weapon: " + id + " (have " + weapons().keySet() + ")"));
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		// into its CS slot (replacing what's there, as CS2's give does); anything else into the inventory
		ItemStack stack = new ItemStack(item);
		if (finish > 0) {
			stack.set(dev.countercraft.ModComponents.SKIN, finish);
		}
		if (!dev.countercraft.team.CsInventory.give(player, stack)) {
			player.getInventory().add(stack);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Gave " + id), false);
		return 1;
	}

	private static int giveAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		CounterCraft.GUNS.values().forEach(g -> player.getInventory().add(new ItemStack(g)));
		CounterCraft.KNIVES.values().forEach(k -> player.getInventory().add(new ItemStack(k)));
		CounterCraft.GRENADES.values().forEach(g -> player.getInventory().add(new ItemStack(g)));
		ctx.getSource().sendSuccess(() -> Component.literal("Gave " + CounterCraft.GUNS.size() + " guns, " + CounterCraft.KNIVES.size()
				+ " knives, " + CounterCraft.GRENADES.size() + " grenades"), false);
		return 1;
	}

	private static int test(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		GunItem item = gun(ctx);
		if (item == null) {
			return 0;
		}
		GunSpec spec = item.spec();
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ServerLevel level = ctx.getSource().getLevel();

		Vec3 look = player.getViewVector(1f);
		Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
		Vec3 pos = player.position().add(flat.scale(TEST_DISTANCE));
		Zombie zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
		if (zombie == null) {
			ctx.getSource().sendFailure(Component.literal("Couldn't spawn the test zombie"));
			return 0;
		}
		zombie.snapTo(pos.x, pos.y, pos.z, player.getYRot() + 180f, 0f);
		zombie.setNoAi(true);
		zombie.setPersistenceRequired();
		level.addFreshEntity(zombie);

		Vec3 eye = player.getEyePosition();
		float before = zombie.getHealth();
		Ballistics.ShotResult body = Ballistics.shoot(level, player, spec, eye,
				new Vec3(zombie.getX(), zombie.getY() + 1.0, zombie.getZ()).subtract(eye).normalize());
		float afterBody = zombie.getHealth();
		Ballistics.ShotResult head = Ballistics.shoot(level, player, spec, eye,
				new Vec3(zombie.getX(), zombie.getEyeY(), zombie.getZ()).subtract(eye).normalize());
		float afterHead = zombie.isDeadOrDying() ? 0f : zombie.getHealth();

		String msg = String.format(
				"test %s dist=%.2f body: hit=%s head=%s dealt=%.2f | head: hit=%s head=%s dealt=%.2f | expected body=%.2f head=%.2f | zombie %s",
				spec.id(), body.distance(),
				body.target() == zombie, body.headshot(), before - afterBody,
				head.target() == zombie, head.headshot(), afterBody - afterHead,
				Ballistics.csDamage(spec, body.distance(), false) * Ballistics.CS_TO_MC,
				Ballistics.csDamage(spec, head.distance(), true) * Ballistics.CS_TO_MC,
				zombie.isDeadOrDying() ? "dead" : "alive");
		CounterCraft.LOG.info("[countercraft] {}", msg);
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		if (!zombie.isDeadOrDying()) {
			zombie.discard();
		}
		return 1;
	}

	private CcCommand() {
	}
}
