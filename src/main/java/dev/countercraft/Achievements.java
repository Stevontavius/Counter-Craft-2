package dev.countercraft;

import dev.countercraft.gun.GunItem;
import dev.countercraft.net.KillFeedPayload;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The Counter-Craft 2 advancement tab (data/countercraft/advancement/cs2/*.json). Every criterion is
 * {@code minecraft:impossible}: this class grants them from the game's own events.
 * <ul>
 * <li>Eco Round: a full Minecraft day (dawn to dawn, online and alive) without buying anything.</li>
 * <li>Save!: a full day carrying a weapon of {@link #EXPENSIVE} or more without firing it.</li>
 * <li>Kobe!: a hostile mob killed by your HE grenade at least {@link #KOBE_RANGE} blocks away from you.</li>
 * <li>Blind Luck: a kill while fully flashed (the white-out part of the flash).</li>
 * <li>Knife to Meet You: a knife backstab on a hostile mob.</li>
 * <li>HvH? (challenge): 10 no-scope headshot kills on skeletons (any kind) with a scoped rifle.</li>
 * <li>Movement Player! (challenge): 100 no-scope AWP kills while airborne (players or hostile mobs).</li>
 * <li>The Global Elite (challenge): 100 days survived in a row; dying resets the count.</li>
 * <li>Smoke Screen: a hostile mob chasing you loses you in a smoke and gives up its search (MobVision).</li>
 * <li>Flash Photography: one of your flashbangs blinds 5 hostile mobs.</li>
 * <li>Ace!: 5 hostile mobs killed with guns within {@link #ACE_TICKS}, with no reload started in between.</li>
 * <li>Collateral Damage: one bullet kills 3 mobs (Ballistics' body penetration).</li>
 * <li>Electrician: your Zeus charges a creeper.</li>
 * <li>Tactical Retreat: your flashbang stops a hissing creeper's fuse.</li>
 * <li>Money Talks (challenge): {@link #NIGHT_MONEY} in mob bounties (kills, assists, bosses; before the money cap) in
 * one night ({@link #NIGHT_START} to dawn).</li>
 * <li>The Floor Is Lava (challenge): 10 hostile mobs killed by one molotov / incendiary's fire (burning on after it too).</li>
 * <li>Deforestation Department (challenge): a hostile mob killed by a decoy's pop.</li>
 * <li>To the Window! To the Wall! (challenge): a hostile mob killed by a grenade that smashed through glass.</li>
 * <li>Clutch or Kick (challenge, hardcore): the only player alive at a dawn that brings the others back (AnotherChance).</li>
 * <li>The day-long goals read the player's saved {@link SurvivalRecord} (deaths and days survived in this world).</li>
 * </ul>
 * Counting challenges keep their count as criteria ("1".."N"), so the advancement screen shows the progress and it saves
 * with the world.
 */
public final class Achievements {
	/** "Expensive weapon" for Save!: rifle money (an AK-47 is $2700). */
	public static final int EXPENSIVE = 2700;
	/** Kobe! range in blocks (about 790 CS units). */
	public static final double KOBE_RANGE = 20.0;
	private static final long DAY = 24000L;
	/** Ace!: the five kills within this many ticks (10 s). */
	public static final long ACE_TICKS = 200;
	/** Money Talks: bounties in one night. */
	public static final int NIGHT_MONEY = 10_000;
	/** Night, for Money Talks: from when beds work (ticks into the day) to dawn. */
	public static final long NIGHT_START = 12542;
	private static final java.util.Map<java.util.UUID, java.util.ArrayDeque<Long>> ACE = new java.util.concurrent.ConcurrentHashMap<>();
	/** Player -> {night, bounties so far that night}. */
	private static final java.util.Map<java.util.UUID, long[]> NIGHT_BOUNTIES = new java.util.concurrent.ConcurrentHashMap<>();
	/** Molotov / incendiary entity id -> {hostile kills, game time of the last}. */
	private static final java.util.Map<Integer, long[]> FIRE_KILLS = new java.util.concurrent.ConcurrentHashMap<>();

	private static long lastDay = -1;

	private Achievements() {
	}

	private static long day(MinecraftServer server) {
		return server.overworld().getOverworldClockTime() / DAY;
	}

	private static AdvancementHolder holder(MinecraftServer server, String name) {
		return server.getAdvancements().get(CounterCraft.id("cs2/" + name));
	}

	/** Grant a one-step advancement. */
	public static void award(ServerPlayer p, String name) {
		AdvancementHolder h = holder(p.level().getServer(), name);
		if (h != null) {
			p.getAdvancements().award(h, "done");
		}
	}

	/** One more towards a counting challenge (criteria "1".."N"). */
	private static void step(ServerPlayer p, String name) {
		AdvancementHolder h = holder(p.level().getServer(), name);
		if (h == null) {
			return;
		}
		AdvancementProgress prog = p.getAdvancements().getOrStartProgress(h);
		String next = null;
		for (String c : prog.getRemainingCriteria()) {
			if (next == null || Integer.parseInt(c) < Integer.parseInt(next)) {
				next = c;
			}
		}
		if (next != null) {
			p.getAdvancements().award(h, next);
		}
	}

	/** A counting challenge's progress set to at least {@code n} (criteria "1".."n"). */
	private static void awardUpTo(ServerPlayer p, String name, int n) {
		AdvancementHolder h = holder(p.level().getServer(), name);
		if (h == null) {
			return;
		}
		for (String c : p.getAdvancements().getOrStartProgress(h).getRemainingCriteria()) {
			if (Integer.parseInt(c) <= n) {
				p.getAdvancements().award(h, c);
			}
		}
	}

	/** Back to zero (an unfinished counting challenge). */
	private static void reset(ServerPlayer p, String name) {
		AdvancementHolder h = holder(p.level().getServer(), name);
		if (h == null) {
			return;
		}
		AdvancementProgress prog = p.getAdvancements().getOrStartProgress(h);
		if (prog.isDone()) {
			return;
		}
		for (String c : prog.getCompletedCriteria()) {
			p.getAdvancements().revoke(h, c);
		}
	}

	/**
	 * Joined: the record carries on if no dawn passed since they were last online (always in singleplayer); otherwise every
	 * clean run starts again with the next full day.
	 */
	public static void join(ServerPlayer p) {
		long today = day(p.level().getServer());
		SurvivalRecord.update(p, r -> (r.lastDay() == today ? r : r.restartAll(today)).withLastDay(today));
	}

	public static void leave(ServerPlayer p) {
		SurvivalRecord.update(p, r -> r.withLastDay(day(p.level().getServer())));
	}

	public static void died(ServerPlayer p) {
		SurvivalRecord.update(p, r -> r.died(day(p.level().getServer())));
		reset(p, "global_elite");
	}

	/** Bought something (Eco Round breaks). */
	public static void bought(ServerPlayer p) {
		SurvivalRecord.update(p, r -> r.withEcoFrom(day(p.level().getServer()) + 1));
	}

	/** Fired a gun (Save! breaks if it's an expensive one). */
	public static void fired(ServerPlayer p, GunItem gun) {
		if (gun.spec().price() >= EXPENSIVE) {
			SurvivalRecord.update(p, r -> r.withSaveFrom(day(p.level().getServer()) + 1));
		}
	}

	private static boolean carriesExpensive(Player p) {
		for (ItemStack st : p.getInventory()) {
			if (st.getItem() instanceof GunItem g && g.spec().price() >= EXPENSIVE) {
				return true;
			}
		}
		return dev.countercraft.team.CsInventory.of(p).items.stream().anyMatch(st -> st.getItem() instanceof GunItem g && g.spec().price() >= EXPENSIVE);
	}

	/** Every server tick: the carry check once a second, and the day-end checks at each new day. */
	public static void tick(MinecraftServer server) {
		long d = day(server);
		if (server.getTickCount() % 20 == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (!carriesExpensive(p) && SurvivalRecord.of(p).saveFrom() <= d) {
					SurvivalRecord.update(p, r -> r.withSaveFrom(d + 1));
				}
			}
		}
		if (lastDay < 0 || d < lastDay) {
			lastDay = d; // first tick, or time set backwards
			return;
		}
		if (d == lastDay) {
			return;
		}
		lastDay = d;
		long finished = d - 1;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!p.isAlive() || p.isSpectator() || dev.countercraft.team.TeamLock.pending(p)) {
				// dead, spectating, or (Counter-Craft) not yet on a team: the run isn't on
				SurvivalRecord.update(p, r -> r.withLastDay(d));
				continue;
			}
			SurvivalRecord r = SurvivalRecord.of(p);
			if (r.ecoFrom() <= finished) {
				award(p, "eco_round");
			}
			if (r.saveFrom() <= finished) {
				award(p, "save");
			}
			if (r.aliveFrom() <= finished) {
				r = SurvivalRecord.update(p, x -> x.survivedDay().withLastDay(d));
				awardUpTo(p, "global_elite", r.streak());
				p.sendSystemMessage(net.minecraft.network.chat.Component.literal("Day survived! Streak: " + r.streak() + (r.streak() == 1 ? " day" : " days"))
						.withColor(0xDCB45A), true);
			} else {
				SurvivalRecord.update(p, x -> x.withLastDay(d));
			}
		}
	}

	/** {@code /survival [player]}: the survival record (everyone may read anyone's). */
	public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> d) {
		d.register(net.minecraft.commands.Commands.literal("survival")
				.executes(c -> show(c.getSource(), c.getSource().getPlayerOrException()))
				.then(net.minecraft.commands.Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
						.executes(c -> show(c.getSource(), net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player")))));
	}

	private static int show(net.minecraft.commands.CommandSourceStack src, ServerPlayer p) {
		SurvivalRecord r = SurvivalRecord.of(p);
		src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(p.getName().getString() + ": " + r.streak() + " days survived since the last death (best "
				+ r.best() + "), " + r.total() + " days survived in total, " + r.deaths() + (r.deaths() == 1 ? " death" : " deaths")), false);
		return r.streak();
	}

	/** A gun reload started (Ace! breaks). */
	public static void reloaded(ServerPlayer p) {
		ACE.remove(p.getUUID());
	}

	/** {@code p} was paid a mob bounty (a kill, an assist, a boss): Money Talks counts tonight's. */
	public static void bounty(ServerPlayer p, int amount) {
		long clock = p.level().getServer().overworld().getOverworldClockTime();
		if (amount <= 0 || Math.floorMod(clock, DAY) < NIGHT_START) {
			return;
		}
		long night = Math.floorDiv(clock, DAY);
		long[] n = NIGHT_BOUNTIES.computeIfAbsent(p.getUUID(), u -> new long[] {night, 0});
		if (n[0] != night) {
			n[0] = night;
			n[1] = 0;
		}
		n[1] += amount;
		if (n[1] >= NIGHT_MONEY) {
			award(p, "money_talks");
		}
	}

	/** A player's kill, with the kill feed's weapon id and flags; {@code source}: the grenade's entity id (0: none). */
	public static void onKill(ServerPlayer killer, LivingEntity victim, String weapon, int flags, int source) {
		boolean hostile = victim instanceof Enemy;
		long now = killer.level().getGameTime();
		if (hostile && "decoy".equals(weapon) && (flags & KillFeedPayload.F_POP) != 0) {
			award(killer, "deforestation_department");
		}
		if (hostile && (flags & KillFeedPayload.F_WINDOW) != 0) {
			award(killer, "to_the_window");
		}
		if (hostile && "inferno".equals(weapon) && source != 0) {
			FIRE_KILLS.values().removeIf(v -> now - v[1] > 20 * 60);
			long[] k = FIRE_KILLS.computeIfAbsent(source, x -> new long[2]);
			k[0]++;
			k[1] = now;
			if (k[0] >= 10) {
				award(killer, "the_floor_is_lava");
			}
		}
		if (hostile && CounterCraft.GUNS.containsKey(weapon)) {
			java.util.ArrayDeque<Long> kills = ACE.computeIfAbsent(killer.getUUID(), u -> new java.util.ArrayDeque<>());
			kills.addLast(now);
			while (now - kills.peekFirst() > ACE_TICKS) {
				kills.pollFirst();
			}
			if (kills.size() >= 5) {
				award(killer, "ace");
			}
		}
		boolean head = (flags & KillFeedPayload.F_HEADSHOT) != 0;
		boolean noscope = (flags & KillFeedPayload.F_NOSCOPE) != 0;
		boolean air = (flags & KillFeedPayload.F_INAIR) != 0;
		if (hostile && "hegrenade".equals(weapon) && killer.distanceTo(victim) >= KOBE_RANGE) {
			award(killer, "kobe");
		}
		if (hostile && (flags & KillFeedPayload.F_IMPACT) != 0) {
			award(killer, "bonk");
		}
		if (Combat.fullyFlashed(killer)) {
			award(killer, "blind_luck");
		}
		if (victim instanceof AbstractSkeleton && head && noscope) {
			step(killer, "hvh");
		}
		if ((hostile || victim instanceof Player) && "awp".equals(weapon) && noscope && air) {
			step(killer, "movement_player");
		}
	}
}
