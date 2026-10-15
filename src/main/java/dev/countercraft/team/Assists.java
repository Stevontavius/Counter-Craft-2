package dev.countercraft.team;

import dev.countercraft.gun.Ballistics;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Assists on mob kills (Counter-Craft's co-op; CS2's assist rules, its bounty is ours): when a player kills a hostile mob,
 * every other player who did it at least {@link #ASSIST_DAMAGE} CS damage (CS2's assist threshold: guns, knives,
 * grenades, fire), or whose flashbang had it blinded (CS2's flash assist), gets {@link #SHARE} of its bounty, once.
 */
public final class Assists {
	/** CS2: more than 40 damage counts as an assist. */
	private static final float ASSIST_DAMAGE = 40f;
	private static final float SHARE = 0.5f;
	/** Damage older than this doesn't count (game ticks). */
	private static final long MEMORY = 20 * 30;

	private record Dealt(Map<UUID, Float> damage, long[] last) {
	}

	/** Mob -> each player's CS damage to it. */
	private static final Map<UUID, Dealt> DAMAGE = new ConcurrentHashMap<>();
	/** Mob -> who flashed it, and until when it's blind. */
	private static final Map<UUID, Object[]> FLASHED = new ConcurrentHashMap<>();

	private Assists() {
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (!(entity instanceof Player) && source.getEntity() instanceof ServerPlayer p && taken > 0f) {
				Dealt d = DAMAGE.computeIfAbsent(entity.getUUID(), k -> new Dealt(new ConcurrentHashMap<>(), new long[1]));
				d.damage().merge(p.getUUID(), Math.min(taken, entity.getMaxHealth()) / Ballistics.CS_TO_MC, Float::sum);
				d.last()[0] = entity.level().getGameTime();
				if (DAMAGE.size() > 512) {
					long now = entity.level().getGameTime();
					DAMAGE.values().removeIf(x -> now - x.last()[0] > MEMORY);
				}
			}
		});
		// (cleared by onKill; mobs that die some other way are pruned as the maps grow)
	}

	/** A flashbang thrown by {@code thrower} blinded this mob until {@code until} (game time). */
	public static void flashed(LivingEntity mob, net.minecraft.world.entity.Entity thrower, long until) {
		if (thrower instanceof ServerPlayer p) {
			FLASHED.put(mob.getUUID(), new Object[] {p.getUUID(), until});
			if (FLASHED.size() > 512) {
				long now = mob.level().getGameTime();
				FLASHED.values().removeIf(x -> now > (long) x[1]);
			}
		}
	}

	/** {@code killer} killed {@code victim}: its assists are paid. */
	public static void onKill(ServerPlayer killer, LivingEntity victim) {
		if (victim instanceof Player || dev.countercraft.BossFights.bounty(victim) > 0) {
			return; // players: no bounty; bosses: everyone who fought them is paid (BossFights)
		}
		int bounty = Economy.baseBounty(victim);
		long now = victim.level().getGameTime();
		Dealt d = DAMAGE.get(victim.getUUID());
		Object[] f = FLASHED.get(victim.getUUID());
		java.util.Set<UUID> paid = new java.util.HashSet<>();
		if (bounty > 0 && d != null && now - d.last()[0] <= MEMORY) {
			for (Map.Entry<UUID, Float> e : d.damage().entrySet()) {
				if (!e.getKey().equals(killer.getUUID()) && e.getValue() > ASSIST_DAMAGE) {
					pay(killer, e.getKey(), bounty, "for an assist on ", victim, paid);
				}
			}
		}
		if (bounty > 0 && f != null && now <= (long) f[1] && !killer.getUUID().equals(f[0])) {
			pay(killer, (UUID) f[0], bounty, "for a flash assist on ", victim, paid);
		}
		forget(victim.getUUID());
	}

	private static void pay(ServerPlayer killer, UUID who, int bounty, String why, LivingEntity victim, java.util.Set<UUID> paid) {
		ServerPlayer p = killer.level().getServer().getPlayerList().getPlayer(who);
		if (p == null || !paid.add(who) || Economy.freeBuy(p)) {
			return;
		}
		boolean spawner = Boolean.TRUE.equals(victim.getAttached(Economy.FROM_SPAWNER));
		int award = Math.max(1, Math.round(bounty * SHARE * (spawner ? 0.25f : 1f)));
		int before = Economy.money(p);
		Economy.add(p, award);
		dev.countercraft.Achievements.bounty(p, award);
		int got = Economy.money(p) - before;
		if (got > 0) {
			String name = victim.getType().getDescription().getString();
			p.sendSystemMessage(Component.literal("+$" + got + ": Award " + why + (name.matches("(?i)^[aeiou].*") ? "an " : "a ") + name + ".")
					.withColor(0x6FB86F), true);
		}
	}

	private static void forget(UUID mob) {
		DAMAGE.remove(mob);
		FLASHED.remove(mob);
	}
}
