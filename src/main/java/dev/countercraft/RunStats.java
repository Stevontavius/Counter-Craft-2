package dev.countercraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.countercraft.net.KillFeedPayload;
import dev.countercraft.net.RunStatsPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;

/**
 * A Counter-Craft (hardcore) run's deep-dive stats for one player. CC2's own counters (damage, money, kills by weapon,
 * shots, CS2 kill flags...) are kept with the player (saved, kept through death); Minecraft's own statistics (mobs, blocks,
 * distances...) are read from the vanilla stats. Both go to the player every five seconds, and the client keeps the
 * latest copy in its run history, so it outlives the world.
 */
public final class RunStats {
	/** CC2's counters ({@code counts}) and kills per weapon id as the kill feed names it ({@code weapons}). */
	public record Data(Map<String, Long> counts, Map<String, Integer> weapons) {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("counts").forGetter(Data::counts),
				Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("weapons").forGetter(Data::weapons)).apply(i, (c, w) -> new Data(new HashMap<>(c), new HashMap<>(w))));
	}

	public static final AttachmentType<Data> STATS = AttachmentRegistry.<Data>builder()
			.persistent(Data.CODEC)
			.copyOnDeath()
			.buildAndRegister(CounterCraft.id("run_stats"));

	private RunStats() {
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (taken <= 0) {
				return;
			}
			if (entity instanceof ServerPlayer p) {
				add(p, "damage_taken", Math.round(taken * 10));
			}
			if (source.getEntity() instanceof ServerPlayer p && p != entity) {
				add(p, "damage_given", Math.round(taken * 10));
			}
		});
	}

	private static Data data(ServerPlayer p) {
		return p.getAttachedOrCreate(STATS, () -> new Data(new HashMap<>(), new HashMap<>()));
	}

	private static boolean counts(Player p) {
		return p instanceof ServerPlayer && p.level().getLevelData().isHardcore();
	}

	/** Add to one of CC2's counters (hardcore worlds only). */
	public static void add(Player p, String key, long amount) {
		if (counts(p)) {
			data((ServerPlayer) p).counts().merge(key, amount, Long::sum);
		}
	}

	private static void max(ServerPlayer p, String key, long value) {
		data(p).counts().merge(key, value, Math::max);
	}

	/** Money credited (kill awards, day rewards...). */
	public static void earned(Player p, int amount) {
		if (amount > 0) {
			add(p, "money_earned", amount);
		}
	}

	/** Money spent; also tracks the most spent on one in-game day. */
	public static void spent(Player p, int amount) {
		if (!counts(p) || amount <= 0) {
			return;
		}
		ServerPlayer sp = (ServerPlayer) p;
		Map<String, Long> c = data(sp).counts();
		long day = sp.level().getServer().overworld().getOverworldClockTime() / 24000L;
		if (c.getOrDefault("spend_day", -1L) != day) {
			c.put("spend_day", day);
			c.put("day_spend", 0L);
		}
		c.merge("money_spent", (long) amount, Long::sum);
		long today = c.merge("day_spend", (long) amount, Long::sum);
		max(sp, "best_day_spend", today);
	}

	/** Money refunded (sell back): it wasn't spent after all (the day's total too; the best day stays as it was). */
	public static void unspent(Player p, int amount) {
		if (!counts(p) || amount <= 0) {
			return;
		}
		Map<String, Long> c = data((ServerPlayer) p).counts();
		c.computeIfPresent("money_spent", (k, v) -> Math.max(0, v - amount));
		c.computeIfPresent("day_spend", (k, v) -> Math.max(0, v - amount));
	}

	/** A kill by this player, with the kill feed's weapon id and flags. */
	public static void onKill(ServerPlayer killer, LivingEntity victim, String weapon, int flags) {
		if (!counts(killer)) {
			return;
		}
		Data d = data(killer);
		d.weapons().merge(weapon == null || weapon.isEmpty() ? "fists" : weapon, 1, Integer::sum);
		Map<String, Long> c = d.counts();
		c.merge("kills", 1L, Long::sum);
		if ((flags & KillFeedPayload.F_HEADSHOT) != 0) {
			c.merge("headshots", 1L, Long::sum);
		}
		if ((flags & KillFeedPayload.F_PENETRATE) != 0) {
			c.merge("wallbangs", 1L, Long::sum);
		}
		if ((flags & KillFeedPayload.F_NOSCOPE) != 0) {
			c.merge("noscopes", 1L, Long::sum);
		}
		if ((flags & KillFeedPayload.F_SMOKE) != 0) {
			c.merge("through_smoke", 1L, Long::sum);
		}
		if ((flags & KillFeedPayload.F_BLIND) != 0) {
			c.merge("blind_kills", 1L, Long::sum);
		}
		if ((flags & KillFeedPayload.F_INAIR) != 0) {
			c.merge("airborne_kills", 1L, Long::sum);
		}
		max(killer, "longest_kill_cm", Math.round(killer.distanceTo(victim) * 100));
	}

	/** Every five seconds: send each hardcore player their run's stats. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 0) {
			return;
		}
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (counts(p) && ServerPlayNetworking.canSend(p, RunStatsPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload(p));
			}
		}
	}

	private static RunStatsPayload payload(ServerPlayer p) {
		Data d = data(p);
		// days survived: dawns since the run began (the first team pick), frozen once the player is dead
		Long picked = p.getAttached(dev.countercraft.team.TeamLock.TEAM_DAY);
		if (picked != null) {
			d.counts().putIfAbsent("start_day", picked);
		}
		Long start = d.counts().get("start_day");
		if (start != null && p.isAlive() && !p.isSpectator()) {
			long today = Math.floorDiv(p.level().getServer().overworld().getOverworldClockTime(), dev.countercraft.team.DayCycle.DAY);
			d.counts().put("days", Math.max(0, today - start));
		}
		Map<String, Long> out = new HashMap<>(d.counts());
		out.remove("spend_day");
		out.remove("day_spend");
		out.remove("start_day");
		ServerStatsCounter s = p.getStats();
		long placed = 0;
		for (var item : BuiltInRegistries.ITEM) {
			if (item instanceof BlockItem) {
				placed += s.getValue(Stats.ITEM_USED, item);
			}
		}
		long mined = 0;
		for (var block : BuiltInRegistries.BLOCK) {
			mined += s.getValue(Stats.BLOCK_MINED, block);
		}
		long crafted = 0;
		for (var item : BuiltInRegistries.ITEM) {
			crafted += s.getValue(Stats.ITEM_CRAFTED, item);
		}
		out.put("blocks_placed", placed);
		out.put("blocks_mined", mined);
		out.put("items_crafted", crafted);
		out.put("mob_kills", (long) s.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
		out.put("player_kills", (long) s.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
		out.put("play_ticks", (long) s.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)));
		long walked = 0;
		for (var id : new net.minecraft.resources.Identifier[] {Stats.WALK_ONE_CM, Stats.SPRINT_ONE_CM, Stats.CROUCH_ONE_CM, Stats.SWIM_ONE_CM,
				Stats.WALK_ON_WATER_ONE_CM, Stats.WALK_UNDER_WATER_ONE_CM, Stats.CLIMB_ONE_CM}) {
			walked += s.getValue(Stats.CUSTOM.get(id));
		}
		out.put("walked_cm", walked);
		out.put("sprinted_cm", (long) s.getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM)));
		out.put("elytra_cm", (long) s.getValue(Stats.CUSTOM.get(Stats.AVIATE_ONE_CM)));
		out.put("fallen_cm", (long) s.getValue(Stats.CUSTOM.get(Stats.FALL_ONE_CM)));
		out.put("jumps", (long) s.getValue(Stats.CUSTOM.get(Stats.JUMP)));
		out.put("animals_bred", (long) s.getValue(Stats.CUSTOM.get(Stats.ANIMALS_BRED)));
		out.put("fish_caught", (long) s.getValue(Stats.CUSTOM.get(Stats.FISH_CAUGHT)));
		out.put("villager_trades", (long) s.getValue(Stats.CUSTOM.get(Stats.TRADED_WITH_VILLAGER)));
		out.put("items_enchanted", (long) s.getValue(Stats.CUSTOM.get(Stats.ENCHANT_ITEM)));
		out.put("raids_won", (long) s.getValue(Stats.CUSTOM.get(Stats.RAID_WIN)));
		out.put("cake_slices", (long) s.getValue(Stats.CUSTOM.get(Stats.EAT_CAKE_SLICE)));
		out.put("nights_slept", (long) s.getValue(Stats.CUSTOM.get(Stats.SLEEP_IN_BED)));
		out.put("shield_blocked", (long) s.getValue(Stats.CUSTOM.get(Stats.DAMAGE_BLOCKED_BY_SHIELD)));
		out.put("chests_opened", (long) s.getValue(Stats.CUSTOM.get(Stats.OPEN_CHEST)));
		Map<String, Integer> mobs = new HashMap<>();
		for (var type : BuiltInRegistries.ENTITY_TYPE) {
			int n = s.getValue(Stats.ENTITY_KILLED, type);
			if (n > 0) {
				mobs.put(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), n);
			}
		}
		return new RunStatsPayload(out, new HashMap<>(d.weapons()), mobs);
	}

	/** Shooter of a hit, as a player (or null). */
	public static void hit(Entity shooter) {
		if (shooter instanceof Player p) {
			add(p, "hits", 1);
		}
	}
}
