package dev.countercraft.bot;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.countercraft.CounterCraft;
import dev.countercraft.gun.GunItem;
import dev.countercraft.team.CsInventory;
import dev.countercraft.team.Loadouts;
import dev.countercraft.team.TeamLock;
import dev.countercraft.team.Teams;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Stationary test bots ({@code /bot}, operators): a T or CT player standing still, holding a weapon. Always the team's default agent (they send no loadout, so other clients draw CS2's
 * default T / CT agent, knife and gloves). A dead bot comes back at the same spot 3 s later (after its ragdoll fell).
 * <ul>
 * <li>{@code /bot add t|ct [weapon]}: a bot here, holding the weapon (a gun id such as ak47, or knife; default: the
 * team's starting pistol). It's put 2 blocks in front of you, facing you.</li>
 * <li>{@code /bot remove}: all bots go. {@code /bot list}.</li>
 * </ul>
 * Bots leave nothing behind in the world's player data.
 */
public final class TestBots {
	private static final List<BotPlayer> BOTS = new ArrayList<>();
	private static final int RESPAWN_TICKS = 60;
	private static int counter;

	private TestBots() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("bot")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("add")
						.then(Commands.literal(Teams.T).executes(c -> add(c, Teams.T, ""))
								.then(Commands.argument("weapon", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(weapons(), b))
										.executes(c -> add(c, Teams.T, StringArgumentType.getString(c, "weapon")))))
						.then(Commands.literal(Teams.CT).executes(c -> add(c, Teams.CT, ""))
								.then(Commands.argument("weapon", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(weapons(), b))
										.executes(c -> add(c, Teams.CT, StringArgumentType.getString(c, "weapon"))))))
				.then(Commands.literal("remove").executes(c -> {
					int n = removeAll(c.getSource().getServer());
					c.getSource().sendSuccess(() -> Component.literal("Removed " + n + (n == 1 ? " bot" : " bots")), true);
					return n;
				}))
				.then(Commands.literal("list").executes(c -> {
					StringBuilder sb = new StringBuilder(BOTS.isEmpty() ? "No bots" : "Bots:");
					for (BotPlayer b : BOTS) {
						sb.append("\n  ").append(b.getGameProfile().name()).append(" (").append(b.team.toUpperCase()).append(", ")
								.append(b.weapon.isEmpty() ? "pistol" : b.weapon).append(b.isAlive() ? ")" : ", dead)");
					}
					c.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
					return BOTS.size();
				})));
	}

	private static List<String> weapons() {
		List<String> out = new ArrayList<>(CounterCraft.GUNS.keySet());
		out.add("knife");
		return out;
	}

	private static int add(CommandContext<CommandSourceStack> c, String team, String weapon) throws CommandSyntaxException {
		ServerPlayer me = c.getSource().getPlayerOrException();
		if (!weapon.isEmpty() && !weapon.equals("knife") && !CounterCraft.GUNS.containsKey(weapon)) {
			c.getSource().sendFailure(Component.literal("Unknown weapon " + weapon));
			return 0;
		}
		// 2 blocks ahead on the ground plane, turned to face you
		Vec3 look = Vec3.directionFromRotation(0f, me.getYRot());
		Vec3 at = me.position().add(look.scale(2.0));
		float yaw = me.getYRot() + 180f;
		BotPlayer bot = spawn(c.getSource().getServer(), me.level(), team, weapon, at, yaw, null, UUID.randomUUID());
		c.getSource().sendSuccess(() -> Component.literal("Added " + bot.getGameProfile().name()), true);
		return 1;
	}

	private static BotPlayer spawn(MinecraftServer server, net.minecraft.server.level.ServerLevel level, String team, String weapon, Vec3 at, float yaw, String name, UUID id) {
		if (name == null) {
			name = (Teams.CT.equals(team) ? "Bot_CT" : "Bot_T") + (++counter);
		}
		GameProfile profile = new GameProfile(id, name);
		BotPlayer bot = new BotPlayer(server, level, profile, team, weapon, at, yaw);
		bot.snapTo(at.x, at.y, at.z, yaw, 0f);
		server.getPlayerList().placeNewPlayer(new BotConnection(), bot, new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
		bot.snapTo(at.x, at.y, at.z, yaw, 0f);
		bot.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		TeamLock.choose(bot, team); // also releases a Counter-Craft first-spawn hold
		equip(bot, weapon);
		BOTS.add(bot);
		return bot;
	}

	/** CS mode, holding the weapon (default: the team's starting pistol). */
	private static void equip(BotPlayer bot, String weapon) {
		if (!CsInventory.active(bot)) {
			CsInventory.toggle(bot);
		}
		int slot = CsInventory.KNIFE;
		if (!weapon.equals("knife")) {
			GunItem gun = weapon.isEmpty() ? CounterCraft.GUNS.get(Loadouts.modId(Loadouts.defaults(bot.team).get("secondary0"))) : CounterCraft.GUNS.get(weapon);
			if (gun != null) {
				ItemStack stack = new ItemStack(gun);
				CsInventory.give(bot, stack);
				slot = CsInventory.slotFor(stack);
			}
		}
		bot.getInventory().setSelectedSlot(slot);
		bot.connection.send(new ClientboundSetHeldSlotPacket(slot));
	}

	/** Every server tick: dead bots come back after their ragdoll fell; in hardcore they're gone for good. */
	public static void tick(MinecraftServer server) {
		for (BotPlayer b : new ArrayList<>(BOTS)) {
			if (b.isRemoved() && !b.isDeadOrDying()) {
				BOTS.remove(b); // kicked some other way
			} else if (b.isDeadOrDying() && ++b.deathWait >= RESPAWN_TICKS) {
				BOTS.remove(b);
				remove(server, b);
				if (!b.level().getLevelData().isHardcore()) {
					// the same player id: clients clear its ragdoll once that player is alive again
					spawn(server, b.level(), b.team, b.weapon, b.home, b.yaw, b.getGameProfile().name(), b.getUUID());
				}
			}
		}
	}

	private static void remove(MinecraftServer server, BotPlayer b) {
		// the normal disconnect: every per-player state on the server is dropped, as for a player who leaves
		b.connection.onDisconnect(new net.minecraft.network.DisconnectionDetails(Component.literal("Bot removed")));
		UUID id = b.getUUID();
		// nothing of the bot stays in the world's player files
		for (Path p : new Path[] {
				server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"),
				server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat_old"),
				server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(id + ".json"),
				server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json")}) {
			try {
				Files.deleteIfExists(p);
			} catch (Exception e) {
				CounterCraft.LOG.debug("[countercraft] bot file {} not removed: {}", p, e.toString());
			}
		}
	}

	public static int removeAll(MinecraftServer server) {
		int n = BOTS.size();
		for (BotPlayer b : new ArrayList<>(BOTS)) {
			remove(server, b);
		}
		BOTS.clear();
		return n;
	}

	public static boolean isBot(ServerPlayer p) {
		return p instanceof BotPlayer;
	}
}
