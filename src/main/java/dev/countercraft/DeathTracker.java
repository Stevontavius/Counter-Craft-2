package dev.countercraft;

import dev.countercraft.gun.Ballistics;
import dev.countercraft.net.DeathInfoPayload;
import dev.countercraft.net.KillFeedPayload;
import dev.countercraft.net.RagdollPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * CS2's death panel data: per player, this life's damage taken from and given to every other living thing (CS2 HP =
 * Minecraft health x 5) and in how many hits; on death the player gets it with the killer ({@link DeathInfoPayload}),
 * and everyone who sees them gets a ragdoll pushed by the killing hit ({@link RagdollPayload}).
 */
public final class DeathTracker {
	/** Minecraft health -> CS2 HP (a player's 20 health = 100 HP). */
	private static final float TO_CS = 1f / Ballistics.CS_TO_MC;

	/** [damage taken, hits taken, damage given, hits given]. */
	private static final Map<UUID, Map<UUID, float[]>> TALLY = new ConcurrentHashMap<>();

	private DeathTracker() {
	}

	private static float[] tally(UUID player, UUID other) {
		return TALLY.computeIfAbsent(player, k -> new HashMap<>()).computeIfAbsent(other, k -> new float[4]);
	}

	/** Any living thing takes damage (CombatTrackerMixin: before its health drops, the killing hit included). */
	public static void damaged(LivingEntity victim, DamageSource source, float taken) {
		if (taken <= 0) {
			return;
		}
		Entity attacker = source.getEntity();
		if (attacker == victim || !(attacker instanceof LivingEntity)) {
			return;
		}
		// what the hit really takes (an overkill only takes what was left)
		float dealt = Math.min(taken, victim.getHealth()) * TO_CS;
		if (victim instanceof ServerPlayer) {
			float[] t = tally(victim.getUUID(), attacker.getUUID());
			t[0] += dealt;
			t[1]++;
		}
		if (attacker instanceof ServerPlayer) {
			float[] t = tally(attacker.getUUID(), victim.getUUID());
			t[2] += dealt;
			t[3]++;
		}
	}

	/** A new life (respawned or left): the tallies start over. */
	public static void reset(UUID player) {
		TALLY.remove(player);
	}

	/** {@code player} died; {@code killer} / {@code weapon} / {@code flags} as the kill feed has them. */
	public static void died(ServerPlayer player, DamageSource source, Entity killer, String weapon, int flags) {
		if (killer == player) {
			killer = null;
		}
		Map<UUID, float[]> mine = TALLY.getOrDefault(player.getUUID(), Map.of());
		float[] t = killer == null ? new float[4] : mine.getOrDefault(killer.getUUID(), new float[4]);
		String cause = killer != null ? "killed" : source.getEntity() == player || source.is(DamageTypes.FELL_OUT_OF_WORLD) ? "self" : "environment";
		ItemStack held = ItemStack.EMPTY;
		float health = 0;
		if (killer instanceof LivingEntity le) {
			held = le.getMainHandItem().copy();
			health = le.getMaxHealth() > 0 ? Math.max(0f, le.getHealth() / le.getMaxHealth()) : 0f;
		}
		String cs = weapon == null || weapon.startsWith("item:") ? "" : weapon;
		DeathInfoPayload info = new DeathInfoPayload(killer == null ? -1 : killer.getId(),
				killer == null ? "" : BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()).toString(),
				killer == null ? Component.empty() : killer.getDisplayName(), killer instanceof Player, health, held, cs,
				Math.min(100, Math.round(t[0])), (int) t[1], Math.round(t[2]), (int) t[3], source.getLocalizedDeathMessage(player), cause,
				SurvivalRecord.of(player).streak(), // read before the death resets it
				dev.countercraft.team.AnotherChance.died(player));
		if (ServerPlayNetworking.canSend(player, DeathInfoPayload.TYPE)) {
			ServerPlayNetworking.send(player, info);
		}
		// the ragdoll's push: along the killing hit (the projectile's flight, else from the killer), harder for explosions
		Vec3 dir;
		float speed;
		Entity direct = source.getDirectEntity();
		if (direct instanceof Projectile pr && pr.getDeltaMovement().lengthSqr() > 1e-4) {
			dir = pr.getDeltaMovement().normalize();
			speed = 3f;
		} else if (killer != null) {
			dir = player.position().subtract(killer.position()).multiply(1, 0, 1);
			dir = dir.lengthSqr() < 1e-6 ? player.getLookAngle().reverse() : dir.normalize();
			speed = weapon != null && (weapon.startsWith("knife") || weapon.equals("bayonet")) ? 2f : 3f;
		} else {
			dir = player.getDeltaMovement().lengthSqr() > 1e-4 ? player.getDeltaMovement().normalize() : player.getLookAngle().reverse();
			speed = 1f;
		}
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
			speed = 7f;
			dir = dir.add(0, 0.6, 0).normalize();
		}
		RagdollPayload rag = new RagdollPayload(player.getId(), (float) dir.x, (float) dir.y, (float) dir.z, speed,
				(flags & KillFeedPayload.F_HEADSHOT) != 0);
		for (ServerPlayer sp : PlayerLookup.tracking(player)) {
			if (ServerPlayNetworking.canSend(sp, RagdollPayload.TYPE)) {
				ServerPlayNetworking.send(sp, rag);
			}
		}
		if (ServerPlayNetworking.canSend(player, RagdollPayload.TYPE)) {
			ServerPlayNetworking.send(player, rag);
		}
		reset(player.getUUID());
	}
}
