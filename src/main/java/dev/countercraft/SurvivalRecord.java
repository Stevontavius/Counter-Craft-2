package dev.countercraft;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.UnaryOperator;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;

/**
 * A player's survival record in this world (saved with the player, kept through death): deaths, days survived since the
 * last death (the streak), the best streak, total days survived, and when each day-long goal's clean run started.
 * A day counts when a dawn passes while the player is online and alive and has been since the previous dawn (sleeping
 * through the night counts). Read with {@code /survival}; drives Eco Round, Save! and The Global Elite ({@link Achievements}).
 *
 * @param deaths deaths in this world
 * @param streak days survived since the last death
 * @param best the longest streak
 * @param total all days survived
 * @param lastDay the last day index this player was online for (a dawn missed while offline breaks the clean runs)
 * @param aliveFrom first day of the current streak's clean run (day index)
 * @param ecoFrom first day counted for Eco Round (no purchases since)
 * @param saveFrom first day counted for Save! (expensive gun carried, not fired since)
 */
public record SurvivalRecord(int deaths, int streak, int best, int total, long lastDay, long aliveFrom, long ecoFrom, long saveFrom) {
	public static final SurvivalRecord NEW = new SurvivalRecord(0, 0, 0, 0, -1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

	static final Codec<SurvivalRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("deaths").forGetter(SurvivalRecord::deaths),
			Codec.INT.fieldOf("streak").forGetter(SurvivalRecord::streak),
			Codec.INT.fieldOf("best").forGetter(SurvivalRecord::best),
			Codec.INT.fieldOf("total").forGetter(SurvivalRecord::total),
			Codec.LONG.fieldOf("last_day").forGetter(SurvivalRecord::lastDay),
			Codec.LONG.fieldOf("alive_from").forGetter(SurvivalRecord::aliveFrom),
			Codec.LONG.fieldOf("eco_from").forGetter(SurvivalRecord::ecoFrom),
			Codec.LONG.fieldOf("save_from").forGetter(SurvivalRecord::saveFrom)).apply(i, SurvivalRecord::new));

	/** The player's own HUD / screens only need the counts. */
	static final StreamCodec<io.netty.buffer.ByteBuf, SurvivalRecord> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SurvivalRecord::deaths, ByteBufCodecs.VAR_INT, SurvivalRecord::streak,
			ByteBufCodecs.VAR_INT, SurvivalRecord::best, ByteBufCodecs.VAR_INT, SurvivalRecord::total,
			(d, s, b, t) -> new SurvivalRecord(d, s, b, t, -1, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));

	public static final AttachmentType<SurvivalRecord> RECORD = AttachmentRegistry.<SurvivalRecord>builder()
			.persistent(CODEC)
			.copyOnDeath()
			.syncWith(STREAM_CODEC, AttachmentSyncPredicate.targetOnly())
			.buildAndRegister(CounterCraft.id("survival"));

	/** Load the class at startup: {@link #RECORD} must be registered before anyone joins. */
	public static void init() {
	}

	public static SurvivalRecord of(ServerPlayer p) {
		SurvivalRecord r = p.getAttached(RECORD);
		return r != null ? r : NEW;
	}

	public static SurvivalRecord update(ServerPlayer p, UnaryOperator<SurvivalRecord> change) {
		SurvivalRecord r = change.apply(of(p));
		p.setAttached(RECORD, r);
		return r;
	}

	/** Every clean run restarts with the next full day ({@code day} is today's index). */
	public SurvivalRecord restartAll(long day) {
		return new SurvivalRecord(deaths, streak, best, total, lastDay, Math.max(aliveFrom == Long.MAX_VALUE ? 0 : aliveFrom, day + 1),
				Math.max(ecoFrom == Long.MAX_VALUE ? 0 : ecoFrom, day + 1), Math.max(saveFrom == Long.MAX_VALUE ? 0 : saveFrom, day + 1));
	}

	public SurvivalRecord died(long day) {
		return new SurvivalRecord(deaths + 1, 0, best, total, lastDay, aliveFrom, ecoFrom, saveFrom).restartAll(day);
	}

	public SurvivalRecord withEcoFrom(long d) {
		return new SurvivalRecord(deaths, streak, best, total, lastDay, aliveFrom, Math.max(ecoFrom, d), saveFrom);
	}

	public SurvivalRecord withSaveFrom(long d) {
		return new SurvivalRecord(deaths, streak, best, total, lastDay, aliveFrom, ecoFrom, Math.max(saveFrom, d));
	}

	public SurvivalRecord withLastDay(long d) {
		return new SurvivalRecord(deaths, streak, best, total, d, aliveFrom, ecoFrom, saveFrom);
	}

	/** A day survived: the streak, best and total go up. */
	public SurvivalRecord survivedDay() {
		return new SurvivalRecord(deaths, streak + 1, Math.max(best, streak + 1), total + 1, lastDay, aliveFrom, ecoFrom, saveFrom);
	}
}
