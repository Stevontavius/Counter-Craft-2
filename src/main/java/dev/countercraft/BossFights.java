package dev.countercraft;

import dev.countercraft.net.MvpPayload;
import dev.countercraft.team.Economy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Boss fights, won together: when the Ender Dragon, the Wither or the Warden dies, everyone who hurt it earns its bounty
 * (the same for each, survival and hardcore alike: beating a boss is the group's success, not the last hit's), and the
 * whole server gets an MVP banner for the player who did the most damage, with their music kit's MVP anthem.
 */
public final class BossFights {
	/** Boss -> player -> damage dealt (Minecraft HP). Server thread. */
	private static final Map<UUID, Map<UUID, Float>> DAMAGE = new HashMap<>();

	private BossFights() {
	}

	/** A boss's bounty, or 0 for anything else. */
	public static int bounty(LivingEntity e) {
		if (e instanceof net.minecraft.world.entity.boss.wither.WitherBoss) {
			return Economy.WITHER_AWARD;
		}
		if (e instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon || e instanceof net.minecraft.world.entity.monster.warden.Warden) {
			return Economy.BOSS_AWARD;
		}
		return 0;
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (taken > 0 && bounty(entity) > 0 && source.getEntity() instanceof ServerPlayer p) {
				DAMAGE.computeIfAbsent(entity.getUUID(), k -> new HashMap<>()).merge(p.getUUID(), taken, Float::sum);
			}
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Map<UUID, Float> dealt = DAMAGE.remove(entity.getUUID());
			int bounty = bounty(entity);
			if (bounty > 0 && dealt != null && !dealt.isEmpty() && entity.level() instanceof ServerLevel level) {
				defeated(level, entity, bounty, dealt);
			}
		});
	}

	private static void defeated(ServerLevel level, LivingEntity boss, int bounty, Map<UUID, Float> dealt) {
		String name = boss.getType().getDescription().getString();
		float total = 0;
		ServerPlayer mvp = null;
		float most = -1;
		for (Map.Entry<UUID, Float> d : dealt.entrySet()) {
			total += d.getValue();
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(d.getKey());
			if (p != null && d.getValue() > most) {
				most = d.getValue();
				mvp = p;
			}
		}
		for (UUID id : dealt.keySet()) {
			ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
			if (p == null || Economy.freeBuy(p)) {
				continue;
			}
			int before = Economy.money(p);
			Economy.add(p, bounty);
			Achievements.bounty(p, bounty);
			int got = Economy.money(p) - before;
			p.sendSystemMessage(Component.literal("+$" + got + ": Award for defeating the " + name + ".").withColor(0x6FB86F));
		}
		if (mvp == null) {
			return;
		}
		int share = total > 0 ? Math.round(most / total * 100) : 100;
		String team = mvp.getAttached(dev.countercraft.team.Teams.TEAM);
		MvpPayload payload = new MvpPayload("BOSS DEFEATED", mvp.getGameProfile().name(), team == null ? "" : team, name,
				"Most damage to the " + name + ": " + share + "%", Mvp.kit(mvp));
		for (ServerPlayer to : level.getServer().getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(to, MvpPayload.TYPE)) {
				ServerPlayNetworking.send(to, payload);
			}
		}
	}
}
