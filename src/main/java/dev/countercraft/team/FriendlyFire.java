package dev.countercraft.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.countercraft.CounterCraft;
import dev.countercraft.ServerCvars;
import dev.countercraft.gun.Ballistics;
import java.util.Objects;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;

/**
 * Team kills and team damage on servers. Every player is a teammate (T and CT are cosmetic), so any player hurting or
 * killing another counts. Each player's tally is kept on them with the world, for good: reaching {@code mp_teamkill_ban}
 * team kills (5) or {@code mp_teamdamage_ban} team damage (500, in CS2 health: a player has 100) bans them from the
 * server permanently. They're warned along the way, a last time one step before. 0 turns a limit off. Never in
 * singleplayer or for the world's host, and never for operators (they run the server; they'd only ban themselves).
 */
public final class FriendlyFire {
	/** A player's lifetime team kills and team damage (CS2 health points). */
	public record Tally(int kills, float damage) {
		static final Tally NONE = new Tally(0, 0f);
		static final Codec<Tally> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("kills").forGetter(Tally::kills),
				Codec.FLOAT.fieldOf("damage").forGetter(Tally::damage)).apply(i, Tally::new));
	}

	public static final AttachmentType<Tally> TALLY = AttachmentRegistry.<Tally>builder()
			.persistent(Tally.CODEC)
			.copyOnDeath()
			.buildAndRegister(CounterCraft.id("friendly_fire"));

	/** Team damage warnings at these shares of the limit (the last one is the final warning). */
	private static final float[] DAMAGE_WARNINGS = {0.2f, 0.5f, 0.8f, 0.9f};

	private FriendlyFire() {
	}

	public static void init() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
			if (entity instanceof ServerPlayer victim && source.getEntity() instanceof ServerPlayer attacker && attacker != victim && taken > 0f) {
				// CS2 health (a player has 100); overkill past a full health bar doesn't count
				damage(attacker, Math.min(taken, victim.getMaxHealth()) / Ballistics.CS_TO_MC);
			}
		});
	}

	/** Players these rules don't apply to: singleplayer and the world's host, operators. */
	private static boolean exempt(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		return server.isSingleplayerOwner(p.nameAndId()) || server.getPlayerList().isOp(p.nameAndId());
	}

	private static Tally of(ServerPlayer p) {
		return Objects.requireNonNullElse(p.getAttached(TALLY), Tally.NONE);
	}

	private static void damage(ServerPlayer attacker, float cs) {
		int limit = ServerCvars.get("mp_teamdamage_ban");
		if (limit <= 0 || exempt(attacker)) {
			return;
		}
		Tally t = of(attacker);
		float before = t.damage();
		float now = before + cs;
		attacker.setAttached(TALLY, new Tally(t.kills(), now));
		if (now >= limit) {
			ban(attacker, "Too much team damage (" + Math.round(now) + " of " + limit + ").");
			return;
		}
		for (int i = DAMAGE_WARNINGS.length - 1; i >= 0; i--) {
			float mark = DAMAGE_WARNINGS[i] * limit;
			if (before < mark && now >= mark) {
				warn(attacker, (i == DAMAGE_WARNINGS.length - 1 ? "FINAL WARNING: " : "Warning: ") + "you've dealt " + Math.round(now) + " team damage. At "
						+ limit + " you are permanently banned from this server.");
				break;
			}
		}
	}

	/** {@code killer} killed another player (Combat.onDeath). */
	public static void teamKill(ServerPlayer killer) {
		int limit = ServerCvars.get("mp_teamkill_ban");
		if (limit <= 0 || exempt(killer)) {
			return;
		}
		Tally t = of(killer);
		int kills = t.kills() + 1;
		killer.setAttached(TALLY, new Tally(kills, t.damage()));
		if (kills >= limit) {
			ban(killer, "Too many team kills (" + kills + " of " + limit + ").");
		} else if (kills == limit - 1) {
			warn(killer, "FINAL WARNING: team kill " + kills + " of " + limit + ". One more and you are permanently banned from this server.");
		} else {
			warn(killer, "Warning: team kill " + kills + " of " + limit + ". At " + limit + " you are permanently banned from this server.");
		}
	}

	private static void warn(ServerPlayer p, String text) {
		p.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		p.sendOverlayMessage(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	private static void ban(ServerPlayer p, String why) {
		MinecraftServer server = p.level().getServer();
		server.getPlayerList().getBans().add(new UserBanListEntry(p.nameAndId(), null, "Counter-Craft 2", null, why));
		CounterCraft.LOG.info("[countercraft] {} permanently banned: {}", p.getName().getString(), why);
		p.connection.disconnect(Component.literal("You are permanently banned from this server.\n" + why));
		server.getPlayerList().broadcastSystemMessage(
				Component.literal(p.getName().getString() + " was permanently banned: " + why).withStyle(ChatFormatting.RED), false);
	}
}
